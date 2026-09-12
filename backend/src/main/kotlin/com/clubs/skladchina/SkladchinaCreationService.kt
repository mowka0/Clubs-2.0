package com.clubs.skladchina

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.debt.DebtRepository
import com.clubs.debt.NewDebt
import com.clubs.event.EventRepository
import com.clubs.event.EventResponseRepository
import com.clubs.generated.jooq.enums.DebtStatus
import com.clubs.generated.jooq.enums.SkladchinaKind
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.membership.MembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Создание сбора (docs/modules/skladchina-v3.md § 2.1, § 3). Три вида — три ветки `when (kind)`:
 * всё, чем они отличаются при создании, это откуда берётся список должников и появляются ли долги
 * сразу. Создать сбор может любой активный участник клуба.
 */
@Service
class SkladchinaCreationService(
    private val skladchinaRepository: SkladchinaRepository,
    private val debtRepository: DebtRepository,
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository,
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val queryService: SkladchinaQueryService
) {
    private val log = LoggerFactory.getLogger(SkladchinaCreationService::class.java)

    /** Чем заканчивается разбор запроса по виду: доли (должник → сумма), адресаты DM, нужна ли запись создателя «В деле». */
    private data class CreationPlan(
        val shares: Map<UUID, Long>,
        val amountKopecks: Long?,
        val eventId: UUID?,
        val recipientUserIds: List<UUID>,
        val enrollCreator: Boolean
    )

    @Transactional
    fun createSkladchina(clubId: UUID, request: CreateSkladchinaRequest, creatorId: UUID): SkladchinaDetailDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (!membershipRepository.isActiveMemberInActiveClub(creatorId, clubId)) {
            throw ForbiddenException("Создать сбор может только участник клуба")
        }
        val kind = SkladchinaKind.entries.find { it.literal == request.kind }
            ?: throw ValidationException("Неизвестный вид сбора: ${request.kind}")
        val now = OffsetDateTime.now()
        validateCommon(kind, request, now)

        val plan = when (kind) {
            SkladchinaKind.shared -> planShared(clubId, creatorId, request, now)
            SkladchinaKind.per_head -> planPerHead(clubId, creatorId, request)
            SkladchinaKind.voluntary -> planVoluntary(clubId, creatorId, request)
        }

        val id = UUID.randomUUID()
        val created = skladchinaRepository.create(
            Skladchina(
                id = id,
                clubId = clubId,
                creatorId = creatorId,
                title = request.title.trim(),
                description = request.description?.trim()?.takeIf { it.isNotEmpty() },
                rules = request.rules?.trim()?.takeIf { it.isNotEmpty() },
                photoUrl = request.photoUrl,
                kind = kind,
                amountKopecks = plan.amountKopecks,
                paymentLink = request.paymentLink.trim(),
                paymentMethodNote = request.paymentMethodNote?.trim()?.takeIf { it.isNotEmpty() },
                eventId = plan.eventId,
                deadline = request.deadline,
                enrollmentUntil = if (plan.enrollCreator) request.enrollmentUntil else null,
                minParticipants = if (plan.enrollCreator) request.minParticipants else null,
                lockedAt = null,
                orderedAt = null,
                hiddenFromUserId = if (kind == SkladchinaKind.voluntary) request.hiddenFromUserId else null,
                status = SkladchinaStatus.active,
                closedAt = null,
                reminderSentAt = null,
                orderRemindedAt = null,
                createdAt = now,
                updatedAt = now
            )
        )
        if (plan.shares.isNotEmpty()) {
            debtRepository.insertAll(plan.shares.map { (userId, amount) -> newDebt(created, userId, amount, now) })
        }
        if (plan.enrollCreator) skladchinaRepository.addEnrollment(id, creatorId)

        log.info(
            "Skladchina created: id={} clubId={} creatorId={} kind={} amount={} debts={} enrolling={} hidden={}",
            id, clubId, creatorId, kind, plan.amountKopecks, plan.shares.size, plan.enrollCreator, request.hiddenFromUserId != null
        )
        eventPublisher.publishEvent(
            SkladchinaCreatedEvent(
                skladchinaId = id,
                clubId = clubId,
                clubName = club.name,
                creatorId = creatorId,
                kind = kind,
                title = created.title,
                description = created.description,
                paymentLink = created.paymentLink,
                paymentMethodNote = created.paymentMethodNote,
                amountKopecks = created.amountKopecks,
                deadline = created.deadline,
                enrollmentUntil = created.enrollmentUntil,
                hiddenFromUserId = created.hiddenFromUserId,
                recipientUserIds = plan.recipientUserIds,
                debtorShares = plan.shares.filterKeys { it != creatorId }
            )
        )
        return queryService.getDetail(id, creatorId)
    }

    private fun validateCommon(kind: SkladchinaKind, request: CreateSkladchinaRequest, now: OffsetDateTime) {
        val deadline = request.deadline
        if (kind != SkladchinaKind.voluntary && deadline == null) throw ValidationException("Укажите срок оплаты")
        if (deadline != null) {
            if (ChronoUnit.HOURS.between(now, deadline) < MIN_DEADLINE_HOURS) {
                throw ValidationException("Срок должен быть не раньше чем через $MIN_DEADLINE_HOURS ч")
            }
            if (ChronoUnit.DAYS.between(now, deadline) > MAX_DEADLINE_DAYS) {
                throw ValidationException("Срок не дальше $MAX_DEADLINE_DAYS дней")
            }
        }
        if (kind != SkladchinaKind.voluntary && request.amountKopecks == null) {
            throw ValidationException(if (kind == SkladchinaKind.per_head) "Укажите цену за человека" else "Укажите сумму сбора")
        }
        if (kind != SkladchinaKind.voluntary && request.hiddenFromUserId != null) {
            throw ValidationException("Скрыть от кого-то можно только сбор «По желанию»")
        }
        if (kind != SkladchinaKind.shared && (request.eventId != null || request.enrollmentUntil != null || request.debtors.isNotEmpty())) {
            throw ValidationException("Встреча, этап «Кто в деле?» и список людей есть только у сбора «Скинуться»")
        }
    }

    /** shared: список из встречи, либо этап записи, либо список от создателя. */
    private fun planShared(clubId: UUID, creatorId: UUID, request: CreateSkladchinaRequest, now: OffsetDateTime): CreationPlan {
        val amount = request.amountKopecks!!
        val eventId = request.eventId
        val enrollmentUntil = request.enrollmentUntil
        return when {
            eventId != null -> {
                if (enrollmentUntil != null) throw ValidationException("У сбора после встречи этапа записи нет")
                val attended = resolveAttended(clubId, eventId, now)
                val shares = SkladchinaShares.equal(amount, attended).toMap()
                CreationPlan(shares, amount, eventId, attended.filter { it != creatorId }, enrollCreator = false)
            }
            enrollmentUntil != null -> {
                if (!enrollmentUntil.isAfter(now)) throw ValidationException("Срок записи уже прошёл")
                if (enrollmentUntil.isAfter(request.deadline)) throw ValidationException("Запись должна закрыться не позже срока оплаты")
                if (request.debtors.isNotEmpty()) throw ValidationException("На этапе «Кто в деле?» список набирается сам")
                val members = skladchinaRepository.findActiveMemberIds(clubId).filter { it != creatorId }
                CreationPlan(emptyMap(), amount, null, members, enrollCreator = true)
            }
            else -> {
                val shares = resolveListedShares(clubId, creatorId, request, amount)
                CreationPlan(shares, shares.values.sum(), null, shares.keys.filter { it != creatorId }, enrollCreator = false)
            }
        }
    }

    /** per_head: долгов при создании нет, «Беру» жмут сами; сумма — цена за человека; знают о сборе все участники клуба. */
    private fun planPerHead(clubId: UUID, creatorId: UUID, request: CreateSkladchinaRequest): CreationPlan {
        val members = skladchinaRepository.findActiveMemberIds(clubId).filter { it != creatorId }
        return CreationPlan(emptyMap(), request.amountKopecks, null, members, enrollCreator = false)
    }

    /** voluntary: без долгов; тихий сбор скрыт от одного человека — он не адресат. */
    private fun planVoluntary(clubId: UUID, creatorId: UUID, request: CreateSkladchinaRequest): CreationPlan {
        val hidden = request.hiddenFromUserId
        if (hidden != null) {
            if (hidden == creatorId) throw ValidationException("Нельзя скрыть сбор от самого себя")
            if (skladchinaRepository.findNonActiveMembers(clubId, listOf(hidden)).isNotEmpty()) {
                throw ValidationException("Скрыть можно только от участника клуба")
            }
        }
        val members = skladchinaRepository.findActiveMemberIds(clubId).filter { it != creatorId && it != hidden }
        return CreationPlan(emptyMap(), request.amountKopecks, null, members, enrollCreator = false)
    }

    /** Пришедшие на встречу активные участники; те же условия, что отбирают встречи в списке «Скинуться после встречи». */
    private fun resolveAttended(clubId: UUID, eventId: UUID, now: OffsetDateTime): List<UUID> {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Встреча не найдена")
        if (event.clubId != clubId) throw ValidationException("Встреча из другого клуба")
        if (!event.attendanceMarked) throw ValidationException("Сначала отметьте, кто пришёл на встречу")
        if (event.eventDatetime.isBefore(now.minusDays(MAX_EVENT_AGE_DAYS))) {
            throw ValidationException("Встреча старше $MAX_EVENT_AGE_DAYS дней — скинуться уже не выйдет")
        }
        skladchinaRepository.findBlockingByEventId(eventId)?.let { existing ->
            throw ValidationException(
                if (existing.status == SkladchinaStatus.active) "По этой встрече сбор уже идёт — откройте его"
                else "По этой встрече уже собрано"
            )
        }
        val attendedAll = eventResponseRepository.findAttendedUserIds(eventId)
        val notActive = skladchinaRepository.findNonActiveMembers(clubId, attendedAll)
        val attended = attendedAll.filter { it !in notActive }
        // Пришедшие считаются целиком, создатель среди них может быть, а может и нет: нужно как минимум двое.
        if (attended.size < MIN_ATTENDED) {
            throw ValidationException("Нужно минимум $MIN_ATTENDED пришедших участника, чтобы скинуться")
        }
        return attended
    }

    /** Список от создателя: суммы либо у всех (по людям), либо ни у кого (поровну); все — активные участники. */
    private fun resolveListedShares(clubId: UUID, creatorId: UUID, request: CreateSkladchinaRequest, amount: Long): Map<UUID, Long> {
        val debtors = request.debtors
        if (debtors.isEmpty()) throw ValidationException("Добавьте хотя бы одного человека")
        val userIds = debtors.map { it.userId }
        if (userIds.distinct().size != userIds.size) throw ValidationException("Человек в списке дважды")
        if (userIds.all { it == creatorId }) throw ValidationException("Нужен хотя бы один человек кроме вас")
        if (skladchinaRepository.findNonActiveMembers(clubId, userIds).isNotEmpty()) {
            throw ForbiddenException("В списке есть не участники клуба")
        }
        val withAmount = debtors.count { it.amountKopecks != null }
        return when (withAmount) {
            0 -> {
                if (amount < userIds.size) throw ValidationException("Сумма слишком мала — на каждого не выходит и копейки")
                SkladchinaShares.equal(amount, userIds).toMap()
            }
            debtors.size -> debtors.associate { it.userId to it.amountKopecks!! }
            else -> throw ValidationException("Суммы должны быть либо у всех, либо ни у кого (тогда поровну)")
        }
    }

    /** Доля создателя сразу received: он не должен сам себе, но «получено X из Y» должно сходиться. */
    private fun newDebt(s: Skladchina, userId: UUID, amount: Long, now: OffsetDateTime): NewDebt {
        val own = userId == s.creatorId
        return NewDebt(
            skladchinaId = s.id,
            debtorId = userId,
            creditorId = s.creatorId,
            amountKopecks = amount,
            dueAt = s.deadline,
            status = if (own) DebtStatus.received else DebtStatus.waiting,
            confirmedAt = if (own) now else null
        )
    }

    companion object {
        private const val MIN_DEADLINE_HOURS = 1L   // минимальный отступ срока от текущего момента
        private const val MAX_DEADLINE_DAYS = 90L   // максимальный горизонт срока вперёд
        // Встреча, по которой ещё можно скинуться: не старше 30 дней (общее с findSplittableEvents).
        const val MAX_EVENT_AGE_DAYS = 30L
        // Минимум пришедших, чтобы было между кем делить счёт.
        const val MIN_ATTENDED = 2
    }
}
