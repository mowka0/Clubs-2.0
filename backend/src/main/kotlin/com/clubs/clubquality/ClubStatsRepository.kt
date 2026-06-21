package com.clubs.clubquality

import java.util.UUID

interface ClubStatsRepository {

    /**
     * Computes the owner-only club statistics via read-only windowed aggregations.
     * Returns `null` when no club row exists for [clubId] (caller maps to 404). In practice the
     * `@RequiresOrganizer` aspect already rejects a missing club before the service runs.
     */
    fun findClubStats(clubId: UUID): ClubStats?
}
