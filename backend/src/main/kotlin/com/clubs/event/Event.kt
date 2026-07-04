package com.clubs.event

import com.clubs.generated.jooq.enums.EventStatus
import java.time.OffsetDateTime
import java.util.UUID

data class Event(
    val id: UUID,
    val clubId: UUID,
    val createdBy: UUID,
    val title: String,
    val description: String?,
    val locationText: String,
    val eventDatetime: OffsetDateTime,
    val participantLimit: Int,
    val votingOpensDaysBefore: Int,
    val status: EventStatus,
    val stage2Triggered: Boolean,
    val attendanceMarked: Boolean,
    val attendanceFinalized: Boolean,
    // Опциональная причина от организатора, задаваемая при отмене (F5-14); иначе null.
    val cancellationReason: String? = null,
    val photoUrl: String?,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?
)
