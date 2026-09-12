package com.clubs.skladchina

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.debt.DebtRepository
import com.clubs.debt.NewDebt
import com.clubs.generated.jooq.enums.DebtStatus
import com.clubs.generated.jooq.enums.SkladchinaKind
import com.clubs.generated.jooq.enums.SkladchinaStatus
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Стадии сбора (docs/modules/skladchina-v3.md § 2.1, § 3): заморозка списка «Кто в деле?»,
 * «Заказываю», «Закрыть сбор», отмена и закрытие само собой, когда открытых долгов не осталось.
 * Итог только collected или cancelled: порогов, процентов и «не собран» нет.
 */
@Service
class SkladchinaLifecycleService(
    private val skladchinaRepository: SkladchinaRepository,
    private val debtRepository: DebtRepository,
    private val clubRepository: ClubRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val queryService: SkladchinaQueryService
) {
    private val log = LoggerFactory.getLogger(SkladchinaLifecycleService::class.java)

    /** «Закрыть запись» раньше срока — только создатель. */
    @Transactional
    fun lock(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val s = requireActiveAsCreator(skladchinaId, callerId)
        if (!s.isEnrolling) throw ValidationException("У этого сбора нет открытой записи")
        lockInternal(s, OffsetDateTime.now())
        return queryService.getDetail(skladchinaId, callerId)
    }

    /** Заморозка по сроку записи (шедулер): каждая в своей транзакции, чтобы сбой одной не откатил остальные. */
    @Transactional
    fun lockBySchedule(skladchinaId: UUID) {
        val s = skladchinaRepository.findById(skladchinaId) ?: return
        if (!s.isActive || !s.isEnrolling) return
        lockInternal(s, OffsetDateTime.now())
    }

    fun findEnrollmentDueIds(now: OffsetDateTime): List<UUID> =
        skladchinaRepository.findEnrollmentDue(now).map { it.id }

    /**
     * Заморозка: меньше минимума → отмена без долгов («не набрали», денег никто не переводил);
     * иначе доли поровну, долги созданы, доля создателя сразу received.
     */
    private fun lockInternal(s: Skladchina, now: OffsetDateTime) {
        if (!skladchinaRepository.claimLock(s.id, now)) {
            log.info("Skladchina lock lost race: id={}", s.id)
            return
        }
        val enrolled = skladchinaRepository.findEnrolledUserIds(s.id)
        val min = s.minParticipants ?: 1
        val clubName = clubRepository.findById(s.clubId)?.name ?: ""
        // Только создатель в деле — собирать не с кого; считаем это недобором.
        val shortfall = enrolled.size < min || enrolled.all { it == s.creatorId }
        if (shortfall) {
            skladchinaRepository.claimClose(s.id, SkladchinaStatus.cancelled, now)
            log.info("Skladchina enrollment shortfall: id={} enrolled={} min={}", s.id, enrolled.size, min)
            eventPublisher.publishEvent(lockedEvent(s, clubName, enrolled.size, cancelled = true, shares = emptyMap()))
            return
        }
        val shares = SkladchinaShares.equal(s.amountKopecks!!, enrolled).toMap()
        debtRepository.insertAll(shares.map { (userId, amount) ->
            val own = userId == s.creatorId
            NewDebt(
                skladchinaId = s.id, debtorId = userId, creditorId = s.creatorId, amountKopecks = amount,
                dueAt = s.deadline,
                status = if (own) DebtStatus.received else DebtStatus.waiting,
                confirmedAt = if (own) now else null
            )
        })
        log.info("Skladchina locked: id={} enrolled={} share={}", s.id, enrolled.size, shares.values.firstOrNull())
        eventPublisher.publishEvent(lockedEvent(s, clubName, enrolled.size, cancelled = false, shares = shares.filterKeys { it != s.creatorId }))
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(s.id))
    }

    private fun lockedEvent(s: Skladchina, clubName: String, enrolledCount: Int, cancelled: Boolean, shares: Map<UUID, Long>) =
        SkladchinaLockedEvent(
            skladchinaId = s.id, clubName = clubName, creatorId = s.creatorId, title = s.title,
            paymentLink = s.paymentLink, paymentMethodNote = s.paymentMethodNote, deadline = s.deadline,
            enrolledCount = enrolledCount, minParticipants = s.minParticipants,
            cancelledForShortfall = cancelled, debtorShares = shares
        )

    /** «Заказываю» (per_head): приём закрыт, неоплатившие выбывают без долга, claimed остаются на разбор. */
    @Transactional
    fun order(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val s = requireActiveAsCreator(skladchinaId, callerId)
        if (s.kind != SkladchinaKind.per_head) throw ValidationException("«Заказываю» есть только у сбора «Кто берёт?»")
        val now = OffsetDateTime.now()
        if (!skladchinaRepository.claimOrder(skladchinaId, now)) throw ConflictException("Заказ уже сделан — обновите экран")
        val dropped = debtRepository.dropWaitingBySkladchina(skladchinaId)
        val clubName = clubRepository.findById(s.clubId)?.name ?: ""
        log.info("Skladchina ordered: id={} dropped={}", skladchinaId, dropped.size)
        eventPublisher.publishEvent(SkladchinaOrderedEvent(skladchinaId, clubName, s.title, dropped.map { it.debtorId }))
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
        maybeComplete(skladchinaId)
        return queryService.getDetail(skladchinaId, callerId)
    }

    /** «Закрыть сбор» (voluntary): только рукой создателя и только когда все «Перевёл» разобраны (вариант А). */
    @Transactional
    fun close(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val s = requireActiveAsCreator(skladchinaId, callerId)
        if (s.kind != SkladchinaKind.voluntary) throw ValidationException("Сбор закроется сам, когда все долги будут закрыты")
        val totals = debtRepository.totals(skladchinaId)
        if (totals.claimedCount > 0) throw ValidationException("Разберите переводы: ${totals.claimedCount}")
        complete(s, SkladchinaStatus.collected, OffsetDateTime.now(), refunds = emptyMap())
        return queryService.getDetail(skladchinaId, callerId)
    }

    /** Отменить: создатель или владелец клуба (со-организаторы нет). Открытые долги прощаются, полученные — «кому вернуть». */
    @Transactional
    fun cancel(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val s = skladchinaRepository.findById(skladchinaId) ?: throw NotFoundException("Сбор не найден")
        val club = clubRepository.findById(s.clubId) ?: throw NotFoundException("Club not found")
        if (s.creatorId != callerId && club.ownerId != callerId) {
            throw ForbiddenException("Отменить сбор может только его создатель или владелец клуба")
        }
        if (!s.isActive) throw ValidationException("Сбор уже закрыт")
        val refunds = debtRepository.findBySkladchina(skladchinaId)
            .filter { it.debt.status == DebtStatus.received && it.debt.debtorId != s.creatorId }
            .associate { it.debt.debtorId to it.debt.amountKopecks }
        val forgiven = debtRepository.forgiveOpenBySkladchina(skladchinaId)
        log.info("Skladchina cancelled: id={} by={} forgiven={} refunds={}", skladchinaId, callerId, forgiven, refunds.size)
        complete(s, SkladchinaStatus.cancelled, OffsetDateTime.now(), refunds)
        return queryService.getDetail(skladchinaId, callerId)
    }

    /**
     * Проверка «не осталось открытых долгов» после каждого «Получил» / «Простить» / «Заказываю» /
     * подтверждения сальдо. Выполняется в транзакции вызывающего. shared с открытой записью и
     * per_head без заказа не закрываются: долги ещё могут появиться; voluntary — только рукой.
     */
    fun maybeComplete(skladchinaId: UUID) {
        val s = skladchinaRepository.findById(skladchinaId) ?: return
        if (!s.isActive) return
        val totals = debtRepository.totals(skladchinaId)
        val ready = when (s.kind) {
            SkladchinaKind.shared -> !s.isEnrolling && totals.debtCount > 0
            SkladchinaKind.per_head -> s.orderedAt != null
            SkladchinaKind.voluntary -> false
        }
        if (!ready || totals.openCount > 0) return
        complete(s, SkladchinaStatus.collected, OffsetDateTime.now(), refunds = emptyMap())
    }

    private fun complete(s: Skladchina, finalStatus: SkladchinaStatus, at: OffsetDateTime, refunds: Map<UUID, Long>) {
        if (!skladchinaRepository.claimClose(s.id, finalStatus, at)) {
            log.info("Skladchina close claim lost: id={} — already closed concurrently", s.id)
            return
        }
        val totals = debtRepository.totals(s.id)
        val clubName = clubRepository.findById(s.clubId)?.name ?: ""
        log.info("Skladchina closed: id={} status={} received={} of {} debts={}/{}",
            s.id, finalStatus, totals.receivedKopecks, totals.targetKopecks, totals.receivedCount, totals.debtCount)
        eventPublisher.publishEvent(
            SkladchinaClosedEvent(
                skladchinaId = s.id, creatorId = s.creatorId, clubName = clubName, title = s.title, kind = s.kind,
                finalStatus = finalStatus, receivedKopecks = totals.receivedKopecks,
                targetKopecks = totals.targetKopecks ?: s.amountKopecks,
                receivedCount = totals.receivedCount, debtCount = totals.debtCount, refunds = refunds
            )
        )
    }

    /** per_head без заказа с прошедшим сроком: штампует и возвращает те, кому пора напомнить создателю. */
    @Transactional
    fun claimOrderReminders(now: OffsetDateTime): List<Skladchina> {
        val due = skladchinaRepository.findPerHeadNeedingOrderReminder(now, now.minusDays(ORDER_REMINDER_EVERY_DAYS))
        due.forEach { skladchinaRepository.markOrderReminded(it.id, now) }
        return due
    }

    private fun requireActiveAsCreator(skladchinaId: UUID, callerId: UUID): Skladchina {
        val s = skladchinaRepository.findById(skladchinaId) ?: throw NotFoundException("Сбор не найден")
        if (s.isHiddenFrom(callerId)) throw NotFoundException("Сбор не найден")
        if (s.creatorId != callerId) throw ForbiddenException("Это действие доступно только создателю сбора")
        if (!s.isActive) throw ValidationException("Сбор уже закрыт")
        return s
    }

    companion object {
        // Напоминание создателю per_head «пора заказывать»: в срок и раз в день после.
        private const val ORDER_REMINDER_EVERY_DAYS = 1L
    }
}
