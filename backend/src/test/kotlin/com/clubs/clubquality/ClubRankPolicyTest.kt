package com.clubs.clubquality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Locks the L3 anti-farm invariants (docs/modules/club-quality.md §10). Pure — no DB, deterministic
 * via a fixed NOW. The numbers here are the v1 PROVISIONAL defaults; if they are recalibrated this
 * test is the single place that changes.
 */
class ClubRankPolicyTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-06-21T12:00:00Z")
    private val thisOwner: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val otherOwners = (1..4).map { UUID.fromString("00000000-0000-0000-0000-0000000000b$it") }

    private fun uid(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-0000000${String.format("%05d", n)}")

    /** A maximally-credible account: mature, full profile, broad cross-owner footprint → credibility 1.0. */
    private fun strongCred(id: UUID): CredibilityInput =
        CredibilityInput(id, now.minusDays(400), true, true, otherOwners.take(3).associateWith { 1 })

    /** A fresh, full-profile, single-OTHER-owner account → ageW .4 × signalW 1 × footprintW .6 = 0.24. */
    private fun freshSingleClub(id: UUID): CredibilityInput =
        CredibilityInput(id, now.minusDays(5), true, true, mapOf(otherOwners[0] to 1))

    private fun acct(id: UUID, ageDays: Long = 5): AccountOutcome = AccountOutcome(id, now.minusDays(ageDays))

    private fun signals(
        owner: UUID = thisOwner,
        category: String = "sport",
        isPaid: Boolean = false,
        core: List<AccountOutcome> = emptyList(),
        payers: List<AccountOutcome> = emptyList(),
        scamPayers: Set<UUID> = emptySet(),
        renewers: List<AccountOutcome> = emptyList(),
        voters: List<AccountOutcome> = emptyList(),
        qualityEvents: List<OffsetDateTime> = emptyList(),
        disputes: List<OffsetDateTime> = emptyList(),
        ghosting: List<OffsetDateTime> = emptyList(),
        churn: Int = 0,
    ): ClubRankSignals = ClubRankSignals(
        clubId = UUID.randomUUID(), ownerId = owner, category = category, isPaid = isPaid,
        clubCreatedAt = now.minusDays(400), core = core, payers = payers, scamPayers = scamPayers,
        renewers = renewers, voters = voters, qualityEvents = qualityEvents, disputes = disputes,
        ghosting = ghosting, autoRejects = emptyList(), skladchinaGhosts = emptyList(), churnEvents90d = churn,
    )

    // ---- Credibility weight ----

    @Test
    fun `mature full-profile broad-footprint account is fully credible`() {
        assertEquals(1.0, ClubRankPolicy.credibility(strongCred(uid(1)), thisOwner, now), 1e-9)
    }

    @Test
    fun `fresh single-club account is cheap but above the floor`() {
        assertEquals(0.24, ClubRankPolicy.credibility(freshSingleClub(uid(1)), thisOwner, now), 1e-9)
    }

    @Test
    fun `naked fresh account falls below CRED_MIN and is excluded`() {
        val naked = CredibilityInput(uid(1), now.minusDays(5), false, false, mapOf(otherOwners[0] to 1))
        // ageW .4 × signalW .6 × footprintW .6 = 0.144 < CRED_MIN(0.2)
        assertTrue(ClubRankPolicy.credibility(naked, thisOwner, now) < ClubRankPolicy.CRED_MIN)
    }

    @Test
    fun `footprintW counts distinct OWNERS, not raw clubs`() {
        val twoOwners = CredibilityInput(uid(1), now.minusDays(400), true, true, mapOf(otherOwners[0] to 1, otherOwners[1] to 1))
        assertEquals(0.85, ClubRankPolicy.credibility(twoOwners, thisOwner, now), 1e-9)
    }

    @Test
    fun `owner-concentrated account is pressed to CRED_MIN regardless of age or profile`() {
        // A puppet whose entire footprint is in THIS owner's clubs is not independent evidence.
        val concentrated = CredibilityInput(uid(1), now.minusDays(400), true, true, mapOf(thisOwner to 6))
        assertEquals(ClubRankPolicy.CRED_MIN, ClubRankPolicy.credibility(concentrated, thisOwner, now), 1e-9)
    }

    // ---- Existence gate ----

    @Test
    fun `a count-8 ring of cheap single-club puppets fails the credibility-weighted gate`() {
        val ring = (1..8).map { uid(it) }
        val cred = ring.associateWith { freshSingleClub(it) }
        val rank = ClubRankPolicy.computeRank(signals(core = ring.map { acct(it) }), cred, now)
        // 8 accounts clear a head-count, but Σcredibility ≈ 1.92 < EFFECTIVE_K(8.0) → UNRANKED.
        assertFalse(rank.isRanked)
        assertEquals(0.0, rank.rankScore, 1e-9)
    }

    @Test
    fun `core accounts below CRED_MIN contribute zero to the gate sum`() {
        // Eight naked fresh single-club accounts, each raw credibility 0.144 < CRED_MIN → effectiveK 0.
        val ring = (1..8).map { uid(it) }
        val cred = ring.associateWith {
            CredibilityInput(it, now.minusDays(5), false, false, mapOf(otherOwners[0] to 1))
        }
        val rank = ClubRankPolicy.computeRank(signals(core = ring.map { acct(it) }), cred, now)
        assertEquals(0.0, rank.effectiveK, 1e-9)
        assertFalse(rank.isRanked)
    }

    @Test
    fun `eight fully-credible core accounts pass the gate and produce a positive score`() {
        val core = (1..8).map { uid(it) }
        val cred = core.associateWith { strongCred(it) }
        val rank = ClubRankPolicy.computeRank(signals(core = core.map { acct(it) }), cred, now)
        assertTrue(rank.isRanked)
        assertTrue(rank.rankScore > 0.0)
        assertEquals(8.0, rank.effectiveK, 1e-9)
    }

    @Test
    fun `skladchina-paid laundering cannot help — only event-attendance core feeds the gate`() {
        // The repository never puts owner-mark-paid accounts into `core` (skladchina_paid is excluded
        // from the ledger read-port). A club with strong payers but no real core stays UNRANKED.
        val payers = (1..10).map { uid(it) }
        val cred = payers.associateWith { strongCred(it) }
        val rank = ClubRankPolicy.computeRank(
            signals(isPaid = true, core = emptyList(), payers = payers.map { acct(it) }),
            cred, now,
        )
        assertFalse(rank.isRanked)
    }

    // ---- Free-club weight redistribution ----

    @Test
    fun `free-club weights drop paying and redistribute, still summing to one`() {
        assertEquals(0.50, ClubRankPolicy.W_DIVERSITY_FREE, 1e-9)
        assertEquals(1.0, ClubRankPolicy.W_DIVERSITY_FREE + ClubRankPolicy.W_DEMAND_FREE + ClubRankPolicy.W_ACTIVITY_FREE, 1e-9)
    }

    // ---- Badge: "★ Топ-5 в категории" gates ----

    private fun ranked(id: Int, owner: UUID, category: String, score: Double): RankedClub =
        RankedClub(uid(id), owner, category, score)

    @Test
    fun `badge is empty when the feature flag is off`() {
        val clubs = (1..8).map { ranked(it, otherOwners[0], "sport", 0.5) }
        assertTrue(ClubRankPolicy.topInCategory(clubs, badgeEnabled = false).isEmpty())
    }

    @Test
    fun `badge is suppressed below the global rank floor`() {
        val clubs = (1..7).map { ranked(it, uid(100 + it), "sport", 0.5) } // 7 < GLOBAL_RANK_FLOOR(8)
        assertTrue(ClubRankPolicy.topInCategory(clubs, badgeEnabled = true).isEmpty())
    }

    @Test
    fun `one owner cannot manufacture a category — per-owner collapse blocks the badge`() {
        // 6 clubs in 'sport' ALL owned by the attacker + 2 elsewhere (to clear the global floor).
        val attackerClubs = (1..6).map { ranked(it, thisOwner, "sport", 0.5) }
        val filler = (7..8).map { ranked(it, uid(200 + it), "food", 0.5) }
        val badged = ClubRankPolicy.topInCategory(attackerClubs + filler, badgeEnabled = true)
        // Attacker's 6 same-owner clubs collapse to 1 in the category → below MIN_CATEGORY_SIZE → no badge.
        assertTrue(badged.isEmpty())
    }

    @Test
    fun `a real category of six distinct owners badges the clearly-separated top five`() {
        val clubs = listOf(
            ranked(1, otherOwners[0], "sport", 0.90),
            ranked(2, otherOwners[1], "sport", 0.80),
            ranked(3, otherOwners[2], "sport", 0.70),
            ranked(4, otherOwners[3], "sport", 0.60),
            ranked(5, uid(50), "sport", 0.50),
            ranked(6, uid(51), "sport", 0.40), // #6 — below the cut, also the selectivity reference
            ranked(7, uid(52), "food", 0.50),
            ranked(8, uid(53), "food", 0.50),
        )
        val badged = ClubRankPolicy.topInCategory(clubs, badgeEnabled = true)
        assertEquals(setOf(uid(1), uid(2), uid(3), uid(4), uid(5)), badged)
    }

    @Test
    fun `a position-5 club below the absolute score floor is not badged`() {
        val clubs = listOf(
            ranked(1, otherOwners[0], "sport", 0.90),
            ranked(2, otherOwners[1], "sport", 0.80),
            ranked(3, otherOwners[2], "sport", 0.70),
            ranked(4, otherOwners[3], "sport", 0.60),
            ranked(5, uid(50), "sport", 0.15), // top-5 by position but < BADGE_SCORE_FLOOR(0.20)
            ranked(6, uid(51), "sport", 0.05),
            ranked(7, uid(52), "food", 0.50),
            ranked(8, uid(53), "food", 0.50),
        )
        val badged = ClubRankPolicy.topInCategory(clubs, badgeEnabled = true)
        assertFalse(badged.contains(uid(50)))
        assertTrue(badged.contains(uid(1)))
    }

    @Test
    fun `no badge when the cut between fifth and sixth is within the selectivity margin`() {
        val clubs = (1..6).map { ranked(it, uid(60 + it), "sport", 0.50) } + // all tied → #5 ≈ #6
            (7..8).map { ranked(it, uid(70 + it), "food", 0.50) }
        val badged = ClubRankPolicy.topInCategory(clubs, badgeEnabled = true)
        assertTrue(badged.none { it in (1..6).map { n -> uid(n) } })
    }

    // ---- Anomaly + scam dampeners ----

    @Test
    fun `a too-clean club at volume is dampened versus an identical club with organic churn`() {
        val core = (1..12).map { uid(it) }
        val cred = core.associateWith { strongCred(it) }
        // churn is an anomaly INPUT only (never a score penalty), so it isolates the dampener: the
        // zero-friction club at volume loses 10% confidence; the one with a normal churn event does not.
        val tooClean = ClubRankPolicy.computeRank(signals(core = core.map { acct(it) }, churn = 0), cred, now)
        val organic = ClubRankPolicy.computeRank(signals(core = core.map { acct(it) }, churn = 1), cred, now)
        assertTrue(tooClean.rankScore < organic.rankScore)
        assertEquals(organic.rankScore * (1.0 - ClubRankPolicy.ANOMALY_STEP), tooClean.rankScore, 1e-9)
    }
}
