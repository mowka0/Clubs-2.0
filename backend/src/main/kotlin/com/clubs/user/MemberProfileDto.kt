package com.clubs.user

import java.math.BigDecimal
import java.util.UUID

data class MemberProfileDto(
    val userId: UUID,
    val clubId: UUID,
    val firstName: String,
    val username: String?,
    val avatarUrl: String?,
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
    // P1b §H8 others-tier: member's GLOBAL account level name (e.g. "Активист"), or null below
    // "Гость". A global signal independent of this club's track record (a newcomer here can still
    // carry a level earned elsewhere). Exact XP / badges / internals are never exposed.
    val levelName: String?
)
