package com.clubs.subscription

import com.clubs.common.security.AuthenticatedUser
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/subscriptions")
class SubscriptionController(
    private val subscriptionService: SubscriptionService,
) {

    private val log = LoggerFactory.getLogger(SubscriptionController::class.java)

    @GetMapping("/status")
    fun status(@AuthenticationPrincipal user: AuthenticatedUser): ResponseEntity<SubscriptionStatusDto> =
        ResponseEntity.ok(subscriptionService.status(user.userId))

    @GetMapping("/plans")
    fun plans(@AuthenticationPrincipal user: AuthenticatedUser): ResponseEntity<List<PlanOptionDto>> =
        ResponseEntity.ok(subscriptionService.listPlans())

    @PostMapping
    fun subscribe(
        @RequestBody @Valid request: CreateSubscriptionRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): ResponseEntity<SubscriptionStatusDto> {
        log.info("Subscribe: userId={} plan={} role={}", user.userId, request.plan, request.role)
        return ResponseEntity.ok(subscriptionService.subscribe(user.userId, request))
    }

    @PostMapping("/cancel")
    fun cancel(@AuthenticationPrincipal user: AuthenticatedUser): ResponseEntity<SubscriptionStatusDto> {
        log.info("Cancel subscription: userId={}", user.userId)
        return ResponseEntity.ok(subscriptionService.cancel(user.userId))
    }

    /**
     * Provider webhook. permitAll in SecurityConfig — authenticity is the provider signature, not a JWT.
     * Raw body so the (future) signature check sees exactly what was signed.
     */
    @PostMapping("/webhook")
    fun webhook(
        @RequestBody body: String,
        @RequestHeader(name = "X-Signature", required = false) signature: String?,
    ): ResponseEntity<Void> {
        subscriptionService.handleWebhook(body, signature)
        return ResponseEntity.ok().build()
    }
}
