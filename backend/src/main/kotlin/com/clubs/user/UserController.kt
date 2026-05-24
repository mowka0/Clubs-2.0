package com.clubs.user

import com.clubs.application.ApplicationDto
import com.clubs.application.ApplicationService
import com.clubs.common.dto.PageResponse
import com.clubs.common.security.AuthenticatedUser
import com.clubs.event.MyEventListItemDto
import com.clubs.event.UserEventsService
import com.clubs.membership.MembershipDto
import com.clubs.membership.MembershipService
import com.clubs.membership.UserClubReputationDto
import com.clubs.skladchina.ActionRequiredCountDto
import com.clubs.skladchina.MySkladchinaListItemDto
import com.clubs.skladchina.UserSkladchinasService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService,
    private val applicationService: ApplicationService,
    private val membershipService: MembershipService,
    private val userEventsService: UserEventsService,
    private val userSkladchinasService: UserSkladchinasService
) {

    @GetMapping("/me")
    fun getCurrentUser(
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<UserDto> =
        ResponseEntity.ok(userService.getUserById(user.userId))

    @GetMapping("/me/clubs")
    fun getMyClubs(
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<MembershipDto>> =
        ResponseEntity.ok(membershipService.getUserMemberships(user.userId))

    @GetMapping("/me/reputation")
    fun getMyReputation(
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<UserClubReputationDto>> =
        ResponseEntity.ok(membershipService.getUserClubsWithReputation(user.userId))

    @GetMapping("/me/applications")
    fun getMyApplications(
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<ApplicationDto>> =
        ResponseEntity.ok(applicationService.getMyApplications(user.userId))

    @GetMapping("/me/events")
    fun getMyEvents(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<MyEventListItemDto>> {
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
        return ResponseEntity.ok(userEventsService.getMyEvents(user.userId, safePage, safeSize))
    }

    @GetMapping("/me/skladchinas")
    fun getMySkladchinas(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<MySkladchinaListItemDto>> {
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
        return ResponseEntity.ok(userSkladchinasService.getMySkladchinas(user.userId, safePage, safeSize))
    }

    @GetMapping("/me/skladchinas/action-required-count")
    fun getMySkladchinaActionRequiredCount(
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ActionRequiredCountDto> =
        ResponseEntity.ok(userSkladchinasService.countActionRequired(user.userId))

    companion object {
        private const val MAX_PAGE_SIZE = 50
    }
}
