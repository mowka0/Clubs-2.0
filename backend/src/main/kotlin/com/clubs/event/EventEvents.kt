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
 * Публикуется при редактировании встречи организатором (разрешено только на Этапе 1) —
 * как при переносе даты, так и при смене места или прочих полей.
 * [event] несёт уже НОВОЕ состояние, [oldEvent] — прежнее, для строк «было → стало».
 *
 * Слушатель (EventEditedListener) на AFTER_COMMIT: громкий пост в чат + DM участникам,
 * которых пост не покрыл, + dirty-флаг закрепа (данные в нём перерисуются flush-проходом).
 *
 * Публикуется ТОЛЬКО когда изменилось критичное — «где» или «когда» (см.
 * [EventEditedEvent.hasCriticalChanges]). Правка названия, описания, фото, лимита или
 * интервала Этапа 2 проходит молча: дёргать весь клуб из-за опечатки в заголовке —
 * шум, из-за которого уведомления перестают читать (решение PO 2026-07-26).
 */
data class EventEditedEvent(val event: Event, val oldEvent: Event) {

    /** Что именно поменялось — для текста уведомления и для решения, слать ли его вообще. */
    val isDatetimeChanged: Boolean
        get() = oldEvent.eventDatetime != event.eventDatetime

    /**
     * Место в широком смысле: адрес, точка на карте и уточнение «как найти». Уточнение
     * включено намеренно — «вход со двора, домофон 12» отвечает на тот же вопрос «куда идти»,
     * что и сам адрес, и участник обязан узнать о его изменении.
     */
    val isLocationChanged: Boolean
        get() = oldEvent.locationText != event.locationText ||
            oldEvent.locationLat != event.locationLat ||
            oldEvent.locationLon != event.locationLon ||
            oldEvent.locationHint != event.locationHint

    val hasCriticalChanges: Boolean
        get() = isDatetimeChanged || isLocationChanged
}

/**
 * Публикуется при любом изменении ростера события, видимом в «живом закрепе» чата:
 * голос Этапа 1 (castVote), подтверждение/отказ Этапа 2 (включая промоут из очереди),
 * освобождение брони при выходе участника из клуба. Слушатель (LivePinListener) лишь
 * ставит dirty-флаг — реальная перерисовка закрепа идёт flush-планировщиком LivePinService
 * с дебаунсом, поэтому шторм голосов не упирается в лимиты Telegram.
 */
data class EventRosterChangedEvent(val eventId: UUID)
