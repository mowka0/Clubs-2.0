package com.clubs.bot

import com.clubs.event.EventRepository
import com.clubs.event.RosterBrokenEvent
import com.clubs.event.RosterClosedEvent
import com.clubs.event.RosterWarningEvent
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Уведомления набора состава: закрытие (всем), правила ② и ③ (организатору). AFTER_COMMIT —
 * по той же причине, что у [Stage2StartedListener]: @Async-рассылка читает состав на отдельном
 * соединении и должна видеть уже закоммиченные строки.
 *
 * Недобор к дедлайну своего уведомления не имеет: это отмена встречи, и она уходит обычным
 * каскадом отмены с названной причиной (правило ①).
 */
@Component
class RosterListener(
    private val notificationService: NotificationService,
    private val eventRepository: EventRepository
) {

    private val log = LoggerFactory.getLogger(RosterListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onRosterClosed(event: RosterClosedEvent) {
        log.info("Roster closed for event {} — notifying roster and waitlist", event.event.id)
        notificationService.sendRosterClosed(event.event, event.confirmedCount)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onRosterBroken(event: RosterBrokenEvent) {
        val organizerTelegramId = organizerOf(event.event.id)
        if (organizerTelegramId == null) {
            log.warn("Roster-broken DM SKIPPED — no organizer telegram id for event {}", event.event.id)
            return
        }
        notificationService.sendRosterBroken(
            event = event.event,
            organizerTelegramId = organizerTelegramId,
            confirmedCount = event.confirmedCount,
            minParticipants = event.minParticipants
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onRosterWarning(event: RosterWarningEvent) {
        val organizerTelegramId = organizerOf(event.event.id)
        if (organizerTelegramId == null) {
            log.warn("Roster-warning DM SKIPPED — no organizer telegram id for event {}", event.event.id)
            return
        }
        notificationService.sendRosterWarning(
            event = event.event,
            organizerTelegramId = organizerTelegramId,
            confirmedCount = event.confirmedCount,
            minParticipants = event.minParticipants,
            rosterDeadline = event.rosterDeadline
        )
    }

    /**
     * Кому писать про судьбу набора: ведёт встречу тот, кто её создал (в клубе с
     * со-организаторами это не обязательно владелец). Владелец клуба — фолбэк, если у создателя
     * нет telegram id.
     */
    private fun organizerOf(eventId: UUID): Long? =
        eventRepository.findEventCreatorTelegramId(eventId)
            ?: eventRepository.findOrganizerTelegramId(eventId)
}
