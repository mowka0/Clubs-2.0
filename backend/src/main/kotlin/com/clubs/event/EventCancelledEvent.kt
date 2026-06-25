package com.clubs.event

/**
 * Published when an organizer cancels an event (F5-14). Consumed AFTER_COMMIT by
 * [com.clubs.bot.EventCancelledListener] to DM interested voters. Carries the (pre-cancel)
 * domain event for title/datetime and the optional reason for the message body.
 */
data class EventCancelledEvent(val event: Event, val reason: String?)
