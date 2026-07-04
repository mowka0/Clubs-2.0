package com.clubs.reputation

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.ReputationAxis
import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.generated.jooq.enums.ReputationSource
import com.clubs.generated.jooq.enums.Stage_1Vote
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Одна append-only строка `reputation_ledger`, вставляемая идемпотентно.
 * `occurredAt` — момент поведения (event datetime / skladchina closed_at),
 * НЕ момент обработки — стабильный, воспроизводимый якорь для decay в P1b.
 */
data class LedgerEntry(
    val userId: UUID,
    val clubId: UUID,
    val axis: ReputationAxis,
    val kind: ReputationKind,
    val points: Int,
    val occurredAt: OffsetDateTime,
    val sourceType: ReputationSource,
    val sourceId: UUID
)

/**
 * Одно открытое обязательство, которое бросает уходящий пользователь (исходная строка + момент
 * поведения), передаётся в [ReputationService.penalizeExit]. `occurredAt` = дата/время события
 * (no_show) или дедлайн складчины (skladchina_expired) — неизменяемый якорь decay, а не момент выхода.
 * Модуль репутации сам владеет маппингом kind/points/axis/source; вызывающий код только сообщает,
 * какие обязательства были нарушены и когда они должны были быть выполнены.
 */
data class ExitObligation(
    val sourceId: UUID,
    val occurredAt: OffsetDateTime
)

/** Контекст клуба, нужный для построения строк ledger по посещаемости для одного финализированного события. */
data class EventReputationContext(
    val clubId: UUID,
    val ownerId: UUID,
    val eventDatetime: OffsetDateTime
)

/**
 * Подтверждённый отклик на событие — только такие отклики порождают строку ledger.
 * Неподтверждённые (declined / waitlisted / null) дают 0 в любой агрегат,
 * поэтому они отфильтровываются на уровне запроса и никогда не записываются.
 */
data class ConfirmedResponse(
    val userId: UUID,
    val stage1Vote: Stage_1Vote?,
    val attendance: AttendanceStatus?
)

/**
 * Один из ledger-исходов пользователя (клуб + kind + момент поведения), читается для on-read
 * P1b Trust. `occurredAt` — якорь для decay по давности; `kind` классифицируется как
 * kept/broke/neutral в TrustPolicy.
 */
data class ClubLedgerOutcome(
    val clubId: UUID,
    val kind: ReputationKind,
    val occurredAt: OffsetDateTime
)

/**
 * Ledger-исход участника клуба (user + kind + момент поведения) — читается пакетно для списка
 * участников клуба, чтобы Trust на участника считался одним запросом, а не N+1 по участникам.
 */
data class MemberLedgerOutcome(
    val userId: UUID,
    val kind: ReputationKind,
    val occurredAt: OffsetDateTime
)
