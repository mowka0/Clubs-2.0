package com.clubs.event

import java.time.OffsetDateTime

/**
 * Набор редактируемых полей встречи (решение PO 2026-07-26) — доменный параметр-объект
 * для [EventRepository.updateEvent]. Существует, чтобы DTO не протекал в репозиторий
 * и чтобы не тащить восемь позиционных аргументов, которые легко перепутать местами.
 *
 * Содержит ТОЛЬКО то, что организатор может менять после создания. Формат встречи
 * (открытая/срочная), окно голосования и все счётчики сюда намеренно не входят.
 */
data class EventEdit(
    val title: String,
    val description: String?,
    val locationText: String?,
    val locationLat: Double?,
    val locationLon: Double?,
    val locationHint: String?,
    val eventDatetime: OffsetDateTime,
    // null = открытая встреча; для события с местами лимит обязателен (проверяет Service).
    val participantLimit: Int?,
    // null = глобальный дефолт интервала Этапа 2 из конфига.
    val stage2LeadMinutes: Int?,
    val photoUrl: String?
)
