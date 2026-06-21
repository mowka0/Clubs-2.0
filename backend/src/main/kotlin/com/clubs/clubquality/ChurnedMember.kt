package com.clubs.clubquality

import java.time.OffsetDateTime
import java.util.UUID

/**
 * A former member to win back: left or expired within the retention window and currently NOT an
 * active/grace member of this club (i.e. genuinely gone, not someone who already came back). Backs
 * the expandable «Верните N ушедших» nudge in the owner «Статистика» panel (§9.5).
 *
 * [leftAt] is the most recent departure (`membership_history.occurred_at`), used to order the roster
 * most-recently-gone first.
 */
data class ChurnedMember(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val leftAt: OffsetDateTime,
)
