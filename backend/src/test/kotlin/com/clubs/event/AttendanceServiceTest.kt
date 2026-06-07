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
    private val service = AttendanceService(eventRepository, eventResponseRepository, clubRepository, publisher, 2880L)

    private val eventId = UUID.randomUUID()
    private val organizerId = UUID.randomUUID()
    private val clubId = UUID.randomUUID()
    private val attendeeId = UUID.randomUUID()

    private fun event(finalized: Boolean = false, eventDatetime: OffsetDateTime = OffsetDateTime.now().minusHours(1)) = Event(
        id = eventId, clubId = clubId, createdBy = organizerId, title = "E", description = null,
        locationText = "P", eventDatetime = eventDatetime, participantLimit = 10, votingOpensDaysBefore = 14,
        status = EventStatus.completed, stage2Triggered = true, attendanceMarked = false,
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
}
