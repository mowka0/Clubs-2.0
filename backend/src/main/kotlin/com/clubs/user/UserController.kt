package com.clubs.user

import com.clubs.application.ApplicationDto
import com.clubs.application.ApplicationService
import com.clubs.common.security.AuthenticatedUser
import com.clubs.membership.MembershipDto
import com.clubs.membership.MembershipService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService,
    private val applicationService: ApplicationService,
    private val membershipService: MembershipService
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

    @GetMapping("/me/applications")
    fun getMyApplications(
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<ApplicationDto>> =
        ResponseEntity.ok(applicationService.getMyApplications(user.userId))
}
