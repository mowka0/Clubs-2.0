package com.clubs.user

import com.clubs.application.ApplicationDto
import com.clubs.application.ApplicationService
import com.clubs.common.security.AuthenticatedUser
import com.clubs.membership.MembershipDto
import com.clubs.membership.MembershipRepository
import com.clubs.membership.toDto
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
    private val membershipRepository: MembershipRepository
) {

    @GetMapping("/me")
    fun getCurrentUser(
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<UserDto> =
        ResponseEntity.ok(userService.getUserById(user.userId))

    @GetMapping("/me/clubs")
    fun getMyClubs(
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<MembershipDto>> {
        val memberships = membershipRepository.findByUserId(user.userId).map { it.toDto() }
        return ResponseEntity.ok(memberships)
    }

    @GetMapping("/me/applications")
    fun getMyApplications(
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<ApplicationDto>> =
        ResponseEntity.ok(applicationService.getMyApplications(user.userId))
}
