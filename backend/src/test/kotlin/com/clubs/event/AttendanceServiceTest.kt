package com.clubs.event

import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.EventStatus
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * markAttendance authorization + lifecycle guards. The key regression here is ATT-4:
 * marking must be rejected once attendance is finalized, otherwise the displayed roster
 * silently diverges from the locked-in reputation ledger.
 */
class AttendanceServiceTest {

    private val eventRepository = mockk<EventRepository>()
    private val eventResponseRepository = mockk<EventResponseRepository>(relaxed = true)
    private val clubRepository = mockk<ClubRepository>()
    private val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = AttendanceService(eventRepository, eventResponseRepository, clubRepository, publisher, 2880L, 2880L)

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
        every { eventRepository.markAttendanceMarked(eventId) } just Runs

        val result = service.markAttendance(eventId, organizerId, request)

        assertEquals(1, result.markedCount)
        verify { eventResponseRepository.setAttendance(eventId, attendeeId, true) }
        verify { eventRepository.markAttendanceMarked(eventId) }
    }

    @Test
    fun `markAttendance publishes AttendanceMarkedEvent so absent participants get a dispute DM (ATT-3)`() {
        every { eventRepository.findById(eventId) } returns event()
        stubClub()
        every { eventResponseRepository.setAttendance(eventId, attendeeId, true) } returns 1
        every { eventRepository.markAttendanceMarked(eventId) } just Runs

        service.markAttendance(eventId, organizerId, request)

        verify { publisher.publishEvent(AttendanceMarkedEvent(eventId)) }
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
