package com.clubs.award

import java.time.OffsetDateTime
import java.util.UUID

/**
 * A club-local award (member admin profile S2). Pure cosmetic recognition an organizer grants to a
 * member — NOT a global earned-badge; never affects reputation/XP/rank (R4). Visible to all members (R3).
 */
data class Award(
    val id: UUID,
    val clubId: UUID,
    val userId: UUID,
    val emoji: String,
    val label: String,
    val awardedBy: UUID,
    val awardedAt: OffsetDateTime
)

/** Distinct (emoji, label) ever granted in a club — the autocomplete source for the grant form («как интересы»). */
data class AwardSuggestion(
    val emoji: String,
    val label: String
)
