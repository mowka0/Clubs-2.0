package com.clubs.subscription

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Управляет [ServiceSubscriptionLifecycleService]. Отличается от устаревшего Stars-membership
 * `com.clubs.payment.SubscriptionScheduler` (тот истекает платные membership; этот завершает
 * подписки на сервисный сбор платформы) — другое имя, чтобы избежать конфликта имён Spring-бинов.
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
