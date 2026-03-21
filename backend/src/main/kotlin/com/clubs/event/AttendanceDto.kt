package com.clubs.event

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
