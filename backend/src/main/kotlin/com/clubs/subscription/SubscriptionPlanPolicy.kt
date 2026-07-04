package com.clubs.subscription

import com.clubs.generated.jooq.enums.SubscriptionPlan

/**
 * Ёмкость каждого плана (docs/modules/payment-v2.md §3.1). Единица счёта — ПЛАТНЫЙ клуб
 * (`clubs.subscription_price > 0`); бесплатные клубы безлимитны и не считаются вовсе. `max_paid_clubs` —
 * стабильный продуктовый инвариант: живёт здесь, в коде (не в БД), и охраняется [PricingInvariant].
 */
object SubscriptionPlanPolicy {

    fun maxPaidClubs(plan: SubscriptionPlan): Int = when (plan) {
        SubscriptionPlan.FREE -> 1
        SubscriptionPlan.TRIO -> 3
        SubscriptionPlan.UNLIMITED -> Int.MAX_VALUE
    }

    /** Наименьший план, ёмкости которого хватает на [paidClubs] платных клубов. */
    fun smallestPlanFor(paidClubs: Int): SubscriptionPlan = when {
        paidClubs <= maxPaidClubs(SubscriptionPlan.FREE) -> SubscriptionPlan.FREE
        paidClubs <= maxPaidClubs(SubscriptionPlan.TRIO) -> SubscriptionPlan.TRIO
        else -> SubscriptionPlan.UNLIMITED
    }

    /** Ёмкость для отображения: null = безлимит (чтобы API никогда не отдавал наружу Int.MAX_VALUE). */
    fun displayMaxPaidClubs(plan: SubscriptionPlan): Int? =
        maxPaidClubs(plan).takeIf { it != Int.MAX_VALUE }
}
