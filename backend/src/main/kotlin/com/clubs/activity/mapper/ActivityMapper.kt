package com.clubs.activity.mapper

import com.clubs.activity.dto.ActivityItemDto
import com.clubs.event.Event
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.skladchina.Skladchina
import com.clubs.skladchina.SkladchinaWithAggregates
import org.springframework.stereotype.Component

@Component
class ActivityMapper {

    fun toEventActivity(
        event: Event,
        goingCount: Int,
        confirmedCount: Int,
        actionRequired: Boolean
    ): ActivityItemDto.EventActivity {
        // events.created_at is NOT NULL at DB level; domain type is nullable only because
        // the create-path constructs the object before the row is fetched back. Reads always
        // have createdAt populated. Falling back to event_datetime would corrupt sort order,
        // so we require it here and fail fast on a violated invariant.
        val createdAt = event.createdAt
            ?: error("Event ${event.id} has null createdAt; database invariant violated")
        return ActivityItemDto.EventActivity(
            id = event.id,
            clubId = event.clubId,
            title = event.title,
            createdAt = createdAt,
            isCompleted = event.status in COMPLETED_EVENT_STATUSES,
            eventDatetime = event.eventDatetime,
            locationText = event.locationText,
            participantLimit = event.participantLimit,
            goingCount = goingCount,
            confirmedCount = confirmedCount,
            status = event.status.literal,
            descriptionPreview = buildDescriptionPreview(event.description),
            photoUrl = event.photoUrl,
            actionRequired = actionRequired
        )
    }

    fun toSkladchinaActivity(item: SkladchinaWithAggregates): ActivityItemDto.SkladchinaActivity {
        val s: Skladchina = item.skladchina
        return ActivityItemDto.SkladchinaActivity(
            id = s.id,
            clubId = s.clubId,
            title = s.title,
            createdAt = s.createdAt,
            isCompleted = s.status in COMPLETED_SKLADCHINA_STATUSES,
            paymentMode = s.paymentMode.literal,
            totalGoalKopecks = s.totalGoalKopecks,
            collectedKopecks = item.collectedKopecks,
            deadline = s.deadline,
            participantCount = item.participantCount,
            paidCount = item.paidCount,
            status = s.status.literal,
            affectsReputation = s.affectsReputation,
            photoUrl = s.photoUrl
        )
    }

    /**
     * Returns null if description is null/blank; otherwise trims and truncates to
     * [MAX_PREVIEW_LENGTH] chars, appending an ellipsis when truncated.
     */
    private fun buildDescriptionPreview(description: String?): String? {
        val trimmed = description?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length > MAX_PREVIEW_LENGTH) {
            trimmed.substring(0, MAX_PREVIEW_LENGTH) + ELLIPSIS
        } else {
            trimmed
        }
    }

    companion object {
        const val MAX_PREVIEW_LENGTH = 40
        private const val ELLIPSIS = "…"

        private val COMPLETED_EVENT_STATUSES = setOf(EventStatus.completed, EventStatus.cancelled)
        private val COMPLETED_SKLADCHINA_STATUSES = setOf(
            SkladchinaStatus.closed_success,
            SkladchinaStatus.closed_failed,
            SkladchinaStatus.cancelled
        )
    }
}
