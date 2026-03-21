package com.clubs.auth

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/telegram")
    fun authenticate(
        @RequestBody @Valid request: AuthRequest
    ): ResponseEntity<AuthResponse> =
        ResponseEntity.ok(authService.authenticate(request))
}
