package com.clubs.bot

import com.clubs.chatlink.ChatDoorService
import com.clubs.chatlink.ChatLinkBotService
import com.clubs.event.EventMessageTemplate
import com.clubs.event.EventRepository
import com.clubs.event.EventResponseRepository
import com.clubs.event.locationDisplay
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

// Статусы бота в чате, означающие «его там нет» и «он там есть» (литералы Bot API). Переход
// первого во второй = бота только что добавили в группу.
private val OUTSIDE_CHAT_STATUSES = setOf("left", "kicked")
private val INSIDE_CHAT_STATUSES = setOf("member", "administrator", "creator")

@Component
class ClubsBot(
    @Value("\${telegram.bot-token}") private val botToken: String,
    private val telegramClient: TelegramClient,
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val chatLinkBotService: ChatLinkBotService,
    private val chatDoorService: ChatDoorService,
    private val rosterCallbackService: RosterCallbackService,
    private val skladchinaCallbackService: SkladchinaCallbackService,
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

        // Inline-кнопки бота: «Отвязать чат» (привязка) и кнопки набора состава (V86).
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
                text.startsWith("/кто_идет") || text.startsWith("/kto_idet") -> handleWhoIsGoing(update.message)
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
     *
     * Само сообщение с командой стираем: подключение обязано быть незаметным для участников
     * группы, а команду в чат кладёт клиент Telegram — от бота это не зависит. Право
     * «Удаление сообщений» запрашивается ссылкой привязки; без него Telegram откажет, и
     * команда просто останется в ленте.
     */
    private fun handleGroupStart(message: Message) {
        chatLinkBotService.deleteServiceCommand(message.chatId, message.messageId.toLong())
        val payload = message.text.split(Regex("\\s+")).getOrNull(1) ?: return
        val from = message.from ?: return

        // Точка входа чат-модели: бота добавили ссылкой ?startgroup=new, клуба ещё нет —
        // создаём его из самого чата. Прежний сценарий (payload = UUID существующего клуба)
        // продолжает работать: привязка чата из «Управления клубом» никуда не делась.
        if (payload == ChatLinkBotService.NEW_CLUB_START_PAYLOAD) {
            chatLinkBotService.handleGroupStartNewClub(
                chatId = message.chatId,
                chatTitle = message.chat.title,
                fromTelegramId = from.id
            )
            return
        }

        val clubId = try {
            UUID.fromString(payload)
        } catch (_: IllegalArgumentException) {
            log.warn("Group /start with non-UUID payload ignored: chatId={}", message.chatId)
            return
        }
        chatLinkBotService.handleGroupStart(
            chatId = message.chatId,
            chatTitle = message.chat.title,
            fromTelegramId = from.id,
            clubId = clubId
        )
    }

    /**
     * Статус самого бота в чате изменился: обновляем health привязки (мокап 01-C), а если бота
     * только что ДОБАВИЛИ в группу — это ещё и штатная точка входа привязки чата.
     *
     * Раньше входом была команда `/start <payload>`, которую Telegram отправлял за человека. Со
     * ссылкой, запрашивающей права администратора (`&admin=…`), клиент показывает экран выбора
     * прав и команду не отправляет — человеку приходилось писать её руками. Здесь payload'а нет,
     * поэтому «зачем добавляли» приложение откладывает заранее (ChatLinkIntentStore).
     */
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

        // Именно добавление, а не выдача прав уже сидящему боту: иначе каждая правка прав
        // заводила бы привязку заново. Клуб-хозяин чата и прочие конфликты проверяет сервис.
        val wasOutside = updated.oldChatMember.status in OUTSIDE_CHAT_STATUSES
        val isInsideNow = newMember.status in INSIDE_CHAT_STATUSES
        if (wasOutside && isInsideNow) {
            chatLinkBotService.handleBotAddedToChat(
                chatId = chat.id,
                chatTitle = chat.title,
                fromTelegramId = updated.from.id
            )
        }
    }

    /**
     * Ответ на inline-кнопку. Форматы data: «chatlink:unlink:<uuid>» (см. ChatLinkBotService),
     * «roster:proceed:<uuid>» и «roster:remind:<uuid>» (см. RosterCallbackService). Права на
     * действие проверяет сервис по `query.from.id` — сам факт нажатия кнопки прав не даёт.
     */
    private fun handleCallbackQuery(query: CallbackQuery) {
        val data = query.data ?: return
        val answerText = when {
            data.startsWith(ChatLinkBotService.UNLINK_CALLBACK_PREFIX) ->
                parseCallbackId(data, ChatLinkBotService.UNLINK_CALLBACK_PREFIX)
                    ?.let { chatLinkBotService.handleUnlinkCallback(query.from.id, it) }
                    ?: RosterCallbackService.INVALID_REQUEST
            data.startsWith(RosterCallbackService.PROCEED_CALLBACK_PREFIX) ->
                parseCallbackId(data, RosterCallbackService.PROCEED_CALLBACK_PREFIX)
                    ?.let { rosterCallbackService.handleProceed(query.from.id, it) }
                    ?: RosterCallbackService.INVALID_REQUEST
            data.startsWith(RosterCallbackService.REMIND_CALLBACK_PREFIX) -> {
                val id = parseCallbackId(data, RosterCallbackService.REMIND_CALLBACK_PREFIX)
                // null от сервиса — «отчёт ушёл отдельным DM», алерт не нужен.
                if (id == null) RosterCallbackService.INVALID_REQUEST else rosterCallbackService.handleRemind(query.from.id, id)
            }
            // Долги: «Получил / Не получил» по долгу и по сальдо пары (skladchina-v3 § 5).
            data.startsWith(SkladchinaCallbackService.CONFIRM_PREFIX) ->
                parseCallbackId(data, SkladchinaCallbackService.CONFIRM_PREFIX)
                    ?.let { skladchinaCallbackService.handleDebt(query.from.id, it, confirm = true) }
                    ?: RosterCallbackService.INVALID_REQUEST
            data.startsWith(SkladchinaCallbackService.REJECT_PREFIX) ->
                parseCallbackId(data, SkladchinaCallbackService.REJECT_PREFIX)
                    ?.let { skladchinaCallbackService.handleDebt(query.from.id, it, confirm = false) }
                    ?: RosterCallbackService.INVALID_REQUEST
            data.startsWith(SkladchinaCallbackService.SETTLE_CONFIRM_PREFIX) ->
                parseCallbackId(data, SkladchinaCallbackService.SETTLE_CONFIRM_PREFIX)
                    ?.let { skladchinaCallbackService.handleSettlement(query.from.id, it, confirm = true) }
                    ?: RosterCallbackService.INVALID_REQUEST
            data.startsWith(SkladchinaCallbackService.SETTLE_REJECT_PREFIX) ->
                parseCallbackId(data, SkladchinaCallbackService.SETTLE_REJECT_PREFIX)
                    ?.let { skladchinaCallbackService.handleSettlement(query.from.id, it, confirm = false) }
                    ?: RosterCallbackService.INVALID_REQUEST
            data.startsWith(SkladchinaCallbackService.ENROLL_PREFIX) ->
                parseCallbackId(data, SkladchinaCallbackService.ENROLL_PREFIX)
                    ?.let { skladchinaCallbackService.handleEnroll(query.from.id, it) }
                    ?: RosterCallbackService.INVALID_REQUEST
            data.startsWith(SkladchinaCallbackService.TAKE_PREFIX) ->
                parseCallbackId(data, SkladchinaCallbackService.TAKE_PREFIX)
                    ?.let { skladchinaCallbackService.handleTake(query.from.id, it) }
                    ?: RosterCallbackService.INVALID_REQUEST
            data.startsWith(SkladchinaCallbackService.FORGIVE_PREFIX) ->
                parseCallbackId(data, SkladchinaCallbackService.FORGIVE_PREFIX)
                    ?.let { skladchinaCallbackService.handleForgive(query.from.id, it) }
                    ?: RosterCallbackService.INVALID_REQUEST
            data.startsWith(SkladchinaCallbackService.CLOSE_PREFIX) ->
                parseCallbackId(data, SkladchinaCallbackService.CLOSE_PREFIX)
                    ?.let { skladchinaCallbackService.handleClose(query.from.id, it) }
                    ?: RosterCallbackService.INVALID_REQUEST
            else -> {
                log.warn("Unknown callback data ignored: {}", data.take(32))
                null
            }
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

    /** UUID из `callback_data` после префикса; битые данные (подделка, обрезка) — null. */
    private fun parseCallbackId(data: String, prefix: String): UUID? = try {
        UUID.fromString(data.removePrefix(prefix))
    } catch (_: IllegalArgumentException) {
        null
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

    /**
     * «/кто_идет» — ближайшая встреча КЛУБА, к чьему чату привязан бот. Ответ несёт место и
     * время встречи, то есть данные для участников клуба, поэтому единственная аудитория команды —
     * привязанная группа. В личке команда отвечает подсказкой: до 2026-09-15 она отдавала там
     * ближайшую встречу ВСЕЙ платформы — адрес чужого клуба любому, кто нашёл бота (OWASP A01).
     */
    private fun handleWhoIsGoing(message: Message) {
        val chatId = message.chatId.toString()
        if (!isGroupChat(message)) {
            sendText(chatId, "Команда работает в чате клуба, к которому подключён бот. Свои встречи смотри в приложении.")
            return
        }

        val clubId = chatLinkBotService.findLinkedClubId(message.chatId)
        if (clubId == null) {
            sendText(chatId, "Этот чат не привязан к клубу.")
            return
        }

        // Тот же набор статусов, что у «Живого закрепа», ближайшая встреча — первая в списке.
        val event = eventRepository.findFutureEventsByClub(clubId, OffsetDateTime.now()).firstOrNull()
        if (event == null) {
            sendText(chatId, "Ближайших встреч нет")
            return
        }

        val counts = eventResponseRepository.countByVote(event.id)
        val goingCount = counts["going"] ?: 0
        val maybeCount = counts["maybe"] ?: 0

        val formattedDate = event.eventDatetime.format(dateFormatter)

        val text = buildString {
            appendLine("\uD83D\uDCC5 Ближайшая встреча: ${event.title}")
            // Место опционально (V58): строку с адресом печатаем, только когда оно указано.
            event.locationDisplay?.let { appendLine("\uD83D\uDCCD $it") }
            appendLine("\uD83D\uDDD3 $formattedDate")
            appendLine("\u2705 Пойдут: $goingCount")
            appendLine("\uD83E\uDD14 Возможно: $maybeCount")
            // Что означает число участников — общая строка со всеми бот-поверхностями (V85).
            append(EventMessageTemplate.seatsLine(event))
        }

        sendText(chatId, text)
    }

    /** Короткий текстовый ответ в чат — общий для всех реплик команды. */
    private fun sendText(chatId: String, text: String) {
        telegramClient.execute(
            SendMessage
                .builder()
                .chatId(chatId)
                .text(text)
                .build()
        )
    }
}
