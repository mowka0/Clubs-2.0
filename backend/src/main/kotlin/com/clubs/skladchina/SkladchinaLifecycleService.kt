package com.clubs.skladchina

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.ReputationAxis
import com.clubs.generated.jooq.enums.ReputationSource
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
import java.util.UUID

/**
 * Closure side of the skladchina engine: manual close, scheduler/auto-close, final-status
 * computation and the reputation deltas applied at close. Split out of the former
 * god-`SkladchinaService` by responsibility.
 *
 * [maybeAutoCloseAfterStateChange] and [closeInternal] live in THIS bean on purpose: a participant
 * action (SkladchinaPaymentService) invokes maybeAutoClose within its own @Transactional, and
 * maybeAutoClose → closeInternal is a SELF-invocation here (no proxy), so closeInternal joins the
 * caller's transaction and a caught application error never marks it rollback-only (F5-18). Moving
 * either across a bean boundary would change that rollback semantics.
 */
@Service
class SkladchinaLifecycleService(
    private val skladchinaRepository: SkladchinaRepository,
    private val clubRepository: ClubRepository,
    private val reputationService: ReputationService,
    private val eventPublisher: ApplicationEventPublisher,
    private val queryService: SkladchinaQueryService
) {
    private val log = LoggerFactory.getLogger(SkladchinaLifecycleService::class.java)

    /**
     * Auto-close trigger that fires after mark-paid / decline / organizer-mark-paid.
     * Closes ONLY when every participant is in a terminal status (no more `pending`).
     *
     * Phase A (A-4): goal-reached NO LONGER force-closes (it was the source of F5-02
     * complexity). Money is decoration now — the app is a tracker, not a payment system —
     * so collecting the target early just sits there; closure is by deadline / manual /
     * everyone-answered. Org-unmark never reaches here (it only grows `pending`).
     *
     * F5-18: a close/reputation failure is caught and logged HERE so the participant's
     * own markPaid/decline never 500s because of it (NFR skladchina.md). Scope note:
     * maybeAutoClose runs in the caller's transaction and closeInternal is a self-invocation,
     * so the catch shields against application-level failures; a DB-level error inside
     * closeInternal still aborts the shared Postgres transaction.
     */
    fun maybeAutoCloseAfterStateChange(skladchinaId: UUID) {
        val skladchina = skladchinaRepository.findById(skladchinaId) ?: return
        if (skladchina.status != SkladchinaStatus.active) return

        val noPendingLeft = skladchinaRepository.countParticipantsByStatus(
            skladchinaId, SkladchinaParticipantStatus.pending
        ) == 0
        if (!noPendingLeft) return

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
        return queryService.getDetail(skladchinaId, callerId)
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
     * transactional participant actions self-invoke this via maybeAutoCloseAfterStateChange and
     * simply run inside their existing tx. Atomicity guarantees a ledger-append failure rolls back
     * the claim and the reputation_applied marks too, so a retry can recover (no orphaned participants).
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

    companion object {
        private const val SUCCESS_THRESHOLD = 0.80     // fixed-mode: ≥80% → success at deadline
    }
}
