package com.clubs.subscription

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Time-driven transitions for the platform service-fee subscription. Slice 1 handles the period-end
 * of cancelled subscriptions (CANCELLED_PENDING_END → ENDED). PAST_DUE dunning timeout is deferred
 * until a real acquirer drives failed renewals (the stub never fails one) — see payment-v2.md §4.6.
 *
 * Named `ServiceSubscription*` to stay distinct from the legacy Stars-membership
 * `com.clubs.payment.SubscriptionLifecycleService` (avoids a Spring bean-name clash).
 */
@Service
class ServiceSubscriptionLifecycleService(
    private val repository: SubscriptionRepository,
) {

    private val log = LoggerFactory.getLogger(ServiceSubscriptionLifecycleService::class.java)

    @Transactional
    fun endElapsedSubscriptions(now: OffsetDateTime): Int {
        val ended = repository.endElapsedCancelled(now)
        if (ended > 0) log.info("Ended {} cancelled subscription(s) past period end", ended)
        return ended
    }
}
