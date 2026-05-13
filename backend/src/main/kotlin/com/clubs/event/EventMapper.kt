package com.clubs.event

import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.tables.records.EventsRecord
import org.springframework.stereotype.Component

@Component
class EventMapper {

    fun toDomain(record: EventsRecord): Event = Event(
        id = record.id!!,
        clubId = record.clubId,
        createdBy = record.createdBy,
        title = record.title,
        description = record.description,
        locationText = record.locationText,
        eventDatetime = record.eventDatetime,
        participantLimit = record.participantLimit,
        votingOpensDaysBefore = record.votingOpensDaysBefore ?: DEFAULT_VOTING_OPENS_DAYS_BEFORE,
        status = record.status ?: EventStatus.upcoming,
        stage2Triggered = record.stage_2Triggered ?: false,
        attendanceMarked = record.attendanceMarked ?: false,
        attendanceFinalized = record.attendanceFinalized ?: false,
        createdAt = record.createdAt,
        updatedAt = record.updatedAt
    )

    fun toDetailDto(
        event: Event,
        goingCount: Int,
        maybeCount: Int,
        notGoingCount: Int,
        confirmedCount: Int
    ) = EventDetailDto(
        id = event.id,
        clubId = event.clubId,
        title = event.title,
        description = event.description,
        locationText = event.locationText,
        eventDatetime = event.eventDatetime,
        participantLimit = event.participantLimit,
        votingOpensDaysBefore = event.votingOpensDaysBefore,
        status = event.status.literal,
        goingCount = goingCount,
        maybeCount = maybeCount,
        notGoingCount = notGoingCount,
        confirmedCount = confirmedCount,
        attendanceMarked = event.attendanceMarked,
        attendanceFinalized = event.attendanceFinalized,
        createdAt = event.createdAt
    )

    fun toListItemDto(event: Event, goingCount: Int) = EventListItemDto(
        id = event.id,
        title = event.title,
        eventDatetime = event.eventDatetime,
        locationText = event.locationText,
        participantLimit = event.participantLimit,
        goingCount = goingCount,
        status = event.status.literal
    )

    companion object {
        const val DEFAULT_VOTING_OPENS_DAYS_BEFORE = 14
    }
}
