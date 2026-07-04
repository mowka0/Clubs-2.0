package com.clubs.membership

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/** Один клуб + репутация в нём аутентифицированного пользователя. */
data class UserClubReputationDto(
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val category: String,
    val role: String,
    val joinedAt: OffsetDateTime?,
    // P1b Trust 0-100. null = "Новичок"/скрыт (истории ещё нет — outcome_count ниже порога
    // отображения — либо это владелец в своём клубе; для владельца рендерить через `role`).
    val trust: Int?,
    val promiseFulfillmentPct: BigDecimal?,
    val totalConfirmations: Int?,
    val totalAttendances: Int?,
    val spontaneityCount: Int?
)
