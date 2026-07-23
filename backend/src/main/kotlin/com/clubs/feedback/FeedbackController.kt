package com.clubs.feedback

import com.clubs.common.security.AuthenticatedUser
import com.clubs.feedback.dto.SubmitFeedbackRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/feedback")
class FeedbackController(private val feedbackService: FeedbackService) {

    /** Баг-репорт/пожелание от любого аутентифицированного пользователя → DM саппорт-аккаунту. */
    @PostMapping
    fun submit(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody request: SubmitFeedbackRequest,
    ): ResponseEntity<Void> {
        feedbackService.submitFeedback(user.userId, request)
        return ResponseEntity.noContent().build()
    }
}
