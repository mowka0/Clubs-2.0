package com.clubs.bot

import com.clubs.event.EventCancelledEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * F5-14: когда организатор отменяет событие, шлём DM заинтересованным голосовавшим (going/maybe),
 * что оно отменено. AFTER_COMMIT — потому что [NotificationService.sendEventCancelled] @Async и
 * читает строки голосовавших через отдельное соединение, которое обязано видеть уже закоммиченную
 * отмену. Best-effort — ошибки доставки глотаются внутри sendDm. Зеркалит Stage2StartedListener.
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
