package com.clubs.chatlink

import java.util.UUID

interface ChatLinkRepository {
    fun findByClubId(clubId: UUID): ChatLink?
    fun findByChatId(chatId: Long): ChatLink?
    fun insert(link: ChatLink): ChatLink

    /** Обновить статус бота и его права (my_chat_member / refresh). */
    fun updateBotState(clubId: UUID, botStatus: BotChatStatus, canPinMessages: Boolean, canInviteUsers: Boolean)

    /** Обновить название чата (refresh через GetChat). */
    fun updateChatTitle(clubId: UUID, chatTitle: String?)

    /** Тумблер двери вместе со ссылкой (включение — ссылка обязана быть, выключение — null). */
    fun updateDoor(clubId: UUID, doorEnabled: Boolean, doorInviteLink: String?)

    /** Миграция группы в супергруппу: Telegram меняет chat_id (migrate_to_chat_id). */
    fun updateChatId(oldChatId: Long, newChatId: Long)

    fun delete(clubId: UUID)
}
