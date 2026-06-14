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

/**
 * One open obligation a leaving user abandons (the source row + its behaviour time), passed
 * to [ReputationService.penalizeExit]. `occurredAt` = the event datetime (no_show) or the
 * skladchina deadline (skladchina_expired) — the immutable decay anchor, not leave time.
 * The reputation module owns the kind/points/axis/source mapping; the caller only supplies
 * which obligations broke and when they were due.
 */
data class ExitObligation(
    val sourceId: UUID,
    val occurredAt: OffsetDateTime
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

/**
 * One of a user's ledger outcomes (club + kind + behaviour time), read for on-read P1b Trust.
 * `occurredAt` anchors the recency decay; `kind` is classified kept/broke/neutral by TrustPolicy.
 */
data class ClubLedgerOutcome(
    val clubId: UUID,
    val kind: ReputationKind,
    val occurredAt: OffsetDateTime
)

/**
 * A club member's ledger outcome (user + kind + behaviour time) — batch-read for the club member
 * list so per-member Trust is computed from one query rather than an N+1 over members.
 */
data class MemberLedgerOutcome(
    val userId: UUID,
    val kind: ReputationKind,
    val occurredAt: OffsetDateTime
)

/**
 * A user's ledger outcome WITH its club, batch-read across a set of users (one query) — the source
 * for each member's GLOBAL XP level on the club member list (§H8 others-tier), without an N+1.
 * `clubId` is needed because the diversity bonus counts distinct kept clubs.
 */
data class UserLedgerOutcome(
    val userId: UUID,
    val clubId: UUID,
    val kind: ReputationKind,
    val occurredAt: OffsetDateTime
)
