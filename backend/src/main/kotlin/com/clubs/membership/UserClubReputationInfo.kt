package com.clubs.membership

import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.MembershipRole
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Projection of MEMBERSHIPS x CLUBS x USER_CLUB_REPUTATION used by the authenticated user's
 * reputation overview (Profile tab). One row per club the user has in scope: currently-active
 * clubs AND left clubs that still carry a reputation track record (the "История" section).
 */
data class UserClubReputationInfo(
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val category: ClubCategory,
    val role: MembershipRole,
    val joinedAt: OffsetDateTime?,
    // Raw cache siblings (nullable when no reputation row); threshold applied by mapper. P1b shows
    // Trust (computed in MembershipService), not the raw index.
    val promiseFulfillmentPct: BigDecimal?,
    val totalConfirmations: Int?,
    val totalAttendances: Int?,
    val spontaneityCount: Int?,
    val outcomeCount: Int,
    // true = currently-active club (member has access AND club is active); false = "История"
    // (left/expired membership, included only because a reputation track record survives).
    val active: Boolean
)
