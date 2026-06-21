package com.clubs.clubquality

import java.time.OffsetDateTime
import java.util.UUID

/**
 * HTTP DTO for the win-back roster — `GET /api/clubs/{clubId}/churned-members` (owner-only).
 * Enough to render a member row and open the existing profile card by [userId].
 */
data class ChurnedMemberDto(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val leftAt: OffsetDateTime,
)
