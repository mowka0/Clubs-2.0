package com.clubs.subscription

import com.clubs.generated.jooq.enums.SubscriptionPlan

/**
 * Capacity per plan (docs/modules/payment-v2.md §3.1). The unit is a PAID club
 * (`clubs.subscription_price > 0`); free clubs are unlimited and never counted. `max_paid_clubs`
 * is a stable product invariant — it lives here in code (not the DB), guarded by [PricingInvariant].
 */
object SubscriptionPlanPolicy {

    fun maxPaidClubs(plan: SubscriptionPlan): Int = when (plan) {
        SubscriptionPlan.FREE -> 1
        SubscriptionPlan.TRIO -> 3
        SubscriptionPlan.UNLIMITED -> Int.MAX_VALUE
    }

    /** Smallest plan whose capacity covers [paidClubs] paid clubs. */
    fun smallestPlanFor(paidClubs: Int): SubscriptionPlan = when {
        paidClubs <= maxPaidClubs(SubscriptionPlan.FREE) -> SubscriptionPlan.FREE
        paidClubs <= maxPaidClubs(SubscriptionPlan.TRIO) -> SubscriptionPlan.TRIO
        else -> SubscriptionPlan.UNLIMITED
    }

    /** Capacity for display: null = unlimited (so the API never leaks Int.MAX_VALUE). */
    fun displayMaxPaidClubs(plan: SubscriptionPlan): Int? =
        maxPaidClubs(plan).takeIf { it != Int.MAX_VALUE }
}
