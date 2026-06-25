package com.clubs.subscription

import com.clubs.generated.jooq.enums.SubscriptionPlan

/**
 * Free-floor + no-cliff volume-discount invariant (docs/modules/payment-v2.md §3.2/§3.4).
 *
 * Guards the LOCKED product rule independent of the launch numbers, so a future price tweak can never
 * silently reintroduce the rejected "punish the next club" ladder. Pure → unit-tested directly; also
 * suitable for a fail-fast startup check against the seeded `subscription_pricing` rows.
 */
object PricingInvariant {

    fun violations(priceKopecksByPlan: Map<SubscriptionPlan, Int>): List<String> {
        val free = priceKopecksByPlan[SubscriptionPlan.FREE]
        val trio = priceKopecksByPlan[SubscriptionPlan.TRIO]
        val unlimited = priceKopecksByPlan[SubscriptionPlan.UNLIMITED]
        if (free == null || trio == null || unlimited == null) {
            return listOf("pricing missing for one or more plans: $priceKopecksByPlan")
        }
        val errors = mutableListOf<String>()
        // FREE is the giveaway floor — protects the single-club hobbyist / discovery engine.
        if (free != 0) errors += "FREE must be 0 (giveaway floor), was $free"
        // Total price is non-decreasing for more capacity.
        if (trio < free) errors += "TRIO ($trio) must be >= FREE ($free)"
        if (unlimited < trio) errors += "UNLIMITED ($unlimited) must be >= TRIO ($trio)"
        // No-cliff: UNLIMITED opens cheaply, at most 2× the entry paid tier.
        if (unlimited > 2 * trio) errors += "UNLIMITED ($unlimited) must be <= 2×TRIO (${2 * trio}) [no-cliff]"
        return errors
    }

    fun isValid(priceKopecksByPlan: Map<SubscriptionPlan, Int>): Boolean =
        violations(priceKopecksByPlan).isEmpty()
}
