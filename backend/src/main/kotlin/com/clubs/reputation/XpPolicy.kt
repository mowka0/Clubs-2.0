package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationKind
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * P1b XP / уровни / бейджи — pure formula. A SEPARATE channel from Trust: a global, account-level
 * gamification score for the PERSON. It only accumulates (a broken promise is 0 XP, never a minus)
 * and is derived ON READ from the same ledger as Trust (no new table/query).
 *
 * XP rewards PARTICIPATION ONLY (decided 2026-06-14): the kept kinds + a diversity bonus. The old
 * organizer bonus (+15 event / +8 skladchina) was dropped — "organizer result" is a signal about
 * the PLACE and lives in the club-quality track ("Надёжность организатора" axis), not in personal
 * gamification. Anti-farm is inherited for free: the owner has no ledger rows in their own club
 * (rule 1), so they accrue XP only by participating in clubs they don't own.
 *
 * Visibility (§9.1): `self` sees exact XP + progress + badges; `others` see only the level name
 * (the others-tier projection is applied at the DTO boundary, not here).
 */
object XpPolicy {

    /** XP for one kept ledger outcome. Broken/neutral kinds yield 0 (XP never goes down). */
    fun kindXp(kind: ReputationKind): Int = when (kind) {
        ReputationKind.ironclad -> 10
        ReputationKind.spontaneous -> 8
        ReputationKind.skladchina_paid -> 3
        else -> 0
    }

    /** Bonus for the first kept outcome in each NEW club (distinct club_id with ≥1 kept). */
    const val DIVERSITY_BONUS = 20

    /** Trust at/above this counts a (track-record) club as "reliable" for the trust badges. */
    private const val RELIABLE_TRUST = TrustPolicy.RELIABLE_THRESHOLD // 70

    /** 10 level names, index 0..9. Index 0 (Гость) is the floor — every account starts here. */
    val LEVEL_NAMES = listOf(
        "Гость", "Свой", "Участник", "Завсегдатай", "Активист",
        "Энтузиаст", "Душа компании", "Столп сообщества", "Легенда", "Амбассадор"
    )

    /** XP threshold to reach level index [i] (0-based): round(50·i^2). Level 0 = 0 XP.
     *  Curve calibrated 2026-06-14 (40·i^1.85 → 50·i^2) to make higher levels rarer. */
    fun levelThreshold(i: Int): Int = (50.0 * i.toDouble().pow(2.0)).roundToInt()

    /** 0-based level index for a given XP — the highest level whose threshold is reached. */
    fun levelIndexFor(xp: Int): Int =
        LEVEL_NAMES.indices.last { xp >= levelThreshold(it) }

    /** Counts/standings needed to evaluate XP total and badges, computed once from the ledger. */
    data class XpStats(
        val ironcladCount: Int,
        val spontaneousCount: Int,
        val skladchinaPaidCount: Int,
        /** distinct club_id with ≥1 kept outcome — the diversity multiplier. */
        val distinctKeptClubs: Int,
        /** clubs with a shown track record (outcomeCount ≥ N) and Trust ≥ RELIABLE_TRUST. */
        val reliableClubs: Int,
        /** highest per-club Trust among clubs with a shown track record (0 if none). */
        val maxTrustWithRecord: Int
    ) {
        val totalKept: Int get() = ironcladCount + spontaneousCount + skladchinaPaidCount
    }

    /** Total XP = Σ kept-kind XP + diversity bonus per distinct kept club. */
    fun totalXp(stats: XpStats): Int =
        stats.ironcladCount * kindXp(ReputationKind.ironclad) +
            stats.spontaneousCount * kindXp(ReputationKind.spontaneous) +
            stats.skladchinaPaidCount * kindXp(ReputationKind.skladchina_paid) +
            stats.distinctKeptClubs * DIVERSITY_BONUS

    enum class BadgeFamily { PARTICIPATION, DIVERSITY, TRUST }

    /** A badge = a passed threshold (not a raw score). Thresholds are calibratable. */
    data class Badge(
        val id: String,
        val name: String,
        val family: BadgeFamily,
        val earned: (XpStats) -> Boolean
    )

    // Thresholds calibrated 2026-06-14 to make badges rarer. `first_step` and `reliable_1` stay
    // attainable on purpose (a new member needs a visible starter goal); the rest are real milestones.
    val BADGES: List<Badge> = listOf(
        Badge("first_step", "Первый шаг", BadgeFamily.PARTICIPATION) { it.totalKept >= 1 },
        Badge("ironclad_20", "Железобетон", BadgeFamily.PARTICIPATION) { it.ironcladCount >= 20 },
        Badge("spontaneous_10", "Лёгок на подъём", BadgeFamily.PARTICIPATION) { it.spontaneousCount >= 10 },
        Badge("payer_8", "Надёжный плательщик", BadgeFamily.PARTICIPATION) { it.skladchinaPaidCount >= 8 },
        Badge("diverse_5", "Разносторонний", BadgeFamily.DIVERSITY) { it.distinctKeptClubs >= 5 },
        Badge("diverse_8", "Вездесущий", BadgeFamily.DIVERSITY) { it.distinctKeptClubs >= 8 },
        Badge("reliable_1", "Надёжный", BadgeFamily.TRUST) { it.reliableClubs >= 1 },
        Badge("rock_solid", "Кремень", BadgeFamily.TRUST) { it.maxTrustWithRecord >= 95 },
        Badge("reliable_5", "Столп доверия", BadgeFamily.TRUST) { it.reliableClubs >= 5 }
    )

    /** Earned badges for the given stats, in declaration order. */
    fun badgesFor(stats: XpStats): List<Badge> = BADGES.filter { it.earned(stats) }

    fun isReliable(trust: Int): Boolean = trust >= RELIABLE_TRUST
}
