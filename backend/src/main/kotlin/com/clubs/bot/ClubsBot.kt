package com.clubs.bot

import com.clubs.event.EventRepository
import com.clubs.event.EventResponseRepository
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
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
) : SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private val log = LoggerFactory.getLogger(ClubsBot::class.java)

    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    override fun getBotToken(): String = botToken

    override fun getUpdatesConsumer(): LongPollingSingleThreadUpdateConsumer = this

    override fun consume(update: Update) {
        // De-Stars (Slice 2): поток pay-to-join через Stars упразднён. Мы всё ещё отвечаем на
        // pre_checkout в пределах 10-секундного окна Telegram, но только чтобы ОТКЛОНИТЬ его
        // (handlePreCheckoutQuery → ok=false).
        if (update.hasPreCheckoutQuery()) {
            handlePreCheckoutQuery(update.preCheckoutQuery)
            return
        }

        if (!update.hasMessage()) return

        // successful_payment здесь — случайное событие (например, старый invoice, который был в
        // полёте). НЕ активировать доступ — доступ теперь контролирует организатор. Логируем с
        // charge id для ручного возврата средств.
        // Приходит как сообщение без `text`, поэтому обрабатывать нужно ДО return в hasText().
        if (update.message.hasSuccessfulPayment()) {
            val payment = update.message.successfulPayment
            log.warn(
                "Ignoring stray Telegram Stars payment (pay-to-join retired): telegramId={} chargeId={} amount={} payload={} — refund manually",
                update.message.from?.id, payment.telegramPaymentChargeId, payment.totalAmount, payment.invoicePayload
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
     * De-Stars (Slice 2): поток pay-to-join через Stars упразднён, поэтому каждый `pre_checkout_query`
     * ОТКЛОНЯЕТСЯ — через бота больше никого не списывают. Доступ теперь контролирует организатор
     * (AccessGateService). Ответ даётся в пределах 10-секундного окна Telegram с ok=false + пояснением.
     */
    internal fun handlePreCheckoutQuery(query: PreCheckoutQuery) {
        val answer = AnswerPreCheckoutQuery.builder()
            .preCheckoutQueryId(query.id)
            .ok(false)
            .errorMessage("Оплата через бота больше не используется. Доступ к клубу открывает организатор.")
            .build()

        try {
            telegramClient.execute(answer)
            log.info("pre_checkout_query rejected (Stars retired): id={}", query.id)
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
