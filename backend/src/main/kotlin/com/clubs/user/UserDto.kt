package com.clubs.user

import java.util.UUID

data class UserDto(
    val id: UUID,
    val telegramId: Long,
    val telegramUsername: String?,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val city: String?
)
