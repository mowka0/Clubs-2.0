package com.clubs.event

import jakarta.validation.constraints.Size
import java.util.UUID

data class AttendanceEntryRequest(
    val userId: UUID,
    val attended: Boolean
)

data class MarkAttendanceRequest(
    val attendance: List<AttendanceEntryRequest>
)

data class AttendanceResultDto(
    val eventId: UUID,
    val markedCount: Int
)

data class ResolveDisputeRequest(
    val attended: Boolean
)

/** Optional free-text note a participant attaches when disputing their attendance. */
data class DisputeAttendanceRequest(
    @field:Size(max = 500)
    val note: String? = null
)
