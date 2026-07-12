package com.clubs.membership

import com.clubs.common.security.AuthenticatedUser
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Кросс-клубовые представления организатора над membership (de-Stars, слой 2). Привязан к пользователю
 * (а не к конкретному клубу), поэтому живёт вне маппинга `/api/clubs` из MemberController. Managed-скоуп
 * (co-organizers У-5) проверяется внутри запроса (владелец ИЛИ активный со-орг клуба), так что
 * не-менеджер просто получает пустой список — без 403.
 */
@RestController
@RequestMapping("/api/users/me/organizer")
class OrganizerMembersController(private val memberService: MemberService) {

    /** Кросс-клубовое «Ждут оплаты»: участники без доступа по managed-клубам вызывающего, ждущие подтверждения взноса. */
    @GetMapping("/awaiting-dues")
    fun awaitingDues(
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<List<OrganizerDuesMemberDto>> =
        ResponseEntity.ok(memberService.getOrganizerAwaitingDues(caller.userId))
}
