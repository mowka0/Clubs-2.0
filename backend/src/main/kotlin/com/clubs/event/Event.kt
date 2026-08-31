package com.clubs.event

import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.LimitKind
import com.fasterxml.jackson.annotation.JsonValue
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Формат встречи — ответ на единственный вопрос «сколько человек нужно» (решение PO 2026-08-31).
 * Собирается из пары «есть ли лимит» + «как его читать»: наружу пара не торчит, всё ветвление
 * в сервисах, DTO и на фронте идёт по этому значению. Спека: docs/modules/event-formats.md.
 *
 * [wire] — значение на проводе и в БД. Строчное, как литералы jOOQ-энумов и `status` в DTO:
 * фронт и колонка `limit_kind` читают одни и те же три слова.
 */
enum class EventFormat(@get:JsonValue val wire: String) {
    /** Минимум участников: не наберём к дедлайну — встреча отменится. Верхней границы нет. */
    MIN("min"),

    /** Максимум участников: встреча состоится при любом составе, сверх лимита — очередь. */
    MAX("max"),

    /** Сколько придёт: без ограничений, без очереди и целиком вне репутации. */
    ANY("any");

    /** Обратная сторона [Event.format]: как формат ложится в колонку `limit_kind`. */
    val limitKind: LimitKind?
        get() = when (this) {
            MIN -> LimitKind.min
            MAX -> LimitKind.max
            ANY -> null
        }
}

data class Event(
    val id: UUID,
    val clubId: UUID,
    val createdBy: UUID,
    val title: String,
    val description: String?,
    // Адрес места; null = место не указано (опционально с V58, решение PO 2026-07-11).
    val locationText: String?,
    // Гео-точка места (WGS-84): null у событий без точки (легаси или созданы без места).
    // Инвариант: оба null или оба заданы. Дефолты null, чтобы точечные выборки (findMyFeed),
    // не показывающие карту, могли не читать колонки.
    val locationLat: Double? = null,
    val locationLon: Double? = null,
    // Опциональное уточнение организатора к месту, отдельное от адреса; null = нет.
    val locationHint: String? = null,
    val eventDatetime: OffsetDateTime,
    // Число участников, смысл которого задаёт limitKind (V85); null = формат «сколько придёт»:
    // без очереди, порога и штрафа отказа, целиком вне репутации.
    val participantLimit: Int?,
    // Как читать participantLimit: порог набора (min) или потолок мест (max). null строго при
    // participantLimit = null — инвариант пары держит CHECK chk_events_limit_kind.
    val limitKind: LimitKind? = null,
    val votingOpensDaysBefore: Int,
    // Свой интервал Этапа 2 (минут до старта, V67); null = глобальный дефолт из конфига.
    // Дефолт null — точечные выборки (findMyFeed) могут не читать колонку.
    val stage2LeadMinutes: Int? = null,
    val status: EventStatus,
    val stage2Triggered: Boolean,
    val attendanceMarked: Boolean,
    val attendanceFinalized: Boolean,
    // Опциональная причина от организатора, задаваемая при отмене (F5-14); иначе null.
    val cancellationReason: String? = null,
    val photoUrl: String?,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?
) {
    /** Формат встречи одним значением — единственный дискриминатор для сервисов и DTO. */
    val format: EventFormat
        get() = limitKind.toFormat()

    // «Целиком вне репутации»: у формата без лимита никто не держит дефицитное место, поэтому
    // ни отказ, ни выход из клуба не штрафуются. Отдельное имя оставлено намеренно — читающему
    // ReputationService важен именно этот смысл, а не то, какой это из трёх форматов.
    val isOpenEvent: Boolean
        get() = format == EventFormat.ANY

    // У встречи есть состав, который набирается голосами и закрывается в дедлайн набора.
    // Верно для обоих форматов с лимитом: разница между ними — что происходит в сам дедлайн.
    val isRosterEvent: Boolean
        get() = format != EventFormat.ANY
}

/**
 * Как читается колонка `limit_kind` — обратная сторона [EventFormat.limitKind]. Пару
 * «есть лимит ⟺ есть его смысл» держит CHECK, поэтому null здесь однозначно значит
 * «лимита нет», а не «забыли заполнить».
 */
fun LimitKind?.toFormat(): EventFormat = when (this) {
    LimitKind.min -> EventFormat.MIN
    LimitKind.max -> EventFormat.MAX
    null -> EventFormat.ANY
}
