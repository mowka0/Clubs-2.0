package com.clubs.membership

import com.clubs.common.security.AuthenticatedUser
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Cross-club organizer views over memberships (de-Stars Slice 2). User-scoped (not under a specific
 * club), so it lives outside MemberController's `/api/clubs` mapping. Ownership is enforced inside the
 * query (filtered by `clubs.owner_id` = caller), so a non-owner simply gets an empty list — no 403.
 */
@RestController
@RequestMapping("/api/users/me/organizer")
class OrganizerMembersController(private val memberService: MemberService) {

    /** Cross-club «Ждут оплаты»: frozen members across the caller's owned clubs, pending a dues confirm. */
    @GetMapping("/awaiting-dues")
    fun awaitingDues(
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<List<OrganizerDuesMemberDto>> =
        ResponseEntity.ok(memberService.getOrganizerAwaitingDues(caller.userId))
}
