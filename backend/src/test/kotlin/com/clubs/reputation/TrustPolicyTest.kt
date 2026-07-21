package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Locks the P1b Trust formula to the numbers the design session simulated and the PO approved
 * (docs/modules/reputation-v2.md § P1b). Pure unit test — no DB, deterministic via a fixed NOW.
 */
class TrustPolicyTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-06-13T12:00:00Z")

    private fun out(kind: ReputationKind, ageDays: Long): TrustPolicy.Outcome =
        TrustPolicy.Outcome(kind, now.minusDays(ageDays))

    private val IRONCLAD = ReputationKind.ironclad      // kept
    private val NO_SHOW = ReputationKind.no_show        // broke
    private val UNRESOLVED = ReputationKind.confirmed_unresolved // neutral

    // --- per-club Trust: the simulated contract ---

    @Test
    fun `new user with no track record starts at the optimistic prior (85)`() {
        assertEquals(85, TrustPolicy.perClubTrust(emptyList(), now))
    }

    @Test
    fun `a single kept promise sits high (~89)`() {
        assertEquals(89, TrustPolicy.perClubTrust(listOf(out(IRONCLAD, 10)), now))
    }

    @Test
    fun `a single early slip does NOT crater (S2 = 52, not near zero)`() {
        assertEquals(52, TrustPolicy.perClubTrust(listOf(out(NO_SHOW, 5)), now))
    }

    @Test
    fun `three kept promises pass the display threshold high (S3 = 92)`() {
        val outcomes = listOf(out(IRONCLAD, 10), out(IRONCLAD, 10), out(IRONCLAD, 10))
        assertEquals(92, TrustPolicy.perClubTrust(outcomes, now))
    }

    @Test
    fun `five kept against one broken stays healthy (S4 = 76)`() {
        val outcomes = List(5) { out(IRONCLAD, 10) } + out(NO_SHOW, 10)
        assertEquals(76, TrustPolicy.perClubTrust(outcomes, now))
    }

    @Test
    fun `a PATTERN of broken promises is punished below 40 (S7)`() {
        val outcomes = List(3) { out(NO_SHOW, 10) }
        val trust = TrustPolicy.perClubTrust(outcomes, now)
        assertEquals(30, trust)
        assertTrue(trust < 40, "a pattern of no-shows must drop below 40, was $trust")
    }

    @Test
    fun `an old sin decays away as recent behaviour recovers (S6 = 89)`() {
        val outcomes = listOf(out(NO_SHOW, 300)) + List(3) { out(IRONCLAD, 10) }
        assertEquals(89, TrustPolicy.perClubTrust(outcomes, now))
    }

    @Test
    fun `neutral outcomes (disputed unmarked) are excluded from the denominator`() {
        val withNeutrals = List(3) { out(IRONCLAD, 10) } + List(2) { out(UNRESOLVED, 1) }
        val withoutNeutrals = List(3) { out(IRONCLAD, 10) }
        assertEquals(
            TrustPolicy.perClubTrust(withoutNeutrals, now),
            TrustPolicy.perClubTrust(withNeutrals, now),
            "confirmed_unresolved must not move Trust"
        )
    }

    @Test
    fun `kind classification matches kept-broke-neutral`() {
        listOf(ReputationKind.ironclad, ReputationKind.spontaneous, ReputationKind.skladchina_paid)
            .forEach { assertEquals(TrustPolicy.TrustClass.KEPT, TrustPolicy.classOf(it), "$it") }
        // open_no_show (V63): очков меньше (−100), но для Trust это такое же broke.
        listOf(ReputationKind.no_show, ReputationKind.spectator, ReputationKind.skladchina_expired, ReputationKind.open_no_show)
            .forEach { assertEquals(TrustPolicy.TrustClass.BROKE, TrustPolicy.classOf(it), "$it") }
        listOf(ReputationKind.confirmed_unresolved, ReputationKind.skladchina_declined)
            .forEach { assertEquals(TrustPolicy.TrustClass.NEUTRAL, TrustPolicy.classOf(it), "$it") }
    }

    // --- global "надёжен в N из M клубов" ---

    private fun club(trust: Int, outcome: Int, ageDays: Long = 10) =
        TrustPolicy.ClubStanding(trust, outcome, now.minusDays(ageDays))

    @Test
    fun `no track record anywhere yields no number (M = 0)`() {
        assertEquals(TrustPolicy.GlobalTrust(0, 0, null), TrustPolicy.global(emptyList(), now))
        // a single club below the display threshold is NOT track record
        val r = TrustPolicy.global(listOf(club(trust = 85, outcome = 2)), now)
        assertEquals(0, r.trackRecordClubs)
        assertNull(r.score)
    }

    @Test
    fun `reliable in all three of three`() {
        val r = TrustPolicy.global(List(3) { club(trust = 90, outcome = 10) }, now)
        assertEquals(3, r.reliableClubs)
        assertEquals(3, r.trackRecordClubs)
        assertEquals(90, r.score)
    }

    @Test
    fun `single tracked club drives the number directly`() {
        val r = TrustPolicy.global(listOf(club(trust = 90, outcome = 12)), now)
        assertEquals(1, r.reliableClubs)
        assertEquals(1, r.trackRecordClubs)
        assertEquals(90, r.score)
    }

    @Test
    fun `mediocre-everywhere reads as 0 of M even though the number is mid (P4)`() {
        val r = TrustPolicy.global(List(5) { club(trust = 65, outcome = 5) }, now)
        assertEquals(0, r.reliableClubs, "Trust 65 < 70 → not reliable")
        assertEquals(5, r.trackRecordClubs)
        assertEquals(65, r.score)
    }

    @Test
    fun `one bad club drags but does not dominate (2 of 3)`() {
        val standings = listOf(club(90, 10), club(90, 10), club(20, 10))
        val r = TrustPolicy.global(standings, now)
        assertEquals(2, r.reliableClubs)
        assertEquals(3, r.trackRecordClubs)
        assertTrue(r.score!! in 60..70, "bad club drags toward the middle, was ${r.score}")
    }

    // --- water-filling cap ---

    @Test
    fun `cap redistributes an over-weight club's excess to the rest`() {
        assertEquals(listOf(0.5, 0.25, 0.25), TrustPolicy.capNormalize(listOf(0.8, 0.1, 0.1), 0.5))
    }

    @Test
    fun `cap leaves already-balanced weights untouched and forces a singleton to 1`() {
        assertEquals(listOf(0.5, 0.5), TrustPolicy.capNormalize(listOf(0.5, 0.5), 0.5))
        assertEquals(listOf(1.0), TrustPolicy.capNormalize(listOf(0.9), 0.5))
    }
}
