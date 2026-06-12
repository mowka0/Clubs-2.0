package com.clubs.reputation

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.Stage_1Vote

/**
 * Pure mapping of a behaviour outcome to a `reputation_ledger` kind + points.
 * Single source of truth for the PRD §4.4.4 attendance table and the skladchina
 * finance deltas. See docs/modules/reputation-v2.md.
 *
 * Points are keyed to the stage-2 commitment only: a confirmed booking that is
 * attended/missed scores the same regardless of the stage-1 vote. The stage-1
 * vote still selects the kind (ironclad vs spontaneous, no_show vs spectator) —
 * kinds feed display traits like spontaneity_count, not the score.
 */
object ReputationPolicy {

    /**
     * "Право на ошибку": below this many ledger outcomes in a club, the UI shows
     * "Новичок" (no number) so a single early miss does not brand a newcomer.
     * The cache still stores the true index — this threshold is presentational.
     */
    const val MIN_OUTCOMES_FOR_DISPLAY = 3

    /**
     * A confirmed response → attendance kind. Caller guarantees final_status=confirmed,
     * which implies stage_1_vote ∈ {going, maybe} (Stage2Service rejects the rest).
     * disputed / null attendance → confirmed_unresolved (terminal, 0 points).
     */
    fun attendanceKind(stage1Vote: Stage_1Vote?, attendance: AttendanceStatus?): ReputationKind = when {
        attendance == AttendanceStatus.attended && stage1Vote == Stage_1Vote.going -> ReputationKind.ironclad
        attendance == AttendanceStatus.attended && stage1Vote == Stage_1Vote.maybe -> ReputationKind.spontaneous
        attendance == AttendanceStatus.absent && stage1Vote == Stage_1Vote.going -> ReputationKind.no_show
        attendance == AttendanceStatus.absent && stage1Vote == Stage_1Vote.maybe -> ReputationKind.spectator
        else -> ReputationKind.confirmed_unresolved
    }

    /**
     * Skladchina terminal participant status → finance kind. Null for non-reputational
     * statuses (see docs/backlog/skladchina-reputation-redesign.md):
     *  - declined: an explicit refusal is the DESIRED behaviour ("can't pay — say so
     *    now") and the free exit from a punitive skladchina. No row at all (not a
     *    0-point row): three one-tap declines must not graduate a user out of
     *    "Новичок" (outcome_count inflation). Historic skladchina_declined rows keep
     *    their stored points; the kind stays in the enum but is never emitted again.
     *  - released: the skladchina closed BEFORE its deadline (F5-02). The promise was
     *    "answer by the deadline" and the deadline never came — no promise broken.
     */
    fun financeKind(status: SkladchinaParticipantStatus): ReputationKind? = when (status) {
        SkladchinaParticipantStatus.paid -> ReputationKind.skladchina_paid
        SkladchinaParticipantStatus.expired_no_response -> ReputationKind.skladchina_expired
        SkladchinaParticipantStatus.declined -> null
        SkladchinaParticipantStatus.released -> null
        SkladchinaParticipantStatus.pending -> null
    }

    fun pointsFor(kind: ReputationKind): Int = when (kind) {
        ReputationKind.ironclad -> 100
        // A confirmed booking that is skipped burns the slot and the organizer's
        // plan: one no-show costs two attendances (break-even attendance = 67%).
        ReputationKind.no_show -> -200
        // Same as ironclad/no_show: once the stage-2 booking is confirmed, the
        // promise (and the damage of breaking it) does not depend on the stage-1 vote.
        ReputationKind.spontaneous -> 100
        ReputationKind.spectator -> -200
        ReputationKind.confirmed_unresolved -> 0
        // 1/10 of ironclad (+100): attendance is verified by the organizer, payment
        // is self-declared. Symbolic plus until org-confirmation lands (P2).
        ReputationKind.skladchina_paid -> 10
        // Historic kind — no longer emitted (financeKind(declined) = null since the
        // 2026-06-12 redesign). Old -5 rows on staging keep their stored points; the
        // ledger reads stored points, never this function, so 0 here only guards a
        // hypothetical future caller.
        ReputationKind.skladchina_declined -> 0
        // 1/5 of no_show (-200): the harm is comparable (a burned booking), but the
        // obligation was imposed by the organizer — the participant never pressed
        // "confirm" as in event stage 2. Break-even ≈ 80% payments, slightly above
        // the "≥70% pay on time" success metric.
        ReputationKind.skladchina_expired -> -40
    }

    /** Presentational gate: show the real index only once a track record exists. */
    fun isShown(outcomeCount: Int): Boolean = outcomeCount >= MIN_OUTCOMES_FOR_DISPLAY
}
