package com.clubs.event

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.enums.Stage_2Vote
import com.clubs.membership.MembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class Stage2Service(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val membershipRepository: MembershipRepository,
    private val eventPublisher: ApplicationEventPublisher,
    // Lead time (minutes before event start) at which an upcoming event transitions to Stage 2.
    // Default 24h. Minutes unit lets staging shorten it for an end-to-end two-stage test:
    // a short value keeps a brief Stage-1 voting window before the event flips to confirmation.
    @Value("\${events.stage2-trigger-minutes-before:1440}") private val stage2TriggerMinutesBefore: Long
) {
    private val log = LoggerFactory.getLogger(Stage2Service::class.java)

    // The confirmation window is [flip .. event start], and the flip lands anywhere within one
    // poll period after the trigger boundary — so the tick must be much finer than the trigger
    // lead. The old hardcoded 5min tick ate a short staging lead (3min) whole: the flip often
    // landed after event start, leaving a zero-length window.
    @Scheduled(fixedDelayString = "\${events.stage2-poll-ms:60000}")
    @Transactional
    fun triggerStage2ForReadyEvents() {
        val cutoff = OffsetDateTime.now().plusMinutes(stage2TriggerMinutesBefore)
        val events = eventRepository.findEventsToTriggerStage2(cutoff)
        events.forEach { event ->
            try {
                triggerStage2(event)
                log.info("Stage 2 triggered for event ${event.id}")
            } catch (e: Exception) {
                log.error("Failed to trigger Stage 2 for event ${event.id}", e)
            }
        }
    }

    private fun triggerStage2(event: Event) {
        eventRepository.transitionToStage2(event.id)

        // First N going voters (by stage_1_timestamp) keep stage_2_vote = null — they confirm explicitly.
        // Extras start as waitlisted.
        val goingVoters = eventResponseRepository.findGoingByEventOrderByTimestamp(event.id)
        goingVoters.forEachIndexed { index, response ->
            if (index >= event.participantLimit) {
                eventResponseRepository.updateStage2Vote(response.id, Stage_2Vote.waitlisted, FinalStatus.waitlisted)
            }
        }

        // S2T-2: ask going/maybe voters to confirm. Without this DM nobody learns Stage 2
        // started, nobody confirms, and everyone auto-expires at event start. AFTER_COMMIT
        // hop (Stage2StartedListener) — the @Async DM must read committed rows.
        // A late flip (event already started) still transitions — the expiry sweep and the
        // completion lifecycle depend on it — but the confirm window is already closed
        // (confirmParticipation rejects at event start), so the DM would be a dead end.
        if (event.eventDatetime.isAfter(OffsetDateTime.now())) {
            eventPublisher.publishEvent(Stage2StartedEvent(event))
        } else {
            log.info("Stage 2 confirm DM skipped for event ${event.id} — flipped after event start (window closed)")
        }
    }

    @Transactional
    fun confirmParticipation(eventId: UUID, userId: UUID): ConfirmResponseDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        if (event.status != EventStatus.stage_2) {
            throw ValidationException("Event is not in confirmation stage")
        }

        // Bug B: the confirmation window closes at event start. Otherwise a past event
        // stays stage_2 until the hourly EventCompletionService sweep, leaving a window
        // where one could confirm an event that already happened. See events.md.
        if (!event.eventDatetime.isAfter(OffsetDateTime.now())) {
            throw ValidationException("Confirmation window has closed")
        }

        if (!membershipRepository.isMember(userId, event.clubId)) {
            throw ForbiddenException("Not a member of this club")
        }

        // S2-01/F5-07: the slot decision below is a non-atomic read-modify-write
        // (countConfirmed < limit → updateStage2Vote). Two concurrent confirms on the last
        // slot would both pass the check and overbook. The per-event advisory lock serializes
        // every slot mutation (shared with declineParticipation, which covers F5-11 — double
        // promotion of the same waitlisted user). Taken before ANY event_responses read so a
        // blocked transaction re-reads committed state once it acquires the lock.
        eventResponseRepository.lockEventSlots(eventId)

        val response = eventResponseRepository.findByEventAndUser(eventId, userId)
            ?: throw ValidationException("You didn't vote in this event")

        if (response.stage1Vote != Stage_1Vote.going && response.stage1Vote != Stage_1Vote.maybe) {
            throw ValidationException("You voted not_going for this event")
        }

        if (response.stage2Vote == Stage_2Vote.confirmed) {
            val count = eventResponseRepository.countConfirmed(eventId)
            return ConfirmResponseDto(eventId, "confirmed", count, event.participantLimit)
        }

        if (response.stage2Vote == Stage_2Vote.declined) {
            throw ValidationException("You already declined participation")
        }

        if (response.stage2Vote == Stage_2Vote.waitlisted) {
            // FIFO (S2-02/S2T-3): promotion off the waitlist is system-driven — it happens
            // only when a confirmed member declines (declineParticipation → findFirstWaitlisted),
            // strictly by stage_1_timestamp. A waitlisted user re-confirming must NOT race into a
            // freed slot ahead of an earlier-queued member, so we keep them waitlisted (idempotent).
            val count = eventResponseRepository.countConfirmed(eventId)
            return ConfirmResponseDto(eventId, "waitlisted", count, event.participantLimit)
        }

        val confirmedCount = eventResponseRepository.countConfirmed(eventId)

        val newStatus: Stage_2Vote
        val finalStatus: FinalStatus
        if (confirmedCount < event.participantLimit) {
            newStatus = Stage_2Vote.confirmed
            finalStatus = FinalStatus.confirmed
        } else {
            newStatus = Stage_2Vote.waitlisted
            finalStatus = FinalStatus.waitlisted
        }

        eventResponseRepository.updateStage2Vote(response.id, newStatus, finalStatus)

        val newCount = eventResponseRepository.countConfirmed(eventId)
        return ConfirmResponseDto(eventId, newStatus.literal, newCount, event.participantLimit)
    }

    @Transactional
    fun declineParticipation(eventId: UUID, userId: UUID): ConfirmResponseDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        if (event.status != EventStatus.stage_2) {
            throw ValidationException("Event is not in confirmation stage")
        }

        // Bug B: mirrors confirmParticipation — no decline after the event has started.
        if (!event.eventDatetime.isAfter(OffsetDateTime.now())) {
            throw ValidationException("Confirmation window has closed")
        }

        // S2-05 (OWASP A01): decline is a state change that frees a slot and promotes the
        // first waitlisted member — it must require active membership, symmetric with confirm.
        if (!membershipRepository.isMember(userId, event.clubId)) {
            throw ForbiddenException("Not a member of this club")
        }

        // F5-11: same advisory lock as confirmParticipation — two concurrent declines would
        // both read the same findFirstWaitlisted row and promote one user for two freed slots,
        // permanently losing a slot.
        eventResponseRepository.lockEventSlots(eventId)

        val response = eventResponseRepository.findByEventAndUser(eventId, userId)
            ?: throw ValidationException("You didn't vote in this event")

        if (response.stage2Vote == Stage_2Vote.declined) {
            val count = eventResponseRepository.countConfirmed(eventId)
            return ConfirmResponseDto(eventId, "declined", count, event.participantLimit)
        }

        val wasConfirmed = response.stage2Vote == Stage_2Vote.confirmed
        eventResponseRepository.updateStage2Vote(response.id, Stage_2Vote.declined, FinalStatus.declined)

        if (wasConfirmed) {
            val firstWaitlisted = eventResponseRepository.findFirstWaitlisted(eventId)
            firstWaitlisted?.let {
                eventResponseRepository.updateStage2Vote(it.id, Stage_2Vote.confirmed, FinalStatus.confirmed)
            }
        }

        val count = eventResponseRepository.countConfirmed(eventId)
        return ConfirmResponseDto(eventId, "declined", count, event.participantLimit)
    }

    /**
     * Feature A (PRD §4.4.2 / §623 "авто-отклонение"): once an event has started, any
     * going/maybe voter who never confirmed (stage_2_vote IS NULL) is moved to the explicit
     * terminal state [Stage_2Vote.expired_no_confirm] so the roster is honest instead of
     * carrying ambiguous NULL holes. A single idempotent bulk update — the
     * `stage_2_vote IS NULL` predicate makes re-runs no-ops and never touches
     * confirmed/waitlisted/declined rows. Reputation is unaffected (it reads only
     * final_status = confirmed). See events.md § "Закрытие окна подтверждения".
     */
    @Scheduled(fixedDelayString = "\${events.stage2-expire-poll-ms:300000}")
    @Transactional
    fun expireUnconfirmedParticipants() {
        val count = eventResponseRepository.expireUnconfirmedForStartedEvents(OffsetDateTime.now())
        if (count > 0) log.info("Auto-expired {} unconfirmed Stage 2 responses", count)
    }
}
