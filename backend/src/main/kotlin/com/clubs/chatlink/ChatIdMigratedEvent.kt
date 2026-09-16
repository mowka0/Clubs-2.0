package com.clubs.chatlink

import java.util.UUID

/**
 * Telegram сменил id чата при переезде группы в супергруппу; привязка уже переписана на
 * [newChatId] в той же транзакции. Слушатели переносят свои данные, привязанные к chat_id
 * (например, признак бесплатной встречи), — без прямой зависимости chatlink → subscription.
 */
data class ChatIdMigratedEvent(
    val clubId: UUID,
    val oldChatId: Long,
    val newChatId: Long,
)
