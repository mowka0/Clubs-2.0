package com.clubs.membership

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/** One club + the authenticated user's reputation in it. */
data class UserClubReputationDto(
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val category: String,
    val role: String,
    val joinedAt: OffsetDateTime?,
    // null = "Новичок"/suppressed (no track record yet, or owner in own club — use `role`
    // to render the organizer framing "репутация начисляется за организаторские качества").
    val reliabilityIndex: Int?,
    val promiseFulfillmentPct: BigDecimal?,
    val totalConfirmations: Int?,
    val totalAttendances: Int?
)
