package com.clubs.subscription

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Тики [BillingLifecycleService]. Отличается от устаревшего Stars-membership
 * `com.clubs.payment.SubscriptionScheduler` (тот истекает платные membership; этот ведёт
 * подписку платформы за чат) — другое имя, чтобы избежать конфликта имён Spring-бинов.
 */
@Component
class ServiceSubscriptionScheduler(
    private val lifecycleService: BillingLifecycleService,
) {

    /** Ежедневно: напоминания, дочерние списания, PAST_DUE, ENDED. */
    @Scheduled(cron = "\${subscription.lifecycle-cron:0 30 9 * * *}")
    fun runDaily() {
        lifecycleService.runDaily(OffsetDateTime.now())
    }

    /** Ежечасно: счета без ответа провайдера. */
    @Scheduled(cron = "\${billing.reconcile-cron:0 15 * * * *}")
    fun reconcilePending() {
        lifecycleService.reconcilePending(OffsetDateTime.now())
    }
}
