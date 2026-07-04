package com.clubs.clubquality

import java.time.OffsetDateTime
import java.util.UUID

/**
 * HTTP DTO для ростера win-back — `GET /api/clubs/{clubId}/churned-members` (только владелец).
 * Достаточно, чтобы отрендерить строку участника и открыть существующую карточку профиля по [userId].
 */
data class ChurnedMemberDto(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val leftAt: OffsetDateTime,
)
