package com.clubs.payment

import com.clubs.bot.NotificationService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

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
                "⚠️ Ваша подписка на клуб «${entry.clubName}» истекает через 3 дня. Продлите доступ в приложении."
            )
        }
        enteringGrace.forEach { entry ->
            notificationService.sendDirectMessage(
                entry.telegramId,
                "❗ Ваша подписка на клуб «${entry.clubName}» истекла. У вас есть 3 дня на пополнение баланса Stars, иначе доступ будет прекращён."
            )
        }

        // DB mutations — single short transaction via a separate bean to keep AOP proxying.
        lifecycleService.processExpiry(now)
    }
}
