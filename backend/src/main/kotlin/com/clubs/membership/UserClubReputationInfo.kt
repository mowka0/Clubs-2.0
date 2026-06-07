package com.clubs.membership

import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.MembershipRole
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Projection of MEMBERSHIPS x CLUBS x USER_CLUB_REPUTATION used by the
 * authenticated user's reputation overview (Profile tab). One row per club the
 * user is an active member of, carrying that club's reliability index for them.
 */
data class UserClubReputationInfo(
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val category: ClubCategory,
    val role: MembershipRole,
    val joinedAt: OffsetDateTime?,
    // Raw cache values (nullable when no reputation row); threshold applied by mapper.
    val reliabilityIndex: Int?,
    val promiseFulfillmentPct: BigDecimal?,
    val totalConfirmations: Int?,
    val totalAttendances: Int?,
    val outcomeCount: Int
)
