package com.clubs.event

import com.clubs.generated.jooq.enums.EventStatus
import java.time.OffsetDateTime
import java.util.UUID

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
    // Максимум подтверждённых участников; null = ОТКРЫТАЯ ВСТРЕЧА (V62): без гонки за
    // места, листа ожидания, порога и штрафа отказа; репутация за посещение не начисляется.
    val participantLimit: Int?,
    val votingOpensDaysBefore: Int,
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
    // Открытая встреча (V62): продуктовый тип поверх того же движка, дискриминатор — отсутствие лимита.
    val isOpenEvent: Boolean
        get() = participantLimit == null
}
