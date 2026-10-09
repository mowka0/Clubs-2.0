package com.clubs.subscription

import com.clubs.bot.NotificationService
import com.clubs.club.Club
import com.clubs.user.UserRepository
import com.clubs.user.displayName
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Личные сообщения владельцу о деньгах (platform-billing.md § 8); оплатившему участнику — только
 * «спасибо» (billing-member-pays.md § 3.2). В чат — ничего: бюджет
 * «1 закреп + 2 поста» не тратится. По тексту платят «за клуб» (решение PO 2026-09-07), хотя
 * единица счёта — чат. Best-effort: ошибки доставки глотает NotificationService.
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
            // Списание — в день окончания периода; отдельного DM «завтра спишем» нет (PO 2026-09-07).
            "Автопродление включено — ${dateFmt.format(periodEnd)} спишем ${rubles(priceKopecks)} с этой же карты. Отключить можно на странице клуба."
        } else {
            "Автопродление выключено — напомним за 3 дня и за день до конца."
        }
        send(club, "✅ Оплачено до ${dateFmt.format(periodEnd)}: клуб «${club.name}» ведём дальше. $tail", openClub(club), "Открыть клуб")
    }

    /**
     * Оплатил участник (billing-member-pays.md § 3.2): спасибо ему и новость владельцу. Это не
     * напоминания — напоминания об оплате по-прежнему только владельцу (M5).
     */
    fun paidByMember(club: Club, payerId: UUID, periodEnd: OffsetDateTime, ownerAutopayOn: Boolean) {
        val until = dateFmt.format(periodEnd)
        val payer = userRepository.findById(payerId)
        val payerTelegramId = payer?.telegramId
        if (payerTelegramId == null) {
            log.warn("Billing DM skipped, payer has no telegram id: clubId={} payerId={}", club.id, payerId)
        } else {
            notificationService.sendDirectMessageWithDeepLink(
                payerTelegramId, "💛 Спасибо! Клуб «${club.name}» оплачен до $until.", "/clubs/${club.id}", "Открыть клуб",
            )
        }
        val name = payer?.let { displayName(it.firstName, it.lastName) } ?: "Участник"
        // Автосписание владельца не отменяется, а сдвигается на новый конец периода (M3).
        val tail = if (ownerAutopayOn) " Автосписание с вашей карты перенесено на $until." else ""
        send(club, "💛 Участник клуба $name оплатил месяц: клуб «${club.name}» оплачен до $until.$tail", openClub(club), "Открыть клуб")
    }

    fun renewed(club: Club, periodEnd: OffsetDateTime) {
        // Текст PO 2026-10-06.
        send(
            club,
            "✅ Подписка за клуб «${club.name}» продлена до ${dateFmt.format(periodEnd)}. Спасибо, что пользуетесь Clubs! " +
                "Мы делаем приложение так, чтобы в жизни было больше живого общения и встреч офлайн.\n\n" +
                "Поначалу друзей бывает непросто приучить к этому формату, но это лишь дело привычки :)",
            openClub(club), "Открыть клуб",
        )
    }

    /** −3 и −1 день без автосписания. */
    fun expiringSoon(club: Club, periodEnd: OffsetDateTime, priceKopecks: Int, daysLeft: Int) {
        val text = if (daysLeft <= 1) {
            "⏳ Завтра заканчивается подписка за клуб «${club.name}». После — 7 дней всё работает, потом новые встречи только после оплаты."
        } else {
            "⏳ Подписка за клуб «${club.name}» заканчивается ${dateFmt.format(periodEnd)}. Продлить на месяц — ${rubles(priceKopecks)}."
        }
        send(club, text, payLink(club), "💳 Оплатить")
    }

    /**
     * −7 и −1 день до конца бесплатного периода. Текст не пугает стеной, а называет, что именно
     * бот продолжит делать за эти деньги: платит человек за снятую рутину, а не за доступ.
     */
    fun trialEndingSoon(club: Club, trialEnd: OffsetDateTime, priceKopecks: Int, daysLeft: Int) {
        val text = if (daysLeft <= 1) {
            "⏳ Завтра заканчивается бесплатный период клуба «${club.name}». Дальше — ${rubles(priceKopecks)} в месяц, " +
                "и бот продолжит вести встречи: афиша в чате, «кто идёт», напоминания участникам и сборы."
        } else {
            "⏳ Бесплатный период клуба «${club.name}» заканчивается ${dateFmt.format(trialEnd)}. Дальше — " +
                "${rubles(priceKopecks)} в месяц. Оплатить можно заранее — встречи и афиши в чате не прервутся."
        }
        send(club, text, payLink(club), "💳 Оплатить")
    }

    fun chargeFailed(club: Club, priceKopecks: Int, graceUntil: OffsetDateTime) {
        send(
            club,
            "⚠️ Не удалось списать ${rubles(priceKopecks)} за клуб «${club.name}». Обновите карту или оплатите вручную — до ${dateFmt.format(graceUntil)} всё работает как раньше.",
            payLink(club), "💳 Оплатить",
        )
    }

    fun periodEnded(club: Club, graceUntil: OffsetDateTime) {
        send(
            club,
            "⏳ Подписка за клуб «${club.name}» закончилась. До ${dateFmt.format(graceUntil)} всё работает, потом новые встречи — после оплаты.",
            payLink(club), "💳 Оплатить",
        )
    }

    fun graceExhausted(club: Club) {
        send(club, "🚫 Новые встречи в клубе «${club.name}» недоступны до оплаты. Начатое доживёт, бот из чата не уходит.", payLink(club), "💳 Оплатить")
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
