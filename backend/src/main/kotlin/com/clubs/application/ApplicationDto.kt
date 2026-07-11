package com.clubs.application

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
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
 * «Расширить клуб и принять всех» (club-invites): новый лимит участников + pending-заявки,
 * которые организатор оставил в блоке полного клуба после отсева. Бизнес-валидации
 * (лимит больше текущего и вмещает всех) — в ApplicationService.expandAndApproveAll.
 */
data class ExpandAndApproveRequest(
    @field:Min(1)
    val newMemberLimit: Int,

    @field:NotEmpty
    @field:Size(max = 50)
    val applicationIds: List<UUID>
)

/**
 * Payload отказа. Причина теперь обязательна (5-500 символов после trim), чтобы
 * заявитель всегда получал в своём профиле фидбек, на который можно опереться.
 * См. docs/modules/applications-inbox.md § "POST /api/applications/{id}/reject".
 */
data class RejectApplicationRequest(
    @field:NotBlank
    @field:Size(min = 5, max = 500)
    val reason: String
)
