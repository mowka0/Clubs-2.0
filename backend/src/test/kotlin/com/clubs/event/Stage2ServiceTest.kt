package com.clubs.event

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.enums.Stage_2Vote
import com.clubs.membership.MembershipRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Stage 2 window-close (Bug B), auto-expire delegation (Feature A), Stage-2-started DM
 * publication (S2T-2) and slot-lock ordering (S2-01/F5-07, F5-11).
 * Bug B: confirm/decline must be rejected once the event has started, even while the
 * status still reads stage_2 (the hourly completion sweep hasn't run yet).
 */
class Stage2ServiceTest {

    private val eventRepository = mockk<EventRepository>()
    private val eventResponseRepository = mockk<EventResponseRepository>(relaxed = true)
    private val membershipRepository = mockk<MembershipRepository>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = Stage2Service(eventRepository, eventResponseRepository, membershipRepository, eventPublisher, stage2TriggerMinutesBefore = 1440)

    private val eventId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val clubId = UUID.randomUUID()

    @Test
    fun `confirm is rejected after the event has started and does not mutate state`() {
        every { eventRepository.findById(eventId) } returns event(eventDatetime = OffsetDateTime.now().minusMinutes(1))

        val ex = assertFailsWith<ValidationException> { service.confirmParticipation(eventId, userId) }
        assertEquals("Confirmation window has closed", ex.message)
        // AC-B1 "статус не меняется": the guard must reject before any write.
        verify(exactly = 0) { eventResponseRepository.updateStage2Vote(any(), any(), any()) }
    }

    @Test
    fun `decline is rejected after the event has started and does not mutate state`() {
        every { eventRepository.findById(eventId) } returns event(eventDatetime = OffsetDateTime.now().minusMinutes(1))

        val ex = assertFailsWith<ValidationException> { service.declineParticipation(eventId, userId) }
        assertEquals("Confirmation window has closed", ex.message)
        verify(exactly = 0) { eventResponseRepository.updateStage2Vote(any(), any(), any()) }
    }

    @Test
    fun `decline before start works and promotes the first waitlisted member`() {
        val waitlistedId = UUID.randomUUID()
        every { eventRepository.findById(eventId) } returns event(eventDatetime = OffsetDateTime.now().plusHours(2))
        every { membershipRepository.isMember(userId, clubId) } returns true
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns
            response(stage1 = Stage_1Vote.going, stage2 = Stage_2Vote.confirmed)
        every { eventResponseRepository.findFirstWaitlisted(eventId) } returns
            EventResponse(waitlistedId, eventId, UUID.randomUUID(), Stage_1Vote.going, null,
                Stage_2Vote.waitlisted, null, FinalStatus.waitlisted, null, false, null, null)
        every { eventResponseRepository.countConfirmed(eventId) } returns 1

        val result = service.declineParticipation(eventId, userId)

        assertEquals("declined", result.status)
        // The freed slot is handed to the first waitlisted member.
        verify(exactly = 1) {
            eventResponseRepository.updateStage2Vote(waitlistedId, Stage_2Vote.confirmed, FinalStatus.confirmed)
        }
    }

    @Test
    fun `confirm still works before the event starts`() {
        every { eventRepository.findById(eventId) } returns event(eventDatetime = OffsetDateTime.now().plusHours(2))
        every { membershipRepository.isMember(userId, clubId) } returns true
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns
            response(stage1 = Stage_1Vote.going, stage2 = null)
        every { eventResponseRepository.countConfirmed(eventId) } returnsMany listOf(0, 1)
        every { eventResponseRepository.updateStage2Vote(any(), any(), any()) } returns
            response(stage1 = Stage_1Vote.going, stage2 = Stage_2Vote.confirmed)

        val result = service.confirmParticipation(eventId, userId)

        assertEquals("confirmed", result.status)
        assertEquals(1, result.confirmedCount)
    }

    @Test
    fun `confirm rejects a non-member`() {
        every { eventRepository.findById(eventId) } returns event(eventDatetime = OffsetDateTime.now().plusHours(2))
        every { membershipRepository.isMember(userId, clubId) } returns false

        assertFailsWith<ForbiddenException> { service.confirmParticipation(eventId, userId) }
    }

    @Test
    fun `confirm by a full event waitlists the user`() {
        every { eventRepository.findById(eventId) } returns event(eventDatetime = OffsetDateTime.now().plusHours(2))
        every { membershipRepository.isMember(userId, clubId) } returns true
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns
            response(stage1 = Stage_1Vote.going, stage2 = null)
        every { eventResponseRepository.countConfirmed(eventId) } returns 10 // == participantLimit
        every { eventResponseRepository.updateStage2Vote(any(), any(), any()) } returns
            response(stage1 = Stage_1Vote.going, stage2 = Stage_2Vote.waitlisted)

        val result = service.confirmParticipation(eventId, userId)

        assertEquals("waitlisted", result.status)
        verify { eventResponseRepository.updateStage2Vote(any(), Stage_2Vote.waitlisted, FinalStatus.waitlisted) }
    }

    @Test
    fun `waitlisted user re-confirming stays waitlisted (FIFO, no self-promotion)`() {
        every { eventRepository.findById(eventId) } returns event(eventDatetime = OffsetDateTime.now().plusHours(2))
        every { membershipRepository.isMember(userId, clubId) } returns true
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns
            response(stage1 = Stage_1Vote.going, stage2 = Stage_2Vote.waitlisted)
        every { eventResponseRepository.countConfirmed(eventId) } returns 3

        val result = service.confirmParticipation(eventId, userId)

        assertEquals("waitlisted", result.status)
        // S2-02: must NOT grab a freed slot — no promotion write happens.
        verify(exactly = 0) { eventResponseRepository.updateStage2Vote(any(), any(), any()) }
    }

    @Test
    fun `decline rejects a non-member`() {
        every { eventRepository.findById(eventId) } returns event(eventDatetime = OffsetDateTime.now().plusHours(2))
        every { membershipRepository.isMember(userId, clubId) } returns false

        assertFailsWith<ForbiddenException> { service.declineParticipation(eventId, userId) }
        verify(exactly = 0) { eventResponseRepository.updateStage2Vote(any(), any(), any()) }
    }

    @Test
    fun `stage 2 trigger publishes Stage2StartedEvent so voters get the confirm DM`() {
        // S2T-2: without this publication nobody learns Stage 2 started and everyone
        // auto-expires at event start.
        val event = event(eventDatetime = OffsetDateTime.now().plusDays(1), status = EventStatus.stage_1)
        every { eventRepository.findEventsToTriggerStage2(any()) } returns listOf(event)
        justRun { eventRepository.transitionToStage2(eventId) }
        every { eventResponseRepository.findGoingByEventOrderByTimestamp(eventId) } returns emptyList()

        service.triggerStage2ForReadyEvents()

        verify(exactly = 1) { eventPublisher.publishEvent(Stage2StartedEvent(event)) }
    }

    @Test
    fun `stage 2 trigger publishes after the waitlist overflow is assigned`() {
        // The AFTER_COMMIT listener reads voter rows fresh — but within the transaction the
        // publication must follow the overflow assignment so a failed assignment also skips the DM.
        val event = event(eventDatetime = OffsetDateTime.now().plusDays(1), status = EventStatus.stage_1)
        val overflow = response(stage1 = Stage_1Vote.going, stage2 = null)
        every { eventRepository.findEventsToTriggerStage2(any()) } returns listOf(event)
        justRun { eventRepository.transitionToStage2(eventId) }
        // participantLimit = 10 → 11 going voters, the last one overflows to waitlist.
        every { eventResponseRepository.findGoingByEventOrderByTimestamp(eventId) } returns
            List(10) { response(stage1 = Stage_1Vote.going, stage2 = null) } + overflow

        service.triggerStage2ForReadyEvents()

        verifyOrder {
            eventResponseRepository.updateStage2Vote(overflow.id, Stage_2Vote.waitlisted, FinalStatus.waitlisted)
            eventPublisher.publishEvent(Stage2StartedEvent(event))
        }
    }

    @Test
    fun `confirm takes the per-event slot lock before reading slot state`() {
        // S2-01/F5-07: the capacity check is a read-modify-write — the lock must come first,
        // so a blocked transaction re-reads committed state after acquiring it.
        every { eventRepository.findById(eventId) } returns event(eventDatetime = OffsetDateTime.now().plusHours(2))
        every { membershipRepository.isMember(userId, clubId) } returns true
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns
            response(stage1 = Stage_1Vote.going, stage2 = null)
        every { eventResponseRepository.countConfirmed(eventId) } returns 0
        every { eventResponseRepository.updateStage2Vote(any(), any(), any()) } returns
            response(stage1 = Stage_1Vote.going, stage2 = Stage_2Vote.confirmed)

        service.confirmParticipation(eventId, userId)

        verifyOrder {
            eventResponseRepository.lockEventSlots(eventId)
            eventResponseRepository.findByEventAndUser(eventId, userId)
            eventResponseRepository.countConfirmed(eventId)
        }
    }

    @Test
    fun `decline takes the per-event slot lock before promoting from the waitlist`() {
        // F5-11: two unserialized declines would promote the same waitlisted user twice,
        // permanently losing a freed slot.
        every { eventRepository.findById(eventId) } returns event(eventDatetime = OffsetDateTime.now().plusHours(2))
        every { membershipRepository.isMember(userId, clubId) } returns true
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns
            response(stage1 = Stage_1Vote.going, stage2 = Stage_2Vote.confirmed)
        every { eventResponseRepository.findFirstWaitlisted(eventId) } returns null
        every { eventResponseRepository.countConfirmed(eventId) } returns 0

        service.declineParticipation(eventId, userId)

        verifyOrder {
            eventResponseRepository.lockEventSlots(eventId)
            eventResponseRepository.findByEventAndUser(eventId, userId)
            eventResponseRepository.findFirstWaitlisted(eventId)
        }
    }

    @Test
    fun `expireUnconfirmedParticipants delegates the sweep to the repository`() {
        every { eventResponseRepository.expireUnconfirmedForStartedEvents(any()) } returns 2

        service.expireUnconfirmedParticipants()

        verify(exactly = 1) { eventResponseRepository.expireUnconfirmedForStartedEvents(any()) }
    }

    private fun event(eventDatetime: OffsetDateTime, status: EventStatus = EventStatus.stage_2) = Event(
        id = eventId,
        clubId = clubId,
        createdBy = UUID.randomUUID(),
        title = "Event",
        description = null,
        locationText = "Place",
        eventDatetime = eventDatetime,
        participantLimit = 10,
        votingOpensDaysBefore = 14,
        status = status,
        stage2Triggered = true,
        attendanceMarked = false,
        attendanceFinalized = false,
        photoUrl = null,
        createdAt = null,
        updatedAt = null
    )

    private fun response(stage1: Stage_1Vote?, stage2: Stage_2Vote?) = EventResponse(
        id = UUID.randomUUID(),
        eventId = eventId,
        userId = userId,
        stage1Vote = stage1,
        stage1Timestamp = null,
        stage2Vote = stage2,
        stage2Timestamp = null,
        finalStatus = if (stage2 == Stage_2Vote.confirmed) FinalStatus.confirmed else null,
        attendance = null,
        attendanceFinalized = false,
        createdAt = null,
        updatedAt = null
    )
}
