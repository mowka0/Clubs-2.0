package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import java.time.OffsetDateTime
import java.util.UUID

data class Membership(
    val id: UUID,
    val userId: UUID,
    val clubId: UUID,
    val status: MembershipStatus,
    val role: MembershipRole,
    val joinedAt: OffsetDateTime,
    val subscriptionExpiresAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
)
