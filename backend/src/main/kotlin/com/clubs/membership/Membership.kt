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
    // Private organizer note (member admin profile S1). Null = none. Organizer-only on read.
    // Default null so existing Membership(...) test builders don't all need updating; prod sets it via the mapper.
    val organizerNote: String? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
)
