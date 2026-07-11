package com.clubs.bot

import com.clubs.chatlink.ChatDoorService
import com.clubs.chatlink.ChatLinkBotService
import com.clubs.event.EventRepository
import com.clubs.event.EventResponseRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Component
class ClubsBot(
    @Value("\${telegram.bot-token}") private val botToken: String,
    private val telegramClient: TelegramClient,
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val chatLinkBotService: ChatLinkBotService,
    private val chatDoorService: ChatDoorService,
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

        // Чат-интеграция (club-chat-link): статус самого бота в группах (кик/возврат/права) —
        // health-мониторинг привязки. my_chat_member приходит при пустом allowed_updates из коробки.
        if (update.hasMyChatMember()) {
            try {
                handleMyChatMember(update)
            } catch (e: Exception) {
                log.error("Error handling my_chat_member: {}", e.message, e)
            }
            return
        }

        // Чат-«дверь»: человек постучался в привязанный чат по door-ссылке.
        if (update.hasChatJoinRequest()) {
            val request = update.chatJoinRequest
            try {
                chatDoorService.onChatJoinRequest(request.chat.id, request.user.id)
            } catch (e: Exception) {
                log.error("Error handling chat_join_request: chatId={} error={}", request.chat.id, e.message, e)
            }
            return
        }

        // Inline-кнопки бота (сейчас единственная — «Отвязать чат» из DM-петли подтверждения привязки).
        if (update.hasCallbackQuery()) {
            try {
                handleCallbackQuery(update.callbackQuery)
            } catch (e: Exception) {
                log.error("Error handling callback query: {}", e.message, e)
            }
            return
        }

        if (!update.hasMessage()) return

        // Миграция группы в супергруппу: Telegram меняет chat_id — переносим привязку чата.
        update.message.migrateToChatId?.let { newChatId ->
            try {
                chatLinkBotService.handleChatMigration(update.message.chatId, newChatId)
            } catch (e: Exception) {
                log.error("Error handling chat migration: {} → {}: {}", update.message.chatId, newChatId, e.message, e)
            }
            return
        }

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
                // /start в ГРУППЕ — попытка привязки чата deep link'ом ?startgroup=<club_id>
                // (клиент Telegram шлёт «/start <payload>» в группу после добавления бота).
                // /start в личке — прежний welcome.
                text.startsWith("/start") ->
                    if (isGroupChat(update.message)) handleGroupStart(update.message) else handleStart(chatId)
                text.startsWith("/кто_идет") || text.startsWith("/kto_idet") -> handleWhoIsGoing(chatId)
            }
        } catch (e: Exception) {
            log.error("Error handling command '{}' from chat {}: {}", text, chatId, e.message, e)
        }
    }

    private fun isGroupChat(message: Message): Boolean =
        message.chat.type == "group" || message.chat.type == "supergroup"

    /**
     * «/start <club_id>» в группе → привязка чата к клубу. Без валидного UUID-payload —
     * молчаливый no-op: бота могли добавить в группу руками или тапнуть /start@bot без
     * payload'а, спамить группу инструкциями не надо.
     */
    private fun handleGroupStart(message: Message) {
        val payload = message.text.split(Regex("\\s+")).getOrNull(1) ?: return
        val clubId = try {
            UUID.fromString(payload)
        } catch (_: IllegalArgumentException) {
            log.warn("Group /start with non-UUID payload ignored: chatId={}", message.chatId)
            return
        }
        val from = message.from ?: return
        chatLinkBotService.handleGroupStart(
            chatId = message.chatId,
            chatTitle = message.chat.title,
            fromTelegramId = from.id,
            clubId = clubId
        )
    }

    /** Статус самого бота в чате изменился: обновляем health привязки (мокап 01-C). */
    private fun handleMyChatMember(update: Update) {
        val updated = update.myChatMember
        val chat = updated.chat
        if (chat.type != "group" && chat.type != "supergroup") return
        val newMember = updated.newChatMember
        val admin = newMember as? ChatMemberAdministrator
        chatLinkBotService.handleMyChatMember(
            chatId = chat.id,
            newStatusLiteral = newMember.status,
            canPinMessages = admin?.canPinMessages ?: false,
            canInviteUsers = admin?.canInviteUsers ?: false,
            canRestrictMembers = admin?.canRestrictMembers ?: false
        )
    }

    /** Ответ на inline-кнопку. Формат data: «chatlink:unlink:<uuid>» (см. ChatLinkBotService). */
    private fun handleCallbackQuery(query: CallbackQuery) {
        val data = query.data ?: return
        val answerText = if (data.startsWith(ChatLinkBotService.UNLINK_CALLBACK_PREFIX)) {
            val clubId = try {
                UUID.fromString(data.removePrefix(ChatLinkBotService.UNLINK_CALLBACK_PREFIX))
            } catch (_: IllegalArgumentException) {
                null
            }
            clubId?.let { chatLinkBotService.handleUnlinkCallback(query.from.id, it) } ?: "Некорректный запрос"
        } else {
            log.warn("Unknown callback data ignored: {}", data.take(32))
            null
        }

        // Telegram требует ответить на каждый callback, иначе у пользователя крутится спиннер.
        val answer = AnswerCallbackQuery.builder()
            .callbackQueryId(query.id)
            .apply { answerText?.let { text(it).showAlert(true) } }
            .build()
        try {
            telegramClient.execute(answer)
        } catch (e: Exception) {
            log.warn("Failed to answer callback query {}: {}", query.id, e.message)
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
            // \u041C\u0435\u0441\u0442\u043E \u043E\u043F\u0446\u0438\u043E\u043D\u0430\u043B\u044C\u043D\u043E (V58): \u0441\u0442\u0440\u043E\u043A\u0430 \uD83D\uDCCD \u0442\u043E\u043B\u044C\u043A\u043E \u043A\u043E\u0433\u0434\u0430 \u043E\u043D\u043E \u0443\u043A\u0430\u0437\u0430\u043D\u043E.
            event.locationText?.let { appendLine("\uD83D\uDCCD $it") }
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
