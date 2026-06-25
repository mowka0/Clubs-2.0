package com.clubs.subscription

import jakarta.validation.constraints.NotBlank
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Subscribe / upgrade the platform service fee. `plan` and `role` are strings at the HTTP boundary
 * (validated → jOOQ enum in the service), matching the project convention (cf. CreateClubRequest.category).
 */
data class CreateSubscriptionRequest(
    @field:NotBlank val plan: String,
    val role: String = "ORGANIZER",
    /** Required only for member-pays (gated behind MEMBER_PAYS_ENABLED). */
    val subjectClubId: UUID? = null,
)

/** Current organizer plan/status. `status` is null when on the implicit FREE plan (no subscription row). */
data class SubscriptionStatusDto(
    val plan: String,
    val status: String?,
    val currentPeriodEnd: OffsetDateTime?,
    /** null = unlimited. */
    val maxPaidClubs: Int?,
    val priceKopecks: Int,
)

/** One row of the plan catalog (for the management screen + paywall modal). */
data class PlanOptionDto(
    val plan: String,
    /** null = unlimited. */
    val maxPaidClubs: Int?,
    val priceKopecks: Int,
)
