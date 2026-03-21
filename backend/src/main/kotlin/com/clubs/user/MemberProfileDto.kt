package com.clubs.user

import java.math.BigDecimal
import java.util.UUID

data class MemberProfileDto(
    val userId: UUID,
    val clubId: UUID,
    val firstName: String,
    val username: String?,
    val avatarUrl: String?,
    val reliabilityIndex: Int,
    val promiseFulfillmentPct: BigDecimal,
    val totalConfirmations: Int,
    val totalAttendances: Int
)
