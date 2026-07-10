package com.clubs.chatlink

import java.time.OffsetDateTime
import java.util.UUID

// Имя invite-ссылки бота в списке приглашений группы — чтобы организатор узнавал её в
// настройках Telegram. Общая для создания при привязке/включении двери (ChatLinkService)
// и пересоздания после возвращения бота (ChatLinkBotService).
internal const val DOOR_INVITE_LINK_NAME = "Clubs: вход через заявки"

/**
 * Статус БОТА в привязанном чате (зеркалит статусы Telegram my_chat_member,
 * которые нам важны). Хранится в club_chat_links.bot_status как lowercase-литерал.
 */
enum class BotChatStatus(val literal: String) {
    ADMINISTRATOR("administrator"),
    MEMBER("member"),
    LEFT("left"),
    KICKED("kicked");

    /** Бот присутствует в чате (может читать/писать) — фичи в принципе возможны. */
    val isInChat: Boolean get() = this == ADMINISTRATOR || this == MEMBER

    companion object {
        fun fromLiteral(literal: String): BotChatStatus =
            entries.firstOrNull { it.literal == literal } ?: MEMBER

        /**
         * Маппинг сырого статуса Telegram (creator/administrator/member/restricted/left/kicked)
         * в наш enum. Экзотические для бота статусы схлопываются в ближайший осмысленный.
         */
        fun fromTelegramStatus(status: String): BotChatStatus = when (status) {
            "administrator", "creator" -> ADMINISTRATOR
            "left" -> LEFT
            "kicked" -> KICKED
            else -> MEMBER
        }
    }
}

/** Доменная модель привязки чата к клубу (строка club_chat_links). */
data class ChatLink(
    val clubId: UUID,
    val chatId: Long,
    val chatTitle: String?,
    val linkedByUserId: UUID,
    val linkedAt: OffsetDateTime,
    val botStatus: BotChatStatus,
    val canPinMessages: Boolean,
    val canInviteUsers: Boolean,
    val canRestrictMembers: Boolean,
    val canManageTags: Boolean,
    val doorEnabled: Boolean,
    val doorInviteLink: String?,
    val livePinEnabled: Boolean,
    val skladchinaStatusEnabled: Boolean,
    val strictModeEnabled: Boolean,
    val awardTagsEnabled: Boolean
)
