package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the P1b XP contract numbers (weights, level thresholds, badge predicates). XP is
 * participation-only — organizer bonuses were dropped (see reputation-v2.md § H3).
 */
class XpPolicyTest {

    @Test
    fun `kept-kind weights, negatives and neutrals score zero`() {
        assertEquals(10, XpPolicy.kindXp(ReputationKind.ironclad))
        assertEquals(8, XpPolicy.kindXp(ReputationKind.spontaneous))
        assertEquals(3, XpPolicy.kindXp(ReputationKind.skladchina_paid))
        // broken / neutral kinds never add XP (XP only accumulates)
        assertEquals(0, XpPolicy.kindXp(ReputationKind.no_show))
        assertEquals(0, XpPolicy.kindXp(ReputationKind.spectator))
        assertEquals(0, XpPolicy.kindXp(ReputationKind.skladchina_expired))
        assertEquals(0, XpPolicy.kindXp(ReputationKind.confirmed_unresolved))
        assertEquals(0, XpPolicy.kindXp(ReputationKind.skladchina_declined))
    }

    @Test
    fun `level thresholds follow round(50 n^2) — contract numbers`() {
        val expected = listOf(0, 50, 200, 450, 800, 1250, 1800, 2450, 3200, 4050)
        assertEquals(expected, (0..9).map { XpPolicy.levelThreshold(it) })
        assertEquals(10, XpPolicy.LEVEL_NAMES.size)
        assertEquals("Гость", XpPolicy.LEVEL_NAMES.first())
        assertEquals("Амбассадор", XpPolicy.LEVEL_NAMES.last())
    }

    @Test
    fun `levelIndexFor picks the highest reached threshold`() {
        assertEquals(0, XpPolicy.levelIndexFor(0))     // Гость floor
        assertEquals(0, XpPolicy.levelIndexFor(49))
        assertEquals(1, XpPolicy.levelIndexFor(50))    // Свой
        assertEquals(1, XpPolicy.levelIndexFor(199))
        assertEquals(2, XpPolicy.levelIndexFor(200))   // Участник
        assertEquals(9, XpPolicy.levelIndexFor(4050))  // Амбассадор
        assertEquals(9, XpPolicy.levelIndexFor(999_999)) // capped at max
    }

    @Test
    fun `totalXp sums kept-kind XP plus diversity bonus per distinct kept club`() {
        // 3 ironclad (30) + 2 spontaneous (16) + 1 skladchina_paid (3) across 2 distinct kept clubs (40)
        val stats = statsOf(ironclad = 3, spontaneous = 2, skladchinaPaid = 1, distinctKeptClubs = 2)
        assertEquals(30 + 16 + 3 + 40, XpPolicy.totalXp(stats)) // 89
    }

    @Test
    fun `no kept outcomes yields zero XP (Гость) — XP never goes negative`() {
        val none = statsOf()
        assertEquals(0, XpPolicy.totalXp(none))
        assertEquals(0, XpPolicy.levelIndexFor(XpPolicy.totalXp(none)))
    }

    @Test
    fun `badges are passed-threshold gates across the three families`() {
        val fresh = statsOf(ironclad = 1, distinctKeptClubs = 1)
        assertEquals(setOf("first_step"), XpPolicy.badgesFor(fresh).map { it.id }.toSet())

        val veteran = statsOf(
            ironclad = 25, spontaneous = 12, skladchinaPaid = 10,
            distinctKeptClubs = 10, reliableClubs = 6, maxTrustWithRecord = 96
        )
        val ids = XpPolicy.badgesFor(veteran).map { it.id }.toSet()
        assertEquals(
            setOf("first_step", "ironclad_20", "spontaneous_10", "payer_8", "diverse_5", "diverse_8", "reliable_1", "rock_solid", "reliable_5"),
            ids
        )

        // boundary: reliable but not rock-solid, diverse_5 but not diverse_8, reliable_1 but not reliable_5
        val mid = statsOf(ironclad = 2, distinctKeptClubs = 5, reliableClubs = 1, maxTrustWithRecord = 80)
        val midIds = XpPolicy.badgesFor(mid).map { it.id }.toSet()
        assertTrue("reliable_1" in midIds && "diverse_5" in midIds)
        assertFalse("rock_solid" in midIds || "diverse_8" in midIds || "reliable_5" in midIds)
    }

    private fun statsOf(
        ironclad: Int = 0,
        spontaneous: Int = 0,
        skladchinaPaid: Int = 0,
        distinctKeptClubs: Int = 0,
        reliableClubs: Int = 0,
        maxTrustWithRecord: Int = 0
    ) = XpPolicy.XpStats(ironclad, spontaneous, skladchinaPaid, distinctKeptClubs, reliableClubs, maxTrustWithRecord)
}
