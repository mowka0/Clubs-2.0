package com.clubs.payment

import com.clubs.bot.NotificationService
import com.clubs.membership.ExpiryReminderCandidate
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Ежедневный жизненный цикл honor-system окна доступа (de-Stars Slice 2). НЕ спит: хотя Stars-flow
 * упразднён, `subscription_expires_at` снова записывается организаторскими действиями «Взнос получен»
 * (markDuesPaid, +30 дн) и «Своя дата» (setAccessUntil), поэтому планировщик по крону
 * `membership.expiry-cron` (дефолт — ежедневно 9:00):
 *  1) шлёт DM «истекает» (пороги 3 и 1 день, ровно по одному разу — см. [ExpiryReminderRules])
 *     и «истёк» (внешний IO до транзакции);
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

    private val log = LoggerFactory.getLogger(SubscriptionScheduler::class.java)

    // Крон конфигурируем (membership.expiry-cron, env MEMBERSHIP_EXPIRY_CRON): прод — ежедневно 9:00,
    // staging может тикать каждые пару минут для теста DM-уведомлений и авто-истечения.
    @Scheduled(cron = "\${membership.expiry-cron}")
    fun checkSubscriptions() {
        val now = OffsetDateTime.now()

        // Сначала уведомления — внешний IO, вынесен за пределы DB-транзакции.
        // Снимки для чтения нужно брать ДО processExpiry, иначе строки,
        // которым вот-вот истечёт срок, к тому моменту уже перейдут в expired.
        val reminderCandidates = lifecycleService.findExpiryReminderCandidates(
            now, now.plusDays(ExpiryReminderRules.SCAN_HORIZON_DAYS)
        )
        val nowExpired = lifecycleService.findActiveExpired(now)

        sendExpiryReminders(now, reminderCandidates)

        nowExpired.forEach { entry ->
            // Кнопка-диплинк ведёт на страницу клуба с СРАЗУ открытым шитом взноса (`?pay=1`):
            // кнопка называется «Оплатить взнос» и обязана давать оплату, а не экран, на котором
            // её надо найти заново (решение PO 2026-07-30). Дальше — claim → организатор
            // подтверждает «Взнос получен».
            notificationService.sendDirectMessageWithDeepLink(
                entry.telegramId,
                "❗ Ваш доступ к клубу «${entry.clubName}» истёк — подписка закончилась. Оплатите взнос организатору, чтобы вернуть доступ.",
                webAppPath = "/clubs/${entry.clubId}?pay=1",
                buttonText = "Оплатить взнос"
            )
        }

        // Изменения в БД — одна короткая транзакция через отдельный бин, чтобы сохранить AOP-проксирование.
        lifecycleService.processExpiry(now)
    }

    /**
     * Ровно два напоминания на окно доступа — за 3 дня и за 1 день (PO 2026-08-08). Порог, который
     * уже отправлен, помечен в membership, поэтому следующие тики того же порога молчат: раньше
     * дедупа не было и участник получал DM на каждом тике крона.
     */
    private fun sendExpiryReminders(now: OffsetDateTime, candidates: List<ExpiryReminderCandidate>) {
        val notifiedByThreshold = mutableMapOf<Int, MutableList<UUID>>()

        candidates.forEach { candidate ->
            val daysLeft = ExpiryReminderRules.daysLeft(now, candidate.expiresAt)
            val threshold = ExpiryReminderRules.thresholdFor(daysLeft) ?: return@forEach
            if (!ExpiryReminderRules.isDue(threshold, candidate.lastReminderDaysLeft)) return@forEach

            // Кнопка ведёт на «Мои клубы» — там в окне T-3 живёт секция «Подписка истекает»
            // с «Продлить подписку» (раннее продление, membership-lifecycle.md §7).
            notificationService.sendDirectMessageWithDeepLink(
                candidate.telegramId,
                "⚠️ Ваша подписка на клуб «${candidate.clubName}» истекает ${ExpiryReminderRules.deadlinePhrase(daysLeft)}. " +
                    "Продлите взнос, чтобы не потерять доступ.",
                webAppPath = "/my-clubs",
                buttonText = "Продлить подписку"
            )
            notifiedByThreshold.getOrPut(threshold) { mutableListOf() }.add(candidate.membershipId)
        }

        // Отметка после отправки: DM best-effort (Telegram может не доставить молча), и повторный
        // спам — именно тот баг, который здесь чинится, поэтому попытка отправки закрывает порог.
        notifiedByThreshold.forEach { (threshold, membershipIds) ->
            lifecycleService.markExpiryRemindersSent(membershipIds, threshold)
            log.info("Expiry reminders sent: threshold={}d recipients={}", threshold, membershipIds.size)
        }
    }
}
