package com.clubs.skladchina

import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.debt.DebtClaimedEvent
import com.clubs.debt.DebtCreatedEvent
import com.clubs.debt.DebtReplacedEvent
import com.clubs.debt.DebtRepository
import com.clubs.debt.NewDebt
import com.clubs.generated.jooq.enums.DebtStatus
import com.clubs.generated.jooq.enums.SkladchinaKind
import com.clubs.membership.MembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Как человек попадает в сбор и выходит из него (§ 2.2, § 3): «В деле» / «Беру» / «Перевёл»,
 * «Передумал», добавление и замена должника создателем. Каждая мутация в своей транзакции.
 */
@Service
class SkladchinaParticipationService(
    private val skladchinaRepository: SkladchinaRepository,
    private val debtRepository: DebtRepository,
    private val membershipRepository: MembershipRepository,
    private val lifecycleService: SkladchinaLifecycleService,
    private val eventPublisher: ApplicationEventPublisher,
    private val queryService: SkladchinaQueryService
) {
    private val log = LoggerFactory.getLogger(SkladchinaParticipationService::class.java)

    /** «В деле» (shared с этапом) или «Беру» (per_head). */
    @Transactional
    fun join(skladchinaId: UUID, callerId: UUID, note: String?): SkladchinaDetailDto {
        val s = requireActiveForMember(skladchinaId, callerId)
        when {
            s.isEnrolling -> {
                skladchinaRepository.addEnrollment(skladchinaId, callerId)
                log.info("Skladchina enrolled: id={} userId={}", skladchinaId, callerId)
            }
            s.kind == SkladchinaKind.per_head -> {
                if (s.orderedAt != null) throw ValidationException("Приём закрыт: заказ уже сделан")
                debtRepository.findBySkladchinaAndDebtor(skladchinaId, callerId)?.let { existing ->
                    throw ValidationException(
                        if (existing.status == DebtStatus.dropped) "Вы уже выбывали из этого сбора" else "Вы уже берёте"
                    )
                }
                val now = OffsetDateTime.now()
                val own = callerId == s.creatorId
                debtRepository.insertAll(listOf(
                    NewDebt(
                        skladchinaId = skladchinaId, debtorId = callerId, creditorId = s.creatorId,
                        amountKopecks = s.amountKopecks!!, dueAt = s.deadline,
                        status = if (own) DebtStatus.received else DebtStatus.waiting,
                        confirmedAt = if (own) now else null,
                        note = note?.trim()?.takeIf { it.isNotEmpty() }
                    )
                ))
                log.info("Skladchina take: id={} userId={}", skladchinaId, callerId)
            }
            else -> throw ValidationException("В этом сборе нет записи")
        }
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
        return queryService.getDetail(skladchinaId, callerId)
    }

    /** «Передумал»: до заморозки списка или до заказа. */
    @Transactional
    fun leave(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val s = requireActiveForMember(skladchinaId, callerId)
        when {
            s.isEnrolling -> {
                if (skladchinaRepository.removeEnrollment(skladchinaId, callerId) == 0) {
                    throw ValidationException("Вы не отмечались «В деле»")
                }
            }
            s.kind == SkladchinaKind.per_head -> {
                if (s.orderedAt != null) throw ValidationException("Заказ уже сделан — передумать нельзя")
                val debt = debtRepository.findBySkladchinaAndDebtor(skladchinaId, callerId)
                    ?: throw ValidationException("Вы не брали")
                if (debt.status == DebtStatus.claimed) throw ValidationException("Сначала отмените «Отдал»")
                if (debtRepository.drop(debt.id, setOf(DebtStatus.waiting, DebtStatus.promised)) == 0) {
                    throw ConflictException("Долг уже изменился — обновите экран")
                }
            }
            else -> throw ValidationException("Из этого сбора так не выходят")
        }
        log.info("Skladchina leave: id={} userId={}", skladchinaId, callerId)
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
        return queryService.getDetail(skladchinaId, callerId)
    }

    /** «Перевёл N ₽» (voluntary): долг рождается сразу claimed, получатель подтверждает. */
    @Transactional
    fun contribute(skladchinaId: UUID, callerId: UUID, amountKopecks: Long): SkladchinaDetailDto {
        val s = requireActiveForMember(skladchinaId, callerId)
        if (s.kind != SkladchinaKind.voluntary) throw ValidationException("«Перевёл» есть только у сбора «По желанию»")
        if (callerId == s.creatorId) throw ValidationException("Создатель не переводит сам себе")
        if (amountKopecks > MAX_CONTRIBUTION_KOPECKS) throw ValidationException("Сумма не может превышать ${MAX_CONTRIBUTION_KOPECKS / 100} ₽")
        debtRepository.findBySkladchinaAndDebtor(skladchinaId, callerId)?.let { existing ->
            throw ValidationException(
                if (existing.isOpen) "Ваш перевод уже ждёт подтверждения" else "Вы уже переводили в этот сбор"
            )
        }
        val now = OffsetDateTime.now()
        val created = debtRepository.insertAll(listOf(
            NewDebt(
                skladchinaId = skladchinaId, debtorId = callerId, creditorId = s.creatorId,
                amountKopecks = amountKopecks, dueAt = null, status = DebtStatus.claimed, claimedAt = now
            )
        )).single()
        log.info("Skladchina contribute: id={} userId={} amount={}", skladchinaId, callerId, amountKopecks)
        eventPublisher.publishEvent(DebtClaimedEvent(debtRepository.findWithContext(created.id)!!))
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
        return queryService.getDetail(skladchinaId, callerId)
    }

    /** Создатель добавляет человека в shared; сумма по умолчанию = доля последнего добавленного. */
    @Transactional
    fun addDebtor(skladchinaId: UUID, callerId: UUID, userId: UUID, amountKopecks: Long?): SkladchinaDetailDto {
        val s = requireSharedAsCreator(skladchinaId, callerId)
        if (s.isEnrolling) throw ValidationException("Сначала закройте запись")
        if (skladchinaRepository.findNonActiveMembers(s.clubId, listOf(userId)).isNotEmpty()) {
            throw ValidationException("Добавить можно только участника клуба")
        }
        val existing = debtRepository.findBySkladchina(skladchinaId)
        if (existing.any { it.debt.debtorId == userId }) throw ValidationException("Этот человек уже в сборе")
        val amount = amountKopecks ?: existing.lastOrNull()?.debt?.amountKopecks ?: s.amountKopecks
            ?: throw ValidationException("Укажите сумму")
        val now = OffsetDateTime.now()
        val own = userId == s.creatorId
        val created = debtRepository.insertAll(listOf(
            NewDebt(
                skladchinaId = skladchinaId, debtorId = userId, creditorId = s.creatorId, amountKopecks = amount,
                dueAt = s.deadline,
                status = if (own) DebtStatus.received else DebtStatus.waiting,
                confirmedAt = if (own) now else null
            )
        )).single()
        log.info("Skladchina debtor added: id={} userId={} amount={} by={}", skladchinaId, userId, amount, callerId)
        if (!own) eventPublisher.publishEvent(DebtCreatedEvent(debtRepository.findWithContext(created.id)!!))
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
        return queryService.getDetail(skladchinaId, callerId)
    }

    /** «Заменить»: прежнему долг прощён, у замены новый долг той же суммы. Доли не пересчитываются. */
    @Transactional
    fun replaceDebtor(skladchinaId: UUID, debtId: UUID, callerId: UUID, newUserId: UUID): SkladchinaDetailDto {
        val s = requireSharedAsCreator(skladchinaId, callerId)
        val debt = debtRepository.findById(debtId)?.takeIf { it.skladchinaId == skladchinaId }
            ?: throw NotFoundException("Долг не найден")
        if (debt.status !in setOf(DebtStatus.waiting, DebtStatus.promised)) {
            throw ValidationException("Заменить можно только того, кто ещё не платил")
        }
        if (newUserId == debt.debtorId) throw ValidationException("Это тот же человек")
        if (skladchinaRepository.findNonActiveMembers(s.clubId, listOf(newUserId)).isNotEmpty()) {
            throw ValidationException("Заменить можно только участником клуба")
        }
        if (debtRepository.findBySkladchinaAndDebtor(skladchinaId, newUserId) != null) {
            throw ValidationException("Этот человек уже участвует в сборе")
        }
        if (debtRepository.forgive(debtId) == 0) throw ConflictException("Долг уже изменился — обновите экран")
        val created = debtRepository.insertAll(listOf(
            NewDebt(
                skladchinaId = skladchinaId, debtorId = newUserId, creditorId = s.creatorId,
                amountKopecks = debt.amountKopecks, dueAt = debt.dueAt
            )
        )).single()
        log.info("Skladchina debtor replaced: id={} debt={} old={} new={}", skladchinaId, debtId, debt.debtorId, newUserId)
        eventPublisher.publishEvent(DebtReplacedEvent(debt.debtorId, debtRepository.findWithContext(created.id)!!))
        eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId))
        lifecycleService.maybeComplete(skladchinaId)
        return queryService.getDetail(skladchinaId, callerId)
    }

    private fun requireActiveForMember(skladchinaId: UUID, callerId: UUID): Skladchina {
        val s = skladchinaRepository.findById(skladchinaId) ?: throw NotFoundException("Сбор не найден")
        if (s.isHiddenFrom(callerId)) throw NotFoundException("Сбор не найден")
        if (!membershipRepository.isActiveMemberInActiveClub(callerId, s.clubId)) {
            throw ForbiddenException("Только для участников клуба")
        }
        if (!s.isActive) throw ValidationException("Сбор уже закрыт")
        return s
    }

    private fun requireSharedAsCreator(skladchinaId: UUID, callerId: UUID): Skladchina {
        val s = skladchinaRepository.findById(skladchinaId) ?: throw NotFoundException("Сбор не найден")
        if (s.creatorId != callerId) throw ForbiddenException("Это действие доступно только создателю сбора")
        if (!s.isActive) throw ValidationException("Сбор уже закрыт")
        if (s.kind != SkladchinaKind.shared) throw ValidationException("Список людей есть только у сбора «Скинуться»")
        return s
    }

    companion object {
        // Верхняя граница перевода «по желанию»: гигиена статистики, не защита от злоупотреблений.
        private const val MAX_CONTRIBUTION_KOPECKS = 10_000_000L
    }
}
