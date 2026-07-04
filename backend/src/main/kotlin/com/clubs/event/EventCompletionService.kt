package com.clubs.event

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Автоматически завершает события, чьё время уже прошло.
 *
 * Здесь чинится пробел в жизненном цикле: `Stage2Service` переводит события
 * `upcoming -> stage_2`, но ничто не выставляло [com.clubs.generated.jooq.enums.EventStatus.completed].
 * Из-за этого прошедшие события навсегда оставались `upcoming`/`stage_2`, и единая лента активности
 * никогда их не приглушала (приглушение = статус в completed/cancelled) — в отличие от складчин,
 * которые закрывает [com.clubs.skladchina.SkladchinaScheduler].
 *
 * Флоу посещаемости после события ([AttendanceService.markAttendance], спор, разрешение,
 * [AttendanceService.finalizeAttendance]) опирается только на булевы `attendance_marked` /
 * `attendance_finalized` и `event_datetime` — никогда на статус — поэтому переключение
 * статуса на `completed` этому не мешает. Небольшой запас [COMPLETION_GRACE_HOURS] не даёт
 * приглушить событие, которое ещё идёт (буфер на часовой пояс / длительность).
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
        // Период планировщика: раз в час, совпадает с AttendanceService
        private const val COMPLETION_SCHEDULER_PERIOD_MS = 3_600_000L
        // Запас в часах перед завершением события — не даёт приглушить ещё идущее событие
        private const val COMPLETION_GRACE_HOURS = 6L
    }
}
