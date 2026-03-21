package com.clubs.event

import com.clubs.common.dto.PageResponse
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.tables.records.EventsRecord
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.EVENTS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class EventRepository(private val dsl: DSLContext) {

    fun create(request: CreateEventRequest, clubId: UUID, createdBy: UUID): EventsRecord =
        dsl.insertInto(EVENTS)
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

    fun findById(id: UUID): EventsRecord? =
        dsl.selectFrom(EVENTS)
            .where(EVENTS.ID.eq(id))
            .fetchOne()

    fun findByClubId(clubId: UUID, status: EventStatus?, page: Int, size: Int): PageResponse<EventListItemDto> {
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

        val eventIds = events.map { it.id!! }
        val goingCounts = fetchGoingCounts(eventIds)

        val items = events.map { event ->
            EventListItemDto(
                id = event.id!!,
                title = event.title,
                eventDatetime = event.eventDatetime,
                locationText = event.locationText,
                participantLimit = event.participantLimit,
                goingCount = goingCounts[event.id] ?: 0,
                status = event.status?.literal ?: "upcoming"
            )
        }

        val totalPages = if (size == 0) 0 else ((total + size - 1) / size).toInt()
        return PageResponse(content = items, totalElements = total, totalPages = totalPages, page = page, size = size)
    }

    fun getVoteCounts(eventId: UUID): Map<String, Int> {
        val going = dsl.selectCount().from(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId).and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.going)))
            .fetchOne(0, Int::class.java) ?: 0
        val maybe = dsl.selectCount().from(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId).and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.maybe)))
            .fetchOne(0, Int::class.java) ?: 0
        val notGoing = dsl.selectCount().from(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId).and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.not_going)))
            .fetchOne(0, Int::class.java) ?: 0
        return mapOf("going" to going, "maybe" to maybe, "notGoing" to notGoing)
    }

    private fun fetchGoingCounts(eventIds: List<UUID>): Map<UUID, Int> {
        if (eventIds.isEmpty()) return emptyMap()
        return dsl.select(EVENT_RESPONSES.EVENT_ID, org.jooq.impl.DSL.count())
            .from(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.`in`(eventIds)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.going))
            )
            .groupBy(EVENT_RESPONSES.EVENT_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }
    }
}
