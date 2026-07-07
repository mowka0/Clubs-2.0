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

    // Шлюз доступа от организатора (de-Stars, Slice 2). Только владелец через @RequiresOrganizer; сервис
    // защищает переход статуса (409 при проигранной гонке) и запрещает управлять организатором.
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

    // De-Stars B+C: отклонить платное вступление (вместо «Взнос получен»). Только владелец; удаляет frozen-
    // участника, возврат денег — офлайн-обязанность организатора. Причина опциональна.
    @RequiresOrganizer(clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/reject-dues")
    fun rejectMember(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody(required = false) request: RejectDuesRequest?,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.rejectMember(clubId, userId, caller.userId, request?.reason))

    // Кик организатором: удалить участника из клуба за проступок (причина обязательна, уходит в DM участнику).
    // Только владелец; отличается от «Закрыть доступ» (freeze) — здесь membership отменяется и доступ отзывается.
    @RequiresOrganizer(clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/remove")
    fun removeMember(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: RemoveMemberRequest,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.removeMember(clubId, userId, caller.userId, request.reason))

    @RequiresOrganizer(clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/dues-unpaid")
    fun unmarkDues(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.unmarkDues(clubId, userId, caller.userId))

    // Member admin profile (Variant B, S1) — правки, доступные только владельцу, сверх шлюза оплаты взносов.
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

    // De-Stars: участник заявляет, что оплатил офлайн-взнос (sbp + скриншот, либо cash).
    // Самообслуживание участника — БЕЗ гейта организатора; сервис действует над собственным frozen-membership
    // вызывающего и создаёт заявку, которую проверяет организатор. Доступ всё равно открывается только через
    // «Взнос получен» (markDuesPaid).
    @PostMapping("/{clubId}/dues-claim")
    fun claimDues(
        @PathVariable clubId: UUID,
        @Valid @RequestBody request: ClaimDuesRequest,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.claimDues(clubId, caller.userId, request.method, request.proofUrl))

    // Member admin profile (Variant B, S2) — награды локальные для клуба. Выдача/отзыв — только организатор;
    // сами награды публичны на карточке участника (отдаются через MemberProfileDto, см. MemberService).
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

    // Автодополнение для формы выдачи награды («как интересы»): уникальные прошлые награды в этом клубе.
    // Только организатор, поскольку до формы выдачи доходит только он (@RequiresMembership раскрыл бы
    // набор наград клуба всем участникам).
    @RequiresOrganizer(clubIdParam = "clubId")
    @GetMapping("/{clubId}/award-suggestions")
    fun awardSuggestions(@PathVariable clubId: UUID): ResponseEntity<List<AwardSuggestionDto>> =
        ResponseEntity.ok(awardService.getSuggestions(clubId))
}
