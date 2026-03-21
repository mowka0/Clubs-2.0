package com.clubs.event

import jakarta.validation.constraints.Future
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

data class EventDetailDto(
    val id: UUID,
    val clubId: UUID,
    val title: String,
    val description: String?,
    val locationText: String,
    val eventDatetime: OffsetDateTime,
    val participantLimit: Int,
    val votingOpensDaysBefore: Int,
    val status: String,
    val goingCount: Int,
    val maybeCount: Int,
    val notGoingCount: Int,
    val confirmedCount: Int,
    val attendanceMarked: Boolean,
    val attendanceFinalized: Boolean,
    val createdAt: OffsetDateTime?
)

data class EventListItemDto(
    val id: UUID,
    val title: String,
    val eventDatetime: OffsetDateTime,
    val locationText: String,
    val participantLimit: Int,
    val goingCount: Int,
    val status: String
)

data class CreateEventRequest(
    @field:NotBlank(message = "Title is required")
    @field:Size(max = 255, message = "Title must be at most 255 characters")
    val title: String,

    val description: String? = null,

    @field:NotBlank(message = "Location is required")
    @field:Size(max = 500, message = "Location must be at most 500 characters")
    val locationText: String,

    @field:NotNull(message = "Event datetime is required")
    @field:Future(message = "Event datetime must be in the future")
    val eventDatetime: OffsetDateTime,

    @field:NotNull(message = "Participant limit is required")
    @field:Positive(message = "Participant limit must be positive")
    val participantLimit: Int,

    @field:Min(value = 1, message = "Voting opens days before must be at least 1")
    @field:Max(value = 14, message = "Voting opens days before must be at most 14")
    val votingOpensDaysBefore: Int = 5
)
