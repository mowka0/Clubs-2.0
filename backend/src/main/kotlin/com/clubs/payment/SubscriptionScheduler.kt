package com.clubs.payment

import com.clubs.bot.NotificationService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Ежедневный жизненный цикл honor-system окна доступа (de-Stars Slice 2). НЕ спит: хотя Stars-flow
 * упразднён, `subscription_expires_at` снова записывается организаторскими действиями «Взнос получен»
 * (markDuesPaid, +30 дн) и «Своя дата» (setAccessUntil), поэтому планировщик ежедневно в 9:00:
 *  1) шлёт DM «истекает через 3 дня» и «истёк» (внешний IO до транзакции);
 *  2) processExpiry: каждый active с истёкшим окном → frozen (доступ закрыт до подтверждения
 *     следующего взноса). Жёсткое отсечение без grace-периода — решение PO (de-Stars).
 * Прежний комментарий «планировщик спит» был написан в момент смерти Stars-flow и устарел с
 * появлением honor-system (этот факт уже вводил в заблуждение при ревью — не возвращать его).
 * ПРИМЕЧАНИЕ: не путать с com.clubs.subscription.ServiceSubscriptionScheduler (подписка
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
