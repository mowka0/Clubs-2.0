package com.clubs.bot

import com.clubs.event.AttendanceMarkedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * ATT-3: after an organizer marks attendance, DM the participants flagged absent so they can
 * dispute. Reacts AFTER_COMMIT because [com.clubs.bot.NotificationService.sendAttendanceMarked]
 * is @Async — it queries the just-written `absent` rows on a separate connection, which only
 * sees them once the markAttendance transaction has committed. Best-effort: delivery errors are
 * swallowed inside sendDm, like every other DM. Mirrors AttendanceFinalizedListener.
 */
@Component
class AttendanceMarkedListener(
    private val notificationService: NotificationService
) {

    private val log = LoggerFactory.getLogger(AttendanceMarkedListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAttendanceMarked(event: AttendanceMarkedEvent) {
        log.info("Attendance marked for event {} — notifying {} newly-absent participant(s)", event.eventId, event.newlyAbsentUserIds.size)
        notificationService.sendAttendanceMarked(event.eventId, event.newlyAbsentUserIds)
    }
}
