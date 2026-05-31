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

    fun updateStatus(id: UUID, status: SkladchinaStatus, closedBy: UUID?, closedAt: OffsetDateTime)

    /** Returns participants joined with user info — for organizer view + reputation hook. */
    fun findParticipantsWithInfo(skladchinaId: UUID): List<SkladchinaParticipantInfo>

    /** Plain participant records — for state-machine logic (mark paid, reputation hook). */
    fun findParticipants(skladchinaId: UUID): List<SkladchinaParticipant>

    fun findParticipant(skladchinaId: UUID, userId: UUID): SkladchinaParticipant?

    fun setParticipantPaid(
        skladchinaId: UUID,
        userId: UUID,
        declaredAmountKopecks: Long,
        paidAt: OffsetDateTime
    ): Int

    fun setParticipantDeclined(
        skladchinaId: UUID,
        userId: UUID,
        declinedAt: OffsetDateTime
    ): Int

    /** Move all `pending` participants to `expired_no_response` for given skladchina. */
    fun expirePendingParticipants(skladchinaId: UUID): Int

    fun markReputationApplied(skladchinaId: UUID, userId: UUID)

    /** Sum of declared_amount for participants with status='paid'. */
    fun sumCollectedKopecks(skladchinaId: UUID): Long

    fun countParticipants(skladchinaId: UUID): Int

    fun countParticipantsByStatus(skladchinaId: UUID, status: SkladchinaParticipantStatus): Int

    /** Returns subset of given userIds that are NOT active members of given club. */
    fun findNonActiveMembers(clubId: UUID, userIds: Collection<UUID>): Set<UUID>

    /**
     * Cascade-delete on club leave: removes [userId] from every active skladchina
     * of [clubId]. Closed/cancelled skladchinas are preserved as historical
     * obligations. Returns number of rows deleted.
     */
    fun deleteParticipantFromActiveSkladchinasInClub(userId: UUID, clubId: UUID): Int
}

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
