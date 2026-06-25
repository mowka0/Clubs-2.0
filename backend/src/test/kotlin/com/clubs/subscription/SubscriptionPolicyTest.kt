package com.clubs.subscription

import com.clubs.generated.jooq.enums.SubscriptionPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the capacity ladder + the free-floor/no-cliff pricing invariant (docs/modules/payment-v2.md
 * §3.1–3.4). Pure — guards the LOCKED product rule independent of the launch numbers.
 */
class SubscriptionPolicyTest {

    private val launchGrid = mapOf(
        SubscriptionPlan.FREE to 0,
        SubscriptionPlan.TRIO to 20000,
        SubscriptionPlan.UNLIMITED to 40000,
    )

    @Test
    fun `launch grid satisfies the free-floor and no-cliff invariant`() {
        assertTrue(
            PricingInvariant.isValid(launchGrid),
            "launch grid must be valid: ${PricingInvariant.violations(launchGrid)}",
        )
    }

    @Test
    fun `FREE must be zero`() {
        assertFalse(PricingInvariant.isValid(launchGrid + (SubscriptionPlan.FREE to 100)))
    }

    @Test
    fun `no-cliff rejects UNLIMITED above twice TRIO`() {
        assertFalse(PricingInvariant.isValid(launchGrid + (SubscriptionPlan.UNLIMITED to 40001)))
    }

    @Test
    fun `UNLIMITED must not be cheaper than TRIO`() {
        assertFalse(PricingInvariant.isValid(launchGrid + (SubscriptionPlan.UNLIMITED to 19000)))
    }

    @Test
    fun `missing plan is a violation`() {
        assertFalse(PricingInvariant.isValid(mapOf(SubscriptionPlan.FREE to 0, SubscriptionPlan.TRIO to 20000)))
    }

    @Test
    fun `max paid clubs per plan`() {
        assertEquals(1, SubscriptionPlanPolicy.maxPaidClubs(SubscriptionPlan.FREE))
        assertEquals(3, SubscriptionPlanPolicy.maxPaidClubs(SubscriptionPlan.TRIO))
        assertEquals(Int.MAX_VALUE, SubscriptionPlanPolicy.maxPaidClubs(SubscriptionPlan.UNLIMITED))
    }

    @Test
    fun `smallest plan covering N paid clubs`() {
        assertEquals(SubscriptionPlan.FREE, SubscriptionPlanPolicy.smallestPlanFor(1))
        assertEquals(SubscriptionPlan.TRIO, SubscriptionPlanPolicy.smallestPlanFor(2))
        assertEquals(SubscriptionPlan.TRIO, SubscriptionPlanPolicy.smallestPlanFor(3))
        assertEquals(SubscriptionPlan.UNLIMITED, SubscriptionPlanPolicy.smallestPlanFor(4))
    }

    @Test
    fun `display capacity hides Int MAX for unlimited`() {
        assertEquals(1, SubscriptionPlanPolicy.displayMaxPaidClubs(SubscriptionPlan.FREE))
        assertNull(SubscriptionPlanPolicy.displayMaxPaidClubs(SubscriptionPlan.UNLIMITED))
    }
}
