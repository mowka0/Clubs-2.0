package com.clubs.event

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.EVENTS
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AttendanceService(
    private val eventRepository: EventRepository,
    private val clubRepository: ClubRepository,
    private val dsl: DSLContext
) {

    @Transactional
    fun markAttendance(eventId: UUID, organizerId: UUID, request: MarkAttendanceRequest): AttendanceResultDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        val club = clubRepository.findById(event.clubId!!) ?: throw NotFoundException("Club not found")
        if (club.ownerId != organizerId) throw ForbiddenException("Only the club organizer can mark attendance")

        // Event must be in the past (or completed)
        val now = java.time.OffsetDateTime.now()
        if (event.eventDatetime?.isAfter(now) == true) {
            throw ValidationException("Cannot mark attendance before the event takes place")
        }

        var markedCount = 0
        request.attendance.forEach { entry ->
            val updated = dsl.update(EVENT_RESPONSES)
                .set(EVENT_RESPONSES.ATTENDANCE, if (entry.attended) AttendanceStatus.attended else AttendanceStatus.absent)
                .where(
                    EVENT_RESPONSES.EVENT_ID.eq(eventId)
                        .and(EVENT_RESPONSES.USER_ID.eq(entry.userId))
                )
                .execute()
            if (updated > 0) markedCount++
        }

        // Mark event as attendance_marked
        dsl.update(EVENTS)
            .set(EVENTS.ATTENDANCE_MARKED, true)
            .where(EVENTS.ID.eq(eventId))
            .execute()

        return AttendanceResultDto(eventId, markedCount)
    }
}
