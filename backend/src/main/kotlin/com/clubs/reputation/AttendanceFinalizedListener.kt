package com.clubs.reputation

import com.clubs.event.AttendanceFinalizedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Low-latency reputation processing: reacts to AttendanceFinalizedEvent AFTER the
 * finalize transaction commits, in its own REQUIRES_NEW transaction (inside
 * ReputationService). Best-effort — a failure here is logged and recovered by the
 * hourly poll (ReputationScheduler). Decoupled from finalization on purpose: a
 * reputation error must never roll back the attendance finalize.
 */
@Component
class AttendanceFinalizedListener(
    private val reputationService: ReputationService
) {

    private val log = LoggerFactory.getLogger(AttendanceFinalizedListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAttendanceFinalized(event: AttendanceFinalizedEvent) {
        try {
            reputationService.processFinalizedEvent(event.eventId)
        } catch (e: Exception) {
            log.error("Live reputation processing failed for event {} (poll will retry)", event.eventId, e)
        }
    }
}
