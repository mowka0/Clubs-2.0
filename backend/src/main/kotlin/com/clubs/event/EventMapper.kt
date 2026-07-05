package com.clubs.event

import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.tables.records.EventsRecord
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

@Component
class EventMapper(
    // Порог отказа подтверждённого (минут до старта) — тот же yaml-ключ, что читает
    // Stage2Service.declineCutoffMinutes (единый источник значения на бэке). Нужен, чтобы отдать
    // фронту готовый дедлайн отказа в EventDetailDto вместо дублирования порога хардкодом на клиенте.
    @Value("\${events.stage2-decline-cutoff-minutes:240}") private val declineCutoffMinutes: Long
) {

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
        cancellationReason = record.cancellationReason,
        photoUrl = record.photoUrl,
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
        confirmedDeclineDeadline = event.eventDatetime.minusMinutes(declineCutoffMinutes),
        attendanceMarked = event.attendanceMarked,
        attendanceFinalized = event.attendanceFinalized,
        cancellationReason = event.cancellationReason,
        photoUrl = event.photoUrl,
        createdAt = event.createdAt
    )

    fun toMyFeedItemDto(item: MyFeedItem, now: OffsetDateTime = OffsetDateTime.now()): MyEventListItemDto {
        val event = item.event
        return MyEventListItemDto(
            id = event.id,
            title = event.title,
            eventDatetime = event.eventDatetime,
            locationText = event.locationText,
            status = event.status.literal,
            clubId = event.clubId,
            clubName = item.clubName,
            clubAvatarUrl = item.clubAvatarUrl,
            myVote = item.myVote?.literal,
            myParticipationStatus = item.myFinalStatus?.literal,
            goingCount = item.goingCount,
            confirmedCount = item.confirmedCount,
            participantLimit = event.participantLimit,
            actionRequired = computeActionRequired(item, now)
        )
    }

    private fun computeActionRequired(item: MyFeedItem, now: OffsetDateTime): Boolean {
        val event = item.event
        return when (event.status) {
            EventStatus.upcoming -> {
                val votingOpensAt = event.eventDatetime.minusDays(event.votingOpensDaysBefore.toLong())
                !now.isBefore(votingOpensAt) && item.myVote == null
            }
            EventStatus.stage_2 -> {
                item.myVote in setOf(Stage_1Vote.going, Stage_1Vote.maybe) && item.myFinalStatus == null
            }
            else -> false
        }
    }

    fun toListItemDto(event: Event, goingCount: Int) = EventListItemDto(
        id = event.id,
        title = event.title,
        eventDatetime = event.eventDatetime,
        locationText = event.locationText,
        participantLimit = event.participantLimit,
        goingCount = goingCount,
        status = event.status.literal,
        photoUrl = event.photoUrl
    )

    companion object {
        const val DEFAULT_VOTING_OPENS_DAYS_BEFORE = 14
    }
}
