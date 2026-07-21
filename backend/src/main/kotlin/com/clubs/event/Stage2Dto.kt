package com.clubs.event

import java.util.UUID

data class ConfirmResponseDto(
    val eventId: UUID,
    val status: String,
    val confirmedCount: Int,
    // null = открытая встреча (V62): лимита нет, фронт показывает счёт без знаменателя.
    val participantLimit: Int?
)
