package com.clubs.bot

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.api.methods.GetMe
import org.telegram.telegrambots.meta.api.methods.groupadministration.ApproveChatJoinRequest
import org.telegram.telegrambots.meta.api.methods.groupadministration.CreateChatInviteLink
import org.telegram.telegrambots.meta.api.methods.groupadministration.DeclineChatJoinRequest
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
import org.telegram.telegrambots.meta.api.methods.groupadministration.LeaveChat
import org.telegram.telegrambots.meta.api.methods.groupadministration.RevokeChatInviteLink
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberOwner
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
    val canInviteUsers: Boolean
)

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
                canInviteUsers = member.canInviteUsers ?: false
            )
            // creator недостижим для бота, но маппинг честный: владельцу можно всё.
            is ChatMemberOwner -> BotChatState(member.status, canPinMessages = true, canInviteUsers = true)
            else -> BotChatState(member.status, canPinMessages = false, canInviteUsers = false)
        }
    }

    /** Состоит ли ПОЛЬЗОВАТЕЛЬ в чате. Null = не удалось узнать (сеть/права — не путать с false). */
    fun isChatParticipant(chatId: Long, userId: Long): Boolean? {
        val member = getChatMember(chatId, userId) ?: return null
        return when (member.status) {
            "creator", "administrator", "member" -> true
            "restricted" -> (member as? ChatMemberRestricted)?.isMember ?: false
            else -> false
        }
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
