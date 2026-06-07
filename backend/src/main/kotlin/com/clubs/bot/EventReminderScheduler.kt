package com.clubs.bot

import com.clubs.event.EventRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Poll-based event reminders (bot module = notification layer; depends on `event`).
 *
 *  A. [remindUnconfirmedVoters] — ~`confirmHoursBefore` (default 2h) before the event, DM
 *     going/maybe voters who haven't confirmed yet, nudging them to confirm before the
 *     window closes at event start (Bug B / Feature A).
 *  B. [remindOrganizersToMarkAttendance] — ~`attendanceHoursAfter` (default 24h) after the
 *     event, DM the organizer to mark attendance (without which reputation never finalizes — EXP-2).
 *
 * Deliberately NO @Transactional on the loop methods: each event's `mark*ReminderSent` is an
 * independent auto-committed UPDATE, so one bad event can't poison the batch. This avoids the
 * catch-inside-@Transactional pitfall (EXP-3) that the trigger loop has. The dedup flag is set
 * BEFORE the @Async DM so a recurring poll never double-sends; a DM that then fails is logged
 * and dropped (delivery is best-effort, like every other DM in NotificationService).
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
