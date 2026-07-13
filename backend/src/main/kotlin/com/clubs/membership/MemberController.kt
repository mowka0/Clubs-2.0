package com.clubs.membership

import com.clubs.award.AwardDto
import com.clubs.award.AwardService
import com.clubs.award.AwardSuggestionDto
import com.clubs.award.GrantAwardRequest
import com.clubs.common.auth.ClubCapability
import com.clubs.common.auth.RequiresCapability
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/clubs")
class MemberController(
    private val memberService: MemberService,
    private val accessGateService: AccessGateService,
    private val awardService: AwardService,
    private val memberRoleService: MemberRoleService
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

    // Шлюз доступа (de-Stars, Slice 2). Менеджер клуба (владелец или активный со-орг) через
    // @RequiresCapability(MANAGE_MEMBERS); сервис защищает переход статуса (409 при проигранной гонке) и
    // применяет target-матрицу: со-орг управляет только участниками role=member, организатором не управляет никто.
    @RequiresCapability(ClubCapability.MANAGE_MEMBERS, clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/freeze")
    fun freezeMember(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.freezeAccess(clubId, userId, caller.userId))

    @RequiresCapability(ClubCapability.MANAGE_MEMBERS, clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/unfreeze")
    fun unfreezeMember(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.unfreezeAccess(clubId, userId, caller.userId))

    @RequiresCapability(ClubCapability.MANAGE_MEMBERS, clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/dues-paid")
    fun markDuesPaid(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.markDuesPaid(clubId, userId, caller.userId))

    // De-Stars B+C: отклонить платное вступление (вместо «Взнос получен»). Менеджер клуба; удаляет frozen-
    // участника, возврат денег — офлайн-обязанность организатора. Причина опциональна.
    @RequiresCapability(ClubCapability.MANAGE_MEMBERS, clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/reject-dues")
    fun rejectMember(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody(required = false) request: RejectDuesRequest?,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.rejectMember(clubId, userId, caller.userId, request?.reason))

    // Кик: удалить участника из клуба за проступок (причина обязательна, уходит в DM участнику).
    // Менеджер клуба; отличается от «Закрыть доступ» (freeze) — здесь membership отменяется и доступ отзывается.
    @RequiresCapability(ClubCapability.MANAGE_MEMBERS, clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/remove")
    fun removeMember(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: RemoveMemberRequest,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.removeMember(clubId, userId, caller.userId, request.reason))

    @RequiresCapability(ClubCapability.MANAGE_MEMBERS, clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/dues-unpaid")
    fun unmarkDues(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.unmarkDues(clubId, userId, caller.userId))

    // Member admin profile (Variant B, S1) — правки, доступные менеджеру клуба, сверх шлюза оплаты взносов.
    @RequiresCapability(ClubCapability.MANAGE_MEMBERS, clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/access-until")
    fun setAccessUntil(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: SetAccessUntilRequest,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(accessGateService.setAccessUntil(clubId, userId, request.until, caller.userId))

    @RequiresCapability(ClubCapability.MANAGE_MEMBERS, clubIdParam = "clubId")
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

    // Member admin profile (Variant B, S2) — награды локальные для клуба. Выдача/отзыв — менеджер клуба
    // (target-матрицу по роли цели применяет AwardService); сами награды публичны на карточке участника
    // (отдаются через MemberProfileDto, см. MemberService).
    @RequiresCapability(ClubCapability.GRANT_AWARDS, clubIdParam = "clubId")
    @PostMapping("/{clubId}/members/{userId}/awards")
    fun grantAward(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: GrantAwardRequest,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<AwardDto> =
        ResponseEntity.ok(awardService.grant(clubId, userId, request.emoji, request.label, caller.userId))

    @RequiresCapability(ClubCapability.GRANT_AWARDS, clubIdParam = "clubId")
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
    // Только менеджер клуба, поскольку до формы выдачи доходит только он (@RequiresMembership раскрыл бы
    // набор наград клуба всем участникам).
    @RequiresCapability(ClubCapability.GRANT_AWARDS, clubIdParam = "clubId")
    @GetMapping("/{clubId}/award-suggestions")
    fun awardSuggestions(@PathVariable clubId: UUID): ResponseEntity<List<AwardSuggestionDto>> =
        ResponseEntity.ok(awardService.getSuggestions(clubId))

    // Смена роли участника (club-roles): назначить/снять со-организатора. Право MANAGE_ROLES —
    // владельческое (owner-bypass; у co_organizer его нет → 403 даже на демоут себя). Бизнес-правила
    // (лимит, идемпотентность, только active-промоут, нельзя владельцу/себе) — в MemberRoleService.
    @RequiresCapability(ClubCapability.MANAGE_ROLES, clubIdParam = "clubId")
    @PutMapping("/{clubId}/members/{userId}/role")
    fun updateMemberRole(
        @PathVariable clubId: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: UpdateMemberRoleRequest,
        @AuthenticationPrincipal caller: AuthenticatedUser
    ): ResponseEntity<MembershipDto> =
        ResponseEntity.ok(memberRoleService.changeRole(clubId, userId, caller.userId, request.role))
}
