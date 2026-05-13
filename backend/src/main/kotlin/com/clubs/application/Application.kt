package com.clubs.application

import com.clubs.generated.jooq.enums.ApplicationStatus
import java.time.OffsetDateTime
import java.util.UUID

data class Application(
    val id: UUID,
    val userId: UUID,
    val clubId: UUID,
    val answerText: String?,
    val status: ApplicationStatus,
    val rejectedReason: String?,
    val createdAt: OffsetDateTime,
    val resolvedAt: OffsetDateTime?
)
