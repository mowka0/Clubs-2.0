package com.clubs.event

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.enums.Stage_2Vote
import java.time.OffsetDateTime
import java.util.UUID

data class EventResponse(
    val id: UUID,
    val eventId: UUID,
    val userId: UUID,
    val stage1Vote: Stage_1Vote?,
    val stage1Timestamp: OffsetDateTime?,
    val stage2Vote: Stage_2Vote?,
    val stage2Timestamp: OffsetDateTime?,
    val finalStatus: FinalStatus?,
    val attendance: AttendanceStatus?,
    val attendanceFinalized: Boolean,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?,
    // F5-16: once the organizer resolves a dispute (or ATT-2 auto-resolves it on window
    // expiry), the mark is terminal — disputeAttendance refuses to re-open it (ping-pong).
    // Reset to false only when setAttendance writes a genuinely new mark.
    val disputeTerminal: Boolean = false,
    // F5-04: the participant's own free-text dispute note, surfaced via GET /my-attendance.
    val disputeNote: String? = null
)
