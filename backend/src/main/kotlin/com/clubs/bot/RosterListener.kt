package com.clubs.bot

import com.clubs.event.EventRepository
import com.clubs.event.RosterBrokenEvent
import com.clubs.event.RosterClosedEvent
import com.clubs.event.RosterService
import com.clubs.event.RosterShortfallEvent
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Уведомления по итогам набора состава (V83). AFTER_COMMIT — по той же причине, что у
 * [Stage2StartedListener]: @Async-рассылка читает состав на отдельном соединении и должна
 * видеть уже закоммиченные строки.
 */
@Component
class RosterListener(
    private val notificationService: NotificationService,
    private val eventRepository: EventRepository,
    private val rosterService: RosterService
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
            participantLimit = event.participantLimit,
            pendingCount = eventRepository.getVoteCounts(event.event.id)["noAnswer"] ?: 0
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onRosterShortfall(event: RosterShortfallEvent) {
        // Ведёт встречу тот, кто её создал (в клубе с со-организаторами это не обязательно
        // владелец), поэтому решать судьбу набора зовём его. Владелец клуба — фолбэк на случай,
        // когда у создателя нет telegram id.
        val organizerTelegramId = organizerOf(event.event.id)
        if (organizerTelegramId == null) {
            log.warn("Roster-shortfall DM SKIPPED — no organizer telegram id for event {}", event.event.id)
            return
        }
        // Счёт «кому можно напомнить» берём из того же источника, что и сама рассылка напоминаний,
        // — иначе число на кнопке разойдётся с числом реально отправленных DM.
        val pendingCount = eventRepository.getVoteCounts(event.event.id)["noAnswer"] ?: 0
        notificationService.sendRosterShortfall(
            event = event.event,
            organizerTelegramId = organizerTelegramId,
            confirmedCount = event.confirmedCount,
            participantLimit = event.participantLimit,
            pendingCount = pendingCount,
            autoCancelAt = rosterService.autoCancelAt(event.event)
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
