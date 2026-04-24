package com.clubs.payment

import com.clubs.bot.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Sends a welcome DM after a payment is fully committed.
 * Uses @TransactionalEventListener (default phase = AFTER_COMMIT) so the
 * DM never fires before the membership + transaction rows are persisted.
 */
@Component
class PaymentNotificationHandler(
    private val notificationService: NotificationService
) {

    private val log = LoggerFactory.getLogger(PaymentNotificationHandler::class.java)

    @TransactionalEventListener
    fun onPaymentConfirmed(event: PaymentConfirmedEvent) {
        val text = "✅ Оплата принята. Добро пожаловать в клуб «${event.clubName}»!\n\n" +
            "Открывайте приложение, чтобы посмотреть календарь событий и познакомиться с участниками."
        notificationService.sendDirectMessage(event.telegramId, text)
        log.info("Payment confirmation DM sent: telegramId={} clubName={}", event.telegramId, event.clubName)
    }
}
