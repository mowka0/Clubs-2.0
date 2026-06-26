package com.clubs.membership

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class MemberListItemDto(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val role: String,
    val joinedAt: OffsetDateTime?,
    // P1b Trust 0-100. null = "Новичок" (no track record yet, or owner in own club — frontend
    // uses `role` to render the organizer framing). Whole reputation block is suppressed when null.
    val trust: Int?,
    val promiseFulfillmentPct: BigDecimal?,
    // Stage-2 confirmations to date. The frontend gates the "Обещания X%" line on this being > 0
    // so a finance-only member (skladchina record, no events) never shows a misleading 0% (F5-08).
    val totalConfirmations: Int?,
    // De-Stars Slice 2 — organizer dashboard only (null for regular members): access state
    // ("active"/"frozen") + when the paid access window ends. Drives the «Скоро закончится» /
    // «Ждут оплаты» / «Активные» buckets. `subscriptionExpiresAt` is null for free memberships.
    val accessStatus: String? = null,
    val subscriptionExpiresAt: OffsetDateTime? = null
)

/**
 * Count of members whose paid access ends within the «Скоро закончится» window (de-Stars Slice 2).
 * Feeds the red-dot badge on the «Управление» entry + «Участники» tab so the organizer notices
 * upcoming expirations before access is cut. Organizer-only.
 */
data class MemberAttentionDto(
    val expiringSoon: Int
)
