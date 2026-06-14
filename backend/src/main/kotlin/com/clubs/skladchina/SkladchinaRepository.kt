package com.clubs.skladchina

import com.clubs.common.dto.PageResponse
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.SkladchinaStatus
import java.time.OffsetDateTime
import java.util.UUID

interface SkladchinaRepository {

    fun create(
        skladchina: Skladchina,
        participants: List<Pair<UUID, Long?>>          // (userId, expectedAmountKopecks)
    ): Skladchina

    fun findById(id: UUID): Skladchina?

    fun findActiveByClub(clubId: UUID): List<Skladchina>

    /**
     * Returns ALL skladchinas of the given club (any status when [includeCompleted] = true,
     * active-only otherwise) with batch-loaded aggregates (collected sum, participant
     * counts). Sorted by `created_at DESC, id ASC` for stable merge with the events feed.
     *
     * Used by the unified activity feed. Does NOT load any caller-specific fields —
     * caller resolves `myStatus` etc. on the detail screen, not in the feed.
     */
    fun findAllByClubWithAggregates(clubId: UUID, includeCompleted: Boolean): List<SkladchinaWithAggregates>

    fun findMyFeed(userId: UUID, page: Int, size: Int): PageResponse<MySkladchinaFeedItem>

    /**
     * Count of active skladchinas where the user is a participant still awaiting
     * payment (status='pending'). Mirrors the `actionRequired` flag the feed
     * computes per item — used to badge the "Сборы" tab so unpaid obligations
     * are not lost from sight.
     */
    fun countActionRequired(userId: UUID): Int

    fun findExpiredActive(now: OffsetDateTime): List<Skladchina>

    /**
     * Atomic close claim (F5-12): sets the final status/closed_at/closed_by ONLY if
     * the skladchina is still `active`. Returns false when another closer (scheduler ×
     * auto-close × manual) already won — the loser must no-op, so participants are
     * expired/released once and SkladchinaClosedEvent is published exactly once.
     * Same rows-affected pattern as JooqReputationRepository.claimEvent.
     */
    fun claimClose(id: UUID, status: SkladchinaStatus, closedBy: UUID?, closedAt: OffsetDateTime): Boolean

    /** Returns participants joined with user info — for organizer view + reputation hook. */
    fun findParticipantsWithInfo(skladchinaId: UUID): List<SkladchinaParticipantInfo>

    /** Plain participant records — for state-machine logic (mark paid, reputation hook). */
    fun findParticipants(skladchinaId: UUID): List<SkladchinaParticipant>

    fun findParticipant(skladchinaId: UUID, userId: UUID): SkladchinaParticipant?

    /**
     * Transitions the participant `pending` → `paid` (F5-03: guarded by
     * `WHERE status = 'pending'`). Returns affected rows — 0 means the participant
     * was concurrently expired/released/declined and the caller must 409.
     */
    fun setParticipantPaid(
        skladchinaId: UUID,
        userId: UUID,
        declaredAmountKopecks: Long,
        paidAt: OffsetDateTime
    ): Int

    /** Transitions `pending` → `declined`; same rows-affected contract as [setParticipantPaid]. */
    fun setParticipantDeclined(
        skladchinaId: UUID,
        userId: UUID,
        declinedAt: OffsetDateTime
    ): Int

    /** Move all `pending` participants to `expired_no_response` (close at/after the deadline). */
    fun expirePendingParticipants(skladchinaId: UUID): Int

    /**
     * Move all `pending` participants to `released` — the skladchina closed BEFORE its
     * deadline, so silence broke no promise (F5-02). Neutral: no ledger rows are
     * emitted for this status (ReputationPolicy.financeKind(released) = null).
     */
    fun releasePendingParticipants(skladchinaId: UUID): Int

    fun markReputationApplied(skladchinaId: UUID, userId: UUID)

    /** Sum of declared_amount for participants with status='paid'. */
    fun sumCollectedKopecks(skladchinaId: UUID): Long

    fun countParticipants(skladchinaId: UUID): Int

    fun countParticipantsByStatus(skladchinaId: UUID, status: SkladchinaParticipantStatus): Int

    /** Returns subset of given userIds that are NOT active members of given club. */
    fun findNonActiveMembers(clubId: UUID, userIds: Collection<UUID>): Set<UUID>

    /**
     * Count of the club's reputation-affecting skladchinas created after [since] —
     * feeds the "≤3 important skladchinas per club per rolling 7 days" rate limit
     * (the redesign's only real anti-farm AND anti-griefing mechanism).
     */
    fun countReputationAffectingCreatedSince(clubId: UUID, since: OffsetDateTime): Int

    /**
     * Active reputation-affecting skladchinas whose deadline falls in (now, until]
     * and whose reminder DM has not been sent yet — feed for SkladchinaReminderScheduler.
     */
    fun findNeedingDeadlineReminder(now: OffsetDateTime, until: OffsetDateTime): List<Skladchina>

    /** Dedup stamp for the deadline-reminder DM (set BEFORE sending, like event reminders). */
    fun markReminderSent(skladchinaId: UUID, at: OffsetDateTime)

    /**
     * Exit-with-obligations (P1b hole B): [userId]'s PENDING participations in [clubId]'s active,
     * reputation-affecting skladchinas — the finance obligations broken by leaving (each → a
     * skladchina_expired −40, occurred_at = deadline). Deadline is NOT filtered: the leave cascade
     * deletes every such participant row, so a deadline-passed pending would otherwise escape both
     * the exit penalty and natural expiry. The exit outcome equals what natural expiry would write
     * (−40), and a later natural row collides on the ledger UNIQUE — no double. Same scope the
     * cascade deletes ([deleteParticipantFromActiveSkladchinasInClub]). Read BEFORE the cascade.
     */
    fun findPendingReputationObligations(userId: UUID, clubId: UUID): List<SkladchinaObligation>

    /**
     * Cascade-delete on club leave: removes [userId] from every active skladchina
     * of [clubId]. Closed/cancelled skladchinas are preserved as historical
     * obligations. Returns number of rows deleted.
     */
    fun deleteParticipantFromActiveSkladchinasInClub(userId: UUID, clubId: UUID): Int

    /**
     * Club soft-delete cascade: cancels every active skladchina of [clubId] and releases its
     * pending participants (pending → released — the reputation-neutral terminal status, NOT
     * expired_no_response which would penalize). Deliberately bypasses
     * SkladchinaService.closeInternal so no reputation deltas and no SkladchinaClosedEvent DM
     * fire for a club that is being deleted. Already closed/cancelled skladchinas are left
     * untouched. Returns the number of skladchinas cancelled.
     */
    fun cancelActiveByClub(clubId: UUID): Int
}

/**
 * A pending reputation-affecting participation a leaving user abandons: the skladchina id
 * (ledger source_id) + its deadline (skladchina_expired occurred_at). Read on club leave.
 */
data class SkladchinaObligation(
    val skladchinaId: UUID,
    val deadline: OffsetDateTime
)

/**
 * Caller-agnostic feed row: a skladchina plus the aggregates used by the unified
 * activity-feed card. No `myStatus` / `clubName` — those belong to per-user views
 * (`MySkladchinaFeedItem`).
 */
data class SkladchinaWithAggregates(
    val skladchina: Skladchina,
    val collectedKopecks: Long,
    val participantCount: Int,
    val paidCount: Int
)
