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
    // P1b Trust 0-100. null = "Новичок"/suppressed (no track record yet — outcome_count below the
    // display threshold — or owner in own club; use `role` to render the organizer framing).
    val trust: Int?,
    val promiseFulfillmentPct: BigDecimal?,
    val totalConfirmations: Int?,
    val totalAttendances: Int?,
    val spontaneityCount: Int?
)
