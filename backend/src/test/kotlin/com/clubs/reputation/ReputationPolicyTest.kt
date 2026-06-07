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
        assertEquals(ReputationKind.confirmed_unresolved, kind(null, AttendanceStatus.attended))
    }

    @Test
    fun `attendance points match the legacy deltas`() {
        assertEquals(100, ReputationPolicy.pointsFor(ReputationKind.ironclad))
        assertEquals(-50, ReputationPolicy.pointsFor(ReputationKind.no_show))
        assertEquals(30, ReputationPolicy.pointsFor(ReputationKind.spontaneous))
        assertEquals(-20, ReputationPolicy.pointsFor(ReputationKind.spectator))
        assertEquals(0, ReputationPolicy.pointsFor(ReputationKind.confirmed_unresolved))
    }

    @Test
    fun `finance kinds and points match the legacy skladchina deltas`() {
        assertEquals(ReputationKind.skladchina_paid, ReputationPolicy.financeKind(SkladchinaParticipantStatus.paid))
        assertEquals(ReputationKind.skladchina_declined, ReputationPolicy.financeKind(SkladchinaParticipantStatus.declined))
        assertEquals(ReputationKind.skladchina_expired, ReputationPolicy.financeKind(SkladchinaParticipantStatus.expired_no_response))
        assertNull(ReputationPolicy.financeKind(SkladchinaParticipantStatus.pending))

        assertEquals(10, ReputationPolicy.pointsFor(ReputationKind.skladchina_paid))
        assertEquals(-5, ReputationPolicy.pointsFor(ReputationKind.skladchina_declined))
        assertEquals(-25, ReputationPolicy.pointsFor(ReputationKind.skladchina_expired))
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
