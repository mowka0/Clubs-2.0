package com.clubs.bot

import com.clubs.event.Stage2StartedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * S2T-2: when an event transitions to Stage 2, DM going/maybe voters asking them to confirm
 * participation. Reacts AFTER_COMMIT because [NotificationService.sendStage2Started] is
 * @Async — it queries voter rows on a separate connection, which only sees the transition
 * and waitlist assignments once the scheduler transaction has committed. Best-effort:
 * delivery errors are swallowed inside sendDm, like every other DM. Mirrors
 * AttendanceMarkedListener.
 */
@Component
class Stage2StartedListener(
    private val notificationService: NotificationService
) {

    private val log = LoggerFactory.getLogger(Stage2StartedListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onStage2Started(event: Stage2StartedEvent) {
        log.info("Stage 2 started for event {} — notifying going/maybe voters", event.event.id)
        notificationService.sendStage2Started(event.event)
    }
}
