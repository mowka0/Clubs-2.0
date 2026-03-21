package com.clubs.event

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.EVENTS
import org.jooq.DSLContext
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
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

        val now = OffsetDateTime.now()
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

        dsl.update(EVENTS)
            .set(EVENTS.ATTENDANCE_MARKED, true)
            .where(EVENTS.ID.eq(eventId))
            .execute()

        return AttendanceResultDto(eventId, markedCount)
    }

    @Transactional
    fun disputeAttendance(eventId: UUID, userId: UUID): AttendanceResultDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        if (event.attendanceFinalized == true) {
            throw ValidationException("Attendance has been finalized and cannot be disputed")
        }

        if (event.attendanceMarked != true) {
            throw ValidationException("Attendance has not been marked yet")
        }

        val updated = dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.ATTENDANCE, AttendanceStatus.disputed)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.absent))
            )
            .execute()

        if (updated == 0) {
            throw ValidationException("No absent attendance to dispute")
        }

        return AttendanceResultDto(eventId, updated)
    }

    @Transactional
    fun resolveDispute(eventId: UUID, organizerId: UUID, userId: UUID, attended: Boolean): AttendanceResultDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")
        val club = clubRepository.findById(event.clubId!!) ?: throw NotFoundException("Club not found")
        if (club.ownerId != organizerId) throw ForbiddenException("Only the club organizer can resolve disputes")

        if (event.attendanceFinalized == true) {
            throw ValidationException("Attendance has been finalized")
        }

        dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.ATTENDANCE, if (attended) AttendanceStatus.attended else AttendanceStatus.absent)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.disputed))
            )
            .execute()

        return AttendanceResultDto(eventId, 1)
    }

    @Scheduled(fixedDelay = 3_600_000) // every hour
    @Transactional
    fun finalizeAttendance() {
        val cutoff = OffsetDateTime.now().minusHours(48)

        dsl.update(EVENTS)
            .set(EVENTS.ATTENDANCE_FINALIZED, true)
            .where(
                EVENTS.ATTENDANCE_MARKED.eq(true)
                    .and(EVENTS.ATTENDANCE_FINALIZED.eq(false))
                    .and(EVENTS.EVENT_DATETIME.lessOrEqual(cutoff))
            )
            .execute()
    }
}
