package com.clubs.event

import java.util.UUID

data class ConfirmResponseDto(
    val eventId: UUID,
    val status: String,
    val confirmedCount: Int,
    val participantLimit: Int
)
