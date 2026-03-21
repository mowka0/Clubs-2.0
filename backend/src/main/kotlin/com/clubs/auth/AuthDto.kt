package com.clubs.auth

import com.clubs.user.UserDto
import jakarta.validation.constraints.NotBlank

data class AuthRequest(
    @field:NotBlank(message = "initData must not be blank")
    val initData: String
)

data class AuthResponse(
    val token: String,
    val user: UserDto
)
