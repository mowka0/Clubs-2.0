package com.clubs.application

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

data class RejectApplicationRequest(
    val reason: String? = null
)
