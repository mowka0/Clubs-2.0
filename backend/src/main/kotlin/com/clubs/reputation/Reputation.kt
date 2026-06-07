package com.clubs.reputation

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Cached per-club reputation aggregate, derived (recomputed) from reputation_ledger.
 * `reliabilityIndex` is always the TRUE Σ of points (NOT NULL) — the "Новичок"
 * display threshold (ReputationPolicy.isShown(outcomeCount)) is applied at the DTO
 * boundary, never here.
 */
data class Reputation(
    val userId: UUID,
    val clubId: UUID,
    val reliabilityIndex: Int,
    val promiseFulfillmentPct: BigDecimal,
    val totalConfirmations: Int,
    val totalAttendances: Int,
    val spontaneityCount: Int,
    val outcomeCount: Int,
    val updatedAt: OffsetDateTime = OffsetDateTime.now()
)

/**
 * Cross-club aggregate of one user's reputation rows.
 * memberClubCount = number of clubs the user has a reputation row in (i.e. clubs
 * with a track record; owners do not accrue in their own club by anti-farm rule 1).
 * totalConfirmations / totalAttendances = SUM over those rows.
 */
data class PeerStatsAggregate(
    val memberClubCount: Int,
    val totalConfirmations: Int,
    val totalAttendances: Int
) {
    companion object {
        val EMPTY = PeerStatsAggregate(0, 0, 0)
    }
}
