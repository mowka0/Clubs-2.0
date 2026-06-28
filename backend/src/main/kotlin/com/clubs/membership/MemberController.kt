package com.clubs.membership

import com.clubs.award.AwardDto
import com.clubs.award.AwardService
import com.clubs.award.AwardSuggestionDto
import com.clubs.award.GrantAwardRequest
import com.clubs.common.auth.RequiresOrganizer
import com.clubs.common.security.AuthenticatedUser
import com.clubs.user.MemberProfileDto
import jakarta.validation.Valid
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

@RestController
@RequestMapping("/api/clubs")
class MemberController(
    private val memberService: MemberService,
    private val accessGateService: AccessGateService,
    private val awardService: AwardService
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

    // De-Stars B+C: reject a paid join (instead of «Взнос получен»). Owner-only; removes the frozen
    // member, refund is the organizer's offline responsibility. Reason optional.
    @RequiresOrganizer(clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/reject-dues")
    fun rejectMember(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody(required = false) request: RejectDuesRequest?,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.rejectMember(clubId, userId, caller.userId, request?.reason))

    @RequiresOrganizer(clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/dues-unpaid")
    fun unmarkDues(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.unmarkDues(clubId, userId, caller.userId))

    // Member admin profile (Variant B, S1) — owner-only edits beyond the dues gate.
    @RequiresOrganizer(clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/access-until")
    fun setAccessUntil(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: SetAccessUntilRequest,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.setAccessUntil(clubId, userId, request.until, caller.userId))

    @RequiresOrganizer(clubIdParam = "clubId")
    @PatchMapping("/{clubId}/members/{userId}/note")
    fun updateNote(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: UpdateNoteRequest,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.updateNote(clubId, userId, request.note, caller.userId))

    // De-Stars: the member declares they paid their off-platform dues (sbp + screenshot, or cash).
    // Member self-service — NO organizer gate; the service acts on the caller's own frozen membership and
    // creates a claim the organizer reviews. Access still opens only via «Взнос получен» (markDuesPaid).
    @PostMapping("/{clubId}/dues-claim")
    fun claimDues(
        @PathVariable clubId: UUID,
        @Valid @RequestBody request: ClaimDuesRequest,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.claimDues(clubId, caller.userId, request.method, request.proofUrl))

    // Member admin profile (Variant B, S2) — club-local awards. Grant/revoke is organizer-only;
    // the awards themselves are public on the member card (served via MemberProfileDto, see MemberService).
    @RequiresOrganizer(clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/awards")
    fun grantAward(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: GrantAwardRequest,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<AwardDto> =
        ResponseEntity.ok(awardService.grant(clubId, userId, request.emoji, request.label, caller.userId))

    @RequiresOrganizer(clubIdParam = "clubId")
    @DeleteMapping("/{clubId}/members/{userId}/awards/{awardId}")
    fun revokeAward(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @PathVariable awardId: UUID,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<Void> {
        awardService.revoke(clubId, userId, awardId, caller.userId)
        return ResponseEntity.noContent().build()
    }

    // Autocomplete for the grant form («как интересы»): distinct past awards in this club. Organizer-only,
    // since only the organizer reaches the grant form (@RequiresMembership would leak the club's award set).
    @RequiresOrganizer(clubIdParam = "clubId")
    @GetMapping("/{clubId}/award-suggestions")
    fun awardSuggestions(@PathVariable clubId: UUID): ResponseEntity<List<AwardSuggestionDto>> =
        ResponseEntity.ok(awardService.getSuggestions(clubId))
}
