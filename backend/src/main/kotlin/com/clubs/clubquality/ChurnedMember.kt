package com.clubs.clubquality

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Бывший участник для возврата: ушёл или истёк в пределах окна удержания и сейчас НЕ является
 * active/grace-участником этого клуба (то есть реально ушёл, а не тот, кто уже вернулся). Питает
 * раскрываемый nudge «Верните N ушедших» на панели «Статистика» владельца (§9.5).
 *
 * [leftAt] — самый недавний уход (`membership_history.occurred_at`), используется для сортировки
 * списка так, чтобы недавно ушедшие были первыми.
 */
data class ChurnedMember(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val leftAt: OffsetDateTime,
)
