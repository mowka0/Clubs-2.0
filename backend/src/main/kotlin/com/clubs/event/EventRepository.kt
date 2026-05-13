package com.clubs.event

import com.clubs.common.dto.PageResponse
import com.clubs.generated.jooq.enums.EventStatus
import java.time.OffsetDateTime
import java.util.UUID

interface EventRepository {

    fun create(request: CreateEventRequest, clubId: UUID, createdBy: UUID): Event

    fun findById(id: UUID): Event?

    fun findByClubId(clubId: UUID, status: EventStatus?, page: Int, size: Int): PageResponse<EventListItemDto>

    fun getVoteCounts(eventId: UUID): Map<String, Int>

    fun findEventsToTriggerStage2(): List<Event>

    fun findNextUpcomingEvent(now: OffsetDateTime): Event?

    fun transitionToStage2(id: UUID)

    fun markAttendanceMarked(id: UUID)

    fun finalizeAttendanceBefore(eventDatetimeCutoff: OffsetDateTime): Int
}
