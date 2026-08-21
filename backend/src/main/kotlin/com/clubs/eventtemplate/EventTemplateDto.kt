package com.clubs.eventtemplate

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Шаблон в ответах API. Несёт `clubId` и `clubName`, потому что список в пикере «+»
 * кросс-клубовый: пункт должен показать, чей это шаблон, а переход — знать клуб.
 */
data class EventTemplateDto(
    val id: UUID,
    val clubId: UUID,
    val clubName: String,
    val name: String,
    val title: String,
    val description: String?,
    val locationText: String?,
    val locationLat: Double?,
    val locationLon: Double?,
    val locationHint: String?,
    val participantLimit: Int?,
    val isOpenEvent: Boolean,
    val isUrgentEvent: Boolean,
    val stage2LeadMinutes: Int?,
    val photoUrl: String?,
    // 1 = понедельник … 7 = воскресенье, в локальной зоне организатора; null = дата не угадывается.
    val defaultWeekday: Short?,
    // "HH:mm[:ss]" в локальной зоне организатора; null = время не задано.
    val defaultTime: LocalTime?,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?
)

/**
 * Тело создания (`POST`) и полной замены (`PUT`) шаблона.
 *
 * Семантика PUT — та же, что у [com.clubs.event.UpdateEventRequest]: клиент присылает ПОЛНЫЙ
 * набор полей, `null` = «очистить», а не «не менять». Отдельного эндпоинта переименования нет —
 * клиент держит DTO в кэше и шлёт его обратно с новым `name` (одна точка записи вместо двух,
 * валидация не разъезжается).
 *
 * Bean Validation зеркалит CHECK-и V79: диапазоны, парность координат, согласованность формата
 * с лимитом. Инвариант события «место ИЛИ уточнение обязательны» здесь НАМЕРЕННО отсутствует —
 * шаблон это заготовка, и «Поход» с плавающей точкой старта законен; требование остаётся на
 * форме создания встречи.
 */
data class SaveEventTemplateRequest(
    @field:NotBlank(message = "Template name is required")
    @field:Size(max = 60, message = "Template name must be at most 60 characters")
    val name: String,

    @field:NotBlank(message = "Title is required")
    @field:Size(max = 255, message = "Title must be at most 255 characters")
    val title: String,

    val description: String? = null,

    @field:Size(max = 500, message = "Location must be at most 500 characters")
    val locationText: String? = null,

    @field:DecimalMin(value = "-90.0", message = "Latitude must be >= -90")
    @field:DecimalMax(value = "90.0", message = "Latitude must be <= 90")
    val locationLat: Double? = null,

    @field:DecimalMin(value = "-180.0", message = "Longitude must be >= -180")
    @field:DecimalMax(value = "180.0", message = "Longitude must be <= 180")
    val locationLon: Double? = null,

    @field:Size(max = 200, message = "Location hint must be at most 200 characters")
    val locationHint: String? = null,

    // null = шаблон открытой встречи; согласованность с флагом проверяет isParticipantLimitConsistent.
    @field:Positive(message = "Participant limit must be positive")
    val participantLimit: Int? = null,

    val isOpenEvent: Boolean = false,

    val isUrgentEvent: Boolean = false,

    @field:Min(value = 360, message = "Stage 2 lead must be at least 360 minutes (6 hours)")
    @field:Max(value = 7200, message = "Stage 2 lead must be at most 7200 minutes (5 days)")
    val stage2LeadMinutes: Int? = null,

    @field:Size(max = 1024, message = "Photo URL must be at most 1024 characters")
    val photoUrl: String? = null,

    @field:Min(value = 1, message = "Weekday must be 1 (Monday) .. 7 (Sunday)")
    @field:Max(value = 7, message = "Weekday must be 1 (Monday) .. 7 (Sunday)")
    val defaultWeekday: Short? = null,

    val defaultTime: LocalTime? = null
) {
    @get:AssertTrue(message = "Latitude and longitude must be provided together")
    val isLocationPairConsistent: Boolean
        get() = (locationLat == null) == (locationLon == null)

    @get:AssertTrue(message = "Open event template must have no participant limit; a regular one requires it")
    val isParticipantLimitConsistent: Boolean
        get() = if (isOpenEvent) participantLimit == null else participantLimit != null

    @get:AssertTrue(message = "Open event has no stage 2; stage2LeadMinutes is not applicable")
    val isStage2LeadConsistent: Boolean
        get() = !isOpenEvent || stage2LeadMinutes == null

    @get:AssertTrue(message = "Urgent event template must be a limited event without a custom stage 2 lead")
    val isUrgentConsistent: Boolean
        get() = !isUrgentEvent || (!isOpenEvent && stage2LeadMinutes == null)

    /**
     * Схлопывает пробелы: обрезанные строки, пустые → null. Сервис нормализует запрос ОДИН раз
     * на входе, и дальше и сравнение на дубликат, и запись идут по одному значению — иначе
     * «Йога » и «Йога» считались бы разными шаблонами, а в БД попадали бы одинаковыми.
     */
    fun normalized(): SaveEventTemplateRequest = copy(
        name = name.trim(),
        title = title.trim(),
        description = description.blankToNull(),
        locationText = locationText.blankToNull(),
        locationHint = locationHint.blankToNull(),
        photoUrl = photoUrl.blankToNull()
    )

    private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
