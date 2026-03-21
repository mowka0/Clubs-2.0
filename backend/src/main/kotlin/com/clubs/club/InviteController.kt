package com.clubs.club

import com.clubs.common.security.AuthenticatedUser
import com.clubs.membership.MembershipDto
import com.clubs.membership.MembershipService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class InviteController(
    private val clubService: ClubService,
    private val membershipService: MembershipService
) {

    @GetMapping("/api/invite/{code}")
    fun getClubByInvite(
        @PathVariable code: String
    ): ResponseEntity<ClubDetailDto> =
        ResponseEntity.ok(clubService.getClubByInviteCode(code))

    @PostMapping("/api/invite/{code}/join")
    fun joinByInvite(
        @PathVariable code: String,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(membershipService.joinByInviteCode(code, user.userId))

    @PostMapping("/api/clubs/{id}/regenerate-invite")
    fun regenerateInvite(
        @PathVariable id: java.util.UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ClubDetailDto> =
        ResponseEntity.ok(clubService.regenerateInviteLink(id, user.userId))
}
