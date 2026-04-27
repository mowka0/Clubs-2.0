package com.clubs.event

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class VoteService(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val dsl: DSLContext
) {

    private val log = LoggerFactory.getLogger(VoteService::class.java)

    fun castVote(eventId: UUID, userId: UUID, request: CastVoteRequest): VoteResponseDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        // Check membership in this club
        val isMember = dsl.selectCount().from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.eq(event.clubId)
                    .and(MEMBERSHIPS.USER_ID.eq(userId))
                    .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active))
            )
            .fetchOne(0, Int::class.java)!! > 0

        if (!isMember) throw ForbiddenException("Not a member of this club")

        if (event.status != EventStatus.upcoming) {
            throw ValidationException("Voting is not available for this event")
        }

        val daysUntilEvent = ChronoUnit.DAYS.between(OffsetDateTime.now(), event.eventDatetime)
        val votingOpensDaysBefore = event.votingOpensDaysBefore ?: 14
        if (daysUntilEvent > votingOpensDaysBefore) {
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
        return MyVoteDto(vote = response?.stage_1Vote?.literal)
    }
}
