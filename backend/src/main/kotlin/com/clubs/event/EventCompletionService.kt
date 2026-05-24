package com.clubs.event

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Auto-completes events whose datetime has passed.
 *
 * Lifecycle gap fixed here: `Stage2Service` moves events `upcoming -> stage_2`, but nothing
 * ever set [com.clubs.generated.jooq.enums.EventStatus.completed]. Past events therefore
 * stayed `upcoming`/`stage_2` forever, so the unified activity feed never dimmed them
 * (dimming = status in completed/cancelled), unlike skladchinas which are closed by
 * [com.clubs.skladchina.SkladchinaScheduler].
 *
 * The post-event attendance flow ([AttendanceService.markAttendance], dispute, resolve,
 * [AttendanceService.finalizeAttendance]) gates only on the `attendance_marked` /
 * `attendance_finalized` booleans and `event_datetime` — never on status — so flipping
 * status to `completed` does not interfere with it. A small [COMPLETION_GRACE_HOURS] grace
 * keeps an in-progress event from being dimmed mid-event (timezone / duration buffer).
 */
@Service
class EventCompletionService(
    private val eventRepository: EventRepository
) {
    private val log = LoggerFactory.getLogger(EventCompletionService::class.java)

    @Scheduled(fixedDelay = COMPLETION_SCHEDULER_PERIOD_MS)
    @Transactional
    fun completePastEvents() {
        val cutoff = OffsetDateTime.now().minusHours(COMPLETION_GRACE_HOURS)
        val count = eventRepository.markPastEventsCompleted(cutoff)
        if (count > 0) log.info("Auto-completed {} past events", count)
    }

    companion object {
        private const val COMPLETION_SCHEDULER_PERIOD_MS = 3_600_000L // hourly, matches AttendanceService
        private const val COMPLETION_GRACE_HOURS = 6L
    }
}
