package com.clubs.event

/**
 * Публикуется, когда организатор отменяет событие (F5-14). Обрабатывается AFTER_COMMIT в
 * [com.clubs.bot.EventCancelledListener], чтобы отправить DM заинтересованным проголосовавшим.
 * Несёт (до-отменное) доменное событие для названия/даты-времени и опциональную причину для текста сообщения.
 */
data class EventCancelledEvent(val event: Event, val reason: String?)
