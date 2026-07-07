package com.clubs.payment

import com.clubs.bot.NotificationService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Ежедневный жизненный цикл honor-system окна доступа (de-Stars Slice 2). НЕ спит: хотя Stars-flow
 * упразднён, `subscription_expires_at` снова записывается организаторскими действиями «Взнос получен»
 * (markDuesPaid, +30 дн) и «Своя дата» (setAccessUntil), поэтому планировщик по крону
 * `membership.expiry-cron` (дефолт — ежедневно 9:00):
 *  1) шлёт DM «истекает через 3 дня» и «истёк» (внешний IO до транзакции);
 *  2) processExpiry: каждый active с истёкшим окном → expired (доступ закрыт до подтверждения
 *     следующего взноса, участник остаётся в клубе должником). Жёсткое отсечение — решение PO (de-Stars).
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

    // Крон конфигурируем (membership.expiry-cron, env MEMBERSHIP_EXPIRY_CRON): прод — ежедневно 9:00,
    // staging может тикать каждые пару минут для теста DM-уведомлений и авто-истечения.
    @Scheduled(cron = "\${membership.expiry-cron}")
    fun checkSubscriptions() {
        val now = OffsetDateTime.now()

        // Сначала уведомления — внешний IO, вынесен за пределы DB-транзакции.
        // Снимки для чтения нужно брать ДО processExpiry, иначе строки,
        // которым вот-вот истечёт срок, к тому моменту уже перейдут в expired.
        val expiringSoon = lifecycleService.findExpiringWithin(now, now.plusDays(3))
        val nowExpired = lifecycleService.findActiveExpired(now)

        expiringSoon.forEach { entry ->
            // Кнопка ведёт на «Мои клубы» — там в окне T-3 живёт секция «Подписка истекает»
            // с «Продлить подписку» (раннее продление, membership-lifecycle.md §7).
            notificationService.sendDirectMessageWithDeepLink(
                entry.telegramId,
                "⚠️ Ваша подписка на клуб «${entry.clubName}» истекает через 3 дня. Продлите взнос, чтобы не потерять доступ.",
                webAppPath = "/my-clubs",
                buttonText = "Продлить подписку"
            )
        }
        nowExpired.forEach { entry ->
            // Кнопка-диплинк ведёт на страницу клуба, где expired-участник заявляет оплату
            // («Оплатить взнос» → claim → организатор подтверждает «Взнос получен»).
            notificationService.sendDirectMessageWithDeepLink(
                entry.telegramId,
                "❗ Ваш доступ к клубу «${entry.clubName}» истёк — подписка закончилась. Оплатите взнос организатору, чтобы вернуть доступ.",
                webAppPath = "/clubs/${entry.clubId}",
                buttonText = "Оплатить взнос"
            )
        }

        // Изменения в БД — одна короткая транзакция через отдельный бин, чтобы сохранить AOP-проксирование.
        lifecycleService.processExpiry(now)
    }
}
