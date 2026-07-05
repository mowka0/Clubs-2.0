package com.clubs.membership

import com.clubs.club.NearestEventDto
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
    val spontaneityCount: Int?,
    // «Путь назад» (reputation-path-back.md): Trust после +1 / +2 посещений — детерминированная
    // проекция той же формулы TrustPolicy. null = путь назад не показывается (trust скрыт гейтом,
    // ИЛИ trust >= 70 — просадки нет, ИЛИ клуб неактивен). Три поля заполняются вместе.
    val projectedNext1: Int?,
    val projectedNext2: Int?,
    // Посещений до надёжной зоны (>= 70); cap 9 — UI пишет «9+». null по тем же правилам.
    val meetingsToReliable: Int?,
    // Кольцо «Сборы»: оплачено / (оплачено+просрочено) репутационных складчин. null ниже гейта
    // показа; total = 0 → фронт кольцо не рендерит.
    val skladchinaPaid: Int?,
    val skladchinaTotal: Int?,
    // Ближайшее предстоящее событие клуба (семантика Discovery) — CTA «Ближайшая встреча» в
    // раскрытой карточке «Моих клубов». null = предстоящих событий нет ИЛИ клуб в «Истории».
    val nearestEvent: NearestEventDto?,
    // Клубные награды вызывающего в ЭТОМ клубе (чипы в раскрытой карточке; R3 — косметика,
    // на репутацию не влияет). Пусто, если наград нет или клуб в «Истории».
    val awards: List<com.clubs.award.AwardDto> = emptyList()
)
