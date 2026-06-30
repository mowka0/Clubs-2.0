package com.clubs.payment

import com.clubs.bot.NotificationService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * De-Stars (Slice 2): this scheduler is DORMANT. `subscription_expires_at` is no longer written (the
 * Stars pay-to-join flow is retired), so `processExpiry` / `find*` operate on zero rows. It is kept
 * (not deleted) to (1) let any legacy Stars-paid row still carrying an expiry drain naturally, and
 * (2) preserve the forward-only time-transition machinery the future §7 billing state-machine reuses.
 * NOTE: not to be confused with com.clubs.subscription.ServiceSubscriptionScheduler (the live
 * organizer service-fee subscription from Slice 1).
 */
@Component
class SubscriptionScheduler(
    private val lifecycleService: SubscriptionLifecycleService,
    private val notificationService: NotificationService
) {

    @Scheduled(cron = "0 0 9 * * *")
    fun checkSubscriptions() {
        val now = OffsetDateTime.now()

        // Notifications first — external IO, kept outside the DB transaction.
        // Read snapshots must be taken BEFORE processExpiry, otherwise the
        // about-to-expire rows are already moved to grace_period by then.
        val expiringSoon = lifecycleService.findExpiringWithin(now, now.plusDays(3))
        val enteringGrace = lifecycleService.findActiveExpired(now)

        expiringSoon.forEach { entry ->
            notificationService.sendDirectMessage(
                entry.telegramId,
                "⚠️ Ваш доступ к клубу «${entry.clubName}» истекает через 3 дня. Свяжитесь с организатором, чтобы продлить участие."
            )
        }
        enteringGrace.forEach { entry ->
            notificationService.sendDirectMessage(
                entry.telegramId,
                "❗ Ваш доступ к клубу «${entry.clubName}» истёк. Свяжитесь с организатором, чтобы продлить участие."
            )
        }

        // DB mutations — single short transaction via a separate bean to keep AOP proxying.
        lifecycleService.processExpiry(now)
    }
}
