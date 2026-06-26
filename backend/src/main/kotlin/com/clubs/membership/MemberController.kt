package com.clubs.membership

import com.clubs.common.auth.RequiresOrganizer
import com.clubs.common.security.AuthenticatedUser
import com.clubs.user.MemberProfileDto
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/clubs")
class MemberController(
    private val memberService: MemberService,
    private val accessGateService: AccessGateService
) {

    @GetMapping("/{clubId}/members")
    fun getMembers(
        @PathVariable clubId: UUID,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<List<MemberListItemDto>> =
        ResponseEntity.ok(memberService.getClubMembers(clubId, caller.userId))

    @GetMapping("/{clubId}/members/{userId}")
    fun getMemberProfile(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MemberProfileDto> =
        ResponseEntity.ok(memberService.getMemberProfile(clubId, userId, caller.userId))

    // Red-dot badge feed (de-Stars): how many members' access ends within the week. Separate path
    // (not under /members/{userId}) to avoid the path-variable colliding with a member id.
    @RequiresOrganizer(clubIdParam = "clubId")
    @GetMapping("/{clubId}/member-attention")
    fun memberAttention(@PathVariable clubId: UUID): ResponseEntity<MemberAttentionDto> =
        ResponseEntity.ok(memberService.getAttention(clubId))

    // Organizer access gate (de-Stars, Slice 2). Owner-only via @RequiresOrganizer; the service
    // guards the status transition (409 on a lost race) and rejects managing the organizer.
    @RequiresOrganizer(clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/freeze")
    fun freezeMember(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.freezeAccess(clubId, userId, caller.userId))

    @RequiresOrganizer(clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/unfreeze")
    fun unfreezeMember(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.unfreezeAccess(clubId, userId, caller.userId))

    @RequiresOrganizer(clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/dues-paid")
    fun markDuesPaid(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.markDuesPaid(clubId, userId, caller.userId))

    @RequiresOrganizer(clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/dues-unpaid")
    fun unmarkDues(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.unmarkDues(clubId, userId, caller.userId))
}
