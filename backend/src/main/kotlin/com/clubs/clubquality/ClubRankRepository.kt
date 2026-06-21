package com.clubs.clubquality

import java.time.OffsetDateTime
import java.util.UUID

/** Owner-resistant per-account profile inputs to the L3 credibility weight (the non-footprint half). */
data class UserProfile(
    val userId: UUID,
    val createdAt: OffsetDateTime,
    val hasUsername: Boolean,
    val hasAvatar: Boolean,
)

interface ClubRankRepository {

    /**
     * Gathers raw L3 signals for every active club (grouped queries, no N+1). Every distinct-account
     * list is already filtered to be member-driven and owner-excluded at the query — the repository
     * shapes data, it does not score. [now] bounds all read windows deterministically.
     */
    fun findRankSignals(now: OffsetDateTime): List<ClubRankSignals>

    /** Profile half of the credibility inputs for the given users (the footprint half comes from the
     *  reputation [com.clubs.reputation.LedgerReadPort]). Empty input → empty output. */
    fun findUserProfiles(userIds: Collection<UUID>): Map<UUID, UserProfile>

    /** Idempotent upsert of the recomputed ranks (`ON CONFLICT (club_id) DO UPDATE`). */
    fun upsertRanks(ranks: List<ClubRank>)

    /** All currently-ranked clubs — the input set for the "★ Топ-5 в категории" badge computation. */
    fun findRankedClubs(): List<RankedClub>
}
