package com.clubs.event

import java.util.UUID

data class CastVoteRequest(
    val vote: String  // "going" | "maybe" | "not_going"
)

data class VoteResponseDto(
    val eventId: UUID,
    val vote: String,
    val goingCount: Int,
    val maybeCount: Int,
    val notGoingCount: Int
)

data class MyVoteDto(
    val vote: String?
)
