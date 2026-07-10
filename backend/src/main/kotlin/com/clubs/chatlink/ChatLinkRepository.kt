package com.clubs.chatlink

import java.util.UUID

interface ChatLinkRepository {
    fun findByClubId(clubId: UUID): ChatLink?
    fun findByChatId(chatId: Long): ChatLink?
    fun insert(link: ChatLink): ChatLink

    /** Обновить статус бота и его права (my_chat_member / refresh). */
    fun updateBotState(clubId: UUID, botStatus: BotChatStatus, canPinMessages: Boolean, canInviteUsers: Boolean, canRestrictMembers: Boolean, canPromoteMembers: Boolean)

    /** Обновить название чата (refresh через GetChat). */
    fun updateChatTitle(clubId: UUID, chatTitle: String?)

    /** Тумблер двери. Ссылка живёт НЕЗАВИСИМО от тумблера (по ней работает кнопка «Чат клуба»). */
    fun updateDoor(clubId: UUID, doorEnabled: Boolean, doorInviteLink: String?)

    /** Обновить только invite-ссылку (создание при привязке / пересоздание после возврата бота). */
    fun updateInviteLink(clubId: UUID, doorInviteLink: String)

    /** Тумблер «Живой закреп» (слайс 3). */
    fun updateLivePin(clubId: UUID, livePinEnabled: Boolean)

    /** Тумблер «Статус сборов в чате» (слайс 3.5). */
    fun updateSkladchinaStatus(clubId: UUID, skladchinaStatusEnabled: Boolean)

    /** Тумблер «Строгий режим» (слайс 5). */
    fun updateStrictMode(clubId: UUID, strictModeEnabled: Boolean)

    /** Тумблер «Титулы наград» (слайс 4). */
    fun updateAwardTitles(clubId: UUID, awardTitlesEnabled: Boolean)

    /** Миграция группы в супергруппу: Telegram меняет chat_id (migrate_to_chat_id). */
    fun updateChatId(oldChatId: Long, newChatId: Long)

    fun delete(clubId: UUID)
}
