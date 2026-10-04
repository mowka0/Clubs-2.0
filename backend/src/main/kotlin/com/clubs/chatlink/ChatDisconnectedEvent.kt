package com.clubs.chatlink

import java.util.UUID

/**
 * Клуб потерял чат: строка привязки удалена (отвязка из приложения, кнопка в DM, удаление клуба,
 * перехват осиротевшей привязки) или бота выгнали / он вышел (`my_chat_member`; привязка при этом
 * остаётся ради оживления). Для воронки — шаг `chat_disconnected` (funnel.md § 3.1).
 */
data class ChatDisconnectedEvent(
    val clubId: UUID,
    /** Кто привязывал чат (`club_chat_links.linked_by_user_id`). */
    val linkedByUserId: UUID,
)
