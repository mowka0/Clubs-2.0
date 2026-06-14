package com.clubs.reputation

/**
 * The authenticated user's gamification panel (`self` view, H8): exact XP, current level + progress
 * to the next, and earned badges. Derived on-read from the ledger by [XpService]. The `others`
 * projection (level name only) is a separate, narrower view applied where member cards are built.
 */
data class GamificationDto(
    val xp: Int,
    /** 1-based level number (1..10). */
    val level: Int,
    val levelName: String,
    /** null when the user is at the max level. */
    val nextLevelName: String?,
    /** XP accumulated within the current level (xp − current-level threshold). */
    val xpIntoLevel: Int,
    /** XP span of the current level (next threshold − current threshold); null at max level. */
    val xpSpanToNext: Int?,
    val badges: List<BadgeDto>
)

data class BadgeDto(
    val id: String,
    val name: String,
    val family: String
)
