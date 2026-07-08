package com.clubs.bot

import com.clubs.event.AttendanceDisputeResolvedEvent
import com.clubs.event.AttendanceDisputedEvent
import com.clubs.event.EventRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Продолжение ATT-3: когда участник оспаривает отметку «absent», шлём DM организатору. Без этого
 * пинга организатор, который больше не открывает страницу события, вовсе пропустит спор, а
 * истечение окна вернёт отметку в absent (штраф no_show) без разбора. AFTER_COMMIT по той же
 * причине, что и в AttendanceMarkedListener — @Async-DM читает уже закоммиченные строки.
 * Best-effort: ошибки доставки глотаются внутри sendDm, как и у всех остальных DM.
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

    /** Исход спора — DM спорщику (фидбек PO 2026-07-08: раньше результат узнавали только из UI). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onDisputeResolved(event: AttendanceDisputeResolvedEvent) {
        val domainEvent = eventRepository.findById(event.eventId)
        if (domainEvent == null) {
            log.warn("Dispute-resolved DM skipped: event not found eventId={}", event.eventId)
            return
        }
        val participantTelegramId = userRepository.findById(event.userId)?.telegramId
        if (participantTelegramId == null) {
            log.warn("Dispute-resolved DM skipped: participant telegram id not found userId={}", event.userId)
            return
        }
        notificationService.sendAttendanceDisputeResolved(domainEvent, participantTelegramId, event.attended)
    }
}
