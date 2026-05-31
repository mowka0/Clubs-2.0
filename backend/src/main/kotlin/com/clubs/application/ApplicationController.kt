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
import org.springframework.web.bind.annotation.ResponseStatus
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

    @PostMapping("/api/applications/{id}/reject")
    fun reject(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RejectApplicationRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ApplicationDto> {
        // Intentionally NOT logging request.reason — see docs/modules/applications-inbox.md
        // § Non-functional / Logging: PII-class field.
        log.info("Reject application {}: organizerId={}", id, user.userId)
        val application = applicationService.rejectApplication(id, user.userId, request.reason)
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

    @GetMapping("/api/users/me/applications-awaiting-payment")
    fun getMyAwaitingPaymentApplications(
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<AwaitingPaymentApplicationDto>> =
        ResponseEntity.ok(applicationService.getMyAwaitingPaymentApplications(user.userId))

    @GetMapping("/api/users/me/organizer/awaiting-payment-applicants")
    fun getOrganizerAwaitingPaymentApplicants(
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<OrganizerAwaitingPaymentApplicantDto>> =
        ResponseEntity.ok(applicationService.getOrganizerAwaitingPaymentApplicants(user.userId))

    @GetMapping("/api/clubs/{clubId}/awaiting-payment-applicants")
    fun getClubAwaitingPaymentApplicants(
        @PathVariable clubId: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<AwaitingPaymentApplicantDto>> =
        ResponseEntity.ok(applicationService.getAwaitingPaymentApplicantsByClub(clubId, user.userId))

    @PostMapping("/api/applications/{id}/resend-invoice")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun resendInvoice(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ) {
        // Intentionally NOT logging applicationId at INFO before delegating — Service
        // emits the structured log on success; failures go through GlobalExceptionHandler.
        applicationService.resendInvoice(id, user.userId)
    }

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
