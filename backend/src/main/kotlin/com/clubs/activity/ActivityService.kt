package com.clubs.activity

import com.clubs.activity.dto.ActivityItemDto
import com.clubs.activity.dto.ClubActivityFeedDto
import com.clubs.activity.mapper.ActivityMapper
import com.clubs.event.EventRepository
import com.clubs.event.EventWithGoingCount
import com.clubs.skladchina.SkladchinaRepository
import com.clubs.skladchina.SkladchinaWithAggregates
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ActivityService(
    private val eventRepository: EventRepository,
    private val skladchinaRepository: SkladchinaRepository,
    private val activityMapper: ActivityMapper
) {

    private val log = LoggerFactory.getLogger(ActivityService::class.java)

    /**
     * Возвращает объединённую ленту событий + складчин клуба, разбитую на две
     * заранее отсортированные группы по собственной дате каждой активности.
     * Авторизация обеспечивается на уровне контроллера через `@RequiresMembership`.
     *
     * Стратегия (MVP по спеке D-1): вытягиваем обе коллекции из их репозиториев,
     * сливаем в памяти, затем разбиваем по `isCompleted` и сортируем каждую группу
     * по `relevantDate`. Без пагинации — объём активности клуба ограничен. Перейти
     * на SQL UNION, когда это перестанет выполняться.
     *
     * - `upcoming` = `!isCompleted`, сортировка по relevantDate ASC (ближайшие первыми)
     * - `past` = `isCompleted`, сортировка по relevantDate DESC (самые недавние первыми)
     * - ничья по relevantDate разрешается через `id ASC` в обеих группах
     */
    @Transactional(readOnly = true)
    fun getClubActivities(
        clubId: UUID,
        userId: UUID,
        typeFilter: ActivityType?
    ): ClubActivityFeedDto {
        log.info("Fetch activities: clubId={} userId={} type={}", clubId, userId, typeFilter)

        val rawEvents: List<EventWithGoingCount> = if (typeFilter == ActivityType.SKLADCHINA) {
            emptyList()
        } else {
            eventRepository.findAllByClubWithGoingCount(clubId)
        }

        // События, ожидающие голоса этапа 1 или подтверждения этапа 2 от этого пользователя.
        // Управляет и бейджем "Проголосуй"/"Подтверди участие" (флаг на событие), и закреплением
        // сверху ленты.
        val actionRequiredIds: Set<UUID> = if (rawEvents.isEmpty()) {
            emptySet()
        } else {
            eventRepository.findActionRequiredEventIds(clubId, userId, OffsetDateTime.now())
        }

        val events: List<ActivityItemDto.EventActivity> = rawEvents.map {
            activityMapper.toEventActivity(
                it.event,
                it.goingCount,
                it.confirmedCount,
                actionRequired = it.event.id in actionRequiredIds
            )
        }

        val skladchinas: List<ActivityItemDto.SkladchinaActivity> = if (typeFilter == ActivityType.EVENT) {
            emptyList()
        } else {
            loadSkladchinas(clubId)
        }

        val all: List<ActivityItemDto> = events + skladchinas
        val (past, upcoming) = all.partition { it.isCompleted }

        val upcomingOrder = compareByDescending<ActivityItemDto> {
            it is ActivityItemDto.EventActivity && it.actionRequired
        }.then(UPCOMING_ORDER)
        val sortedUpcoming = upcoming.sortedWith(upcomingOrder)
        val sortedPast = past.sortedWith(PAST_ORDER)

        log.info(
            "Activities fetched: clubId={} upcoming={} past={}",
            clubId, sortedUpcoming.size, sortedPast.size
        )

        return ClubActivityFeedDto(upcoming = sortedUpcoming, past = sortedPast)
    }

    private fun loadSkladchinas(clubId: UUID): List<ActivityItemDto.SkladchinaActivity> {
        val raw: List<SkladchinaWithAggregates> =
            skladchinaRepository.findAllByClubWithAggregates(clubId, includeCompleted = true)
        return raw.map(activityMapper::toSkladchinaActivity)
    }

    companion object {
        /**
         * Ключ сортировки для элемента: собственный datetime события или deadline складчины.
         * Исчерпывающий `when` по sealed-подтипу — компилятор требует ветку
         * для каждого будущего типа активности.
         */
        private fun relevantDate(item: ActivityItemDto): OffsetDateTime = when (item) {
            is ActivityItemDto.EventActivity -> item.eventDatetime
            is ActivityItemDto.SkladchinaActivity -> item.deadline
        }

        /** Ближайшие первыми; ничья разрешается через `id ASC` для детерминированного порядка. */
        private val UPCOMING_ORDER: Comparator<ActivityItemDto> =
            compareBy<ActivityItemDto> { relevantDate(it) }
                .thenBy { it.id }

        /** Самые недавние первыми; ничья разрешается через `id ASC` для детерминированного порядка. */
        private val PAST_ORDER: Comparator<ActivityItemDto> =
            compareByDescending<ActivityItemDto> { relevantDate(it) }
                .thenBy { it.id }
    }
}

enum class ActivityType(val wireValue: String) {
    EVENT("event"),
    SKLADCHINA("skladchina");

    companion object {
        fun fromWire(value: String): ActivityType? =
            entries.find { it.wireValue == value }
    }
}
