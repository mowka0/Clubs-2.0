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
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class Stage2Service(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val membershipRepository: MembershipRepository
) {
    private val log = LoggerFactory.getLogger(Stage2Service::class.java)

    @Scheduled(fixedDelay = STAGE_2_SCHEDULER_PERIOD_MS)
    @Transactional
    fun triggerStage2ForReadyEvents() {
        val events = eventRepository.findEventsToTriggerStage2()
        events.forEach { event ->
            try {
                triggerStage2(event.id, event.participantLimit)
                log.info("Stage 2 triggered for event ${event.id}")
            } catch (e: Exception) {
                log.error("Failed to trigger Stage 2 for event ${event.id}", e)
            }
        }
    }

    private fun triggerStage2(eventId: UUID, participantLimit: Int) {
        eventRepository.transitionToStage2(eventId)

        // First N going voters (by stage_1_timestamp) keep stage_2_vote = null — they confirm explicitly.
        // Extras start as waitlisted.
        val goingVoters = eventResponseRepository.findGoingByEventOrderByTimestamp(eventId)
        goingVoters.forEachIndexed { index, response ->
            if (index >= participantLimit) {
                eventResponseRepository.updateStage2Vote(response.id, Stage_2Vote.waitlisted, FinalStatus.waitlisted)
            }
        }
    }

    @Transactional
    fun confirmParticipation(eventId: UUID, userId: UUID): ConfirmResponseDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        if (event.status != EventStatus.stage_2) {
            throw ValidationException("Event is not in confirmation stage")
        }

        if (!membershipRepository.isMember(userId, event.clubId)) {
            throw ForbiddenException("Not a member of this club")
        }

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

    companion object {
        private const val STAGE_2_SCHEDULER_PERIOD_MS = 300_000L
    }
}
