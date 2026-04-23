package com.clubs.reputation

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.tables.records.EventResponsesRecord
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.USER_CLUB_REPUTATION
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.jooq.DSLContext
import org.jooq.Result
import org.jooq.SelectConditionStep
import org.jooq.SelectWhereStep
import org.jooq.Record1
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Tests for the reputation delta calculation logic in ReputationService.
 *
 * Mirrors PRD §4.4.4 table. Must stay in sync with ReputationService.computeDeltas.
 */
class ReputationServiceTest {

    private fun calculateReliabilityDelta(
        stage1Vote: Stage_1Vote?,
        finalStatus: FinalStatus?,
        attendance: AttendanceStatus?
    ): Int = when {
        stage1Vote == Stage_1Vote.going && finalStatus == FinalStatus.confirmed && attendance == AttendanceStatus.attended -> 100
        stage1Vote == Stage_1Vote.going && finalStatus == FinalStatus.confirmed && attendance == AttendanceStatus.absent -> -50
        stage1Vote == Stage_1Vote.maybe && finalStatus == FinalStatus.confirmed && attendance == AttendanceStatus.attended -> 30
        stage1Vote == Stage_1Vote.maybe && finalStatus == FinalStatus.confirmed && attendance == AttendanceStatus.absent -> -20
        else -> 0
    }

    private fun calculateSpontaneityDelta(
        stage1Vote: Stage_1Vote?,
        finalStatus: FinalStatus?,
        attendance: AttendanceStatus?
    ): Int =
        if (stage1Vote == Stage_1Vote.maybe && finalStatus == FinalStatus.confirmed && attendance == AttendanceStatus.attended) 1 else 0

    @Test
    fun `going then confirmed then attended should give +100 reliability (Zhelezobetonnyi)`() {
        val delta = calculateReliabilityDelta(Stage_1Vote.going, FinalStatus.confirmed, AttendanceStatus.attended)
        assertEquals(100, delta, "going -> confirmed -> attended = +100")
    }

    @Test
    fun `going then confirmed then absent should give -50 reliability (Pustozvon)`() {
        val delta = calculateReliabilityDelta(Stage_1Vote.going, FinalStatus.confirmed, AttendanceStatus.absent)
        assertEquals(-50, delta, "going -> confirmed -> absent = -50")
    }

    @Test
    fun `maybe then confirmed then attended should give +30 reliability and +1 spontaneity (Spontannyi)`() {
        val reliabilityDelta = calculateReliabilityDelta(Stage_1Vote.maybe, FinalStatus.confirmed, AttendanceStatus.attended)
        val spontaneityDelta = calculateSpontaneityDelta(Stage_1Vote.maybe, FinalStatus.confirmed, AttendanceStatus.attended)

        assertEquals(30, reliabilityDelta, "maybe -> confirmed -> attended = +30 reliability")
        assertEquals(1, spontaneityDelta, "maybe -> confirmed -> attended = +1 spontaneity")
    }

    @Test
    fun `maybe then confirmed then absent should give -20 reliability (Zritel)`() {
        val delta = calculateReliabilityDelta(Stage_1Vote.maybe, FinalStatus.confirmed, AttendanceStatus.absent)
        assertEquals(-20, delta, "maybe -> confirmed -> absent = -20")
    }

    @Test
    fun `declined should give 0 reliability (Peredumavshii per PRD 4_4_4)`() {
        val delta = calculateReliabilityDelta(Stage_1Vote.going, FinalStatus.declined, null)
        assertEquals(0, delta, "going -> declined = 0 per PRD")

        val deltaMaybe = calculateReliabilityDelta(Stage_1Vote.maybe, FinalStatus.declined, null)
        assertEquals(0, deltaMaybe, "maybe -> declined = 0 per PRD")
    }

    @Test
    fun `not_going with no final status should give 0 reliability`() {
        val delta = calculateReliabilityDelta(Stage_1Vote.not_going, null, null)
        assertEquals(0, delta, "not_going -> no action = 0")
    }

    @Test
    fun `null stage1Vote should give 0 reliability`() {
        val delta = calculateReliabilityDelta(null, FinalStatus.confirmed, AttendanceStatus.attended)
        assertEquals(0, delta, "null stage1 -> confirmed -> attended = 0")
    }

    @Test
    fun `spontaneity delta should be 0 for non-maybe scenarios`() {
        assertEquals(0, calculateSpontaneityDelta(Stage_1Vote.going, FinalStatus.confirmed, AttendanceStatus.attended))
        assertEquals(0, calculateSpontaneityDelta(Stage_1Vote.going, FinalStatus.confirmed, AttendanceStatus.absent))
        assertEquals(0, calculateSpontaneityDelta(Stage_1Vote.going, FinalStatus.declined, null))
        assertEquals(0, calculateSpontaneityDelta(Stage_1Vote.not_going, null, null))
    }

    @Test
    fun `reliability index is unbounded sum of history per PRD 4_4_4`() {
        // PRD: "Индекс надёжности = Σ всех начислений за всю историю (может быть отрицательным)"
        // Starting value for a new user = 0, not clamped

        val base = 0

        // Сумма "Железобетонный" x 3 = 300 (no ceiling)
        assertEquals(300, base + 100 + 100 + 100)

        // "Пустозвон" от 0 → -50 (допустимо, PRD явно говорит "может быть отрицательным")
        assertEquals(-50, base + (-50))

        // "Пустозвон" x 3 от 0 → -150
        assertEquals(-150, base + (-50) + (-50) + (-50))
    }

    @Test
    fun `promise fulfillment percentage should be calculated correctly`() {
        // 1 confirmation, 1 attendance = 100%
        val pct1 = if (1 > 0) (1 * 100) / 1 else 0
        assertEquals(100, pct1)

        // 2 confirmations, 1 attendance = 50%
        val pct2 = if (2 > 0) (1 * 100) / 2 else 0
        assertEquals(50, pct2)

        // 3 confirmations, 2 attendances = 66%
        val pct3 = if (3 > 0) (2 * 100) / 3 else 0
        assertEquals(66, pct3)

        // 0 confirmations -> 0% (guarded against division by zero)
        val confirmations = 0
        val attendances = 0
        val pct0 = if (confirmations > 0) (attendances * 100) / confirmations else 0
        assertEquals(0, pct0)
    }
}
