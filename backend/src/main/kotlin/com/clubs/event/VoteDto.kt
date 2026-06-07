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

/**
 * A single responder shown in the event's "who's coming" list.
 * [status] is the user's current intent: stage-2 final status when present
 * (confirmed | waitlisted | declined), otherwise the stage-1 vote
 * (going | maybe | not_going).
 * [attendance] is the post-event mark, once the organizer has marked it
 * (attended | absent | disputed), else null. Drives the dispute UI: an absent
 * participant can dispute; the organizer resolves a disputed one.
 */
data class EventResponderDto(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val status: String,
    val attendance: String?
)
