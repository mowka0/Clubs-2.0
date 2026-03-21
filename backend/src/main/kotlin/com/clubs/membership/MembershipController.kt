package com.clubs.membership

import com.clubs.common.security.AuthenticatedUser
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

    @PostMapping("/{id}/join")
    fun joinClub(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<MembershipDto> {
        val membership = membershipService.joinOpenClub(id, user.userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(membership)
    }
}
