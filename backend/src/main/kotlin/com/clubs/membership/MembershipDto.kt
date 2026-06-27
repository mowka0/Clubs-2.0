package com.clubs.membership

import java.time.OffsetDateTime
import java.util.UUID

data class MembershipDto(
    val id: UUID,
    val userId: UUID,
    val clubId: UUID,
    val status: String,
    val role: String,
    val joinedAt: OffsetDateTime?,
    val subscriptionExpiresAt: OffsetDateTime?,
    // Member's own dues claim (de-Stars): when they declared payment (null = none) + the method
    // ("sbp"|"cash"). Drives the member's «оплата на проверке» state on the frozen club screen.
    val duesClaimedAt: OffsetDateTime? = null,
    val duesClaimMethod: String? = null
)
