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
    private val livePinService: LivePinService,
    @Value("\${telegram.bot-username}") private val botUsername: String
) {
    private val log = LoggerFactory.getLogger(ChatLinkService::class.java)

    /**
     * Частичный PATCH тумблеров таба «Чат»: применяет только присланные поля
     * (сейчас фронт шлёт по одному тумблеру за запрос).
     */
    @Transactional
    fun update(clubId: UUID, callerId: UUID, request: UpdateChatLinkRequest): ChatLinkStatusDto {
        var status: ChatLinkStatusDto? = null
        request.doorEnabled?.let { status = setDoor(clubId, callerId, it) }
        request.livePinEnabled?.let { status = setLivePin(clubId, callerId, it) }
        return status ?: getStatus(clubId, callerId)
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
        // Лечим отсутствующую invite-ссылку (привязали без права приглашать, право выдали позже,
        // а my_chat_member-переход по какой-то причине не был пойман) — refresh как ручной ремонт.
        val nowInChat = BotChatStatus.fromTelegramStatus(state.statusLiteral).isInChat
        if (link.doorInviteLink == null && nowInChat && state.canInviteUsers) {
            gateway.createJoinRequestInviteLink(link.chatId, DOOR_INVITE_LINK_NAME)?.let {
                chatLinkRepository.updateInviteLink(clubId, it)
                log.info("Invite link created on refresh: clubId={} chatId={}", clubId, link.chatId)
            }
        }
        log.info("Chat link refreshed: clubId={} chatId={} status={}", clubId, link.chatId, state.statusLiteral)
        return mapper.toStatusDto(chatLinkRepository.findByClubId(clubId), startGroupUrl(clubId))
    }

    /**
     * Тумблер «Вход в чат через заявки» = политика для ЧУЖИХ (включён → бот пишет стучащимся
     * не-участникам правила и впускает только одобренных в клубе). Invite-ссылка живёт
     * НЕЗАВИСИМО от тумблера (реестр багов №4): создаётся при привязке, по ней работает
     * кнопка «Чат клуба», выключение двери её НЕ отзывает — иначе умирает кнопка и все
     * разосланные DM. Включение требует право приглашать (иначе бот не сможет одобрять).
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
            // Ссылка обычно уже создана при привязке; создаём только если её ещё нет
            // (например, право приглашать выдали позже и переход не был пойман).
            val inviteLink = link.doorInviteLink
                ?: gateway.createJoinRequestInviteLink(link.chatId, DOOR_INVITE_LINK_NAME)
                ?: throw ConflictException("Не удалось создать ссылку-приглашение — проверьте права бота и попробуйте позже")
            chatLinkRepository.updateDoor(clubId, doorEnabled = true, doorInviteLink = inviteLink)
            log.info("Chat door enabled: clubId={} chatId={}", clubId, link.chatId)
        } else {
            chatLinkRepository.updateDoor(clubId, doorEnabled = false, doorInviteLink = link.doorInviteLink)
            log.info("Chat door disabled (invite link kept alive): clubId={} chatId={}", clubId, link.chatId)
        }
        return mapper.toStatusDto(chatLinkRepository.findByClubId(clubId), startGroupUrl(clubId))
    }

    /**
     * Тумблер «Живой закреп» (слайс 3). Включение требует бота в чате и право закрепа (409 —
     * зеркалит дверь) и сразу делает backfill: статус-посты для всех будущих событий клуба.
     * Выключение открепляет живые закрепы (сообщения остаются в истории); потеря права закрепа
     * ПОСЛЕ включения тумблер не сбрасывает — редактирование своих сообщений права не требует.
     */
    @Transactional
    fun setLivePin(clubId: UUID, callerId: UUID, enabled: Boolean): ChatLinkStatusDto {
        requireOwner(clubId, callerId)
        val link = chatLinkRepository.findByClubId(clubId)
            ?: throw NotFoundException("Chat is not linked")

        if (enabled == link.livePinEnabled) {
            return mapper.toStatusDto(link, startGroupUrl(clubId)) // идемпотентно
        }

        if (enabled) {
            if (!link.botStatus.isInChat) {
                throw ConflictException("Бот удалён из чата — верните его в группу и проверьте права")
            }
            if (!link.canPinMessages) {
                throw ConflictException("Боту нужно право «Закрепление сообщений» в настройках группы")
            }
            chatLinkRepository.updateLivePin(clubId, livePinEnabled = true)
            livePinService.backfillForClub(clubId)
            log.info("Live pin enabled: clubId={} chatId={}", clubId, link.chatId)
        } else {
            chatLinkRepository.updateLivePin(clubId, livePinEnabled = false)
            livePinService.disableForClub(link)
            log.info("Live pin disabled: clubId={} chatId={}", clubId, link.chatId)
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
     * Общий механизм отвязки (REST и DM-callback): снять живые закрепы (пока бот ещё в чате и
     * может unpin), отозвать door-ссылку, выйти из чата, удалить строку. Telegram-шаги
     * best-effort — запись у нас удаляется в любом случае, иначе мёртвая привязка блокировала
     * бы повторную. Без чистки закрепов их строки остались бы «живыми» навсегда, а flush
     * пытался бы редактировать сообщения в чате, из которого бот вышел.
     */
    @Transactional
    fun doUnlink(link: ChatLink) {
        livePinService.disableForClub(link)
        link.doorInviteLink?.let { gateway.revokeInviteLink(link.chatId, it) }
        gateway.leaveChat(link.chatId)
        chatLinkRepository.delete(link.clubId)
        log.info("Chat unlinked: clubId={} chatId={}", link.clubId, link.chatId)
    }

    /**
     * Deep link для кнопки «Привязать чат»: payload = club_id (решение PO — без одноразовых
     * кодов, гейт — верификация владельца при /start), admin= сразу просит права для
     * закрепа, двери и снятия банов (restrict_members — реестр багов №1: «удалить из группы»
     * = бан, и без этого права бот не может впустить вернувшегося участника).
     */
    fun startGroupUrl(clubId: UUID): String =
        "https://t.me/$botUsername?startgroup=$clubId&admin=pin_messages+invite_users+restrict_members"

    private fun requireOwner(clubId: UUID, callerId: UUID): Club {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != callerId) throw ForbiddenException("Only the club owner can manage the chat link")
        return club
    }
}
