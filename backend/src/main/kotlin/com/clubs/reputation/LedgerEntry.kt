package com.clubs.reputation

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.ReputationAxis
import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.generated.jooq.enums.ReputationSource
import com.clubs.generated.jooq.enums.Stage_1Vote
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One append-only `reputation_ledger` row to insert idempotently.
 * `occurredAt` is the behaviour time (event datetime / skladchina closed_at),
 * NOT the processing time — the stable, reproducible anchor for P1b decay.
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

/** Club context needed to build attendance ledger rows for one finalized event. */
data class EventReputationContext(
    val clubId: UUID,
    val ownerId: UUID,
    val eventDatetime: OffsetDateTime
)

/**
 * A confirmed event response — the only responses that produce a ledger row.
 * Non-confirmed (declined / waitlisted / null) contribute 0 to every aggregate,
 * so they are filtered out at the query and never written.
 */
data class ConfirmedResponse(
    val userId: UUID,
    val stage1Vote: Stage_1Vote?,
    val attendance: AttendanceStatus?
)
