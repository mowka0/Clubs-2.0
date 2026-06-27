package com.clubs.user

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class MemberProfileDto(
    val userId: UUID,
    val clubId: UUID,
    val firstName: String,
    val username: String?,
    val avatarUrl: String?,
    // Public profile fields, shown to every club member on the member card alongside the
    // per-club reputation rings. Already public on the profile and the application card.
    val bio: String?,
    val interests: List<String>,
    // Membership role ("organizer" = club owner). The frontend uses it to render the
    // organizer framing when trust is null in the user's own club.
    val role: String,
    // P1b Trust 0-100. null = "Новичок"/suppressed (no track record yet, or owner in own club).
    val trust: Int?,
    val promiseFulfillmentPct: BigDecimal?,
    val totalConfirmations: Int?,
    val totalAttendances: Int?,
    // "Возможно → Подтвердил → Пришёл": пришёл, хотя обещал только «возможно». Позитивный сигнал.
    val spontaneityCount: Int?,
    // Reputation-affecting skladchina record in THIS club: paid / (paid + expired). null when the
    // reputation block is suppressed; the frontend hides the "Сборы" ring when total == 0.
    val skladchinaPaid: Int?,
    val skladchinaTotal: Int?,
    // De-Stars Slice 2 — ORGANIZER ONLY (null for regular members): when this member's paid access
    // window ends. null also for free memberships (no expiry). Shown as «Подписка активна до …».
    val subscriptionExpiresAt: OffsetDateTime? = null,
    // Member admin S1 — ORGANIZER ONLY (null for regular members): the private organizer note.
    val organizerNote: String? = null
)
