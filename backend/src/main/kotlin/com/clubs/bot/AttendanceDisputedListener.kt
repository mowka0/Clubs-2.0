package com.clubs.bot

import com.clubs.event.AttendanceDisputedEvent
import com.clubs.event.EventRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * ATT-3 follow-up: when a participant disputes an "absent" mark, DM the organizer. Without this
 * nudge an organizer who never reopens the event page misses the dispute entirely, and the
 * window expiry converts it back to absent (no_show penalty) unreviewed. AFTER_COMMIT for the
 * same reason as AttendanceMarkedListener — the @Async DM reads committed rows. Best-effort:
 * delivery errors are swallowed inside sendDm, like every other DM.
 */
@Component
class AttendanceDisputedListener(
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService
) {

    private val log = LoggerFactory.getLogger(AttendanceDisputedListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAttendanceDisputed(event: AttendanceDisputedEvent) {
        val domainEvent = eventRepository.findById(event.eventId)
        if (domainEvent == null) {
            log.warn("Dispute DM skipped: event not found eventId={}", event.eventId)
            return
        }
        val organizerTelegramId = eventRepository.findOrganizerTelegramId(event.eventId)
        if (organizerTelegramId == null) {
            log.warn("Dispute DM skipped: organizer telegram id not found eventId={}", event.eventId)
            return
        }
        val disputerName = userRepository.findById(event.disputerUserId)
            ?.let { listOfNotNull(it.firstName, it.lastName).joinToString(" ").ifBlank { null } }
            ?: "Участник"
        notificationService.sendAttendanceDisputed(domainEvent, organizerTelegramId, disputerName)
    }
}
