package com.clubs.analytics

import com.clubs.analytics.dto.TrackRequest
import com.clubs.common.security.AuthenticatedUser
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/track")
class TrackController(private val trackService: TrackService) {

    /** Пачка событий Mini App от любого аутентифицированного пользователя; ничего не возвращает. */
    @PostMapping
    fun track(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody request: TrackRequest,
    ): ResponseEntity<Void> {
        trackService.track(user.userId, request)
        return ResponseEntity.noContent().build()
    }
}
