package com.clubs.application

import com.clubs.common.security.AuthenticatedUser
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

    @PostMapping("/api/clubs/{id}/apply")
    fun apply(
        @PathVariable id: UUID,
        @RequestBody request: SubmitApplicationRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ApplicationDto> {
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
        val application = applicationService.approveApplication(id, user.userId)
        return ResponseEntity.ok(application)
    }

    @PostMapping("/api/applications/{id}/reject")
    fun reject(
        @PathVariable id: UUID,
        @RequestBody(required = false) request: RejectApplicationRequest?,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<ApplicationDto> {
        val application = applicationService.rejectApplication(id, user.userId, request?.reason)
        return ResponseEntity.ok(application)
    }
}
