package com.clubs.bot

import com.clubs.event.EventCancelledEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * F5-14: when an organizer cancels an event, DM the interested voters (going/maybe) that it's off.
 * AFTER_COMMIT because [NotificationService.sendEventCancelled] is @Async and queries voter rows on
 * a separate connection, which must see the committed cancellation. Best-effort — delivery errors
 * are swallowed inside sendDm. Mirrors Stage2StartedListener.
 */
@Component
class EventCancelledListener(
    private val notificationService: NotificationService
) {

    private val log = LoggerFactory.getLogger(EventCancelledListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onEventCancelled(event: EventCancelledEvent) {
        log.info("Event {} cancelled — notifying interested voters", event.event.id)
        notificationService.sendEventCancelled(event.event, event.reason)
    }
}
