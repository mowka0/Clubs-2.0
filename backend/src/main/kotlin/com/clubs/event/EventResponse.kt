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
    val updatedAt: OffsetDateTime?
)
