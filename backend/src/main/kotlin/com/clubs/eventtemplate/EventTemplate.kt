package com.clubs.eventtemplate

import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Именованная заготовка формы создания встречи, принадлежащая клубу (V79).
 *
 * Хранит всё, что организатор заполняет при создании события, КРОМЕ даты — она меняется
 * всегда. Вместо даты живут [defaultWeekday] и [defaultTime]: форма подставляет ближайшее
 * будущее совпадение, но поле остаётся обычным, редактируемым.
 *
 * Шаблон не является ни расписанием (авто-создание встреч сюда не входит), ни новым форматом
 * события: применение открывает ту же форму и тот же `POST /api/clubs/{id}/events`.
 * Спека: docs/modules/event-templates.md.
 */
data class EventTemplate(
    val id: UUID,
    val clubId: UUID,
    // Ярлык списка выбора, отдельный от title: у двух шаблонов может совпадать название встречи.
    val name: String,
    val title: String,
    val description: String?,
    val locationText: String?,
    // Точка места (WGS-84). Инвариант пары зеркалит события: оба заданы или оба null.
    val locationLat: Double?,
    val locationLon: Double?,
    val locationHint: String?,
    // null = шаблон открытой встречи (согласовано с isOpenEvent).
    val participantLimit: Int?,
    val isOpenEvent: Boolean,
    val isUrgentEvent: Boolean,
    // Свой интервал Этапа 2 в минутах; null = глобальный дефолт бэкенда.
    val stage2LeadMinutes: Int?,
    val photoUrl: String?,
    // День недели повторов (1 = понедельник … 7 = воскресенье, ISO-8601) и время — в ЛОКАЛЬНОЙ
    // зоне организатора. Их считает клиент: event_datetime хранится как TIMESTAMPTZ, и вывод
    // дня недели из UTC сдвинул бы «вторник 19:00» в другой день. null = не задано.
    val defaultWeekday: Short?,
    val defaultTime: LocalTime?,
    val createdBy: UUID,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?
)

/** Шаблон вместе с именем клуба-владельца — кросс-клубовый список в пикере «+» показывает клуб. */
data class EventTemplateWithClub(
    val template: EventTemplate,
    val clubName: String
)
