package com.clubs.membership

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
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
 * Counts that feed the red-dot badge on «Управление» + the «Участники» tab (de-Stars Slice 2),
 * organizer-only. The dot lights when EITHER is > 0:
 *  - expiringSoon — `active` members whose paid window ends within the week (renew & confirm).
 *  - awaitingDues — `frozen` members who joined but haven't been admitted yet (confirm first dues).
 */
data class MemberAttentionDto(
    val expiringSoon: Int,
    val awaitingDues: Int
)

/**
 * A `frozen` member across one of the caller's owned clubs — they joined but haven't been admitted
 * (dues not yet confirmed). Powers the cross-club «Ждут оплаты» section on «Мои клубы» so the
 * organizer confirms dues without entering each club. `subscriptionExpiresAt` is the lapsed window
 * (null for a never-paid first join); `joinedAt` drives the «вступил(а) N назад» line.
 */
data class OrganizerDuesMember(
    val userId: UUID,
    val firstName: String?,
    val lastName: String?,
    val avatarUrl: String?,
    val telegramUsername: String?,
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val joinedAt: OffsetDateTime,
    val subscriptionExpiresAt: OffsetDateTime?
)

data class OrganizerDuesMemberDto(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val telegramUsername: String?,
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val joinedAt: OffsetDateTime?,
    val subscriptionExpiresAt: OffsetDateTime?
)

/** Member admin S1 — set the private organizer note (null/blank clears it). */
data class UpdateNoteRequest(
    @field:Size(max = 500, message = "Заметка: максимум 500 символов")
    val note: String?
)

/** Member admin S1 — set a custom access-window end date («своя дата»). Must be in the future. */
data class SetAccessUntilRequest(
    @field:NotNull(message = "Укажите дату")
    val until: OffsetDateTime
)
