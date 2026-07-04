package com.clubs.award

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Локальная награда клуба (member admin profile S2). Чисто косметическое признание, которое
 * организатор выдаёт участнику — НЕ глобальный заработанный бейдж; никогда не влияет на
 * репутацию/XP/ранг (R4). Видна всем участникам (R3).
 */
data class Award(
    val id: UUID,
    val clubId: UUID,
    val userId: UUID,
    val emoji: String,
    val label: String,
    val awardedBy: UUID,
    val awardedAt: OffsetDateTime
)

/** Уникальные пары (emoji, label), когда-либо выданные в клубе — источник автодополнения для формы выдачи («как интересы»). */
data class AwardSuggestion(
    val emoji: String,
    val label: String
)
