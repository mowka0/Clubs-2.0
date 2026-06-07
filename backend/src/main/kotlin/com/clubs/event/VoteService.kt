package com.clubs.event

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.membership.MembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
class VoteService(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val membershipRepository: MembershipRepository
) {

    private val log = LoggerFactory.getLogger(VoteService::class.java)

    fun castVote(eventId: UUID, userId: UUID, request: CastVoteRequest): VoteResponseDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        if (!membershipRepository.isMember(userId, event.clubId)) {
            throw ForbiddenException("Not a member of this club")
        }

        if (event.status != EventStatus.upcoming) {
            throw ValidationException("Voting is not available for this event")
        }

        // S1-001: the voting window must use the SAME precise boundary as the feed
        // (EventMapper.computeActionRequired / JooqEventRepository.findMyFeed):
        // open iff event_datetime - votingOpensDaysBefore days <= now. ChronoUnit.DAYS.between
        // truncates the fractional day, opening voting up to ~24h early and diverging from the
        // UI's "action required" badge. See events.md § voting window.
        val votingOpensAt = event.eventDatetime.minusDays(event.votingOpensDaysBefore.toLong())
        if (OffsetDateTime.now().isBefore(votingOpensAt)) {
            throw ValidationException("Voting has not started yet")
        }

        val voteEnum = Stage_1Vote.values().find { it.literal == request.vote }
            ?: throw ValidationException("Invalid vote value: ${request.vote}")

        eventResponseRepository.upsertStage1Vote(eventId, userId, voteEnum)
        log.info("Vote cast: eventId={} userId={} vote={}", eventId, userId, request.vote)

        val counts = eventResponseRepository.countByVote(eventId)
        return VoteResponseDto(
            eventId = eventId,
            vote = request.vote,
            goingCount = counts["going"] ?: 0,
            maybeCount = counts["maybe"] ?: 0,
            notGoingCount = counts["notGoing"] ?: 0
        )
    }

    fun getMyVote(eventId: UUID, userId: UUID): MyVoteDto {
        eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")
        val response = eventResponseRepository.findByEventAndUser(eventId, userId)
        // After Stage 2 the user's effective status is final_status (confirmed / waitlisted /
        // declined); before Stage 2 it's the stage-1 vote. The EventPage keys BOTH the
        // confirm/decline buttons and the status badge off this single field, so a confirmed
        // user must read back "confirmed" — not the unchanged stage-1 "going" — or the UI never
        // reflects the confirm/decline. Same precedence as getEventResponders below.
        return MyVoteDto(vote = response?.finalStatus?.literal ?: response?.stage1Vote?.literal)
    }

    /**
     * Returns the list of responders (with user info + current intent) for the
     * event. Restricted to club members — same visibility rule as voting itself.
     */
    fun getEventResponders(eventId: UUID, userId: UUID): List<EventResponderDto> {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        if (!membershipRepository.isMember(userId, event.clubId)) {
            throw ForbiddenException("Not a member of this club")
        }

        return eventResponseRepository.findRespondersWithUsers(eventId).map { r ->
            EventResponderDto(
                userId = r.userId,
                firstName = r.firstName,
                lastName = r.lastName,
                avatarUrl = r.avatarUrl,
                status = r.finalStatus?.literal ?: r.stage1Vote?.literal ?: "going",
                attendance = r.attendance?.literal
            )
        }
    }
}
