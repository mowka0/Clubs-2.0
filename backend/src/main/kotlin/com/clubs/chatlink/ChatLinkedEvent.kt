package com.clubs.chatlink

import java.util.UUID

/**
 * Чат привязан к клубу (любой из входов `ChatLinkBotService.linkChatToClub`). Слушатели ведут
 * свой учёт — шаг воронки `chat_connected` (funnel.md § 3.1) — без зависимости chatlink →
 * subscription. Синхронное событие: слушатель работает в транзакции привязки.
 */
data class ChatLinkedEvent(
    val clubId: UUID,
    val ownerUserId: UUID,
    val ownerTelegramId: Long,
)
