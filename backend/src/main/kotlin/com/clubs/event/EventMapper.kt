package com.clubs.event

import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.tables.records.EventsRecord
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

@Component
class EventMapper(
    // Порог позднего отказа (минут до старта) — тот же yaml-ключ, что читает
    // Stage2Service.lateDeclineThresholdMinutes (единый источник значения на бэке). Нужен, чтобы
    // отдать фронту готовую цену отказа вместо дублирования правил на клиенте.
    @Value("\${events.late-decline-threshold-minutes:240}") private val lateDeclineThresholdMinutes: Long,
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
        minParticipants = record.minParticipants,
        votingOpensDaysBefore = record.votingOpensDaysBefore ?: DEFAULT_VOTING_OPENS_DAYS_BEFORE,
        stage2LeadMinutes = record.stage2LeadMinutes,
        status = record.status ?: EventStatus.upcoming,
        stage2Triggered = record.stage_2Triggered ?: false,
        attendanceMarked = record.attendanceMarked ?: false,
        attendanceFinalized = record.attendanceFinalized ?: false,
        cancellationReason = record.cancellationReason,
        rosterDecidedAt = record.rosterDecidedAt,
        rosterWarningSentAt = record.rosterWarningSentAt,
        photoUrl = record.photoUrl,
        createdAt = record.createdAt,
        updatedAt = record.updatedAt
    )

    fun toDetailDto(
        event: Event,
        goingCount: Int,
        maybeCount: Int,
        notGoingCount: Int,
        confirmedCount: Int,
        noAnswerCount: Int = 0,
        waitlistedCount: Int = 0,
        now: OffsetDateTime = OffsetDateTime.now()
    ): EventDetailDto {
        val rosterClosed = isRosterClosed(event)
        return EventDetailDto(
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
            minParticipants = event.minParticipants,
            votingOpensDaysBefore = event.votingOpensDaysBefore,
            // Эффективное значение: своё у события или глобальный дефолт; у открытой набора нет.
            stage2LeadMinutes = if (event.isOpenEvent) null
                else event.stage2LeadMinutes ?: stage2TriggerMinutesBefore.toInt(),
            // Хранимое значение (null = «глобальный дефолт»). Отдаётся ОТДЕЛЬНО от эффективного,
            // потому что форма редактирования возвращает его обратно в PUT: если слать эффективное,
            // подставленный дефолт станет собственным значением события — а при ужатом дефолте
            // (staging: 5 минут) он ещё и не пройдёт валидацию @Min(1080) и заблокирует любую правку.
            stage2LeadMinutesOverride = event.stage2LeadMinutes,
            status = event.status.literal,
            format = event.format,
            goingCount = goingCount,
            maybeCount = maybeCount,
            notGoingCount = notGoingCount,
            confirmedCount = confirmedCount,
            noAnswerCount = noAnswerCount,
            // Дедлайн набора: момент, когда состав закрывается (а при недоборе минимума — когда
            // встреча отменяется). У открытой набора нет вовсе.
            rosterDeadline = if (event.isOpenEvent) null
                else RosterSchedule.deadline(event.eventDatetime, event.stage2LeadMinutes, stage2TriggerMinutesBefore),
            rosterClosed = rosterClosed,
            waitlistedCount = waitlistedCount,
            rosterDecided = event.isRosterDecided,
            // Цена отказа для участника ИЗ СОСТАВА на момент запроса. Одна и та же для всех, кто
            // держит место: она зависит только от состояния события (закрыт ли состав, близко ли
            // встреча, есть ли замена, держится ли минимум), а не от личности отказывающегося.
            declineCostPoints = RosterPolicy.declineCostPoints(
                DeclineSituation(
                    isOpenEvent = event.isOpenEvent,
                    heldSlot = true,
                    rosterClosed = rosterClosed,
                    withinDeclineCutoff = !event.eventDatetime.isAfter(now.plusMinutes(lateDeclineThresholdMinutes)),
                    hasReplacement = waitlistedCount > 0,
                    staysAtThreshold = RosterPolicy.staysAtThreshold(confirmedCount, event.minParticipants)
                )
            ),
            declineConsequence = RosterPolicy.declineConsequence(
                isOpenEvent = event.isOpenEvent,
                // У открытой «закрытого состава» нет, но отказ живёт в той же фазе подтверждения.
                rosterClosed = isInConfirmationPhase(event),
                waitlistedCount = waitlistedCount,
                confirmedCount = confirmedCount,
                minParticipants = event.minParticipants,
                rosterDecided = event.isRosterDecided
            ),
            attendanceMarked = event.attendanceMarked,
            attendanceFinalized = event.attendanceFinalized,
            cancellationReason = event.cancellationReason,
            photoUrl = event.photoUrl,
            createdAt = event.createdAt
        )
    }

    /**
     * Состав закрыт: встреча дошла до фазы подтверждённого состава. Это ФАЗА события (stage_2 и
     * дальше), а не флаг stage2Triggered — флаг ставится тем же переходом, но статус честнее: он
     * же управляет всем экраном. У открытой такой фазы не бывает.
     */
    private fun isRosterClosed(event: Event): Boolean =
        !event.isOpenEvent && isInConfirmationPhase(event)

    private fun isInConfirmationPhase(event: Event): Boolean =
        event.status == EventStatus.stage_2 || event.status == EventStatus.completed

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
            minParticipants = event.minParticipants,
            format = event.format,
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
                // Встреча с лимитом: состав закрыт, подтверждать нечего — действий от участника
                // больше не требуется. Встать в очередь можно, но это возможность, а не долг,
                // и бейджем «требуется действие» она бы врала.
                if (event.isRosterEvent) false
                // Открытая: Этап 2 открыт всем участникам (PR #92), поэтому и действие требуется
                // от КАЖДОГО, кто ещё не решил на самом Этапе 2 (решение PO 2026-07-23): голос
                // Этапа 1 — в том числе «Не пойду» — не финален, планы меняются. Финальны
                // только confirmed/waitlisted/declined/expired.
                else item.myFinalStatus == null
            }
            else -> false
        }
    }

    // Тизер-афиша: проекция БЕЗ места/фото — приватное не попадает в DTO по построению.
    fun toTeaserDto(item: EventWithGoingCount) = TeaserEventDto(
        id = item.event.id,
        title = item.event.title,
        eventDatetime = item.event.eventDatetime,
        status = item.event.status.literal,
        format = item.event.format,
        participantLimit = item.event.participantLimit,
        minParticipants = item.event.minParticipants,
        goingCount = item.goingCount,
        confirmedCount = item.confirmedCount
    )

    fun toListItemDto(event: Event, goingCount: Int) = EventListItemDto(
        id = event.id,
        title = event.title,
        eventDatetime = event.eventDatetime,
        locationText = event.locationText,
        format = event.format,
        participantLimit = event.participantLimit,
        minParticipants = event.minParticipants,
        goingCount = goingCount,
        status = event.status.literal,
        photoUrl = event.photoUrl
    )

    companion object {
        const val DEFAULT_VOTING_OPENS_DAYS_BEFORE = 14
    }
}
