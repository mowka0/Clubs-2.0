package com.clubs.event

import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.tables.records.EventsRecord
import com.clubs.reputation.ReputationPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

@Component
class EventMapper(
    // Порог отказа подтверждённого (минут до старта) — тот же yaml-ключ, что читает
    // Stage2Service.declineCutoffMinutes (единый источник значения на бэке). Нужен, чтобы отдать
    // фронту готовый дедлайн отказа в EventDetailDto вместо дублирования порога хардкодом на клиенте.
    @Value("\${events.stage2-decline-cutoff-minutes:240}") private val declineCutoffMinutes: Long,
    // Глобальный дефолт интервала Этапа 2 — тот же yaml-ключ, что читает Stage2Service.
    // Нужен для эффективного stage2LeadMinutes в EventDetailDto (у события без своего значения).
    @Value("\${events.stage2-trigger-minutes-before:1080}") private val stage2TriggerMinutesBefore: Long
) {

    fun toDomain(record: EventsRecord): Event = Event(
        id = record.id!!,
        clubId = record.clubId,
        createdBy = record.createdBy,
        title = record.title,
        description = record.description,
        locationText = record.locationText,
        locationLat = record.locationLat,
        locationLon = record.locationLon,
        locationHint = record.locationHint,
        eventDatetime = record.eventDatetime,
        participantLimit = record.participantLimit,
        votingOpensDaysBefore = record.votingOpensDaysBefore ?: DEFAULT_VOTING_OPENS_DAYS_BEFORE,
        stage2LeadMinutes = record.stage2LeadMinutes,
        isUrgent = record.isUrgent ?: false,
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
        locationLat = event.locationLat,
        locationLon = event.locationLon,
        locationHint = event.locationHint,
        eventDatetime = event.eventDatetime,
        participantLimit = event.participantLimit,
        votingOpensDaysBefore = event.votingOpensDaysBefore,
        // Эффективное значение: своё у события или глобальный дефолт; у открытой встречи Этапа 2 нет.
        stage2LeadMinutes = if (event.isOpenEvent) null
            else event.stage2LeadMinutes ?: stage2TriggerMinutesBefore.toInt(),
        status = event.status.literal,
        goingCount = goingCount,
        maybeCount = maybeCount,
        notGoingCount = notGoingCount,
        confirmedCount = confirmedCount,
        // Открытая встреча: порога отказа нет — дедлайн совпадает со стартом события (окно
        // confirm/decline всё равно закрывается стартом, Bug B). Фронт различие не хардкодит.
        confirmedDeclineDeadline = if (event.isOpenEvent) event.eventDatetime
            else event.eventDatetime.minusMinutes(declineCutoffMinutes),
        // Величина штрафа за брошенный слот — из политики репутации, чтобы текст диалога отказа
        // на фронте никогда не разъехался с реальным списанием (фикс PO 2026-07-21).
        abandonedSlotPenaltyPoints = -ReputationPolicy.pointsFor(ReputationKind.abandoned_slot),
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
            photoUrl = event.photoUrl,
            status = event.status.literal,
            clubId = event.clubId,
            clubName = item.clubName,
            clubAvatarUrl = item.clubAvatarUrl,
            myVote = item.myVote?.literal,
            myParticipationStatus = item.myFinalStatus?.literal,
            goingCount = item.goingCount,
            confirmedCount = item.confirmedCount,
            participantLimit = event.participantLimit,
            isUrgent = event.isUrgent,
            actionRequired = computeActionRequired(item, now),
            isHistory = item.isHistory
        )
    }

    private fun computeActionRequired(item: MyFeedItem, now: OffsetDateTime): Boolean {
        // История — прошедшее событие, никаких действий по нему уже не требуется. Отсекаем явно
        // до всех прочих веток: у attended-строки final_status='confirmed' и так дал бы false,
        // но инвариант «история никогда не actionRequired» делаем читаемым, а не выводимым.
        if (item.isHistory) return false
        val event = item.event
        return when (event.status) {
            EventStatus.upcoming -> {
                val votingOpensAt = event.eventDatetime.minusDays(event.votingOpensDaysBefore.toLong())
                !now.isBefore(votingOpensAt) && item.myVote == null
            }
            EventStatus.stage_2 -> {
                // Этап 2 открыт всем участникам (PR #92), поэтому и действие требуется от КАЖДОГО,
                // кто ещё не решил на самом Этапе 2 (решение PO 2026-07-23): голос Этапа 1 — в том
                // числе «Не пойду» — не финален, планы меняются, а у срочной встречи (V69) голосов
                // не бывает вовсе. Финальны только confirmed/waitlisted/declined/expired.
                item.myFinalStatus == null
            }
            else -> false
        }
    }

    // Тизер-афиша: проекция БЕЗ места/фото/лимита — приватное не попадает в DTO по построению.
    fun toTeaserDto(item: EventWithGoingCount) = TeaserEventDto(
        id = item.event.id,
        title = item.event.title,
        eventDatetime = item.event.eventDatetime,
        status = item.event.status.literal,
        isUrgent = item.event.isUrgent,
        isOpenEvent = item.event.isOpenEvent,
        goingCount = item.goingCount,
        confirmedCount = item.confirmedCount
    )

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
