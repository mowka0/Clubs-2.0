package com.clubs.membership

import com.clubs.common.security.AuthenticatedUser
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/clubs")
class MembershipController(private val membershipService: MembershipService) {

    private val log = LoggerFactory.getLogger(MembershipController::class.java)

    @PostMapping("/{id}/join")
    fun joinClub(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<Any> {
        log.info("Join club {}: userId={}", id, user.userId)
        return toHttpResponse(membershipService.joinOpenClub(id, user.userId))
    }

    @PostMapping("/{id}/cancel")
    fun cancelMembership(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<MembershipDto> {
        log.info("Cancel membership in club {}: userId={}", id, user.userId)
        return ResponseEntity.ok(membershipService.cancelMembership(id, user.userId))
    }
}

internal fun toHttpResponse(result: JoinResult): ResponseEntity<Any> = when (result) {
    is JoinResult.Joined -> ResponseEntity.status(HttpStatus.CREATED).body(result.membership)
    is JoinResult.PendingPayment -> ResponseEntity.status(HttpStatus.ACCEPTED).body(result.dto)
}
