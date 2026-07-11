package com.clubs.event

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

data class EventDetailDto(
    val id: UUID,
    val clubId: UUID,
    val title: String,
    val description: String?,
    val locationText: String,
    // Гео-точка места (WGS-84). null у легаси-событий, созданных до фичи event-geo, —
    // фронт показывает место текстом без карты. Для новых событий оба значения всегда заданы.
    val locationLat: Double?,
    val locationLon: Double?,
    // Опциональное уточнение организатора к месту («Вход со двора, домофон 12»); null = нет.
    val locationHint: String?,
    val eventDatetime: OffsetDateTime,
    val participantLimit: Int,
    val votingOpensDaysBefore: Int,
    val status: String,
    val goingCount: Int,
    val maybeCount: Int,
    val notGoingCount: Int,
    val confirmedCount: Int,
    // Крайний момент, до которого ПОДТВЕРЖДЁННЫЙ участник ещё может отказаться от места
    // (= eventDatetime − events.stage2-decline-cutoff-minutes). Фронт прячет кнопку «Отказаться»
    // у confirmed, когда now ≥ этого значения; бэк остаётся источником истины (declineParticipation
    // отклонит поздний отказ). У waitlisted порога нет. Значение — чистая функция от даты события,
    // поэтому одинаково для всех (не пер-юзер) и живёт в общем DTO. Убирает прежний рассинхрон:
    // фронт держал копию порога хардкодом (4 ч), не связанную с рантайм-env бэка.
    val confirmedDeclineDeadline: OffsetDateTime,
    val attendanceMarked: Boolean,
    val attendanceFinalized: Boolean,
    // F5-14: опциональная причина отмены от организатора; null, если отменено без указания причины.
    val cancellationReason: String?,
    val photoUrl: String?,
    val createdAt: OffsetDateTime?
)

data class EventListItemDto(
    val id: UUID,
    val title: String,
    val eventDatetime: OffsetDateTime,
    val locationText: String,
    val participantLimit: Int,
    val goingCount: Int,
    val status: String,
    val photoUrl: String?
)

data class MyEventListItemDto(
    val id: UUID,
    val title: String,
    val eventDatetime: OffsetDateTime,
    val locationText: String,
    val status: String,
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val myVote: String?,
    val myParticipationStatus: String?,
    val goingCount: Int,
    val confirmedCount: Int,
    val participantLimit: Int,
    val actionRequired: Boolean
)

data class CreateEventRequest(
    @field:NotBlank(message = "Title is required")
    @field:Size(max = 255, message = "Title must be at most 255 characters")
    val title: String,

    val description: String? = null,

    @field:NotBlank(message = "Location is required")
    @field:Size(max = 500, message = "Location must be at most 500 characters")
    val locationText: String,

    // Координаты обязательны (решение PO: fail-closed — событие без точки на карте создать
    // нельзя). Nullable тип + @NotNull, чтобы отсутствие поля давало дружелюбный 400 от Bean
    // Validation, а не ошибку десериализации Jackson.
    @field:NotNull(message = "Location latitude is required")
    @field:DecimalMin(value = "-90.0", message = "Latitude must be >= -90")
    @field:DecimalMax(value = "90.0", message = "Latitude must be <= 90")
    val locationLat: Double?,

    @field:NotNull(message = "Location longitude is required")
    @field:DecimalMin(value = "-180.0", message = "Longitude must be >= -180")
    @field:DecimalMax(value = "180.0", message = "Longitude must be <= 180")
    val locationLon: Double?,

    @field:Size(max = 200, message = "Location hint must be at most 200 characters")
    val locationHint: String? = null,

    @field:NotNull(message = "Event datetime is required")
    @field:Future(message = "Event datetime must be in the future")
    val eventDatetime: OffsetDateTime,

    @field:NotNull(message = "Participant limit is required")
    @field:Positive(message = "Participant limit must be positive")
    val participantLimit: Int,

    @field:Min(value = 1, message = "Voting opens days before must be at least 1")
    @field:Max(value = 14, message = "Voting opens days before must be at most 14")
    val votingOpensDaysBefore: Int = 14,

    @field:Size(max = 1024, message = "Photo URL must be at most 1024 characters")
    val photoUrl: String? = null
)


/** F5-14: опциональная причина отмены события от организатора (≤500 символов; пусто → null). */
data class CancelEventRequest(
    @field:Size(max = 500)
    val reason: String? = null
)
