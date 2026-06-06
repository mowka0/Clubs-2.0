package com.clubs.bot

import com.clubs.event.EventCreatedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Bot DM notifier for event creation. Listens to [EventCreatedEvent] published by
 * EventService and notifies club members AFTER the originating transaction has
 * committed (`@TransactionalEventListener`, default phase = AFTER_COMMIT). Like
 * PaymentNotificationHandler / SkladchinaBotNotifier, firing AFTER_COMMIT is the
 * proven path for "send DM after DB mutation succeeded" — the listener is skipped
 * if the createEvent transaction rolls back.
 *
 * Unlike those handlers (which send a small, fixed number of DMs synchronously),
 * an event can have many recipients, so dispatch is delegated to the @Async
 * [NotificationService.sendEventCreated]: this listener returns immediately and
 * the send loop runs off the request thread (backend rule: mass notifications via
 * @Async). Per-DM Telegram failures are caught and logged inside sendEventCreated;
 * delivery is fire-and-forget by design — a Telegram outage must not fail event
 * creation.
 */
@Component
class EventBotNotifier(
    private val notificationService: NotificationService
) {
    private val log = LoggerFactory.getLogger(EventBotNotifier::class.java)

    @TransactionalEventListener(fallbackExecution = true)
    fun onEventCreated(created: EventCreatedEvent) {
        log.info("Event-created DM dispatch: eventId={} clubId={}", created.event.id, created.event.clubId)
        notificationService.sendEventCreated(created.event)
    }
}
