package com.clubs.clubquality

import java.util.UUID

interface ClubQualityRepository {

    /**
     * Computes the club's L1 facts via read-only aggregations.
     * Returns `null` when no club row exists for [clubId] (caller maps to 404).
     */
    fun findClubFacts(clubId: UUID): ClubFacts?
}
