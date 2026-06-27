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
    // Member-initiated dues claim (de-Stars): the frozen member declared they paid. duesClaimedAt = when
    // (null = no claim); duesClaimMethod = "sbp"|"cash"; duesProofUrl = screenshot (sbp only). Cleared once
    // the organizer grants access. Defaults null so existing test builders don't all need updating.
    val duesClaimedAt: OffsetDateTime? = null,
    val duesClaimMethod: String? = null,
    val duesProofUrl: String? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
)
