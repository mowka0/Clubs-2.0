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
    // organizer framing when reliabilityIndex is null in the user's own club.
    val role: String,
    // null = "Новичок"/suppressed (no track record yet, or owner in own club).
    val reliabilityIndex: Int?,
    val promiseFulfillmentPct: BigDecimal?,
    val totalConfirmations: Int?,
    val totalAttendances: Int?,
    // "Возможно → Подтвердил → Пришёл": пришёл, хотя обещал только «возможно». Позитивный сигнал.
    val spontaneityCount: Int?
)
