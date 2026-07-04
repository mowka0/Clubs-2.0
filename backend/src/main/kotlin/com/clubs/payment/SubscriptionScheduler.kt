package com.clubs.payment

import com.clubs.bot.NotificationService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * De-Stars (Slice 2): этот планировщик СПИТ. `subscription_expires_at` больше не записывается
 * (flow оплаты вступления через Stars упразднён), поэтому `processExpiry` / `find*` работают
 * с нулём строк. Оставлен (не удалён), чтобы (1) дать любым legacy-строкам с оплатой через Stars,
 * ещё несущим expiry, естественно истечь, и (2) сохранить машинерию однонаправленных временных
 * переходов, которую переиспользует будущая биллинговая state-machine из §7.
 * ПРИМЕЧАНИЕ: не путать с com.clubs.subscription.ServiceSubscriptionScheduler (живая подписка
 * на сервисный сбор организатора из Slice 1).
 */
@Component
class SubscriptionScheduler(
    private val lifecycleService: SubscriptionLifecycleService,
    private val notificationService: NotificationService
) {

    @Scheduled(cron = "0 0 9 * * *")
    fun checkSubscriptions() {
        val now = OffsetDateTime.now()

        // Сначала уведомления — внешний IO, вынесен за пределы DB-транзакции.
        // Снимки для чтения нужно брать ДО processExpiry, иначе строки,
        // которым вот-вот истечёт срок, к тому моменту уже перейдут в grace_period.
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

        // Изменения в БД — одна короткая транзакция через отдельный бин, чтобы сохранить AOP-проксирование.
        lifecycleService.processExpiry(now)
    }
}
