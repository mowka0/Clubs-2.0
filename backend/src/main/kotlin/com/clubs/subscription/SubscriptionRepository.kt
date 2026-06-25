package com.clubs.subscription

import com.clubs.generated.jooq.enums.SubscriptionPayerRole
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import java.time.OffsetDateTime
import java.util.UUID

interface SubscriptionRepository {

    fun create(
        payerUserId: UUID,
        payerRole: SubscriptionPayerRole,
        plan: SubscriptionPlan,
        subjectClubId: UUID?,
        currentPeriodEnd: OffsetDateTime,
        providerToken: String?,
    ): ServiceSubscription

    /** The organizer's live (non-ENDED) platform-wide plan, or null when on implicit FREE. */
    fun findActiveOrganizerSubscription(payerUserId: UUID): ServiceSubscription?

    fun findByProviderToken(providerToken: String): ServiceSubscription?

    fun updatePlan(id: UUID, plan: SubscriptionPlan): Int

    /** Forward-only status move; returns rows affected (0 = concurrent change, caller guards). */
    fun transitionStatus(id: UUID, from: Collection<SubscriptionStatus>, to: SubscriptionStatus): Int

    fun extendPeriod(id: UUID, newPeriodEnd: OffsetDateTime): Int

    /** CANCELLED_PENDING_END rows whose period has elapsed → ENDED. Returns count. */
    fun endElapsedCancelled(now: OffsetDateTime): Int

    /** Inbound-webhook idempotency: inserts the event; false if [providerEventId] was already seen. */
    fun recordEventIfNew(subscriptionId: UUID, providerEventId: String, kind: String): Boolean

    /** Current price for [plan] = newest row with effective_from <= now. */
    fun currentPriceKopecks(plan: SubscriptionPlan): Int
}
