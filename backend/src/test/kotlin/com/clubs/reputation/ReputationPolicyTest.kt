package com.clubs.reputation

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-unit parity test for the reputation outcome → (kind, points) mapping and the
 * "Новичок" display threshold. This is the single source of truth that must reproduce
 * the legacy ReputationService.computeDeltas table (PRD §4.4.4).
 */
class ReputationPolicyTest {

    @Test
    fun `attendance kinds reproduce the PRD table`() {
        assertEquals(ReputationKind.ironclad, kind(Stage_1Vote.going, AttendanceStatus.attended))
        assertEquals(ReputationKind.no_show, kind(Stage_1Vote.going, AttendanceStatus.absent))
        assertEquals(ReputationKind.spontaneous, kind(Stage_1Vote.maybe, AttendanceStatus.attended))
        assertEquals(ReputationKind.spectator, kind(Stage_1Vote.maybe, AttendanceStatus.absent))
    }

    @Test
    fun `disputed or null attendance is confirmed_unresolved regardless of stage 1`() {
        assertEquals(ReputationKind.confirmed_unresolved, kind(Stage_1Vote.going, AttendanceStatus.disputed))
        assertEquals(ReputationKind.confirmed_unresolved, kind(Stage_1Vote.maybe, AttendanceStatus.disputed))
        assertEquals(ReputationKind.confirmed_unresolved, kind(Stage_1Vote.going, null))
        assertEquals(ReputationKind.confirmed_unresolved, kind(Stage_1Vote.maybe, null))
        assertEquals(ReputationKind.confirmed_unresolved, kind(null, AttendanceStatus.disputed))
    }

    @Test
    fun `attendance kind keys on confirmation, not the stage-1 vote (Stage 2 open to all)`() {
        // Фича «Этап 2 открыт всем»: подтвердиться может not_going и не голосовавший (stage1=null).
        // Обязательство — сам факт подтверждения, поэтому пришёл → +100, не пришёл → −200 и для них.
        assertEquals(ReputationKind.spontaneous, kind(null, AttendanceStatus.attended))
        assertEquals(ReputationKind.spontaneous, kind(Stage_1Vote.not_going, AttendanceStatus.attended))
        assertEquals(ReputationKind.spectator, kind(null, AttendanceStatus.absent))
        assertEquals(ReputationKind.spectator, kind(Stage_1Vote.not_going, AttendanceStatus.absent))
        // going сохраняет свои лейблы ironclad / no_show (очки те же).
        assertEquals(ReputationKind.ironclad, kind(Stage_1Vote.going, AttendanceStatus.attended))
        assertEquals(ReputationKind.no_show, kind(Stage_1Vote.going, AttendanceStatus.absent))
    }

    @Test
    fun `attendance points match the legacy deltas`() {
        assertEquals(100, ReputationPolicy.pointsFor(ReputationKind.ironclad))
        assertEquals(-200, ReputationPolicy.pointsFor(ReputationKind.no_show))
        assertEquals(100, ReputationPolicy.pointsFor(ReputationKind.spontaneous))
        assertEquals(-200, ReputationPolicy.pointsFor(ReputationKind.spectator))
        assertEquals(0, ReputationPolicy.pointsFor(ReputationKind.confirmed_unresolved))
    }

    @Test
    fun `finance kinds and points match the 2026-06-12 skladchina redesign`() {
        assertEquals(ReputationKind.skladchina_paid, ReputationPolicy.financeKind(SkladchinaParticipantStatus.paid))
        assertEquals(ReputationKind.skladchina_expired, ReputationPolicy.financeKind(SkladchinaParticipantStatus.expired_no_response))
        // Decline is the desired behaviour and the free exit from a punitive skladchina:
        // NO ledger row at all (a 0-row would inflate outcome_count out of "Новичок").
        assertNull(ReputationPolicy.financeKind(SkladchinaParticipantStatus.declined))
        // Released (closed before the deadline, F5-02): no promise broken — no row.
        assertNull(ReputationPolicy.financeKind(SkladchinaParticipantStatus.released))
        assertNull(ReputationPolicy.financeKind(SkladchinaParticipantStatus.pending))

        assertEquals(10, ReputationPolicy.pointsFor(ReputationKind.skladchina_paid))
        // -40 = 1/5 of no_show: comparable harm, but the obligation was imposed by the
        // organizer (no stage-2-style "confirm" press). Was -25 before the redesign.
        assertEquals(-40, ReputationPolicy.pointsFor(ReputationKind.skladchina_expired))
        // Historic kind — never emitted since the redesign, 0 guards hypothetical callers.
        assertEquals(0, ReputationPolicy.pointsFor(ReputationKind.skladchina_declined))
    }

    @Test
    fun `display threshold gates a newcomer until three outcomes`() {
        assertEquals(3, ReputationPolicy.MIN_OUTCOMES_FOR_DISPLAY)
        assertFalse(ReputationPolicy.isShown(0))
        assertFalse(ReputationPolicy.isShown(1)) // a single early no-show must not brand a newcomer
        assertFalse(ReputationPolicy.isShown(2))
        assertTrue(ReputationPolicy.isShown(3))
        assertTrue(ReputationPolicy.isShown(10))
    }

    private fun kind(stage1Vote: Stage_1Vote?, attendance: AttendanceStatus?) =
        ReputationPolicy.attendanceKind(stage1Vote, attendance)
}
