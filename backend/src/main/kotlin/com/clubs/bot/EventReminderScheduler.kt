package com.clubs.bot

import com.clubs.event.EventRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Напоминания о событии на основе поллинга (модуль bot = слой уведомлений; зависит от `event`).
 *
 *  A. [remindUnconfirmedVoters] — примерно за `confirmHoursBefore` (по умолчанию 2ч) до события,
 *     DM тем, кто голосовал going/maybe, но ещё не подтвердил, — подталкивает подтвердить до того,
 *     как окно закроется в момент начала события (Bug B / Feature A).
 *  B. [remindOrganizersToMarkAttendance] — примерно через `attendanceHoursAfter` (по умолчанию 24ч)
 *     после события, DM организатору отметить посещаемость (без этого репутация никогда не финализируется — EXP-2).
 *
 * Намеренно БЕЗ @Transactional на методах цикла: `mark*ReminderSent` каждого события — независимый
 * автокоммитящийся UPDATE, так что одно плохое событие не может отравить весь батч. Это обходит
 * ловушку catch-внутри-@Transactional (EXP-3), которая есть у цикла триггеров. Флаг дедупликации
 * выставляется ДО @Async DM, так что повторяющийся поллинг никогда не шлёт дважды; DM, упавший после
 * этого, логируется и отбрасывается (доставка «на лучших усилиях», как и все остальные DM в NotificationService).
 */
@Component
class EventReminderScheduler(
    private val eventRepository: EventRepository,
    private val notificationService: NotificationService,
    @Value("\${events.confirm-reminder-minutes-before:120}") private val confirmMinutesBefore: Long,
    @Value("\${events.attendance-reminder-minutes-after:1440}") private val attendanceMinutesAfter: Long
) {
    private val log = LoggerFactory.getLogger(EventReminderScheduler::class.java)

    @Scheduled(fixedDelayString = "\${events.reminder-poll-ms:300000}")
    fun remindUnconfirmedVoters() {
        val now = OffsetDateTime.now()
        eventRepository.findEventsNeedingConfirmReminder(now, now.plusMinutes(confirmMinutesBefore)).forEach { event ->
            try {
                eventRepository.markConfirmReminderSent(event.id)
                notificationService.sendConfirmReminder(event)
            } catch (e: Exception) {
                log.error("Confirm reminder failed for eventId={}", event.id, e)
            }
        }
    }

    @Scheduled(fixedDelayString = "\${events.reminder-poll-ms:300000}")
    fun remindOrganizersToMarkAttendance() {
        val cutoff = OffsetDateTime.now().minusMinutes(attendanceMinutesAfter)
        eventRepository.findEventsNeedingAttendanceReminder(cutoff).forEach { event ->
            try {
                eventRepository.markAttendanceReminderSent(event.id)
                val organizerTelegramId = eventRepository.findOrganizerTelegramId(event.id)
                if (organizerTelegramId != null) {
                    notificationService.sendAttendanceReminder(event, organizerTelegramId)
                } else {
                    log.warn("Attendance reminder SKIPPED — no organizer telegram id for eventId={}", event.id)
                }
            } catch (e: Exception) {
                log.error("Attendance reminder failed for eventId={}", event.id, e)
            }
        }
    }
}
