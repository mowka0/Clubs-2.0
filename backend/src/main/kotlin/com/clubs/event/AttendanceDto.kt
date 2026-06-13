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

/**
 * F5-04: the caller's OWN attendance state for an event (GET /api/events/{id}/my-attendance).
 * Readable without club membership so a participant who left the club can still reach the dispute
 * UI after the deep-link DM. [canDispute] is computed server-side (window open AND attendance=absent
 * AND not yet terminal) — the frontend keys the "Оспорить" button off it.
 */
data class MyAttendanceDto(
    val attendance: String?,        // attended | absent | disputed | null
    val attendanceMarked: Boolean,
    val attendanceFinalized: Boolean,
    val disputeTerminal: Boolean,
    val canDispute: Boolean,
    val disputeNote: String?        // the caller's own note (their own row only)
)
