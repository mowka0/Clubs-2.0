package com.clubs.chatlink

import com.clubs.bot.ChatTelegramGateway
import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Владельческие операции над привязкой чата (таб «Чат» в «Управлении клубом»).
 * Привязку СОЗДАЁТ не этот сервис, а [ChatLinkBotService] — по событию добавления бота
 * в группу deep link'ом ?startgroup=<club_id>. Здесь: статус, refresh прав, тумблер
 * «двери», отвязка. Каждый метод гейтится владением клубом.
 */
@Service
class ChatLinkService(
    private val chatLinkRepository: ChatLinkRepository,
    private val clubRepository: ClubRepository,
    private val mapper: ChatLinkMapper,
    private val gateway: ChatTelegramGateway,
    @Value("\${telegram.bot-username}") private val botUsername: String
) {
    private val log = LoggerFactory.getLogger(ChatLinkService::class.java)

    // Имя door-ссылки в списке приглашений группы — чтобы организатор узнавал её в настройках Telegram.
    private companion object {
        const val DOOR_INVITE_LINK_NAME = "Clubs: вход через заявки"
    }

    fun getStatus(clubId: UUID, callerId: UUID): ChatLinkStatusDto {
        requireOwner(clubId, callerId)
        return mapper.toStatusDto(chatLinkRepository.findByClubId(clubId), startGroupUrl(clubId))
    }

    /** «Проверить права ещё раз»: перечитывает статус бота и название чата из Telegram. */
    @Transactional
    fun refresh(clubId: UUID, callerId: UUID): ChatLinkStatusDto {
        requireOwner(clubId, callerId)
        val link = chatLinkRepository.findByClubId(clubId)
            ?: throw NotFoundException("Chat is not linked")

        val state = gateway.getBotChatState(link.chatId)
            ?: throw ConflictException("Не удалось проверить чат — Telegram не отвечает, попробуйте позже")
        chatLinkRepository.updateBotState(
            clubId = clubId,
            botStatus = BotChatStatus.fromTelegramStatus(state.statusLiteral),
            canPinMessages = state.canPinMessages,
            canInviteUsers = state.canInviteUsers
        )
        gateway.getChatTitle(link.chatId)?.let { title ->
            if (title != link.chatTitle) chatLinkRepository.updateChatTitle(clubId, title)
        }
        log.info("Chat link refreshed: clubId={} chatId={} status={}", clubId, link.chatId, state.statusLiteral)
        return mapper.toStatusDto(chatLinkRepository.findByClubId(clubId), startGroupUrl(clubId))
    }

    /**
     * Тумблер «Вход в чат через заявки». Включение требует, чтобы бот был админом с правом
     * приглашать (иначе createChatInviteLink невозможен) — возвращаем 409 с объяснением.
     */
    @Transactional
    fun setDoor(clubId: UUID, callerId: UUID, enabled: Boolean): ChatLinkStatusDto {
        requireOwner(clubId, callerId)
        val link = chatLinkRepository.findByClubId(clubId)
            ?: throw NotFoundException("Chat is not linked")

        if (enabled == link.doorEnabled) {
            return mapper.toStatusDto(link, startGroupUrl(clubId)) // идемпотентно
        }

        if (enabled) {
            if (!link.botStatus.isInChat) {
                throw ConflictException("Бот удалён из чата — верните его в группу и проверьте права")
            }
            if (!link.canInviteUsers) {
                throw ConflictException("Боту нужно право «Приглашение участников» в настройках группы")
            }
            val inviteLink = gateway.createJoinRequestInviteLink(link.chatId, DOOR_INVITE_LINK_NAME)
                ?: throw ConflictException("Не удалось создать ссылку-приглашение — проверьте права бота и попробуйте позже")
            chatLinkRepository.updateDoor(clubId, doorEnabled = true, doorInviteLink = inviteLink)
            log.info("Chat door enabled: clubId={} chatId={}", clubId, link.chatId)
        } else {
            // Отзыв старой ссылки best-effort: даже если Telegram недоступен, дверь у нас выключена,
            // а заявки по мёртвой ссылке бот больше не одобряет (door_enabled = false).
            link.doorInviteLink?.let { gateway.revokeInviteLink(link.chatId, it) }
            chatLinkRepository.updateDoor(clubId, doorEnabled = false, doorInviteLink = null)
            log.info("Chat door disabled: clubId={} chatId={}", clubId, link.chatId)
        }
        return mapper.toStatusDto(chatLinkRepository.findByClubId(clubId), startGroupUrl(clubId))
    }

    /** Отвязка владельцем из приложения (или кнопкой в DM-петле подтверждения — через [unlinkAsOwner]). */
    @Transactional
    fun unlink(clubId: UUID, callerId: UUID) {
        requireOwner(clubId, callerId)
        val link = chatLinkRepository.findByClubId(clubId)
            ?: throw NotFoundException("Chat is not linked")
        doUnlink(link)
    }

    /**
     * Общий механизм отвязки (REST и DM-callback): отозвать door-ссылку, выйти из чата,
     * удалить строку. Telegram-шаги best-effort — запись у нас удаляется в любом случае,
     * иначе мёртвая привязка блокировала бы повторную.
     */
    @Transactional
    fun doUnlink(link: ChatLink) {
        link.doorInviteLink?.let { gateway.revokeInviteLink(link.chatId, it) }
        gateway.leaveChat(link.chatId)
        chatLinkRepository.delete(link.clubId)
        log.info("Chat unlinked: clubId={} chatId={}", link.clubId, link.chatId)
    }

    /**
     * Deep link для кнопки «Привязать чат»: payload = club_id (решение PO — без одноразовых
     * кодов, гейт — верификация владельца при /start), admin= сразу просит права для
     * закрепа и двери, чтобы не выпрашивать их вторым заходом.
     */
    fun startGroupUrl(clubId: UUID): String =
        "https://t.me/$botUsername?startgroup=$clubId&admin=pin_messages+invite_users"

    private fun requireOwner(clubId: UUID, callerId: UUID): Club {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != callerId) throw ForbiddenException("Only the club owner can manage the chat link")
        return club
    }
}
