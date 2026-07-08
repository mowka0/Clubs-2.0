package com.clubs.event

import java.util.UUID

/**
 * Публикуется после создания события и коммита исходной транзакции.
 * Слушатель (EventBotNotifier) рассылает DM участникам клуба.
 * Канонический паттерн @TransactionalEventListener см. в
 * SkladchinaBotNotifier / SkladchinaBotNotifier.
 */
data class EventCreatedEvent(val event: Event)

/**
 * Публикуется, когда участник из листа ожидания автоматически повышен в confirmed после того,
 * как освободился слот (отказ подтверждённого через кнопку ИЛИ выход подтверждённого из клуба).
 * Слушатель (WaitlistPromotedListener) на AFTER_COMMIT шлёт повышённому DM с кнопкой на событие.
 * Несёт только идентификаторы — слушатель дозапросит событие и telegram id по закоммиченному состоянию.
 */
data class WaitlistPromotedEvent(val eventId: UUID, val promotedUserId: UUID)

/**
 * Публикуется при любом изменении ростера события, видимом в «живом закрепе» чата:
 * голос Этапа 1 (castVote), подтверждение/отказ Этапа 2 (включая промоут из очереди),
 * освобождение брони при выходе участника из клуба. Слушатель (LivePinListener) лишь
 * ставит dirty-флаг — реальная перерисовка закрепа идёт flush-планировщиком LivePinService
 * с дебаунсом, поэтому шторм голосов не упирается в лимиты Telegram.
 */
data class EventRosterChangedEvent(val eventId: UUID)
