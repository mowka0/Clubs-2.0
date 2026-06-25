package com.clubs.subscription

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Drives [ServiceSubscriptionLifecycleService]. Distinct from the legacy Stars-membership
 * `com.clubs.payment.SubscriptionScheduler` (that one expires paid memberships; this one ends
 * platform service-fee subscriptions) — different name avoids a Spring bean-name clash.
 */
@Component
class ServiceSubscriptionScheduler(
    private val lifecycleService: ServiceSubscriptionLifecycleService,
) {

    @Scheduled(cron = "\${subscription.lifecycle-cron:0 30 9 * * *}")
    fun endElapsedSubscriptions() {
        lifecycleService.endElapsedSubscriptions(OffsetDateTime.now())
    }
}
