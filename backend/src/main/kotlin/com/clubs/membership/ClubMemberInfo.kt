package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipRole
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Projection of MEMBERSHIPS x USERS x USER_CLUB_REPUTATION used by the club
 * members list. Lives in the membership module because it is built around a
 * membership row plus joined user/reputation fields.
 *
 * [subscriptionCancelled] is true when membership row has status=cancelled
 * with `subscription_expires_at > now()` — paid member who cancelled but
 * still in the paid period. Used by skladchina-create UI to surface
 * the "user opted out" hint while keeping them visible until expire.
 */
data class ClubMemberInfo(
    val userId: UUID,
    val firstName: String?,
    val lastName: String?,
    val avatarUrl: String?,
    val role: MembershipRole,
    val joinedAt: OffsetDateTime,
    // Raw cache values (nullable when the user has no reputation row in this club);
    // the "Новичок" threshold (outcomeCount) is applied by the mapper at the DTO edge.
    val reliabilityIndex: Int?,
    val promiseFulfillmentPct: BigDecimal?,
    val outcomeCount: Int,
    val subscriptionCancelled: Boolean = false
)
