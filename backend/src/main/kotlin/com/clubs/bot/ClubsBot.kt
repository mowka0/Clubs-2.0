package com.clubs.bot

import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.USERS
import com.clubs.generated.jooq.tables.references.USER_CLUB_REPUTATION
import com.clubs.payment.PaymentService
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
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
    private val dsl: DSLContext,
    private val paymentService: PaymentService
) : SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private val log = LoggerFactory.getLogger(ClubsBot::class.java)

    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    override fun getBotToken(): String = botToken

    override fun getUpdatesConsumer(): LongPollingSingleThreadUpdateConsumer = this

    override fun consume(update: Update) {
        if (!update.hasMessage() || !update.message.hasText()) return

        val text = update.message.text
        val chatId = update.message.chatId.toString()
        val telegramId = update.message.from?.id

        // Handle successful_payment (Stars)
        if (update.message.hasSuccessfulPayment()) {
            val payment = update.message.successfulPayment
            paymentService.handleSuccessfulPayment(
                telegramId = telegramId ?: return,
                telegramChargeId = payment.telegramPaymentChargeId,
                payload = payment.invoicePayload,
                amount = payment.totalAmount
            )
            return
        }

        try {
            when {
                text.startsWith("/start") -> handleStart(chatId)
                text.startsWith("/кто_идет") || text.startsWith("/kto_idet") -> handleWhoIsGoing(chatId)
                text.startsWith("/мой_рейтинг") || text.startsWith("/moy_reyting") -> handleMyRating(chatId, telegramId)
            }
        } catch (e: Exception) {
            log.error("Error handling command '{}' from chat {}: {}", text, chatId, e.message, e)
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

        // Find nearest upcoming event (status = upcoming, stage_1, or stage_2; datetime in the future)
        val event = dsl.select(
            EVENTS.ID,
            EVENTS.TITLE,
            EVENTS.LOCATION_TEXT,
            EVENTS.EVENT_DATETIME,
            EVENTS.PARTICIPANT_LIMIT
        )
            .from(EVENTS)
            .where(
                EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_1, EventStatus.stage_2)
                    .and(EVENTS.EVENT_DATETIME.gt(now))
            )
            .orderBy(EVENTS.EVENT_DATETIME.asc())
            .limit(1)
            .fetchOne()

        if (event == null) {
            val msg = SendMessage
                .builder()
                .chatId(chatId)
                .text("Нет ближайших событий")
                .build()
            telegramClient.execute(msg)
            return
        }

        val eventId = event[EVENTS.ID]
        val title = event[EVENTS.TITLE]
        val locationText = event[EVENTS.LOCATION_TEXT]
        val eventDatetime = event[EVENTS.EVENT_DATETIME]
        val participantLimit = event[EVENTS.PARTICIPANT_LIMIT]

        // Count going votes
        val goingCount = dsl.selectCount()
            .from(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.going))
            )
            .fetchOne(0, Int::class.java) ?: 0

        // Count maybe votes
        val maybeCount = dsl.selectCount()
            .from(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.maybe))
            )
            .fetchOne(0, Int::class.java) ?: 0

        val formattedDate = eventDatetime?.format(dateFormatter) ?: "не указана"

        val text = buildString {
            appendLine("\uD83D\uDCC5 Ближайшее событие: $title")
            appendLine("\uD83D\uDCCD $locationText")
            appendLine("\uD83D\uDDD3 $formattedDate")
            appendLine("\u2705 Пойдут: $goingCount")
            appendLine("\uD83E\uDD14 Возможно: $maybeCount")
            append("\uD83D\uDC65 Лимит: $participantLimit")
        }

        val msg = SendMessage
            .builder()
            .chatId(chatId)
            .text(text)
            .build()

        telegramClient.execute(msg)
    }

    private fun handleMyRating(chatId: String, telegramId: Long?) {
        if (telegramId == null) {
            val msg = SendMessage
                .builder()
                .chatId(chatId)
                .text("Не удалось определить ваш Telegram ID.")
                .build()
            telegramClient.execute(msg)
            return
        }

        // Look up user by telegram_id
        val userId = dsl.select(USERS.ID)
            .from(USERS)
            .where(USERS.TELEGRAM_ID.eq(telegramId))
            .fetchOne(USERS.ID)

        if (userId == null) {
            val msg = SendMessage
                .builder()
                .chatId(chatId)
                .text("Вы ещё не зарегистрированы в Clubs. Откройте приложение, чтобы начать!")
                .build()
            telegramClient.execute(msg)
            return
        }

        // Look up reputation (take first record across all clubs)
        val reputation = dsl.select(
            USER_CLUB_REPUTATION.RELIABILITY_INDEX,
            USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT,
            USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS
        )
            .from(USER_CLUB_REPUTATION)
            .where(USER_CLUB_REPUTATION.USER_ID.eq(userId))
            .orderBy(USER_CLUB_REPUTATION.UPDATED_AT.desc())
            .limit(1)
            .fetchOne()

        if (reputation == null) {
            val msg = SendMessage
                .builder()
                .chatId(chatId)
                .text("Репутация ещё не сформирована. Участвуйте в событиях клуба!")
                .build()
            telegramClient.execute(msg)
            return
        }

        val reliabilityIndex = reputation[USER_CLUB_REPUTATION.RELIABILITY_INDEX]
        val promisePct = reputation[USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT]
        val totalConfirmations = reputation[USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS]

        val text = buildString {
            appendLine("\u2B50 Ваша репутация:")
            appendLine("Индекс надёжности: $reliabilityIndex")
            appendLine("Выполнение обещаний: $promisePct%")
            append("Подтверждений: $totalConfirmations")
        }

        val msg = SendMessage
            .builder()
            .chatId(chatId)
            .text(text)
            .build()

        telegramClient.execute(msg)
    }
}
