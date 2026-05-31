package com.clubs.application

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

data class ApplicationDto(
    val id: UUID,
    val userId: UUID,
    val clubId: UUID,
    val status: String,
    val answerText: String?,
    val rejectedReason: String?,
    val createdAt: OffsetDateTime?,
    val resolvedAt: OffsetDateTime?
)

data class SubmitApplicationRequest(
    val answerText: String? = null
)

/**
 * Reject payload. Reason is now mandatory (5-500 chars after trim) so the
 * applicant always gets actionable feedback in their profile.
 * See docs/modules/applications-inbox.md § "POST /api/applications/{id}/reject".
 */
data class RejectApplicationRequest(
    @field:NotBlank
    @field:Size(min = 5, max = 500)
    val reason: String
)
