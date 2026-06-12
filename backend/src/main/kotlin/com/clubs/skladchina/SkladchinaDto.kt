package com.clubs.skladchina

import jakarta.validation.Valid
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

data class CreateSkladchinaRequest(
    @field:NotBlank @field:Size(max = 255)
    val title: String,

    val description: String? = null,
    val rules: String? = null,
    val photoUrl: String? = null,

    @field:NotNull
    val paymentMode: String,                       // "fixed_equal" | "fixed_individual" | "voluntary"

    @field:Positive
    val totalGoalKopecks: Long? = null,            // required for fixed_equal; ignored for voluntary

    @field:NotBlank @field:Size(max = 1000)
    val paymentLink: String,

    val paymentMethodNote: String? = null,

    @field:NotNull @field:Future
    val deadline: OffsetDateTime,

    val affectsReputation: Boolean = false,

    @field:NotEmpty @field:Valid
    val participants: List<CreateSkladchinaParticipantRequest>
)

data class CreateSkladchinaParticipantRequest(
    @field:NotNull
    val userId: UUID,
    @field:Positive
    val expectedAmountKopecks: Long? = null        // required for fixed_individual
)

data class MarkPaidRequest(
    @field:NotNull @field:Positive
    val declaredAmountKopecks: Long
)

data class SkladchinaDetailDto(
    val id: UUID,
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val creatorId: UUID,

    val title: String,
    val description: String?,
    val rules: String?,
    val photoUrl: String?,

    val paymentMode: String,
    val totalGoalKopecks: Long?,
    val collectedKopecks: Long,
    val paymentLink: String,
    val paymentMethodNote: String?,

    val deadline: OffsetDateTime,
    val affectsReputation: Boolean,
    val status: String,
    val closedAt: OffsetDateTime?,

    val isOrganizerView: Boolean,                  // caller == creator
    val myStatus: String?,                         // pending|paid|declined|expired_no_response|released or null
    val myExpectedAmountKopecks: Long?,
    val myDeclaredAmountKopecks: Long?,

    val participants: List<SkladchinaParticipantDto>?,   // non-null ONLY for organizer
    val participantCount: Int,
    val paidCount: Int
)

data class SkladchinaParticipantDto(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val expectedAmountKopecks: Long?,
    val declaredAmountKopecks: Long?,
    val status: String,
    val paidAt: OffsetDateTime?
)

data class MySkladchinaListItemDto(
    val id: UUID,
    val title: String,
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val paymentMode: String,
    val totalGoalKopecks: Long?,
    val collectedKopecks: Long,
    val participantCount: Int,
    val paidCount: Int,
    val deadline: OffsetDateTime,
    val status: String,
    val isOrganizerView: Boolean,
    val myStatus: String?,
    val actionRequired: Boolean,
    val affectsReputation: Boolean
)

data class SkladchinaUploadResponse(
    val photoUrl: String
)
