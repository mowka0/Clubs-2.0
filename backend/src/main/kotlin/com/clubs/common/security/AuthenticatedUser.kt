package com.clubs.common.security

import java.util.UUID

data class AuthenticatedUser(
    val userId: UUID,
    val telegramId: Long
)
