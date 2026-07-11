package com.clubs.application

import com.clubs.common.security.AuthenticatedUser
import com.clubs.membership.MembershipDto
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class ApplicationController(private val applicationService: ApplicationService) {

    private val log = LoggerFactory.getLogger(ApplicationController::class.java)

    @PostMapping("/api/clubs/{id}/apply")
    fun apply(
        @PathVariable id: UUID,
        @Valid @RequestBody request: SubmitApplicationRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ApplicationDto> {
        log.info("Apply to club {}: userId={}", id, user.userId)
        val application = applicationService.submitApplication(id, user.userId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(application)
    }

    @GetMapping("/api/clubs/{id}/applications")
    fun getClubApplications(
        @PathVariable id: UUID,
        @RequestParam(required = false) status: String?,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<ApplicationDto>> {
        val applications = applicationService.getClubApplications(id, user.userId, status)
        return ResponseEntity.ok(applications)
    }

    @PostMapping("/api/applications/{id}/approve")
    fun approve(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ApplicationDto> {
        log.info("Approve application {}: organizerId={}", id, user.userId)
        val application = applicationService.approveApplication(id, user.userId)
        return ResponseEntity.ok(application)
    }

    @PostMapping("/api/clubs/{id}/expand-and-approve")
    fun expandAndApprove(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ExpandAndApproveRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<ApplicationDto>> {
        log.info(
            "Expand and approve: clubId={} newLimit={} applications={} organizerId={}",
            id, request.newMemberLimit, request.applicationIds.size, user.userId
        )
        return ResponseEntity.ok(applicationService.expandAndApproveAll(id, user.userId, request))
    }

    @PostMapping("/api/applications/{id}/reject")
    fun reject(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RejectApplicationRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ApplicationDto> {
        // Намеренно НЕ логируем request.reason — см. docs/modules/applications-inbox.md
        // § Non-functional / Logging: поле класса PII.
        log.info("Reject application {}: organizerId={}", id, user.userId)
        val application = applicationService.rejectApplication(id, user.userId, request.reason)
        return ResponseEntity.ok(application)
    }

    @PostMapping("/api/applications/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ApplicationDto> {
        log.info("Cancel application {}: userId={}", id, user.userId)
        val application = applicationService.cancelApplication(id, user.userId)
        return ResponseEntity.ok(application)
    }

    @GetMapping("/api/users/me/applications-pending")
    fun getMyPendingApplications(
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<PendingApplicationDto>> =
        ResponseEntity.ok(applicationService.getMyPendingApplications(user.userId))

    @GetMapping("/api/users/me/applications-pending-count")
    fun getMyClubsActionCounts(
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<PendingApplicationsCountDto> =
        ResponseEntity.ok(applicationService.getMyClubsActionCounts(user.userId))

    @PostMapping("/api/applications/{id}/complete-free-membership")
    fun completeFreeMembership(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<MembershipDto> {
        log.info("Complete free membership: applicationId={} userId={}", id, user.userId)
        val membership = applicationService.completeFreeMembership(id, user.userId)
        return ResponseEntity.ok(membership)
    }
}
