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
    val subscriptionExpiresAt: OffsetDateTime?
)
