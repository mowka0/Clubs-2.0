package com.clubs.event

import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubManagerGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * markAttendance authorization + lifecycle guards. The key regression here is ATT-4:
 * marking must be rejected once attendance is finalized, otherwise the displayed roster
 * silently diverges from the locked-in reputation ledger.
 */
class AttendanceServiceTest {

    private val eventRepository = mockk<EventRepository>()
    private val eventResponseRepository = mockk<EventResponseRepository>(relaxed = true)
    private val clubRepository = mockk<ClubRepository>()
    // Guard-мок membership-репозитория: вызывающий по умолчанию не со-орг (null), owner-путь его не трогает.
    private val guardMembershipRepository = mockk<com.clubs.membership.MembershipRepository> {
        every { findByUserAndClub(any(), any()) } returns null
    }
    private val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = AttendanceService(
        eventRepository, eventResponseRepository, clubRepository,
        ClubManagerGuard(clubRepository, guardMembershipRepository), publisher, 2880L, 2880L
    )

    private val eventId = UUID.randomUUID()
    private val organizerId = UUID.randomUUID()
    private val clubId = UUID.randomUUID()
    private val attendeeId = UUID.randomUUID()

    private fun event(
        finalized: Boolean = false,
        marked: Boolean = false,
        eventDatetime: OffsetDateTime = OffsetDateTime.now().minusHours(1)
    ) = Event(
        id = eventId, clubId = clubId, createdBy = organizerId, title = "E", description = null,
        locationText = "P", eventDatetime = eventDatetime, participantLimit = 10, votingOpensDaysBefore = 14,
        status = EventStatus.completed, stage2Triggered = true, attendanceMarked = marked,
        attendanceFinalized = finalized, photoUrl = null, createdAt = null, updatedAt = null
    )

    private fun stubClub(ownerId: UUID = organizerId) {
        val club = mockk<Club>()
        every { club.ownerId } returns ownerId
        every { club.id } returns clubId
        every { clubRepository.findById(clubId) } returns club
    }

    private val request = MarkAttendanceRequest(listOf(AttendanceEntryRequest(attendeeId, true)))

    @Test
    fun `markAttendance rejects a non-organizer`() {
        every { eventRepository.findById(eventId) } returns event()
        stubClub(ownerId = UUID.randomUUID()) // someone else owns the club

        assertFailsWith<ForbiddenException> { service.markAttendance(eventId, organizerId, request) }
        verify(exactly = 0) { eventResponseRepository.setAttendance(any(), any(), any()) }
    }

    @Test
    fun `markAttendance rejects before the event happens`() {
        every { eventRepository.findById(eventId) } returns event(eventDatetime = OffsetDateTime.now().plusHours(2))
        stubClub()

        val ex = assertFailsWith<ValidationException> { service.markAttendance(eventId, organizerId, request) }
        assertEquals("Cannot mark attendance before the event takes place", ex.message)
    }

    @Test
    fun `markAttendance rejects after finalization (ATT-4)`() {
        every { eventRepository.findById(eventId) } returns event(finalized = true)
        stubClub()

        val ex = assertFailsWith<ValidationException> { service.markAttendance(eventId, organizerId, request) }
        assertEquals("Attendance has been finalized", ex.message)
        // The frozen roster must not be touched.
        verify(exactly = 0) { eventResponseRepository.setAttendance(any(), any(), any()) }
    }

    @Test
    fun `markAttendance records attendance for a past, non-finalized event`() {
        every { eventRepository.findById(eventId) } returns event()
        stubClub()
        every { eventResponseRepository.setAttendance(eventId, attendeeId, true) } returns 1
        every { eventRepository.markAttendanceMarked(eventId) } returns 1
        justRun { eventRepository.markCompleted(eventId) }

        val result = service.markAttendance(eventId, organizerId, request)

        assertEquals(1, result.markedCount)
        verify { eventResponseRepository.setAttendance(eventId, attendeeId, true) }
        verify { eventRepository.markAttendanceMarked(eventId) }
        // PO 2026-07-08: отметка явки = встреча прошла — событие сразу уходит из «предстоящих»,
        // не дожидаясь часового EventCompletionService.
        verify { eventRepository.markCompleted(eventId) }
    }

    @Test
    fun `markAttendance publishes only the newly-absent ids on AttendanceMarkedEvent (ATT-3, F5-15_2)`() {
        val absentRequest = MarkAttendanceRequest(listOf(AttendanceEntryRequest(attendeeId, false)))
        every { eventRepository.findById(eventId) } returns event()
        stubClub()
        every { eventResponseRepository.setAttendance(eventId, attendeeId, false) } returns 1
        every { eventRepository.markAttendanceMarked(eventId) } returns 1
        justRun { eventRepository.markCompleted(eventId) }

        service.markAttendance(eventId, organizerId, absentRequest)

        // Only those who NEWLY became absent (setAttendance matched) carry over — a re-mark of an
        // already-absent row matches 0 rows and is excluded, so no re-DM (F5-15.2).
        verify { publisher.publishEvent(AttendanceMarkedEvent(eventId, listOf(attendeeId))) }
    }

    @Test
    fun `markAttendance carries no ids when nobody is newly absent`() {
        every { eventRepository.findById(eventId) } returns event()
        stubClub()
        every { eventResponseRepository.setAttendance(eventId, attendeeId, true) } returns 1
        every { eventRepository.markAttendanceMarked(eventId) } returns 1
        justRun { eventRepository.markCompleted(eventId) }

        service.markAttendance(eventId, organizerId, request)

        verify { publisher.publishEvent(AttendanceMarkedEvent(eventId, emptyList())) }
    }

    @Test
    fun `markAttendance rejects and rolls back when the finalizer won the race (F5-09)`() {
        every { eventRepository.findById(eventId) } returns event()  // not finalized at the guard read
        stubClub()
        every { eventResponseRepository.setAttendance(eventId, attendeeId, true) } returns 1
        // markAttendanceMarked is guarded on attendance_finalized=false → 0 rows = finalizer won.
        every { eventRepository.markAttendanceMarked(eventId) } returns 0

        val ex = assertFailsWith<ValidationException> { service.markAttendance(eventId, organizerId, request) }
        assertEquals("Attendance has been finalized", ex.message)
        // The DM must not fire — the whole transaction (including setAttendance) is rolled back.
        verify(exactly = 0) { publisher.publishEvent(any()) }
    }

    @Test
    fun `disputeAttendance publishes AttendanceDisputedEvent so the organizer gets a DM`() {
        every { eventRepository.findById(eventId) } returns event(marked = true)
        every { eventResponseRepository.disputeAbsentAttendance(eventId, attendeeId, null) } returns 1

        service.disputeAttendance(eventId, attendeeId, null)

        verify { publisher.publishEvent(AttendanceDisputedEvent(eventId, attendeeId)) }
    }

    @Test
    fun `disputeAttendance does not notify the organizer when there is nothing to dispute`() {
        every { eventRepository.findById(eventId) } returns event(marked = true)
        every { eventResponseRepository.disputeAbsentAttendance(eventId, attendeeId, null) } returns 0

        assertFailsWith<ValidationException> { service.disputeAttendance(eventId, attendeeId, null) }

        verify(exactly = 0) { publisher.publishEvent(any()) }
    }

    @Test
    fun `finalizeAttendance converts expired disputes then publishes one finalized event per id (ATT-2)`() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        every { eventRepository.finalizeAttendanceBefore(any()) } returns listOf(id1, id2)
        every { eventResponseRepository.resolveExpiredDisputesToAbsent(any()) } returns 1

        service.finalizeAttendance()

        // ATT-2: leftover disputes must be turned into absent for the finalized ids before the
        // reputation listener reads the roster (which it does AFTER_COMMIT, after this method).
        verify { eventResponseRepository.resolveExpiredDisputesToAbsent(listOf(id1, id2)) }
        verify { publisher.publishEvent(AttendanceFinalizedEvent(id1)) }
        verify { publisher.publishEvent(AttendanceFinalizedEvent(id2)) }
    }

    @Test
    fun `finalizeAttendance does nothing when no events are due`() {
        every { eventRepository.finalizeAttendanceBefore(any()) } returns emptyList()

        service.finalizeAttendance()

        verify(exactly = 0) { eventResponseRepository.resolveExpiredDisputesToAbsent(any()) }
        verify(exactly = 0) { publisher.publishEvent(any()) }
    }

    @Test
    fun `disputeAttendance trims the note and forwards it to the repository`() {
        every { eventRepository.findById(eventId) } returns event(marked = true)
        every { eventResponseRepository.disputeAbsentAttendance(eventId, attendeeId, "был там") } returns 1

        val result = service.disputeAttendance(eventId, attendeeId, "  был там  ")

        assertEquals(1, result.markedCount)
        verify { eventResponseRepository.disputeAbsentAttendance(eventId, attendeeId, "был там") }
    }

    @Test
    fun `disputeAttendance normalizes a blank note to null and rejects when nothing to dispute`() {
        every { eventRepository.findById(eventId) } returns event(marked = true)
        every { eventResponseRepository.disputeAbsentAttendance(eventId, attendeeId, null) } returns 0

        val ex = assertFailsWith<ValidationException> {
            service.disputeAttendance(eventId, attendeeId, "   ")
        }
        assertEquals("No absent attendance to dispute", ex.message)
        verify { eventResponseRepository.disputeAbsentAttendance(eventId, attendeeId, null) }
    }

    @Test
    fun `resolveDispute throws when there is no disputed mark to resolve`() {
        every { eventRepository.findById(eventId) } returns event()
        stubClub()
        every { eventResponseRepository.resolveDisputedAttendance(eventId, attendeeId, true) } returns 0

        val ex = assertFailsWith<ValidationException> {
            service.resolveDispute(eventId, organizerId, attendeeId, true)
        }
        assertEquals("No disputed attendance to resolve for this user", ex.message)
    }

    @Test
    fun `resolveDispute returns the updated count on success`() {
        every { eventRepository.findById(eventId) } returns event()
        stubClub()
        every { eventResponseRepository.resolveDisputedAttendance(eventId, attendeeId, true) } returns 1

        val result = service.resolveDispute(eventId, organizerId, attendeeId, true)

        assertEquals(1, result.markedCount)
    }

    // --- getMyAttendance (F5-04) — membership-free read of the caller's own attendance ---

    private fun response(attendance: AttendanceStatus?, disputeTerminal: Boolean = false) = EventResponse(
        id = UUID.randomUUID(), eventId = eventId, userId = attendeeId,
        stage1Vote = Stage_1Vote.going, stage1Timestamp = null, stage2Vote = null, stage2Timestamp = null,
        finalStatus = FinalStatus.confirmed, attendance = attendance, attendanceFinalized = false,
        createdAt = null, updatedAt = null, disputeTerminal = disputeTerminal
    )

    @Test
    fun `getMyAttendance allows dispute when window open, absent and not terminal (F5-04)`() {
        every { eventRepository.findById(eventId) } returns event(marked = true)
        every { eventResponseRepository.findByEventAndUser(eventId, attendeeId) } returns response(AttendanceStatus.absent)

        assertTrue(service.getMyAttendance(eventId, attendeeId).canDispute)
    }

    @Test
    fun `getMyAttendance forbids dispute once the mark is terminal (F5-16)`() {
        every { eventRepository.findById(eventId) } returns event(marked = true)
        every { eventResponseRepository.findByEventAndUser(eventId, attendeeId) } returns
            response(AttendanceStatus.absent, disputeTerminal = true)

        val dto = service.getMyAttendance(eventId, attendeeId)
        assertFalse(dto.canDispute)
        assertTrue(dto.disputeTerminal)
    }

    @Test
    fun `getMyAttendance forbids dispute when the participant is not absent`() {
        every { eventRepository.findById(eventId) } returns event(marked = true)
        every { eventResponseRepository.findByEventAndUser(eventId, attendeeId) } returns response(AttendanceStatus.attended)

        assertFalse(service.getMyAttendance(eventId, attendeeId).canDispute)
    }

    @Test
    fun `getMyAttendance forbids dispute after finalization (window closed)`() {
        every { eventRepository.findById(eventId) } returns event(marked = true, finalized = true)
        every { eventResponseRepository.findByEventAndUser(eventId, attendeeId) } returns response(AttendanceStatus.absent)

        assertFalse(service.getMyAttendance(eventId, attendeeId).canDispute)
    }

    @Test
    fun `getMyAttendance throws when the caller has no participation row (F5-04)`() {
        every { eventRepository.findById(eventId) } returns event(marked = true)
        every { eventResponseRepository.findByEventAndUser(eventId, attendeeId) } returns null

        assertFailsWith<NotFoundException> { service.getMyAttendance(eventId, attendeeId) }
    }

    @Test
    fun `neutrallyFinalizeUnmarkedEvents never publishes a finalized event - neutral means no reputation (EXP-2)`() {
        every { eventRepository.neutrallyFinalizeUnmarkedBefore(any()) } returns listOf(UUID.randomUUID())

        service.neutrallyFinalizeUnmarkedEvents()

        // The whole point of EXP-2's neutral path: the event is closed but NO AttendanceFinalizedEvent
        // is emitted, so the reputation pipeline never runs for it (also guarded by the marked=true
        // claim). Reliable participants are not punished, nobody is rewarded.
        verify(exactly = 0) { publisher.publishEvent(any()) }
    }
}
