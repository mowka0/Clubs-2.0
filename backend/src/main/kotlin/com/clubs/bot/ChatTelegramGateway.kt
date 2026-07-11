package com.clubs.bot

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.api.methods.GetMe
import org.telegram.telegrambots.meta.api.methods.groupadministration.ApproveChatJoinRequest
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember
import org.telegram.telegrambots.meta.api.methods.groupadministration.CreateChatInviteLink
import org.telegram.telegrambots.meta.api.methods.groupadministration.DeclineChatJoinRequest
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
import org.telegram.telegrambots.meta.api.methods.groupadministration.LeaveChat
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember
import org.telegram.telegrambots.meta.api.methods.groupadministration.RevokeChatInviteLink
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatMemberTag
import org.telegram.telegrambots.meta.api.methods.groupadministration.UnbanChatMember
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.UnpinChatMessage
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberMember
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberOwner
import org.telegram.telegrambots.meta.api.objects.ChatPermissions
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberRestricted
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * Присутствие и права БОТА в конкретном чате — перевод телеграмного ChatMember в наши
 * примитивы, чтобы сервисы chatlink не зависели от типов библиотеки бота.
 * statusLiteral — сырой статус Telegram (creator/administrator/member/restricted/left/kicked).
 */
data class BotChatState(
    val statusLiteral: String,
    val canPinMessages: Boolean,
    val canInviteUsers: Boolean,
    val canRestrictMembers: Boolean,
    /** Право «Управление тегами» (Bot API 9.5) — библиотека бота его не знает, читается raw HTTP. */
    val canManageTags: Boolean
)

/**
 * Положение ПОЛЬЗОВАТЕЛЯ в чате для «двери»: BANNED выделен отдельно, потому что
 * забаненному не работает ни одна invite-ссылка — перед приглашением нужен unban
 * (реестр багов №1: «удалить из группы» в Telegram = бан).
 */
enum class UserChatState { IN_CHAT, NOT_IN_CHAT, BANNED, UNKNOWN }

/**
 * Результат чтения тега участника: tag=null — тега нет; inChat=false — человек не в чате
 * (тег ставить некуда). Сбой вызова кодируется null всего объекта.
 */
data class MemberTagLookup(val tag: String?, val inChat: Boolean)

// HTML parse_mode Telegram — нужен сообщениям с text_mention-упоминаниями («живой статус
// сбора»); вызывающий обязан экранировать пользовательский ввод (&, <, >).
const val PARSE_MODE_HTML = "HTML"

/**
 * Тонкая обёртка над Telegram Bot API для чат-интеграции (club-chat-link): всё общение бота
 * с ГРУППАМИ и заявками на вход живёт здесь, чтобы сервисы chatlink не зависели от
 * телеграмных типов ниже уровня «примитивы и наши модели». DM-рассылки продуктовых
 * уведомлений остаются в [NotificationService] — тут только чат-механика.
 *
 * Все методы «мягкие»: ошибки Telegram логируются и превращаются в null/false —
 * вызывающий код сам решает, что критично (fail-close для привязки, best-effort для DM).
 */
@Component
class ChatTelegramGateway(
    private val telegramClient: TelegramClient,
    @Value("\${telegram.webapp-base-url}") private val webAppBaseUrl: String
) {
    private val log = LoggerFactory.getLogger(ChatTelegramGateway::class.java)

    // id бота стабилен на всё время жизни процесса (одна нода = один токен) — кэшируем первый
    // УСПЕШНЫЙ GetMe. Именно поэтому не `by lazy`: lazy закэшировал бы и неудачу (null) навсегда,
    // а сбой сети при старте не должен окончательно ослеплять чат-интеграцию до рестарта.
    @Volatile
    private var cachedBotId: Long? = null

    private fun botId(): Long? {
        cachedBotId?.let { return it }
        return try {
            telegramClient.execute(GetMe()).id.also { cachedBotId = it }
        } catch (e: Exception) {
            log.error("GetMe failed — bot id unknown (will retry on next call): {}", e.message)
            null
        }
    }

    /** Статус и права БОТА в чате (getChatMember по собственному id). Null = не удалось узнать. */
    fun getBotChatState(chatId: Long): BotChatState? {
        val self = botId() ?: return null
        val member = getChatMember(chatId, self) ?: return null
        return when (member) {
            is ChatMemberAdministrator -> BotChatState(
                statusLiteral = member.status,
                canPinMessages = member.canPinMessages ?: false,
                canInviteUsers = member.canInviteUsers ?: false,
                canRestrictMembers = member.canRestrictMembers ?: false,
                canManageTags = member.canManageTags ?: false
            )
            // creator недостижим для бота, но маппинг честный: владельцу можно всё.
            is ChatMemberOwner -> BotChatState(member.status, canPinMessages = true, canInviteUsers = true, canRestrictMembers = true, canManageTags = true)
            else -> BotChatState(member.status, canPinMessages = false, canInviteUsers = false, canRestrictMembers = false, canManageTags = false)
        }
    }

    /** Положение ПОЛЬЗОВАТЕЛЯ в чате (перевод статуса Telegram в наши четыре случая). */
    fun getUserChatState(chatId: Long, userId: Long): UserChatState {
        val member = getChatMember(chatId, userId) ?: return UserChatState.UNKNOWN
        return when (member.status) {
            "creator", "administrator", "member" -> UserChatState.IN_CHAT
            "restricted" ->
                if ((member as? ChatMemberRestricted)?.isMember == true) UserChatState.IN_CHAT
                else UserChatState.NOT_IN_CHAT
            "kicked" -> UserChatState.BANNED
            else -> UserChatState.NOT_IN_CHAT
        }
    }

    /** Снять бан (only_if_banned — состоящего в чате не кикнет). Нужно право «Блокировка пользователей». */
    fun unbanChatMember(chatId: Long, userId: Long): Boolean = try {
        telegramClient.execute(UnbanChatMember.builder().chatId(chatId).userId(userId).onlyIfBanned(true).build())
        true
    } catch (e: Exception) {
        log.warn("unbanChatMember failed (нет права «Блокировка пользователей»?): chatId={} userId={} error={}", chatId, userId, e.message)
        false
    }

    /**
     * Строгий режим: должник — «только чтение». Пустой ChatPermissions = все права false
     * (по Bot API опущенное право = запрет), без until_date = бессрочно. Работает только в
     * супергруппах и не действует на админов чата — обе ошибки Telegram глотаются с warn.
     */
    fun muteChatMember(chatId: Long, userId: Long): Boolean = try {
        telegramClient.execute(
            RestrictChatMember.builder().chatId(chatId).userId(userId)
                .permissions(ChatPermissions.builder().build())
                .build()
        )
        true
    } catch (e: Exception) {
        log.warn("muteChatMember failed (базовая группа / админ / не участник?): chatId={} userId={} error={}", chatId, userId, e.message)
        false
    }

    /**
     * Снять «только чтение»: все permissions=true возвращают участника к дефолтным правам
     * группы (стандартный анмьют Bot API). Трогаем ТОЛЬКО restricted-участников — обычного
     * member не превращаем в restricted-с-полными-правами.
     */
    fun unmuteChatMember(chatId: Long, userId: Long): Boolean {
        val member = getChatMember(chatId, userId) ?: return false
        if (member.status != "restricted") return true // и так с голосом — no-op
        return try {
            telegramClient.execute(
                RestrictChatMember.builder().chatId(chatId).userId(userId)
                    .permissions(
                        ChatPermissions.builder()
                            .canSendMessages(true).canSendAudios(true).canSendDocuments(true)
                            .canSendPhotos(true).canSendVideos(true).canSendVideoNotes(true)
                            .canSendVoiceNotes(true).canSendPolls(true).canSendOtherMessages(true)
                            .canAddWebPagePreviews(true).canChangeInfo(true).canInviteUsers(true)
                            .canPinMessages(true).canManageTopics(true)
                            .build()
                    )
                    .build()
            )
            true
        } catch (e: Exception) {
            log.warn("unmuteChatMember failed: chatId={} userId={} error={}", chatId, userId, e.message)
            false
        }
    }

    /**
     * Теги наград (слайс 4, Bot API 9.5): поставить/сменить тег ОБЫЧНОМУ участнику —
     * без повышения в админы. Пустая строка снимает тег. Боту нужно право «Управление
     * тегами» (can_manage_tags). Тег ≤16 символов, без эмодзи — рамки Telegram.
     */
    fun setMemberTag(chatId: Long, userId: Long, tag: String): Boolean = try {
        telegramClient.execute(SetChatMemberTag.builder().chatId(chatId).userId(userId).tag(tag).build())
        true
    } catch (e: Exception) {
        log.warn("setMemberTag failed: chatId={} userId={} error={}", chatId, userId, e.message)
        false
    }

    /**
     * Текущий тег участника (поле tag у member/restricted, Bot API 9.5). null = сбой вызова
     * (шедулер синхронизации должен отличать «нет тега» от «Telegram не ответил»).
     */
    fun getMemberTag(chatId: Long, userId: Long): MemberTagLookup? {
        val member = getChatMember(chatId, userId) ?: return null
        val tag = when (member) {
            is ChatMemberMember -> member.tag
            is ChatMemberRestricted -> member.tag
            else -> null // у админов/владельца тегов нет (у них custom title)
        }?.takeIf { it.isNotEmpty() }
        val inChat = member.status in setOf("creator", "administrator", "member", "restricted")
        return MemberTagLookup(tag, inChat)
    }

    /** Право бота «Управление тегами» (can_manage_tags, Bot API 9.5). */
    fun fetchCanManageTags(chatId: Long): Boolean {
        val self = botId() ?: return false
        return when (val member = getChatMember(chatId, self)) {
            // Владелец чата (creator) может всё; у админа смотрим само право.
            is ChatMemberOwner -> true
            is ChatMemberAdministrator -> member.canManageTags ?: false
            else -> false
        }
    }

    /** Строгий режим: покинувший клуб вылетает из чата (бан). Снятие — unbanChatMember при возврате в клуб. */
    fun banChatMember(chatId: Long, userId: Long): Boolean = try {
        telegramClient.execute(BanChatMember.builder().chatId(chatId).userId(userId).build())
        true
    } catch (e: Exception) {
        log.warn("banChatMember failed (нет права «Блокировка пользователей»?): chatId={} userId={} error={}", chatId, userId, e.message)
        false
    }

    fun getChatTitle(chatId: Long): String? = try {
        telegramClient.execute(GetChat.builder().chatId(chatId).build()).title
    } catch (e: Exception) {
        log.warn("getChat failed: chatId={} error={}", chatId, e.message)
        null
    }

    private fun getChatMember(chatId: Long, userId: Long): ChatMember? = try {
        telegramClient.execute(GetChatMember.builder().chatId(chatId).userId(userId).build())
    } catch (e: Exception) {
        log.warn("getChatMember failed: chatId={} userId={} error={}", chatId, userId, e.message)
        null
    }

    /** Сообщение в группу (не DM). Best-effort. */
    fun sendGroupMessage(chatId: Long, text: String): Boolean = try {
        telegramClient.execute(SendMessage.builder().chatId(chatId).text(text).build())
        true
    } catch (e: Exception) {
        log.warn("sendGroupMessage failed: chatId={} error={}", chatId, e.message)
        false
    }

    /**
     * Пост в группу с url-кнопкой, возвращает message_id (null = не удалось). Для «живого закрепа»:
     * в ГРУППАХ Telegram запрещает WebApp-кнопки, поэтому кнопка — только url
     * `t.me/<bot>?startapp=…` (Main Mini App; DeepLinkHandler фронта разруливает payload).
     */
    fun sendGroupMessageWithUrlButton(
        chatId: Long,
        text: String,
        buttonText: String?,
        url: String?,
        parseMode: String? = null,
        // Тихий пост: приходит в чат без пуша участникам (итог встречи — фон, не событие)
        silent: Boolean = false,
        // Необязательная вторая строка клавиатуры «текст → https-ссылка»
        // (например «Открыть в Яндекс.Картах» у живого закрепа события с гео-точкой)
        secondaryButton: Pair<String, String>? = null
    ): Long? = try {
        val builder = SendMessage.builder().chatId(chatId).text(text)
        buildUrlKeyboard(buttonText, url, secondaryButton)?.let { builder.replyMarkup(it) }
        parseMode?.let { builder.parseMode(it) }
        if (silent) builder.disableNotification(true)
        telegramClient.execute(builder.build()).messageId?.toLong()
    } catch (e: Exception) {
        log.warn("sendGroupMessageWithUrlButton failed: chatId={} error={}", chatId, e.message)
        null
    }

    /**
     * Редактирование своего сообщения в группе (живой закреп). «Message is not modified» —
     * не ошибка (перерисовка совпала с текущим текстом), считаем успехом.
     */
    fun editGroupMessage(
        chatId: Long,
        messageId: Long,
        text: String,
        buttonText: String?,
        url: String?,
        parseMode: String? = null,
        secondaryButton: Pair<String, String>? = null
    ): Boolean = try {
        val builder = EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId.toInt())
            .text(text)
        buildUrlKeyboard(buttonText, url, secondaryButton)?.let { builder.replyMarkup(it) }
        parseMode?.let { builder.parseMode(it) }
        telegramClient.execute(builder.build())
        true
    } catch (e: Exception) {
        if (e.message?.contains("message is not modified") == true) {
            true
        } else {
            log.warn("editGroupMessage failed: chatId={} messageId={} error={}", chatId, messageId, e.message)
            false
        }
    }

    /**
     * Клавиатура групповых постов: основная url-кнопка (t.me-диплинк в Mini App) + необязательная
     * вторая строка (внешняя https-ссылка, например «Открыть в Яндекс.Картах»). null = без клавиатуры.
     */
    private fun buildUrlKeyboard(
        buttonText: String?,
        url: String?,
        secondaryButton: Pair<String, String>?
    ): InlineKeyboardMarkup? {
        val rows = mutableListOf<InlineKeyboardRow>()
        if (buttonText != null && url != null) {
            rows += InlineKeyboardRow(InlineKeyboardButton.builder().text(buttonText).url(url).build())
        }
        secondaryButton?.let { (text, secondaryUrl) ->
            rows += InlineKeyboardRow(InlineKeyboardButton.builder().text(text).url(secondaryUrl).build())
        }
        return if (rows.isEmpty()) null else InlineKeyboardMarkup(rows)
    }

    /** Закрепить сообщение (тихо, без пуша всем участникам). Нужно право «Закрепление сообщений». */
    fun pinChatMessage(chatId: Long, messageId: Long): Boolean = try {
        telegramClient.execute(
            PinChatMessage.builder().chatId(chatId).messageId(messageId.toInt()).disableNotification(true).build()
        )
        true
    } catch (e: Exception) {
        log.warn("pinChatMessage failed (нет права «Закрепление сообщений»?): chatId={} messageId={} error={}", chatId, messageId, e.message)
        false
    }

    fun unpinChatMessage(chatId: Long, messageId: Long): Boolean = try {
        telegramClient.execute(UnpinChatMessage.builder().chatId(chatId).messageId(messageId.toInt()).build())
        true
    } catch (e: Exception) {
        log.warn("unpinChatMessage failed: chatId={} messageId={} error={}", chatId, messageId, e.message)
        false
    }

    /**
     * DM с inline-кнопкой callback (например «Отвязать чат» в петле подтверждения привязки).
     * WebApp-кнопки в DM строит [dmWithWebApp]; callback-кнопки — единственный не-WebApp кейс.
     */
    fun sendDmWithCallbackButton(telegramId: Long, text: String, buttonText: String, callbackData: String): Boolean = try {
        val button = InlineKeyboardButton.builder().text(buttonText).callbackData(callbackData).build()
        val msg = SendMessage.builder()
            .chatId(telegramId.toString())
            .text(text)
            .replyMarkup(InlineKeyboardMarkup(listOf(InlineKeyboardRow(button))))
            .build()
        telegramClient.execute(msg)
        true
    } catch (e: Exception) {
        log.warn("sendDmWithCallbackButton failed: telegramId={} error={}", telegramId, e.message)
        false
    }

    /** DM с WebApp-кнопкой на страницу Mini App (path вида /clubs/{id}). Best-effort. */
    fun sendDmWithWebApp(telegramId: Long, text: String, buttonText: String, webAppPath: String): Boolean = try {
        val button = InlineKeyboardButton.builder()
            .text(buttonText)
            .webApp(WebAppInfo(webAppBaseUrl + webAppPath))
            .build()
        val msg = SendMessage.builder()
            .chatId(telegramId.toString())
            .text(text)
            .replyMarkup(InlineKeyboardMarkup(listOf(InlineKeyboardRow(button))))
            .build()
        telegramClient.execute(msg)
        true
    } catch (e: Exception) {
        log.warn("sendDmWithWebApp failed: telegramId={} error={}", telegramId, e.message)
        false
    }

    /** DM со ссылкой-кнопкой (url, не WebApp) — для door invite link (t.me/+…). Best-effort. */
    fun sendDmWithUrlButton(telegramId: Long, text: String, buttonText: String, url: String): Boolean = try {
        val button = InlineKeyboardButton.builder().text(buttonText).url(url).build()
        val msg = SendMessage.builder()
            .chatId(telegramId.toString())
            .text(text)
            .replyMarkup(InlineKeyboardMarkup(listOf(InlineKeyboardRow(button))))
            .build()
        telegramClient.execute(msg)
        true
    } catch (e: Exception) {
        log.warn("sendDmWithUrlButton failed: telegramId={} error={}", telegramId, e.message)
        false
    }

    /** Одобрить заявку на вход в чат. false = заявки нет / уже участник / нет прав — вызывающий решает. */
    fun approveJoinRequest(chatId: Long, userId: Long): Boolean = try {
        telegramClient.execute(ApproveChatJoinRequest.builder().chatId(chatId).userId(userId).build())
        true
    } catch (e: Exception) {
        log.info("approveJoinRequest failed (often benign — no pending request): chatId={} userId={} error={}", chatId, userId, e.message)
        false
    }

    /** Отклонить заявку на вход в чат. Блайндовый вызов — отсутствие заявки не ошибка. */
    fun declineJoinRequest(chatId: Long, userId: Long): Boolean = try {
        telegramClient.execute(DeclineChatJoinRequest.builder().chatId(chatId).userId(userId).build())
        true
    } catch (e: Exception) {
        log.info("declineJoinRequest failed (often benign — no pending request): chatId={} userId={} error={}", chatId, userId, e.message)
        false
    }

    /** Ссылка-приглашение «двери»: вход только через заявку, которую бот одобряет сам. */
    fun createJoinRequestInviteLink(chatId: Long, name: String): String? = try {
        telegramClient.execute(
            CreateChatInviteLink.builder()
                .chatId(chatId)
                .createsJoinRequest(true)
                .name(name)
                .build()
        ).inviteLink
    } catch (e: Exception) {
        log.warn("createJoinRequestInviteLink failed: chatId={} error={}", chatId, e.message)
        null
    }

    fun revokeInviteLink(chatId: Long, inviteLink: String): Boolean = try {
        telegramClient.execute(RevokeChatInviteLink.builder().chatId(chatId).inviteLink(inviteLink).build())
        true
    } catch (e: Exception) {
        log.warn("revokeInviteLink failed: chatId={} error={}", chatId, e.message)
        false
    }

    fun leaveChat(chatId: Long): Boolean = try {
        telegramClient.execute(LeaveChat.builder().chatId(chatId).build())
        true
    } catch (e: Exception) {
        log.warn("leaveChat failed: chatId={} error={}", chatId, e.message)
        false
    }
}
