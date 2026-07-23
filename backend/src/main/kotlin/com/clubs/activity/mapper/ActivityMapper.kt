package com.clubs.activity.mapper

import com.clubs.activity.dto.ActivityItemDto
import com.clubs.event.Event
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.skladchina.Skladchina
import com.clubs.skladchina.SkladchinaWithAggregates
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

@Component
class ActivityMapper {

    fun toEventActivity(
        event: Event,
        goingCount: Int,
        confirmedCount: Int,
        actionRequired: Boolean,
        now: OffsetDateTime
    ): ActivityItemDto.EventActivity {
        // events.created_at на уровне БД NOT NULL; доменный тип nullable только потому, что
        // путь создания конструирует объект до того, как строка перечитана обратно. При чтении
        // createdAt всегда заполнено. Fallback на event_datetime сломал бы порядок сортировки,
        // поэтому здесь мы требуем его наличие и падаем сразу при нарушении инварианта.
        val createdAt = event.createdAt
            ?: error("Event ${event.id} has null createdAt; database invariant violated")
        return ActivityItemDto.EventActivity(
            id = event.id,
            clubId = event.clubId,
            title = event.title,
            createdAt = createdAt,
            // «Предстоящее/прошедшее» для события определяет ВРЕМЯ, не статус (PO 2026-07-08):
            // статус completed переключают шедулер (раз в час, с 6-часовым запасом) и отметка
            // явки — лента не должна ждать ни того, ни другого. Стартовало — значит уже не
            // «предстоящее». Статусная ветка остаётся для cancelled (отменённое будущее событие
            // тоже уходит из предстоящих).
            isCompleted = event.status in COMPLETED_EVENT_STATUSES || !event.eventDatetime.isAfter(now),
            eventDatetime = event.eventDatetime,
            locationText = event.locationText,
            participantLimit = event.participantLimit,
            isUrgent = event.isUrgent,
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
     * Возвращает null, если description null/пустой; иначе обрезает пробелы и усекает до
     * [MAX_PREVIEW_LENGTH] символов, добавляя многоточие при усечении.
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
        // Максимальная длина превью описания (символов) до усечения с многоточием
        const val MAX_PREVIEW_LENGTH = 40
        private const val ELLIPSIS = "…"

        // Статусы события, считающиеся завершёнными (не активными) для ленты активности
        private val COMPLETED_EVENT_STATUSES = setOf(EventStatus.completed, EventStatus.cancelled)
        // Статусы складчины, считающиеся завершёнными (не активными) для ленты активности
        private val COMPLETED_SKLADCHINA_STATUSES = setOf(
            SkladchinaStatus.closed_success,
            SkladchinaStatus.closed_failed,
            SkladchinaStatus.cancelled
        )
    }
}
