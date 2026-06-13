package com.clubs.event

import com.clubs.generated.jooq.tables.records.EventResponsesRecord
import org.springframework.stereotype.Component

@Component
class EventResponseMapper {

    fun toDomain(record: EventResponsesRecord): EventResponse = EventResponse(
        id = record.id!!,
        eventId = record.eventId,
        userId = record.userId,
        stage1Vote = record.stage_1Vote,
        stage1Timestamp = record.stage_1Timestamp,
        stage2Vote = record.stage_2Vote,
        stage2Timestamp = record.stage_2Timestamp,
        finalStatus = record.finalStatus,
        attendance = record.attendance,
        attendanceFinalized = record.attendanceFinalized ?: false,
        createdAt = record.createdAt,
        updatedAt = record.updatedAt,
        disputeTerminal = record.disputeTerminal ?: false,
        disputeNote = record.disputeNote
    )
}
