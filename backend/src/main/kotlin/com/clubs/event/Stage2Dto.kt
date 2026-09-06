package com.clubs.event

import java.util.UUID

data class ConfirmResponseDto(
    val eventId: UUID,
    val status: String,
    val confirmedCount: Int,
    // null = открытая встреча (V62): лимита нет, фронт показывает счёт без знаменателя.
    val participantLimit: Int?,
    // Сколько очков репутации ФАКТИЧЕСКИ списал этот отказ (0 — бесплатно). Названная на экране
    // цена может разойтись с фактической: пока открыт диалог, очередь успевает опустеть. Экран
    // показывает результат из ответа, а не пересчитывает его сам (V83).
    val penaltyPoints: Int = 0
)
