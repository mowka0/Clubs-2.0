package com.clubs.event

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.enums.Stage_2Vote
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class Stage2Service(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val dsl: DSLContext
) {
    private val log = LoggerFactory.getLogger(Stage2Service::class.java)

    @Scheduled(fixedDelay = 300_000) // every 5 minutes
    @Transactional
    fun triggerStage2ForReadyEvents() {
        val events = eventRepository.findEventsToTriggerStage2()
        events.forEach { event ->
            try {
                triggerStage2(event.id!!, event.participantLimit ?: Int.MAX_VALUE)
                log.info("Stage 2 triggered for event ${event.id}")
            } catch (e: Exception) {
                log.error("Failed to trigger Stage 2 for event ${event.id}", e)
            }
        }
    }

    private fun triggerStage2(eventId: UUID, participantLimit: Int) {
        eventRepository.transitionToStage2(eventId)

        // Assign waitlisted status to going voters beyond the limit
        val goingVoters = eventResponseRepository.findGoingByEventOrderByTimestamp(eventId)
        goingVoters.forEachIndexed { index, response ->
            if (index < participantLimit) {
                // First N going voters: they're in the priority queue (still need to confirm)
                // Leave stage_2_vote as null — they need to explicitly confirm
            } else {
                // Extra going voters start as waitlisted
                eventResponseRepository.updateStage2Vote(response.id!!, Stage_2Vote.waitlisted, FinalStatus.waitlisted)
            }
        }
    }

    @Transactional
    fun confirmParticipation(eventId: UUID, userId: UUID): ConfirmResponseDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        if (event.status != EventStatus.stage_2) {
            throw ValidationException("Event is not in confirmation stage")
        }

        val isMember = dsl.selectCount().from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.eq(event.clubId)
                    .and(MEMBERSHIPS.USER_ID.eq(userId))
                    .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active))
            )
            .fetchOne(0, Int::class.java)!! > 0

        if (!isMember) throw ForbiddenException("Not a member of this club")

        val response = eventResponseRepository.findByEventAndUser(eventId, userId)
            ?: throw ValidationException("You didn't vote in this event")

        if (response.stage_1Vote != Stage_1Vote.going && response.stage_1Vote != Stage_1Vote.maybe) {
            throw ValidationException("You voted not_going for this event")
        }

        if (response.stage_2Vote == Stage_2Vote.confirmed) {
            val count = eventResponseRepository.countConfirmed(eventId)
            return ConfirmResponseDto(eventId, "confirmed", count, event.participantLimit ?: 0)
        }

        if (response.stage_2Vote == Stage_2Vote.declined) {
            throw ValidationException("You already declined participation")
        }

        val confirmedCount = eventResponseRepository.countConfirmed(eventId)
        val participantLimit = event.participantLimit ?: Int.MAX_VALUE

        val newStatus: Stage_2Vote
        val finalStatus: FinalStatus
        if (confirmedCount < participantLimit) {
            newStatus = Stage_2Vote.confirmed
            finalStatus = FinalStatus.confirmed
        } else {
            newStatus = Stage_2Vote.waitlisted
            finalStatus = FinalStatus.waitlisted
        }

        eventResponseRepository.updateStage2Vote(response.id!!, newStatus, finalStatus)

        val newCount = eventResponseRepository.countConfirmed(eventId)
        return ConfirmResponseDto(eventId, newStatus.literal, newCount, event.participantLimit ?: 0)
    }

    @Transactional
    fun declineParticipation(eventId: UUID, userId: UUID): ConfirmResponseDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        if (event.status != EventStatus.stage_2) {
            throw ValidationException("Event is not in confirmation stage")
        }

        val response = eventResponseRepository.findByEventAndUser(eventId, userId)
            ?: throw ValidationException("You didn't vote in this event")

        if (response.stage_2Vote == Stage_2Vote.declined) {
            val count = eventResponseRepository.countConfirmed(eventId)
            return ConfirmResponseDto(eventId, "declined", count, event.participantLimit ?: 0)
        }

        val wasConfirmed = response.stage_2Vote == Stage_2Vote.confirmed
        eventResponseRepository.updateStage2Vote(response.id!!, Stage_2Vote.declined, FinalStatus.declined)

        // Promote first waitlisted to confirmed
        if (wasConfirmed) {
            val firstWaitlisted = eventResponseRepository.findFirstWaitlisted(eventId)
            firstWaitlisted?.let {
                eventResponseRepository.updateStage2Vote(it.id!!, Stage_2Vote.confirmed, FinalStatus.confirmed)
            }
        }

        val count = eventResponseRepository.countConfirmed(eventId)
        return ConfirmResponseDto(eventId, "declined", count, event.participantLimit ?: 0)
    }
}
