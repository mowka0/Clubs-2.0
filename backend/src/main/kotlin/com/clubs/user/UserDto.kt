package com.clubs.user

import java.time.OffsetDateTime
import java.util.UUID

data class UserDto(
    val id: UUID,
    val telegramId: Long,
    val telegramUsername: String?,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val city: String?,
    val country: String?,
    val bio: String?,
    /** Когда прошёл онбординг; null — ещё не проходил, фронт показывает карусель вместо приложения. */
    val onboardedAt: OffsetDateTime?
)
