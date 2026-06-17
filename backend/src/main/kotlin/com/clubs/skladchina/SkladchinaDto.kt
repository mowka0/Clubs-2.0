package com.clubs.skladchina

import jakarta.validation.Valid
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotBlank
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

    // Selects the template strategy. Default "custom" = the Phase A behaviour.
    val template: String = "custom",               // "custom" | "split_bill" | "gear" | "booking" | "birthday"
    // split_bill: the source event whose bill is being split. Ignored by other templates.
    val eventId: UUID? = null,
    // split_bill: drop the organizer from the attendees being charged (they don't owe a share).
    // Equal mode then divides the bill across the remaining attendees. Ignored by other templates.
    val excludeSelf: Boolean = false,

    @field:NotNull
    val paymentMode: String,                       // "fixed_equal" | "fixed_individual" | "voluntary"

    @field:Positive
    val totalGoalKopecks: Long? = null,            // fixed_equal goal; split_bill: the bill total

    @field:NotBlank @field:Size(max = 1000)
    val paymentLink: String,

    val paymentMethodNote: String? = null,

    @field:NotNull @field:Future
    val deadline: OffsetDateTime,

    val affectsReputation: Boolean = false,

    // Required for custom/fixed modes; ignored by split_bill (participants come from attendance).
    // Per-template validation lives in the strategy, not the DTO.
    @field:Valid
    val participants: List<CreateSkladchinaParticipantRequest> = emptyList()
)

data class CreateSkladchinaParticipantRequest(
    @field:NotNull
    val userId: UUID,
    @field:Positive
    val expectedAmountKopecks: Long? = null        // required for fixed_individual
)

data class MarkPaidRequest(
    // Nullable since Phase A (A-1): in fixed modes the server records the assigned
    // share and the client sends nothing. Required only for voluntary (validated in
    // the service, per-mode). @Positive applies only when present.
    @field:Positive
    val declaredAmountKopecks: Long? = null
)

// V28: a participant's decline request (REQUIRES_APPROVAL templates) — reason is mandatory.
data class RequestDeclineRequest(
    @field:NotBlank @field:Size(max = 500)
    val reason: String
)

// V28/V29: organizer resolves a decline request. Rejecting (approve=false) requires a reason
// (why the participant must still pay) — validated in the service, since it's conditional on approve.
data class ResolveDeclineRequest(
    @field:NotNull
    val approve: Boolean,
    @field:Size(max = 500)
    val rejectReason: String? = null
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

    val template: String,                          // custom | split_bill | gear | booking | birthday
    val eventId: UUID?,                            // split_bill: source event (else null)
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

    // V28 decline-with-approval
    val declineRequiresApproval: Boolean,          // template policy — frontend uses the request flow
    val myDeclineRequested: Boolean,               // caller has an open decline request awaiting the organizer
    val myDeclineRejected: Boolean,                // caller's decline was rejected — must pay
    val myDeclineRejectNote: String?,              // V29: organizer's reason for rejecting the caller's decline

    val participants: List<SkladchinaParticipantDto>?,   // non-null ONLY for organizer
    val participantCount: Int,
    val paidCount: Int,
    val pendingCount: Int                          // #3: visible to all, so the last pending sees what's left
)

// State of the split linked to an event — drives the EventPage "Разделить счёт" button.
// Both null = no split yet (button creates). status active → open it; closed_success → already collected.
data class EventSplitStateDto(
    val skladchinaId: UUID?,
    val status: String?
)

data class SkladchinaParticipantDto(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val expectedAmountKopecks: Long?,
    val declaredAmountKopecks: Long?,
    val status: String,
    val paidAt: OffsetDateTime?,
    // V28: open decline request (organizer view) — show the note + approve/reject controls.
    val declineRequested: Boolean,
    val declineNote: String?,
    val declineRejected: Boolean,
    val declineRejectNote: String?                 // V29: organizer's reason if the decline was rejected
)

data class MySkladchinaListItemDto(
    val id: UUID,
    val title: String,
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val template: String,
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
