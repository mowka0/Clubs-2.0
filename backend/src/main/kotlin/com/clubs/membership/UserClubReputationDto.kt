package com.clubs.membership

import java.math.BigDecimal
import java.util.UUID

/** One club + the authenticated user's reputation in it. */
data class UserClubReputationDto(
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val category: String,
    val role: String,
    val reliabilityIndex: Int,
    val promiseFulfillmentPct: BigDecimal,
    val totalAttendances: Int
)
