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
     * Returns the merged event + skladchina feed for a club, split into two
     * pre-sorted groups by each activity's own date. Authorization is enforced
     * at the controller level by `@RequiresMembership`.
     *
     * Strategy (MVP per spec D-1): pull both collections from their repositories,
     * merge in-memory, then partition by `isCompleted` and sort each group by
     * `relevantDate`. No pagination — club activity volume is bounded. Migrate to
     * SQL UNION when that stops holding.
     *
     * - `upcoming` = `!isCompleted`, sorted by relevantDate ASC (soonest first)
     * - `past` = `isCompleted`, sorted by relevantDate DESC (most recent first)
     * - ties on relevantDate are broken by `id ASC` in both groups
     */
    @Transactional(readOnly = true)
    fun getClubActivities(
        clubId: UUID,
        userId: UUID,
        typeFilter: ActivityType?
    ): ClubActivityFeedDto {
        log.info("Fetch activities: clubId={} userId={} type={}", clubId, userId, typeFilter)

        val events: List<ActivityItemDto.EventActivity> = if (typeFilter == ActivityType.SKLADCHINA) {
            emptyList()
        } else {
            loadEvents(clubId)
        }

        val skladchinas: List<ActivityItemDto.SkladchinaActivity> = if (typeFilter == ActivityType.EVENT) {
            emptyList()
        } else {
            loadSkladchinas(clubId)
        }

        // Events awaiting this user's stage-1 vote or stage-2 confirmation — pinned to the top
        // of `upcoming` so the action the user owes is the first thing they see.
        val actionRequiredIds: Set<UUID> = if (events.isEmpty()) {
            emptySet()
        } else {
            eventRepository.findActionRequiredEventIds(clubId, userId, OffsetDateTime.now())
        }

        val all: List<ActivityItemDto> = events + skladchinas
        val (past, upcoming) = all.partition { it.isCompleted }

        val upcomingOrder = compareByDescending<ActivityItemDto> {
            it is ActivityItemDto.EventActivity && it.id in actionRequiredIds
        }.then(UPCOMING_ORDER)
        val sortedUpcoming = upcoming.sortedWith(upcomingOrder)
        val sortedPast = past.sortedWith(PAST_ORDER)

        log.info(
            "Activities fetched: clubId={} upcoming={} past={}",
            clubId, sortedUpcoming.size, sortedPast.size
        )

        return ClubActivityFeedDto(upcoming = sortedUpcoming, past = sortedPast)
    }

    private fun loadEvents(clubId: UUID): List<ActivityItemDto.EventActivity> {
        val raw: List<EventWithGoingCount> = eventRepository.findAllByClubWithGoingCount(clubId)
        return raw.map { activityMapper.toEventActivity(it.event, it.goingCount) }
    }

    private fun loadSkladchinas(clubId: UUID): List<ActivityItemDto.SkladchinaActivity> {
        val raw: List<SkladchinaWithAggregates> =
            skladchinaRepository.findAllByClubWithAggregates(clubId, includeCompleted = true)
        return raw.map(activityMapper::toSkladchinaActivity)
    }

    companion object {
        /**
         * Per-item sort key: an event's own datetime, a skladchina's deadline.
         * Exhaustive `when` over the sealed subtype — compiler enforces a branch
         * for every future activity type.
         */
        private fun relevantDate(item: ActivityItemDto): OffsetDateTime = when (item) {
            is ActivityItemDto.EventActivity -> item.eventDatetime
            is ActivityItemDto.SkladchinaActivity -> item.deadline
        }

        /** Soonest first; ties broken by `id ASC` for deterministic order. */
        private val UPCOMING_ORDER: Comparator<ActivityItemDto> =
            compareBy<ActivityItemDto> { relevantDate(it) }
                .thenBy { it.id }

        /** Most recent first; ties broken by `id ASC` for deterministic order. */
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
