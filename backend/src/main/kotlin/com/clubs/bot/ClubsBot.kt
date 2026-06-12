package com.clubs.bot

import com.clubs.event.EventRepository
import com.clubs.event.EventResponseRepository
import com.clubs.payment.PaymentService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Component
class ClubsBot(
    @Value("\${telegram.bot-token}") private val botToken: String,
    private val telegramClient: TelegramClient,
    private val paymentService: PaymentService,
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
) : SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private val log = LoggerFactory.getLogger(ClubsBot::class.java)

    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    override fun getBotToken(): String = botToken

    override fun getUpdatesConsumer(): LongPollingSingleThreadUpdateConsumer = this

    override fun consume(update: Update) {
        // Telegram Stars: pre_checkout_query MUST be answered within 10s or
        // the payment is cancelled. It arrives as its own update type, not a message.
        if (update.hasPreCheckoutQuery()) {
            handlePreCheckoutQuery(update.preCheckoutQuery)
            return
        }

        if (!update.hasMessage()) return

        // successful_payment is delivered as a message without `text`, so it
        // must be dispatched BEFORE the hasText() early-return below.
        if (update.message.hasSuccessfulPayment()) {
            val telegramId = update.message.from?.id ?: return
            val payment = update.message.successfulPayment
            paymentService.handleSuccessfulPayment(
                telegramId = telegramId,
                telegramChargeId = payment.telegramPaymentChargeId,
                payload = payment.invoicePayload,
                amount = payment.totalAmount
            )
            return
        }

        if (!update.message.hasText()) return

        val text = update.message.text
        val chatId = update.message.chatId.toString()

        try {
            when {
                text.startsWith("/start") -> handleStart(chatId)
                text.startsWith("/кто_идет") || text.startsWith("/kto_idet") -> handleWhoIsGoing(chatId)
            }
        } catch (e: Exception) {
            log.error("Error handling command '{}' from chat {}: {}", text, chatId, e.message, e)
        }
    }

    /**
     * Answers a Stars `pre_checkout_query` within Telegram's 10-second window.
     * Only validates payload format (full business validation already happened
     * at invoice creation). Any unexpected exception still answers with ok=false
     * to avoid leaving the payment in an indeterminate "waiting" state.
     */
    internal fun handlePreCheckoutQuery(query: PreCheckoutQuery) {
        val parts = query.invoicePayload.split(":")
        val valid = parts.size == 3 && parts[0] == "club_subscription"

        val answer = AnswerPreCheckoutQuery.builder()
            .preCheckoutQueryId(query.id)
            .ok(valid)
            .apply {
                if (!valid) errorMessage("Некорректный формат заказа. Попробуйте вступить снова из приложения.")
            }
            .build()

        try {
            telegramClient.execute(answer)
            log.info("pre_checkout_query answered: id={} ok={} payload={}", query.id, valid, query.invoicePayload)
        } catch (e: Exception) {
            log.error("Failed to answer pre_checkout_query {}: {}", query.id, e.message, e)
        }
    }

    private fun handleStart(chatId: String) {
        val button = InlineKeyboardButton
            .builder()
            .text("\uD83C\uDFE0 Открыть Clubs")
            .webApp(WebAppInfo("https://t.me/clubs_v2_bot/app"))
            .build()

        val markup = InlineKeyboardMarkup(listOf(InlineKeyboardRow(button)))

        val msg = SendMessage
            .builder()
            .chatId(chatId)
            .text("\uD83D\uDC4B Привет! Clubs — платформа для офлайн-сообществ.\nОткрой приложение, чтобы найти клуб или создать свой:")
            .replyMarkup(markup)
            .build()

        telegramClient.execute(msg)
    }

    private fun handleWhoIsGoing(chatId: String) {
        val now = OffsetDateTime.now()
        val event = eventRepository.findNextUpcomingEvent(now)

        if (event == null) {
            val msg = SendMessage
                .builder()
                .chatId(chatId)
                .text("Нет ближайших событий")
                .build()
            telegramClient.execute(msg)
            return
        }

        val counts = eventResponseRepository.countByVote(event.id)
        val goingCount = counts["going"] ?: 0
        val maybeCount = counts["maybe"] ?: 0

        val formattedDate = event.eventDatetime.format(dateFormatter)

        val text = buildString {
            appendLine("\uD83D\uDCC5 Ближайшее событие: ${event.title}")
            appendLine("\uD83D\uDCCD ${event.locationText}")
            appendLine("\uD83D\uDDD3 $formattedDate")
            appendLine("\u2705 Пойдут: $goingCount")
            appendLine("\uD83E\uDD14 Возможно: $maybeCount")
            append("\uD83D\uDC65 Лимит: ${event.participantLimit}")
        }

        val msg = SendMessage
            .builder()
            .chatId(chatId)
            .text(text)
            .build()

        telegramClient.execute(msg)
    }
}
