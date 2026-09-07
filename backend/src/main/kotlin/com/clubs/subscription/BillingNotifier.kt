package com.clubs.subscription

import com.clubs.bot.NotificationService
import com.clubs.club.Club
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Личные сообщения владельцу о деньгах (platform-billing.md § 8). В чат — ничего: бюджет
 * «1 закреп + 2 поста» не тратится. Best-effort: ошибки доставки глотает NotificationService.
 */
@Component
class BillingNotifier(
    private val notificationService: NotificationService,
    private val userRepository: UserRepository,
) {

    private val log = LoggerFactory.getLogger(BillingNotifier::class.java)
    private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.of("Europe/Moscow"))

    fun paid(club: Club, periodEnd: OffsetDateTime, autopayOn: Boolean, priceKopecks: Int) {
        val tail = if (autopayOn) {
            "Автопродление включено — ${dateFmt.format(periodEnd.minusDays(1))} спишем ${rubles(priceKopecks)} и напомним за день."
        } else {
            "Автопродление выключено — напомним за 3 дня и за день до конца."
        }
        send(club, "✅ Оплачено до ${dateFmt.format(periodEnd)}: чат «${club.name}» ведём дальше. $tail", openClub(club), "Открыть клуб")
    }

    fun renewed(club: Club, periodEnd: OffsetDateTime) {
        send(club, "✅ Продлено до ${dateFmt.format(periodEnd)}. Спасибо, что встречаетесь.", openClub(club), "Открыть клуб")
    }

    /** −3 и −1 день без автосписания. */
    fun expiringSoon(club: Club, periodEnd: OffsetDateTime, priceKopecks: Int, daysLeft: Int) {
        val text = if (daysLeft <= 1) {
            "⏳ Завтра заканчивается подписка за чат «${club.name}». После — 7 дней всё работает, потом новые встречи только после оплаты."
        } else {
            "⏳ Подписка за чат «${club.name}» заканчивается ${dateFmt.format(periodEnd)}. Продлить на месяц — ${rubles(priceKopecks)}."
        }
        send(club, text, payLink(club), "💳 Оплатить")
    }

    /** −1 день с автосписанием: прозрачность, не тёмный паттерн. */
    fun chargeTomorrow(club: Club, priceKopecks: Int) {
        send(club, "💳 Завтра спишем ${rubles(priceKopecks)} за чат «${club.name}». Отключить автопродление можно на странице клуба.", openClub(club), "Открыть клуб")
    }

    fun chargeFailed(club: Club, priceKopecks: Int, graceUntil: OffsetDateTime) {
        send(
            club,
            "⚠️ Не удалось списать ${rubles(priceKopecks)} за чат «${club.name}». Обновите карту или оплатите вручную — до ${dateFmt.format(graceUntil)} всё работает как раньше.",
            payLink(club), "💳 Оплатить",
        )
    }

    fun periodEnded(club: Club, graceUntil: OffsetDateTime) {
        send(
            club,
            "⏳ Подписка за чат «${club.name}» закончилась. До ${dateFmt.format(graceUntil)} всё работает, потом новые встречи — после оплаты.",
            payLink(club), "💳 Оплатить",
        )
    }

    fun graceExhausted(club: Club) {
        send(club, "🚫 Новые встречи в чате «${club.name}» недоступны до оплаты. Начатое доживёт, бот из чата не уходит.", payLink(club), "💳 Оплатить")
    }

    private fun send(club: Club, text: String, webAppPath: String, buttonText: String) {
        val telegramId = userRepository.findById(club.ownerId)?.telegramId
        if (telegramId == null) {
            log.warn("Billing DM skipped, owner has no telegram id: clubId={} ownerId={}", club.id, club.ownerId)
            return
        }
        notificationService.sendDirectMessageWithDeepLink(telegramId, text, webAppPath, buttonText)
    }

    private fun openClub(club: Club) = "/clubs/${club.id}/manage"

    /** `?billing=1` открывает шит оплаты на странице управления (platform-billing.md § 7). */
    private fun payLink(club: Club) = "/clubs/${club.id}/manage?billing=1"

    private fun rubles(kopecks: Int): String = "${kopecks / 100} ₽"
}
