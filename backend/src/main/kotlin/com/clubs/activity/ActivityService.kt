package com.clubs.activity

import com.clubs.activity.dto.ActivityItemDto
import com.clubs.activity.mapper.ActivityMapper
import com.clubs.common.dto.PageResponse
import com.clubs.event.EventRepository
import com.clubs.event.EventWithGoingCount
import com.clubs.skladchina.SkladchinaRepository
import com.clubs.skladchina.SkladchinaWithAggregates
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ActivityService(
    private val eventRepository: EventRepository,
    private val skladchinaRepository: SkladchinaRepository,
    private val activityMapper: ActivityMapper
) {

    private val log = LoggerFactory.getLogger(ActivityService::class.java)

    /**
     * Returns merged event + skladchina feed for a club. Authorization is enforced
     * at the controller level by `@RequiresMembership`.
     *
     * Strategy (MVP per spec D-1): pull both collections from their repositories,
     * merge in-memory, sort by `(createdAt DESC, id ASC)`, then page-slice.
     * Acceptable for clubs < ~1000 activities. Migrate to SQL UNION when that
     * stops holding.
     */
    @Transactional(readOnly = true)
    fun getClubActivities(
        clubId: UUID,
        typeFilter: ActivityType?,
        includeCompleted: Boolean,
        page: Int,
        size: Int
    ): PageResponse<ActivityItemDto> {
        log.info(
            "Fetch activities: clubId={} type={} includeCompleted={} page={} size={}",
            clubId, typeFilter, includeCompleted, page, size
        )

        val events: List<ActivityItemDto.EventActivity> = if (typeFilter == ActivityType.SKLADCHINA) {
            emptyList()
        } else {
            loadEvents(clubId, includeCompleted)
        }

        val skladchinas: List<ActivityItemDto.SkladchinaActivity> = if (typeFilter == ActivityType.EVENT) {
            emptyList()
        } else {
            loadSkladchinas(clubId, includeCompleted)
        }

        val merged: List<ActivityItemDto> = (events + skladchinas)
            .sortedWith(ACTIVITY_ORDER)

        val total = merged.size.toLong()
        val totalPages = if (size == 0) 0 else ((total + size - 1) / size).toInt()
        val fromIndex = (page * size).coerceAtMost(merged.size)
        val toIndex = (fromIndex + size).coerceAtMost(merged.size)
        val pageContent = if (fromIndex >= toIndex) emptyList() else merged.subList(fromIndex, toIndex)

        log.info(
            "Activities fetched: clubId={} total={} returned={} page={}",
            clubId, total, pageContent.size, page
        )

        return PageResponse(
            content = pageContent,
            totalElements = total,
            totalPages = totalPages,
            page = page,
            size = size
        )
    }

    private fun loadEvents(clubId: UUID, includeCompleted: Boolean): List<ActivityItemDto.EventActivity> {
        val raw: List<EventWithGoingCount> = eventRepository.findAllByClubWithGoingCount(clubId)
        val mapped = raw.map { activityMapper.toEventActivity(it.event, it.goingCount) }
        return if (includeCompleted) mapped else mapped.filterNot { it.isCompleted }
    }

    private fun loadSkladchinas(clubId: UUID, includeCompleted: Boolean): List<ActivityItemDto.SkladchinaActivity> {
        val raw: List<SkladchinaWithAggregates> =
            skladchinaRepository.findAllByClubWithAggregates(clubId, includeCompleted)
        return raw.map(activityMapper::toSkladchinaActivity)
    }

    companion object {
        /**
         * `createdAt DESC` primary; `id ASC` secondary for stable tie-breaking
         * (see CC-12 in the spec — UUID comparison is deterministic).
         */
        private val ACTIVITY_ORDER: Comparator<ActivityItemDto> =
            compareByDescending<ActivityItemDto> { it.createdAt }
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
