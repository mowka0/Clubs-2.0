package com.clubs.reputation

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.Stage_1Vote

/**
 * Pure mapping of a behaviour outcome to a `reputation_ledger` kind + points.
 * Single source of truth for the PRD §4.4.4 attendance table and the skladchina
 * finance deltas. Exact parity with the legacy ReputationService.computeDeltas.
 * See docs/modules/reputation-v2.md.
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

    /** Skladchina terminal participant status → finance kind. Null for non-reputational statuses. */
    fun financeKind(status: SkladchinaParticipantStatus): ReputationKind? = when (status) {
        SkladchinaParticipantStatus.paid -> ReputationKind.skladchina_paid
        SkladchinaParticipantStatus.declined -> ReputationKind.skladchina_declined
        SkladchinaParticipantStatus.expired_no_response -> ReputationKind.skladchina_expired
        SkladchinaParticipantStatus.pending -> null
    }

    fun pointsFor(kind: ReputationKind): Int = when (kind) {
        ReputationKind.ironclad -> 100
        ReputationKind.no_show -> -50
        ReputationKind.spontaneous -> 30
        ReputationKind.spectator -> -20
        ReputationKind.confirmed_unresolved -> 0
        ReputationKind.skladchina_paid -> 10
        // Explicit decline is weaker than ghosting — we punish unreliability, not
        // disagreement. See SkladchinaService history (#6 skladchina-mvp feedback).
        ReputationKind.skladchina_declined -> -5
        ReputationKind.skladchina_expired -> -25
    }

    /** Presentational gate: show the real index only once a track record exists. */
    fun isShown(outcomeCount: Int): Boolean = outcomeCount >= MIN_OUTCOMES_FOR_DISPLAY
}
