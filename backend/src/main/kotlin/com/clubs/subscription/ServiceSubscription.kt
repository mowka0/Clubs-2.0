package com.clubs.subscription

import com.clubs.generated.jooq.enums.SubscriptionPayerRole
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Platform service-fee subscription (docs/modules/payment-v2.md §5.1). Flat monthly: runs the whole
 * paid period regardless of club activity, never pauses, ends only at [currentPeriodEnd].
 */
data class ServiceSubscription(
    val id: UUID,
    val payerUserId: UUID,
    val payerRole: SubscriptionPayerRole,
    val plan: SubscriptionPlan,
    /** NULL = platform-wide organizer capacity plan; club-scoped for member-pays (phase 2). */
    val subjectClubId: UUID?,
    val status: SubscriptionStatus,
    val currentPeriodEnd: OffsetDateTime,
    val providerToken: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)
