package com.clubs.skladchina

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.ReputationAxis
import com.clubs.generated.jooq.enums.ReputationSource
import com.clubs.generated.jooq.enums.SkladchinaMode
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.reputation.LedgerEntry
import com.clubs.reputation.ReputationPolicy
import com.clubs.reputation.ReputationService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class SkladchinaService(
    private val skladchinaRepository: SkladchinaRepository,
    private val clubRepository: ClubRepository,
    private val mapper: SkladchinaMapper,
    private val reputationService: ReputationService,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(SkladchinaService::class.java)

    @Transactional
    fun createSkladchina(clubId: UUID, request: CreateSkladchinaRequest, creatorId: UUID): SkladchinaDetailDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != creatorId) throw ForbiddenException("Only the club organizer can create skladchina")

        val mode = parseMode(request.paymentMode)
        validateRequest(request, mode)

        val now = OffsetDateTime.now()
        val deadlineMinAge = ChronoUnit.HOURS.between(now, request.deadline)
        val deadlineMaxAge = ChronoUnit.DAYS.between(now, request.deadline)
        if (deadlineMinAge < MIN_DEADLINE_HOURS) {
            throw ValidationException("Deadline must be at least $MIN_DEADLINE_HOURS hour ahead")
        }
        if (deadlineMaxAge > MAX_DEADLINE_DAYS) {
            throw ValidationException("Deadline must be at most $MAX_DEADLINE_DAYS days ahead")
        }
        if (request.affectsReputation) {
            validateReputationGates(clubId, mode, deadlineMinAge, now)
        }

        val userIds = request.participants.map { it.userId }.distinct()
        if (userIds.size != request.participants.size) {
            throw ValidationException("Duplicate userId in participants list")
        }
        val notActive = skladchinaRepository.findNonActiveMembers(clubId, userIds)
        if (notActive.isNotEmpty()) {
            throw ForbiddenException("Some participants are not active members of this club")
        }

        val participants = buildParticipantsForCreate(mode, request)

        val skladchinaId = UUID.randomUUID()
        val domain = Skladchina(
            id = skladchinaId,
            clubId = clubId,
            creatorId = creatorId,
            title = request.title,
            description = request.description,
            rules = request.rules,
            photoUrl = request.photoUrl,
            paymentMode = mode,
            totalGoalKopecks = when (mode) {
                SkladchinaMode.voluntary -> null
                SkladchinaMode.fixed_equal -> request.totalGoalKopecks
                SkladchinaMode.fixed_individual -> participants.sumOf { it.second ?: 0L }
            },
            paymentLink = request.paymentLink,
            paymentMethodNote = request.paymentMethodNote,
            deadline = request.deadline,
            affectsReputation = request.affectsReputation,
            status = SkladchinaStatus.active,
            closedAt = null,
            closedBy = null,
            createdAt = now,
            updatedAt = now
        )

        val created = skladchinaRepository.create(domain, participants)
        log.info("Skladchina created: id={} clubId={} creatorId={} mode={} participants={}",
            created.id, clubId, creatorId, mode, participants.size)

        // DM-рассылка идёт через @TransactionalEventListener в SkladchinaBotNotifier —
        // гарантия отправки ПОСЛЕ commit'а транзакции (тот же паттерн что PaymentNotificationHandler).
        eventPublisher.publishEvent(
            SkladchinaCreatedEvent(
                skladchinaId = created.id,
                clubId = clubId,
                clubName = club.name,
                title = created.title,
                description = created.description,
                paymentLink = created.paymentLink,
                paymentMode = created.paymentMode.literal,
                totalGoalKopecks = created.totalGoalKopecks,
                deadline = created.deadline,
                affectsReputation = created.affectsReputation,
                participantUserIds = userIds
            )
        )

        return getDetail(created.id, creatorId)
    }

    @Transactional(readOnly = true)
    fun getClubActiveSkladchinas(clubId: UUID, callerId: UUID): List<MySkladchinaListItemDto> {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        val skladchinas = skladchinaRepository.findActiveByClub(clubId)
        return skladchinas.map { s ->
            val collected = skladchinaRepository.sumCollectedKopecks(s.id)
            val totalParticipants = skladchinaRepository.countParticipants(s.id)
            val paid = skladchinaRepository.countParticipantsByStatus(s.id, SkladchinaParticipantStatus.paid)
            val callerParticipant = skladchinaRepository.findParticipant(s.id, callerId)
            mapper.toMyFeedItemDto(
                MySkladchinaFeedItem(
                    skladchina = s,
                    clubName = club.name,
                    clubAvatarUrl = club.avatarUrl,
                    myStatus = callerParticipant?.status,
                    collectedKopecks = collected,
                    participantCount = totalParticipants,
                    paidCount = paid
                ),
                callerId
            )
        }
    }

    @Transactional(readOnly = true)
    fun getDetail(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        val club = clubRepository.findById(skladchina.clubId)
            ?: throw NotFoundException("Club not found")

        // Access: creator OR active participant
        val isCreator = skladchina.creatorId == callerId
        val callerParticipant = skladchinaRepository.findParticipant(skladchinaId, callerId)
        if (!isCreator && callerParticipant == null) {
            throw ForbiddenException("Not allowed to view this skladchina")
        }

        val participants = skladchinaRepository.findParticipantsWithInfo(skladchinaId)
        val collected = skladchinaRepository.sumCollectedKopecks(skladchinaId)
        return mapper.toDetailDto(skladchina, club.name, club.avatarUrl, callerId, participants, collected)
    }

    @Transactional
    fun markPaid(skladchinaId: UUID, callerId: UUID, declaredAmountKopecks: Long): SkladchinaDetailDto {
        if (declaredAmountKopecks <= 0) throw ValidationException("Declared amount must be positive")

        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Skladchina is not active")
        }
        val participant = skladchinaRepository.findParticipant(skladchinaId, callerId)
            ?: throw ForbiddenException("Not a participant of this skladchina")

        if (participant.status == SkladchinaParticipantStatus.paid) {
            // Idempotent — silently return current state.
            return getDetail(skladchinaId, callerId)
        }
        if (participant.status == SkladchinaParticipantStatus.declined) {
            throw ValidationException("You have already declined this skladchina")
        }

        validateDeclaredAmount(skladchina.paymentMode, participant.expectedAmountKopecks, declaredAmountKopecks)

        val updated = skladchinaRepository.setParticipantPaid(
            skladchinaId, callerId, declaredAmountKopecks, OffsetDateTime.now()
        )
        if (updated == 0) {
            // F5-03: the participant left `pending` between our read and this UPDATE
            // (a concurrent close expired/released them) — refuse instead of
            // overwriting the terminal status its ledger row was emitted for.
            throw ConflictException("Сбор уже закрыт — изменить ответ нельзя. Обновите экран")
        }
        log.info("Skladchina mark-paid: id={} userId={} amount={}", skladchinaId, callerId, declaredAmountKopecks)

        maybeAutoCloseAfterStateChange(skladchinaId)

        return getDetail(skladchinaId, callerId)
    }

    @Transactional
    fun decline(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Skladchina is not active")
        }
        val participant = skladchinaRepository.findParticipant(skladchinaId, callerId)
            ?: throw ForbiddenException("Not a participant of this skladchina")
        if (participant.status == SkladchinaParticipantStatus.declined) {
            throw ValidationException("Already declined")
        }
        if (participant.status == SkladchinaParticipantStatus.paid) {
            throw ValidationException("Already paid, cannot decline")
        }
        val updated = skladchinaRepository.setParticipantDeclined(skladchinaId, callerId, OffsetDateTime.now())
        if (updated == 0) {
            // F5-03: same race as markPaid — concurrent close already resolved this participant.
            throw ConflictException("Сбор уже закрыт — изменить ответ нельзя. Обновите экран")
        }
        log.info("Skladchina declined: id={} userId={}", skladchinaId, callerId)
        maybeAutoCloseAfterStateChange(skladchinaId)
        return getDetail(skladchinaId, callerId)
    }

    /**
     * Auto-close trigger that fires after mark-paid or decline. Closes if:
     *  - goal is reached (fixed-modes), OR
     *  - all participants are in a terminal status (paid / declined) — no more pending.
     * Quiet no-op otherwise.
     *
     * F5-18: a close/reputation failure is caught and logged HERE so the participant's
     * own markPaid/decline never 500s because of it (NFR skladchina.md). Scope note:
     * this is a self-invocation, so closeInternal joins the caller's transaction —
     * the catch shields against application-level failures; a DB-level error inside
     * closeInternal still aborts the shared Postgres transaction.
     */
    private fun maybeAutoCloseAfterStateChange(skladchinaId: UUID) {
        val skladchina = skladchinaRepository.findById(skladchinaId) ?: return
        if (skladchina.status != SkladchinaStatus.active) return

        val goalReached = skladchina.totalGoalKopecks
            ?.let { goal -> skladchinaRepository.sumCollectedKopecks(skladchinaId) >= goal }
            ?: false
        val shouldClose = goalReached || skladchinaRepository.countParticipantsByStatus(
            skladchinaId, SkladchinaParticipantStatus.pending
        ) == 0
        if (!shouldClose) return

        try {
            closeInternal(skladchinaId, closedBy = null, manualClose = false)
        } catch (e: Exception) {
            log.error(
                "Auto-close failed for skladchina {} — keeping the participant's state change",
                skladchinaId, e
            )
        }
    }

    @Transactional
    fun closeManually(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.creatorId != callerId) {
            throw ForbiddenException("Only creator can close skladchina")
        }
        if (skladchina.status != SkladchinaStatus.active) {
            throw ValidationException("Skladchina is already closed")
        }
        closeInternal(skladchinaId, closedBy = callerId, manualClose = true)
        return getDetail(skladchinaId, callerId)
    }

    /**
     * Internal helper, used by both manual close (creator) and auto-close (scheduler / goal-reached).
     * Claims the close atomically, resolves pending participants, applies reputation deltas
     * idempotently, notifies organizer.
     *
     * F5-12: the status flip is an atomic claim (`UPDATE … WHERE status = 'active'`, pattern
     * claimEvent) — a concurrent closer (scheduler × auto-close × manual) loses the claim and
     * no-ops, so participants are resolved once and SkladchinaClosedEvent fires exactly once.
     *
     * F5-02: pending participants are `expired_no_response` (-40) ONLY when the close happens
     * at/after the deadline. An early close (goal reached / everyone answered / manual) moves
     * them to `released` — the promise was "answer by the deadline", and the deadline never
     * came, so no ledger row is emitted (financeKind(released) = null).
     *
     * @Transactional so the scheduler/auto-close path (SkladchinaScheduler -> closeInternal, a
     * cross-bean call with no ambient tx) commits resolve/status/reputation atomically. The already
     * transactional callers (markPaid/decline/closeManually) self-invoke this and simply run inside
     * their existing tx. Atomicity guarantees a ledger-append failure rolls back the claim and
     * the reputation_applied marks too, so a retry can recover (no orphaned participants).
     */
    @Transactional
    fun closeInternal(skladchinaId: UUID, closedBy: UUID?, manualClose: Boolean) {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        if (skladchina.status != SkladchinaStatus.active) {
            log.warn("closeInternal called on non-active skladchina {}: status={}", skladchinaId, skladchina.status)
            return
        }
        val club = clubRepository.findById(skladchina.clubId)
            ?: throw NotFoundException("Club not found")

        val collected = skladchinaRepository.sumCollectedKopecks(skladchinaId)
        val finalStatus = computeFinalStatus(skladchina, collected, manualClose)
        val closedAt = OffsetDateTime.now()

        if (!skladchinaRepository.claimClose(skladchinaId, finalStatus, closedBy, closedAt)) {
            log.info("Skladchina close claim lost: id={} — already closed concurrently, no-op", skladchinaId)
            return
        }

        val deadlineReached = !closedAt.isBefore(skladchina.deadline)
        if (deadlineReached) {
            skladchinaRepository.expirePendingParticipants(skladchinaId)
        } else {
            skladchinaRepository.releasePendingParticipants(skladchinaId)
        }

        val totalParticipants = skladchinaRepository.countParticipants(skladchinaId)
        val paidCount = skladchinaRepository.countParticipantsByStatus(skladchinaId, SkladchinaParticipantStatus.paid)
        log.info("Skladchina closed: id={} status={} collected={} paid={}/{} pendingResolvedAs={}",
            skladchinaId, finalStatus, collected, paidCount, totalParticipants,
            if (deadlineReached) "expired_no_response" else "released")

        if (skladchina.affectsReputation) {
            applyReputationDeltas(skladchinaId, skladchina.clubId, club.ownerId, closedAt)
        }

        // Only THIS close can have produced expired_no_response rows (single claim winner,
        // statuses never leave terminal states), so the query is an exact list for the
        // "репутация снижена на 40" DM.
        val expiredUserIds = if (deadlineReached && skladchina.affectsReputation) {
            skladchinaRepository.findParticipants(skladchinaId)
                .filter { it.status == SkladchinaParticipantStatus.expired_no_response }
                .map { it.userId }
        } else {
            emptyList()
        }

        eventPublisher.publishEvent(
            SkladchinaClosedEvent(
                skladchinaId = skladchinaId,
                creatorId = skladchina.creatorId,
                clubName = club.name,
                title = skladchina.title,
                finalStatus = finalStatus,
                collectedKopecks = collected,
                totalGoalKopecks = skladchina.totalGoalKopecks,
                paidCount = paidCount,
                participantCount = totalParticipants,
                affectsReputation = skladchina.affectsReputation,
                expiredParticipantUserIds = expiredUserIds
            )
        )
    }

    private fun computeFinalStatus(
        skladchina: Skladchina,
        collected: Long,
        manualClose: Boolean
    ): SkladchinaStatus {
        val goal = skladchina.totalGoalKopecks
        return when {
            manualClose && (goal == null || collected < goal) -> SkladchinaStatus.cancelled
            goal == null && collected > 0 -> SkladchinaStatus.closed_success     // voluntary with any payments
            goal != null && collected >= goal -> SkladchinaStatus.closed_success
            goal != null && collected.toDouble() / goal >= SUCCESS_THRESHOLD -> SkladchinaStatus.closed_success
            else -> SkladchinaStatus.closed_failed
        }
    }

    /**
     * Routes skladchina outcomes into the finance axis of the reputation ledger
     * (idempotent — ON CONFLICT + per-participant reputation_applied guard).
     * Weights and the no-row statuses (declined / released) live in ReputationPolicy.
     * Anti-farm rule 1: the club owner does not accrue in their own club.
     * occurredAt = skladchina closed_at. reputation_applied is marked for EVERY
     * resolved participant, including the no-row ones — it means "the reputation
     * decision for this participant has been made", not "a ledger row exists".
     */
    private fun applyReputationDeltas(
        skladchinaId: UUID,
        clubId: UUID,
        ownerId: UUID,
        occurredAt: OffsetDateTime
    ) {
        val participants = skladchinaRepository.findParticipants(skladchinaId)
        val entries = mutableListOf<LedgerEntry>()
        val toMark = mutableListOf<UUID>()
        participants.forEach { p ->
            if (p.reputationApplied) return@forEach
            val kind = ReputationPolicy.financeKind(p.status)
            if (kind != null && p.userId != ownerId) {
                entries += LedgerEntry(
                    userId = p.userId,
                    clubId = clubId,
                    axis = ReputationAxis.finance,
                    kind = kind,
                    points = ReputationPolicy.pointsFor(kind),
                    occurredAt = occurredAt,
                    sourceType = ReputationSource.skladchina,
                    sourceId = skladchinaId
                )
            }
            toMark += p.userId
        }
        if (entries.isNotEmpty()) reputationService.appendAndRecompute(entries)
        // Mark AFTER the ledger append so reputation_applied can never precede the write.
        // closeInternal is @Transactional, so the marks and the append commit atomically
        // (or roll back together) — a failed append leaves reputation_applied=false for retry.
        toMark.forEach { skladchinaRepository.markReputationApplied(skladchinaId, it) }
    }

    private fun parseMode(modeStr: String): SkladchinaMode =
        SkladchinaMode.values().find { it.literal == modeStr }
            ?: throw ValidationException("Invalid payment mode: $modeStr")

    /**
     * Gates for the "важный сбор" toggle (affects_reputation = true), per the
     * 2026-06-12 redesign. Messages are user-facing (organizer's create form).
     */
    private fun validateReputationGates(clubId: UUID, mode: SkladchinaMode, deadlineHoursAhead: Long, now: OffsetDateTime) {
        if (mode == SkladchinaMode.voluntary) {
            // "Voluntary with a silence penalty" is an oxymoron — the toggle is fixed-modes only.
            throw ValidationException("Добровольный сбор не может влиять на репутацию")
        }
        if (deadlineHoursAhead < MIN_REPUTATION_DEADLINE_HOURS) {
            // Anti-"сбор-засада": participants must get a real window to answer
            // (creation DM + the 24h-before reminder DM).
            throw ValidationException("Для важного сбора дедлайн должен быть не раньше чем через 24 часа")
        }
        val recentCount = skladchinaRepository.countReputationAffectingCreatedSince(
            clubId, now.minusDays(REPUTATION_RATE_LIMIT_WINDOW_DAYS)
        )
        if (recentCount >= REPUTATION_RATE_LIMIT_MAX) {
            // The only real anti-farm AND anti-griefing mechanism: caps farming at
            // +30/week/club per friend and griefing at -120/week per ignored victim.
            throw ValidationException(
                "Лимит важных сборов: не больше $REPUTATION_RATE_LIMIT_MAX за " +
                    "$REPUTATION_RATE_LIMIT_WINDOW_DAYS дней в одном клубе. " +
                    "Создайте сбор без влияния на репутацию или попробуйте позже"
            )
        }
    }

    /**
     * Fixed modes: declared must equal the assigned share — a self-declared larger
     * amount let a participant "reach the goal" alone, instantly closing the
     * skladchina and (pre-released) minus-ing every pending participant (F5-02
     * amplifier), and painted a fake collected sum. Voluntary: sanity cap only.
     */
    private fun validateDeclaredAmount(mode: SkladchinaMode, expectedAmountKopecks: Long?, declaredAmountKopecks: Long) {
        when (mode) {
            SkladchinaMode.fixed_equal, SkladchinaMode.fixed_individual -> {
                if (declaredAmountKopecks != expectedAmountKopecks) {
                    throw ValidationException("Сумма оплаты должна совпадать с назначенной суммой")
                }
            }
            SkladchinaMode.voluntary -> {
                if (declaredAmountKopecks > DECLARED_AMOUNT_MAX_KOPECKS) {
                    throw ValidationException(
                        "Сумма не может превышать ${DECLARED_AMOUNT_MAX_KOPECKS / 100} ₽"
                    )
                }
            }
        }
    }

    private fun validateRequest(request: CreateSkladchinaRequest, mode: SkladchinaMode) {
        when (mode) {
            SkladchinaMode.fixed_equal -> {
                val total = request.totalGoalKopecks
                    ?: throw ValidationException("totalGoalKopecks required for fixed_equal mode")
                if (total <= 0) throw ValidationException("totalGoalKopecks must be positive")
            }
            SkladchinaMode.fixed_individual -> {
                request.participants.forEach { p ->
                    if (p.expectedAmountKopecks == null || p.expectedAmountKopecks <= 0) {
                        throw ValidationException("expectedAmountKopecks required for each participant in fixed_individual mode")
                    }
                }
            }
            SkladchinaMode.voluntary -> {
                // total ignored, expected ignored. Nothing to validate.
            }
        }
    }

    /**
     * For fixed_equal: split total equally with remainder going to last participant
     * (deterministic — last in sorted UUID order, see test).
     */
    private fun buildParticipantsForCreate(
        mode: SkladchinaMode,
        request: CreateSkladchinaRequest
    ): List<Pair<UUID, Long?>> {
        return when (mode) {
            SkladchinaMode.voluntary -> request.participants.map { it.userId to null }
            SkladchinaMode.fixed_individual -> request.participants.map { it.userId to it.expectedAmountKopecks }
            SkladchinaMode.fixed_equal -> {
                val total = request.totalGoalKopecks!!
                val n = request.participants.size
                val baseShare = total / n
                val remainder = total - baseShare * n
                val sorted = request.participants.sortedBy { it.userId }
                sorted.mapIndexed { idx, p ->
                    val amount = if (idx == n - 1) baseShare + remainder else baseShare
                    p.userId to amount
                }
            }
        }
    }

    companion object {
        private const val MIN_DEADLINE_HOURS = 1L
        private const val MAX_DEADLINE_DAYS = 90L
        private const val SUCCESS_THRESHOLD = 0.80     // fixed-mode: ≥80% → success at deadline

        // "Важный сбор" gates (docs/backlog/skladchina-reputation-redesign.md § Валидации):
        private const val MIN_REPUTATION_DEADLINE_HOURS = 24L   // anti-ambush; 48h rejected (breaks "бронь на завтра")
        private const val REPUTATION_RATE_LIMIT_MAX = 3         // per club, rolling window
        private const val REPUTATION_RATE_LIMIT_WINDOW_DAYS = 7L
        // Voluntary sanity cap: 100 000 ₽ — statistics hygiene, not an anti-abuse bound.
        private const val DECLARED_AMOUNT_MAX_KOPECKS = 10_000_000L
    }
}
