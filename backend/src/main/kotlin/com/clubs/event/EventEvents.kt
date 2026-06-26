package com.clubs.event

/**
 * Published after an event is created and the originating transaction has
 * committed. Listener (EventBotNotifier) DMs the club's members.
 * See SkladchinaBotNotifier / SkladchinaBotNotifier for the canonical
 * @TransactionalEventListener pattern.
 */
data class EventCreatedEvent(val event: Event)
