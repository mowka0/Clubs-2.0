package com.clubs.event

import com.clubs.generated.jooq.enums.EventStatus
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Формат встречи (v2, решение PO 2026-09-02): их два, и различаются они наличием мест.
 * Определяется целиком по `participant_limit` — второго представления у факта нет.
 * Спека: docs/modules/event-formats.md.
 *
 * [wire] — значение наружу: строчное, как литералы jOOQ-энумов и `status` в DTO.
 */
enum class EventFormat(@get:JsonValue val wire: String) {
    /** Обычная встреча: максимум мест всегда, минимум по желанию (см. [Event.minParticipants]). */
    NORMAL("normal"),

    /** Открытая встреча: без мест, без очереди и целиком вне репутации. */
    OPEN("open")
}

/**
 * Литерал формата НА ВХОДЕ (`CreateEventRequest`, `SaveEventTemplateRequest`). Кроме двух живых
 * значений принимает три из V85 — до следующего релиза: фронт с закэшированным старым бандлом
 * иначе получал бы 400 на создание встречи (известный хвост immutable-кэша). Наружу старые
 * литералы не уходят никогда — там только [EventFormat].
 */
enum class EventFormatInput(
    // @JsonValue и здесь: тесты и клиенты, сериализующие запрос, должны получать литерал, а не имя константы.
    @get:JsonValue val wire: String,
    val format: EventFormat,
    /** Бывший `min`: число было порогом, поэтому минимум подставляется равным лимиту («ровно N»). */
    val impliesMinimum: Boolean = false
) {
    NORMAL("normal", EventFormat.NORMAL),
    OPEN("open", EventFormat.OPEN),
    LEGACY_MIN("min", EventFormat.NORMAL, impliesMinimum = true),
    LEGACY_MAX("max", EventFormat.NORMAL),
    LEGACY_ANY("any", EventFormat.OPEN);

    val isOpen: Boolean
        get() = format == EventFormat.OPEN

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromWire(raw: String): EventFormatInput =
            entries.firstOrNull { it.wire == raw }
                ?: throw IllegalArgumentException("Unknown event format: $raw")
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
    // Максимум участников — потолок мест: сверх него голос «Иду» встаёт в очередь. null = открытая
    // встреча (V62): без очереди, порога и штрафа отказа, целиком вне репутации.
    val participantLimit: Int?,
    // Минимум участников — порог набора (V86), по желанию организатора; null = выключен. Условие
    // сбора состава, а не проведения: не набрали к дедлайну — отмена, просел после закрытия — DM.
    val minParticipants: Int? = null,
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
    // «Проводим» (V86): организатор подтвердил встречу составом ниже минимума; null = не нажимал.
    val rosterDecidedAt: OffsetDateTime? = null,
    // Предупреждение о недоборе (V86) отправлено или израсходовано; null = момент ещё впереди.
    val rosterWarningSentAt: OffsetDateTime? = null,
    val photoUrl: String?,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?
) {
    /** Формат встречи одним значением — единственный дискриминатор для сервисов и DTO. */
    val format: EventFormat
        get() = if (participantLimit == null) EventFormat.OPEN else EventFormat.NORMAL

    // «Целиком вне репутации»: у формата без лимита никто не держит дефицитное место, поэтому
    // ни отказ, ни выход из клуба не штрафуются. Отдельное имя оставлено намеренно — читающему
    // ReputationService важен именно этот смысл, а не то, какой это формат.
    val isOpenEvent: Boolean
        get() = participantLimit == null

    // У встречи есть состав, который набирается голосами и закрывается в дедлайн набора.
    val isRosterEvent: Boolean
        get() = !isOpenEvent

    /** Организатор уже сказал «Проводим»: состав ниже минимума его больше не беспокоит. */
    val isRosterDecided: Boolean
        get() = rosterDecidedAt != null
}
