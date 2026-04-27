package com.clubs.event

import com.clubs.club.ClubRepository
import com.clubs.common.dto.PageResponse
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.tables.records.EventsRecord
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class EventService(
    private val eventRepository: EventRepository,
    private val clubRepository: ClubRepository
) {

    private val log = LoggerFactory.getLogger(EventService::class.java)

    fun createEvent(clubId: UUID, request: CreateEventRequest, userId: UUID): EventDetailDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Only the club organizer can create events")
        val event = eventRepository.create(request, clubId, userId)
        log.info("Event created: id={} clubId={} title='{}' userId={}", event.id, clubId, event.title, userId)
        return event.toDetailDto(goingCount = 0, maybeCount = 0, notGoingCount = 0, confirmedCount = 0)
    }

    fun getClubEvents(clubId: UUID, statusStr: String?, page: Int, size: Int): PageResponse<EventListItemDto> {
        clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        val status = statusStr?.let {
            try { EventStatus.valueOf(it) }
            catch (e: IllegalArgumentException) { null }
        }
        return eventRepository.findByClubId(clubId, status, page, size)
    }

    fun getEvent(id: UUID): EventDetailDto {
        val event = eventRepository.findById(id) ?: throw NotFoundException("Event not found")
        val counts = eventRepository.getVoteCounts(id)
        return event.toDetailDto(
            goingCount = counts["going"] ?: 0,
            maybeCount = counts["maybe"] ?: 0,
            notGoingCount = counts["notGoing"] ?: 0,
            confirmedCount = 0
        )
    }
}

fun EventsRecord.toDetailDto(
    goingCount: Int,
    maybeCount: Int,
    notGoingCount: Int,
    confirmedCount: Int
) = EventDetailDto(
    id = id!!,
    clubId = clubId,
    title = title,
    description = description,
    locationText = locationText,
    eventDatetime = eventDatetime,
    participantLimit = participantLimit,
    votingOpensDaysBefore = votingOpensDaysBefore ?: 14,
    status = status?.literal ?: "upcoming",
    goingCount = goingCount,
    maybeCount = maybeCount,
    notGoingCount = notGoingCount,
    confirmedCount = confirmedCount,
    attendanceMarked = attendanceMarked ?: false,
    attendanceFinalized = attendanceFinalized ?: false,
    createdAt = createdAt
)
