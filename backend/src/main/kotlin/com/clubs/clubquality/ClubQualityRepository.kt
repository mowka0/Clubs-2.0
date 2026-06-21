package com.clubs.clubquality

import java.util.UUID

interface ClubQualityRepository {

    /**
     * Computes the club's L1 facts via read-only aggregations.
     * Returns `null` when no club row exists for [clubId] (caller maps to 404).
     */
    fun findClubFacts(clubId: UUID): ClubFacts?

    /**
     * Batch-computes Discovery-card facts for the given clubs (one BATCHED query per metric, no N+1).
     * Skips ids with no club row. Empty input → empty output (no SQL hit). Order is unspecified;
     * the caller keys the result by [ClubCardFacts.clubId].
     */
    fun findClubCardFacts(clubIds: Collection<UUID>): List<ClubCardFacts>
}
