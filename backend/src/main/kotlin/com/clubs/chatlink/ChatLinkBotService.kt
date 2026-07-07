package com.clubs.chatlink

import com.clubs.bot.ChatTelegramGateway
import com.clubs.club.ClubRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Сторона БОТА в привязке чата: обработка событий Telegram, которые роутит [com.clubs.bot.ClubsBot].
 * Привязка (deep link ?startgroup=<club_id> → /start с payload в группе), health-мониторинг
 * my_chat_member, миграция группы в супергруппу, callback «Отвязать чат» из DM-петли подтверждения.
 */
@Service
class ChatLinkBotService(
    private val chatLinkRepository: ChatLinkRepository,
    private val clubRepository: ClubRepository,
    private val userRepository: UserRepository,
    private val chatLinkService: ChatLinkService,
    private val gateway: ChatTelegramGateway
) {
    private val log = LoggerFactory.getLogger(ChatLinkBotService::class.java)

    /**
     * `/start <club_id>` в группе — попытка привязки. Гейт безопасности (решение PO):
     * отправитель `/start` (= человек, добавивший бота по deep link'у) обязан быть владельцем
     * клуба. Любой отказ — объясняющее сообщение в чат и выход бота из группы, чтобы не
     * оставлять «мёртвого» бота в чужих чатах.
     */
    @Transactional
    fun handleGroupStart(chatId: Long, chatTitle: String?, fromTelegramId: Long, clubId: UUID) {
        val club = clubRepository.findById(clubId)
        if (club == null || !club.isActive) {
            refuseAndLeave(chatId, "Клуб не найден. Откройте «Управление клубом» в приложении Clubs и нажмите «Привязать чат» ещё раз.")
            return
        }

        val sender = userRepository.findByTelegramId(fromTelegramId)
        if (sender?.id != club.ownerId) {
            log.warn("Chat link refused — sender is not club owner: clubId={} chatId={} fromTelegramId={}", clubId, chatId, fromTelegramId)
            refuseAndLeave(chatId, "Привязать чат к клубу «${club.name}» может только владелец клуба в приложении Clubs.")
            return
        }

        val existingForClub = chatLinkRepository.findByClubId(clubId)
        if (existingForClub != null && existingForClub.chatId == chatId) {
            // Повторное добавление в тот же чат — идемпотентно освежаем состояние, без петли подтверждения.
            refreshState(existingForClub)
            gateway.sendGroupMessage(chatId, "Чат уже привязан к клубу «${club.name}».")
            return
        }
        if (existingForClub != null) {
            refuseAndLeave(chatId, "У клуба «${club.name}» уже привязан другой чат. Сначала отвяжите его в «Управлении клубом».")
            return
        }
        val existingForChat = chatLinkRepository.findByChatId(chatId)
        if (existingForChat != null) {
            refuseAndLeave(chatId, "Этот чат уже привязан к другому клубу. Один чат — один клуб.")
            return
        }

        // Права на момент привязки: если владелец пропустил шаг «сделать админом», бот останется
        // member'ом — фичи в UI покажутся как недоступные, refresh дообогатит после выдачи прав.
        val state = gateway.getBotChatState(chatId)
        val link = chatLinkRepository.insert(
            ChatLink(
                clubId = clubId,
                chatId = chatId,
                chatTitle = chatTitle,
                // Гейт выше гарантирует sender.id == club.ownerId — используем ownerId (non-null тип).
                linkedByUserId = club.ownerId,
                linkedAt = OffsetDateTime.now(),
                botStatus = state?.let { BotChatStatus.fromTelegramStatus(it.statusLiteral) } ?: BotChatStatus.MEMBER,
                canPinMessages = state?.canPinMessages ?: false,
                canInviteUsers = state?.canInviteUsers ?: false,
                doorEnabled = false,
                doorInviteLink = null
            )
        )
        log.info("Chat linked: clubId={} chatId={} byTelegramId={} botStatus={}", clubId, chatId, fromTelegramId, link.botStatus.literal)

        // Петля подтверждения (решение PO): фишинг-привязка мгновенно видна и обратима.
        gateway.sendGroupMessage(chatId, "✅ Чат привязан к клубу «${club.name}». Управление — в приложении Clubs, вкладка «Чат».")
        gateway.sendDmWithCallbackButton(
            telegramId = fromTelegramId,
            text = "Чат «${chatTitle ?: "без названия"}» привязан к вашему клубу «${club.name}». Это были вы?\n\nЕсли нет — отвяжите чат кнопкой ниже.",
            buttonText = "Отвязать чат",
            callbackData = "$UNLINK_CALLBACK_PREFIX$clubId"
        )
    }

    /**
     * my_chat_member: статус самого бота в чате изменился (кикнут / вернули / выдали или отняли
     * права). Привязку НЕ удаляем — фичи гаснут, а после возврата прав всё оживает (мокап 01-C).
     */
    @Transactional
    fun handleMyChatMember(chatId: Long, newStatusLiteral: String, canPinMessages: Boolean, canInviteUsers: Boolean) {
        val link = chatLinkRepository.findByChatId(chatId) ?: return
        val status = BotChatStatus.fromTelegramStatus(newStatusLiteral)
        chatLinkRepository.updateBotState(link.clubId, status, canPinMessages, canInviteUsers)
        log.info(
            "Bot chat state updated: clubId={} chatId={} status={} canPin={} canInvite={}",
            link.clubId, chatId, status.literal, canPinMessages, canInviteUsers
        )
    }

    /** Группа мигрировала в супергруппу — Telegram сменил chat_id, переносим привязку. */
    @Transactional
    fun handleChatMigration(oldChatId: Long, newChatId: Long) {
        val link = chatLinkRepository.findByChatId(oldChatId) ?: return
        chatLinkRepository.updateChatId(oldChatId, newChatId)
        log.info("Chat id migrated (group→supergroup): clubId={} {} → {}", link.clubId, oldChatId, newChatId)
    }

    /**
     * Кнопка «Отвязать чат» из DM-петли подтверждения. Возвращает текст для answerCallbackQuery.
     * Гейт: жать может только текущий владелец клуба (DM могли переслать).
     */
    @Transactional
    fun handleUnlinkCallback(fromTelegramId: Long, clubId: UUID): String {
        val club = clubRepository.findById(clubId) ?: return "Клуб не найден"
        val caller = userRepository.findByTelegramId(fromTelegramId)
        if (caller?.id != club.ownerId) return "Отвязать чат может только владелец клуба"
        val link = chatLinkRepository.findByClubId(clubId) ?: return "Чат уже отвязан"
        chatLinkService.doUnlink(link)
        return "Чат отвязан от клуба «${club.name}»"
    }

    private fun refreshState(link: ChatLink) {
        val state = gateway.getBotChatState(link.chatId) ?: return
        chatLinkRepository.updateBotState(
            clubId = link.clubId,
            botStatus = BotChatStatus.fromTelegramStatus(state.statusLiteral),
            canPinMessages = state.canPinMessages,
            canInviteUsers = state.canInviteUsers
        )
    }

    private fun refuseAndLeave(chatId: Long, text: String) {
        gateway.sendGroupMessage(chatId, text)
        gateway.leaveChat(chatId)
    }

    companion object {
        /** Префикс callback_data кнопки «Отвязать чат» в DM-петле подтверждения (дальше — UUID клуба). */
        const val UNLINK_CALLBACK_PREFIX = "chatlink:unlink:"
    }
}
