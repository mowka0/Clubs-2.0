package com.clubs.subscription

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Переходы по времени для платформенной подписки на сервисный сбор. Slice 1 закрывает окончание
 * периода у отменённых подписок (CANCELLED_PENDING_END → ENDED). Таймаут dunning для PAST_DUE
 * отложен, пока реальный эквайер не начнёт давать неудачные продления (стаб их никогда не фейлит) —
 * см. payment-v2.md §4.6.
 *
 * Названо `ServiceSubscription*`, чтобы отличаться от legacy Stars-membership
 * `com.clubs.payment.SubscriptionLifecycleService` (исключает конфликт имён Spring-бинов).
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
