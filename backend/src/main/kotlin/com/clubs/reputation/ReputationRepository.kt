package com.clubs.reputation

import java.util.UUID

interface ReputationRepository {

    // --- Reads (consumed by MemberService, peer-signal) ---

    fun findByUserAndClub(userId: UUID, clubId: UUID): Reputation?

    /**
     * Cross-club reputation aggregate for a batch of users (one SQL query).
     * Returns `userId → aggregate`. Users absent from `user_club_reputation`
     * are absent from the map; callers default to [PeerStatsAggregate.EMPTY].
     * Empty input → emptyMap (no SQL hit).
     */
    fun aggregateByUserIds(userIds: Collection<UUID>): Map<UUID, PeerStatsAggregate>

    /**
     * All of a user's ledger outcomes across ALL clubs (incl. left clubs) — the on-read source
     * for P1b Trust + the all-history global aggregate. No active-membership / is_active filter:
     * a left club's penalty survives in the ledger and must still count toward the global view.
     */
    fun findTrustOutcomesByUser(userId: UUID): List<ClubLedgerOutcome>

    /**
     * Batch variant of [findTrustOutcomesByUser]: every ledger outcome for a set of users in one
     * query, grouped `userId → outcomes`. Powers the applicant cross-club signal (level + "N из M")
     * for the organizer inbox without an N+1 over applicants. Users with no outcomes are absent from
     * the map; empty input → emptyMap (no SQL hit).
     */
    fun findOutcomesByUserIds(userIds: Collection<UUID>): Map<UUID, List<ClubLedgerOutcome>>

    /**
     * All ledger outcomes in one club, one row per (user, outcome) — the batch source for
     * per-member Trust on the club member list (avoids an N+1 over members).
     */
    fun findClubMemberOutcomes(clubId: UUID): List<MemberLedgerOutcome>

    // --- Ledger pipeline (write side, source of truth) ---

    /**
     * Atomically claims an event for reputation processing:
     * `UPDATE events SET reputation_processed=true WHERE id=? AND NOT reputation_processed`.
     * Returns true iff this caller won the claim (a row was updated). Makes the
     * event listener and the hourly poll mutually exclusive — the loser no-ops.
     */
    fun claimEvent(eventId: UUID): Boolean

    /** Finalized+marked events not yet reputation-processed (poll backstop). */
    fun findPendingFinalizedEventIds(): List<UUID>

    /** Club id + owner id + event datetime, for building attendance ledger rows. */
    fun findEventContext(eventId: UUID): EventReputationContext?

    /** Confirmed responses of an event (the only ones that yield a ledger row). */
    fun findConfirmedResponses(eventId: UUID): List<ConfirmedResponse>

    /** Append ledger rows, skipping any that already exist (ON CONFLICT DO NOTHING). */
    fun appendLedgerIfAbsent(entries: List<LedgerEntry>)

    /**
     * Recomputes the user_club_reputation cache row for (user, club) purely from the
     * ledger via an atomic upsert (ON CONFLICT (user_id, club_id) DO UPDATE). Idempotent
     * and commutative under concurrency — both racers derive identical values.
     */
    fun recompute(userId: UUID, clubId: UUID)
}
