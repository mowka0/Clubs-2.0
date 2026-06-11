package com.clubs.event

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
    private val eventPublisher: ApplicationEventPublisher,
    // Dispute window (minutes) before attendance finalizes (PRD §4.4.3 default 48h = 2880).
    // Minutes unit lets staging set a literal 5 for an end-to-end reputation test.
    @Value("\${events.dispute-window-minutes:2880}") private val disputeWindowMinutes: Long,
    // EXP-2: deadline (minutes after event_datetime) for neutral auto-finalization of unmarked
    // past events. Default 48h. Minutes unit so staging can set a literal 5.
    @Value("\${events.auto-finalize-unmarked-minutes:2880}") private val autoFinalizeUnmarkedMinutes: Long
) {

    private val log = LoggerFactory.getLogger(AttendanceService::class.java)

    @Transactional
    fun markAttendance(eventId: UUID, organizerId: UUID, request: MarkAttendanceRequest): AttendanceResultDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        val club = clubRepository.findById(event.clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != organizerId) throw ForbiddenException("Only the club organizer can mark attendance")

        // ATT-4: once finalized the roster is frozen and reputation is computed; re-marking
        // would silently desync the displayed attendance from the locked-in ledger (recompute
        // never re-runs — claimEvent is one-shot). Mirror dispute/resolve, which already guard this.
        if (event.attendanceFinalized) {
            throw ValidationException("Attendance has been finalized")
        }

        if (event.eventDatetime.isAfter(OffsetDateTime.now())) {
            throw ValidationException("Cannot mark attendance before the event takes place")
        }

        var markedCount = 0
        request.attendance.forEach { entry ->
            val updated = eventResponseRepository.setAttendance(eventId, entry.userId, entry.attended)
            if (updated > 0) markedCount++
        }

        eventRepository.markAttendanceMarked(eventId)

        // ATT-3: notify absent participants (DM "вас отметили отсутствующим, оспорьте") so the
        // dispute window is actually reachable. Published, not called directly: the @Async DM reads
        // the just-written absent rows, which are only visible to a separate connection AFTER this
        // transaction commits. AttendanceMarkedListener reacts AFTER_COMMIT — same hazard the
        // reputation pipeline solves for AttendanceFinalizedEvent.
        eventPublisher.publishEvent(AttendanceMarkedEvent(eventId))

        log.info("Attendance marked: eventId={} markedCount={} organizerId={}", eventId, markedCount, organizerId)
        return AttendanceResultDto(eventId, markedCount)
    }

    @Transactional
    fun disputeAttendance(eventId: UUID, userId: UUID, note: String?): AttendanceResultDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        if (event.attendanceFinalized) {
            throw ValidationException("Attendance has been finalized and cannot be disputed")
        }

        if (!event.attendanceMarked) {
            throw ValidationException("Attendance has not been marked yet")
        }

        val updated = eventResponseRepository.disputeAbsentAttendance(eventId, userId, note?.trim()?.ifBlank { null })
        if (updated == 0) {
            throw ValidationException("No absent attendance to dispute")
        }

        // The organizer must hear about the dispute while the window is still open: silence
        // converts it back to absent (no_show penalty) on finalization. AFTER_COMMIT hop for
        // the same reason as AttendanceMarkedEvent — the @Async DM reads committed rows.
        eventPublisher.publishEvent(AttendanceDisputedEvent(eventId, userId))

        log.info("Attendance disputed: eventId={} userId={} hasNote={}", eventId, userId, note != null)
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

        // Reject a no-op resolve: if the target user has no disputed mark on this event, the update
        // touches 0 rows. Returning success would lie to the organizer and mask a bad userId / a
        // dispute that was already resolved. Mirrors the 0-rows guard in disputeAttendance.
        val updated = eventResponseRepository.resolveDisputedAttendance(eventId, userId, attended)
        if (updated == 0) {
            throw ValidationException("No disputed attendance to resolve for this user")
        }

        log.info("Dispute resolved: eventId={} userId={} attended={} organizerId={}", eventId, userId, attended, organizerId)
        return AttendanceResultDto(eventId, updated)
    }

    @Scheduled(fixedDelayString = "\${events.finalize-poll-ms:3600000}")
    @Transactional
    fun finalizeAttendance() {
        val cutoff = OffsetDateTime.now().minusMinutes(disputeWindowMinutes)
        val finalizedEventIds = eventRepository.finalizeAttendanceBefore(cutoff)
        if (finalizedEventIds.isEmpty()) return

        // ATT-2: the dispute window expired without an organizer correction, so the original
        // mark stands — convert leftover `disputed` back to `absent`. This runs in the same
        // transaction, before the reputation listener reads the roster (AFTER_COMMIT), so
        // (going, absent) maps to no_show instead of confirmed_unresolved (0). A disputed
        // mark can only exist on a marked event, so neutrally-finalized (unmarked) events are
        // unaffected. See events.md § ATT-2.
        val resolved = eventResponseRepository.resolveExpiredDisputesToAbsent(finalizedEventIds)
        log.info("Finalized attendance for {} events ({} expired disputes → absent)", finalizedEventIds.size, resolved)
        // Reputation listener (AFTER_COMMIT) picks these up for low-latency ledger
        // processing; the hourly poll is the durable backstop. See reputation-v2.md.
        finalizedEventIds.forEach { eventPublisher.publishEvent(AttendanceFinalizedEvent(it)) }
    }

    /**
     * EXP-2: neutrally finalizes past events whose attendance the organizer never marked, once the
     * deadline ([autoFinalizeUnmarkedMinutes] after `event_datetime`) passes. Sets
     * `attendance_finalized = true` while leaving `attendance_marked = false`, so the reputation
     * pipeline (which claims only marked+finalized events) produces NO ledger rows — the event
     * simply does not count (neither +100 nor −50). Reliable participants are not punished for an
     * inactive organizer, and no one is rewarded for it. Deliberately publishes NO
     * AttendanceFinalizedEvent. Same poll cadence as [finalizeAttendance]; the two paths touch
     * disjoint rows (marked=true vs marked=false). See events.md § EXP-2 and reputation-v2.md.
     */
    @Scheduled(fixedDelayString = "\${events.finalize-poll-ms:3600000}")
    @Transactional
    fun neutrallyFinalizeUnmarkedEvents() {
        val cutoff = OffsetDateTime.now().minusMinutes(autoFinalizeUnmarkedMinutes)
        val ids = eventRepository.neutrallyFinalizeUnmarkedBefore(cutoff)
        if (ids.isNotEmpty()) {
            log.info("Neutrally auto-finalized {} unmarked past events (no reputation accrued)", ids.size)
        }
    }
}
