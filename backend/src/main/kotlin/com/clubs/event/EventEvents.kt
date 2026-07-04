package com.clubs.event

/**
 * Публикуется после создания события и коммита исходной транзакции.
 * Слушатель (EventBotNotifier) рассылает DM участникам клуба.
 * Канонический паттерн @TransactionalEventListener см. в
 * SkladchinaBotNotifier / SkladchinaBotNotifier.
 */
data class EventCreatedEvent(val event: Event)
