package com.clubs.chatlink

import com.clubs.common.security.AuthenticatedUser
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Привязка телеграм-чата к клубу — владельческий API таба «Чат» в «Управлении клубом».
 * Ownership-гейт на каждом методе внутри ChatLinkService (403 не-владельцу).
 */
@RestController
@RequestMapping("/api/clubs/{clubId}/chat-link")
class ChatLinkController(
    private val chatLinkService: ChatLinkService
) {
    private val log = LoggerFactory.getLogger(ChatLinkController::class.java)

    @GetMapping
    fun getStatus(
        @PathVariable clubId: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ChatLinkStatusDto> =
        ResponseEntity.ok(chatLinkService.getStatus(clubId, user.userId))

    @PostMapping("/refresh")
    fun refresh(
        @PathVariable clubId: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ChatLinkStatusDto> {
        log.info("Chat link refresh requested: clubId={} userId={}", clubId, user.userId)
        return ResponseEntity.ok(chatLinkService.refresh(clubId, user.userId))
    }

    @PatchMapping
    fun update(
        @PathVariable clubId: UUID,
        @RequestBody @Valid request: UpdateChatLinkRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ChatLinkStatusDto> {
        log.info(
            "Chat link toggle: clubId={} doorEnabled={} livePinEnabled={} skladchinaStatusEnabled={} userId={}",
            clubId, request.doorEnabled, request.livePinEnabled, request.skladchinaStatusEnabled, user.userId
        )
        return ResponseEntity.ok(chatLinkService.update(clubId, user.userId, request))
    }

    @DeleteMapping
    fun unlink(
        @PathVariable clubId: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<Void> {
        log.info("Chat unlink requested: clubId={} userId={}", clubId, user.userId)
        chatLinkService.unlink(clubId, user.userId)
        return ResponseEntity.noContent().build()
    }
}
