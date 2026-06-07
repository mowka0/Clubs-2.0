package com.clubs.event

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class AttendanceService(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val clubRepository: ClubRepository,
    private val eventPublisher: ApplicationEventPublisher
) {

    private val log = LoggerFactory.getLogger(AttendanceService::class.java)

    @Transactional
    fun markAttendance(eventId: UUID, organizerId: UUID, request: MarkAttendanceRequest): AttendanceResultDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        val club = clubRepository.findById(event.clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != organizerId) throw ForbiddenException("Only the club organizer can mark attendance")

        if (event.eventDatetime.isAfter(OffsetDateTime.now())) {
            throw ValidationException("Cannot mark attendance before the event takes place")
        }

        var markedCount = 0
        request.attendance.forEach { entry ->
            val updated = eventResponseRepository.setAttendance(eventId, entry.userId, entry.attended)
            if (updated > 0) markedCount++
        }

        eventRepository.markAttendanceMarked(eventId)

        log.info("Attendance marked: eventId={} markedCount={} organizerId={}", eventId, markedCount, organizerId)
        return AttendanceResultDto(eventId, markedCount)
    }

    @Transactional
    fun disputeAttendance(eventId: UUID, userId: UUID): AttendanceResultDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        if (event.attendanceFinalized) {
            throw ValidationException("Attendance has been finalized and cannot be disputed")
        }

        if (!event.attendanceMarked) {
            throw ValidationException("Attendance has not been marked yet")
        }

        val updated = eventResponseRepository.disputeAbsentAttendance(eventId, userId)
        if (updated == 0) {
            throw ValidationException("No absent attendance to dispute")
        }

        log.info("Attendance disputed: eventId={} userId={}", eventId, userId)
        return AttendanceResultDto(eventId, updated)
    }

    @Transactional
    fun resolveDispute(eventId: UUID, organizerId: UUID, userId: UUID, attended: Boolean): AttendanceResultDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")
        val club = clubRepository.findById(event.clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != organizerId) throw ForbiddenException("Only the club organizer can resolve disputes")

        if (event.attendanceFinalized) {
            throw ValidationException("Attendance has been finalized")
        }

        eventResponseRepository.resolveDisputedAttendance(eventId, userId, attended)

        log.info("Dispute resolved: eventId={} userId={} attended={} organizerId={}", eventId, userId, attended, organizerId)
        return AttendanceResultDto(eventId, 1)
    }

    @Scheduled(fixedDelay = FINALIZE_SCHEDULER_PERIOD_MS)
    @Transactional
    fun finalizeAttendance() {
        val cutoff = OffsetDateTime.now().minusHours(DISPUTE_WINDOW_HOURS)
        val finalizedEventIds = eventRepository.finalizeAttendanceBefore(cutoff)
        if (finalizedEventIds.isEmpty()) return

        log.info("Finalized attendance for {} events", finalizedEventIds.size)
        // Reputation listener (AFTER_COMMIT) picks these up for low-latency ledger
        // processing; the hourly poll is the durable backstop. See reputation-v2.md.
        finalizedEventIds.forEach { eventPublisher.publishEvent(AttendanceFinalizedEvent(it)) }
    }

    companion object {
        private const val FINALIZE_SCHEDULER_PERIOD_MS = 3_600_000L
        private const val DISPUTE_WINDOW_HOURS = 48L
    }
}
