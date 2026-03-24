package com.clubs.auth

import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    private val log = LoggerFactory.getLogger(AuthController::class.java)

    @PostMapping("/telegram")
    fun authenticate(
        @RequestBody @Valid request: AuthRequest
    ): ResponseEntity<AuthResponse> {
        log.info("Auth request received, initData length={}", request.initData.length)
        val response = authService.authenticate(request)
        log.info("Auth success: userId={} telegramId={}", response.user.id, response.user.telegramId)
        return ResponseEntity.ok(response)
    }
}
