package com.clubs.membership

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class MemberListItemDto(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val role: String,
    val joinedAt: OffsetDateTime?,
    // P1b Trust 0-100. null = "Новичок" (no track record yet, or owner in own club — frontend
    // uses `role` to render the organizer framing). Whole reputation block is suppressed when null.
    val trust: Int?,
    val promiseFulfillmentPct: BigDecimal?,
    val subscriptionCancelled: Boolean = false
)
