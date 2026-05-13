package com.clubs.event

import com.clubs.common.dto.PageResponse
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.EVENTS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqEventRepository(
    private val dsl: DSLContext,
    private val mapper: EventMapper
) : EventRepository {

    override fun create(request: CreateEventRequest, clubId: UUID, createdBy: UUID): Event {
        val record = dsl.insertInto(EVENTS)
            .set(EVENTS.ID, UUID.randomUUID())
            .set(EVENTS.CLUB_ID, clubId)
            .set(EVENTS.CREATED_BY, createdBy)
            .set(EVENTS.TITLE, request.title)
            .set(EVENTS.DESCRIPTION, request.description)
            .set(EVENTS.LOCATION_TEXT, request.locationText)
            .set(EVENTS.EVENT_DATETIME, request.eventDatetime)
            .set(EVENTS.PARTICIPANT_LIMIT, request.participantLimit)
            .set(EVENTS.VOTING_OPENS_DAYS_BEFORE, request.votingOpensDaysBefore)
            .set(EVENTS.STATUS, EventStatus.upcoming)
            .set(EVENTS.STAGE_2_TRIGGERED, false)
            .set(EVENTS.ATTENDANCE_MARKED, false)
            .set(EVENTS.ATTENDANCE_FINALIZED, false)
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }

    override fun findById(id: UUID): Event? =
        dsl.selectFrom(EVENTS)
            .where(EVENTS.ID.eq(id))
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun findByClubId(clubId: UUID, status: EventStatus?, page: Int, size: Int): PageResponse<EventListItemDto> {
        var condition = EVENTS.CLUB_ID.eq(clubId)
        status?.let { condition = condition.and(EVENTS.STATUS.eq(it)) }

        val total = dsl.selectCount().from(EVENTS).where(condition)
            .fetchOne(0, Long::class.java) ?: 0L

        val events = dsl.selectFrom(EVENTS)
            .where(condition)
            .orderBy(EVENTS.EVENT_DATETIME.asc())
            .limit(size)
            .offset(page * size)
            .fetch()
            .map(mapper::toDomain)

        val eventIds = events.map { it.id }
        val goingCounts = fetchGoingCounts(eventIds)

        val items = events.map { event -> mapper.toListItemDto(event, goingCounts[event.id] ?: 0) }

        val totalPages = if (size == 0) 0 else ((total + size - 1) / size).toInt()
        return PageResponse(content = items, totalElements = total, totalPages = totalPages, page = page, size = size)
    }

    override fun getVoteCounts(eventId: UUID): Map<String, Int> {
        val going = countVotes(eventId, Stage_1Vote.going)
        val maybe = countVotes(eventId, Stage_1Vote.maybe)
        val notGoing = countVotes(eventId, Stage_1Vote.not_going)
        return mapOf("going" to going, "maybe" to maybe, "notGoing" to notGoing)
    }

    override fun findEventsToTriggerStage2(): List<Event> {
        val cutoff = OffsetDateTime.now().plusHours(STAGE_2_TRIGGER_HOURS_BEFORE_EVENT)
        return dsl.selectFrom(EVENTS)
            .where(
                EVENTS.STATUS.eq(EventStatus.upcoming)
                    .and(EVENTS.STAGE_2_TRIGGERED.eq(false))
                    .and(EVENTS.EVENT_DATETIME.lessOrEqual(cutoff))
            )
            .fetch()
            .map(mapper::toDomain)
    }

    /**
     * Returns the nearest upcoming event across all clubs.
     * Used by ClubsBot.handleWhoIsGoing (/кто_идет command).
     * Status must be upcoming, stage_1, or stage_2 and event_datetime > now.
     */
    override fun findNextUpcomingEvent(now: OffsetDateTime): Event? =
        dsl.selectFrom(EVENTS)
            .where(
                EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_1, EventStatus.stage_2)
                    .and(EVENTS.EVENT_DATETIME.gt(now))
            )
            .orderBy(EVENTS.EVENT_DATETIME.asc())
            .limit(1)
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun transitionToStage2(id: UUID) {
        dsl.update(EVENTS)
            .set(EVENTS.STATUS, EventStatus.stage_2)
            .set(EVENTS.STAGE_2_TRIGGERED, true)
            .where(EVENTS.ID.eq(id))
            .execute()
    }

    override fun markAttendanceMarked(id: UUID) {
        dsl.update(EVENTS)
            .set(EVENTS.ATTENDANCE_MARKED, true)
            .where(EVENTS.ID.eq(id))
            .execute()
    }

    override fun finalizeAttendanceBefore(eventDatetimeCutoff: OffsetDateTime): Int =
        dsl.update(EVENTS)
            .set(EVENTS.ATTENDANCE_FINALIZED, true)
            .where(
                EVENTS.ATTENDANCE_MARKED.eq(true)
                    .and(EVENTS.ATTENDANCE_FINALIZED.eq(false))
                    .and(EVENTS.EVENT_DATETIME.lessOrEqual(eventDatetimeCutoff))
            )
            .execute()

    private fun countVotes(eventId: UUID, vote: Stage_1Vote): Int =
        dsl.selectCount().from(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId).and(EVENT_RESPONSES.STAGE_1_VOTE.eq(vote)))
            .fetchOne(0, Int::class.java) ?: 0

    private fun fetchGoingCounts(eventIds: List<UUID>): Map<UUID, Int> {
        if (eventIds.isEmpty()) return emptyMap()
        return dsl.select(EVENT_RESPONSES.EVENT_ID, DSL.count())
            .from(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.`in`(eventIds)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.going))
            )
            .groupBy(EVENT_RESPONSES.EVENT_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }
    }

    companion object {
        private const val STAGE_2_TRIGGER_HOURS_BEFORE_EVENT = 24L
    }
}
