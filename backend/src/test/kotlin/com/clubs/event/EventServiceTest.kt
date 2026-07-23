package com.clubs.event

import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.skladchina.SkladchinaRepository
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.EventStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Regression guard for the "event-created DM never fires" bug: createEvent must
 * publish [EventCreatedEvent] so EventBotNotifier can DM members AFTER_COMMIT.
 * Pre-fix the method was orphaned (no caller), so members got no notification.
 */
class EventServiceTest {

    private lateinit var eventRepository: EventRepository
    private lateinit var clubRepository: ClubRepository
    private lateinit var guardMembershipRepository: com.clubs.membership.MembershipRepository
    private lateinit var eventMapper: EventMapper
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var skladchinaRepository: SkladchinaRepository
    private lateinit var eventService: EventService

    @BeforeEach
    fun setUp() {
        eventRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        // Вызывающий по умолчанию не со-орг (null): owner-путь guard'а membership-репозиторий не трогает.
        guardMembershipRepository = mockk { every { findByUserAndClub(any(), any()) } returns null }
        eventMapper = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        skladchinaRepository = mockk(relaxed = true)
        eventService = EventService(
            eventRepository, clubRepository, ClubRoleGuard(clubRepository, guardMembershipRepository),
            eventMapper, eventPublisher, skladchinaRepository
        )
    }

    @Test
    fun `createEvent publishes EventCreatedEvent after persisting`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)
        every { eventRepository.create(any(), clubId, ownerId) } returns event

        eventService.createEvent(clubId, request(), ownerId)

        verify(exactly = 1) { eventPublisher.publishEvent(EventCreatedEvent(event)) }
    }

    @Test
    fun `createEvent for an urgent event flips it to stage 2 before publishing`() {
        // Срочная встреча (PO 2026-07-23): Этапа 1 нет — событие рождается сразу в подтверждении
        // мест, и уведомление (EventCreatedEvent) несёт уже stage_2-состояние.
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)
        every { eventRepository.create(any(), clubId, ownerId) } returns event

        eventService.createEvent(clubId, request().copy(isUrgentEvent = true), ownerId)

        verify(exactly = 1) { eventRepository.transitionToStage2(event.id) }
        verify(exactly = 1) {
            eventPublisher.publishEvent(
                EventCreatedEvent(event.copy(status = EventStatus.stage_2, stage2Triggered = true))
            )
        }
    }

    @Test
    fun `createEvent for a regular event does not touch stage 2`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)
        every { eventRepository.create(any(), clubId, ownerId) } returns event

        eventService.createEvent(clubId, request(), ownerId)

        verify(exactly = 0) { eventRepository.transitionToStage2(any()) }
    }

    @Test
    fun `createEvent normalizes a blank location hint to null`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)
        val requestSlot = slot<CreateEventRequest>()
        every { eventRepository.create(capture(requestSlot), clubId, ownerId) } returns event

        eventService.createEvent(clubId, request().copy(locationHint = "   "), ownerId)

        assertNull(requestSlot.captured.locationHint)
    }

    @Test
    fun `createEvent trims a meaningful location hint`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)
        val requestSlot = slot<CreateEventRequest>()
        every { eventRepository.create(capture(requestSlot), clubId, ownerId) } returns event

        eventService.createEvent(clubId, request().copy(locationHint = "  Вход со двора  "), ownerId)

        assertEquals("Вход со двора", requestSlot.captured.locationHint)
    }

    @Test
    fun `createEvent does not publish when caller is not the organizer`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val intruderId = UUID.randomUUID()
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)

        assertThrows<ForbiddenException> {
            eventService.createEvent(clubId, request(), intruderId)
        }
        verify(exactly = 0) { eventPublisher.publishEvent(any()) }
        verify(exactly = 0) { eventRepository.create(any(), any(), any()) }
    }

    @Test
    fun `createEvent does not publish when club is missing`() {
        val clubId = UUID.randomUUID()
        every { clubRepository.findById(clubId) } returns null

        assertThrows<NotFoundException> {
            eventService.createEvent(clubId, request(), UUID.randomUUID())
        }
        verify(exactly = 0) { eventPublisher.publishEvent(any()) }
    }

    @Test
    fun `cancelEvent cancels event, releases linked split and publishes EventCancelledEvent`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { eventRepository.findById(event.id) } returns event
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)
        every { eventRepository.cancelEvent(event.id, "Заболел") } returns 1

        eventService.cancelEvent(event.id, ownerId, "Заболел")

        verify(exactly = 1) { eventRepository.cancelEvent(event.id, "Заболел") }
        verify(exactly = 1) { skladchinaRepository.cancelActiveByEventId(event.id) }
        verify(exactly = 1) { eventPublisher.publishEvent(EventCancelledEvent(event, "Заболел")) }
    }

    @Test
    fun `cancelEvent normalizes a blank reason to null`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { eventRepository.findById(event.id) } returns event
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)
        every { eventRepository.cancelEvent(event.id, null) } returns 1

        eventService.cancelEvent(event.id, ownerId, "   ")

        verify(exactly = 1) { eventRepository.cancelEvent(event.id, null) }
        verify(exactly = 1) { eventPublisher.publishEvent(EventCancelledEvent(event, null)) }
    }

    @Test
    fun `cancelEvent throws Forbidden when caller is not the organizer`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { eventRepository.findById(event.id) } returns event
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)

        assertThrows<ForbiddenException> { eventService.cancelEvent(event.id, UUID.randomUUID(), null) }

        verify(exactly = 0) { eventRepository.cancelEvent(any(), any()) }
        verify(exactly = 0) { skladchinaRepository.cancelActiveByEventId(any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any()) }
    }

    @Test
    fun `cancelEvent throws Conflict when the event is not cancellable (guard yields 0 rows)`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { eventRepository.findById(event.id) } returns event
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)
        every { eventRepository.cancelEvent(event.id, null) } returns 0

        assertThrows<ConflictException> { eventService.cancelEvent(event.id, ownerId, null) }

        verify(exactly = 0) { skladchinaRepository.cancelActiveByEventId(any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any()) }
    }

    @Test
    fun `cancelEvent throws NotFound when the event is missing`() {
        val eventId = UUID.randomUUID()
        every { eventRepository.findById(eventId) } returns null

        assertThrows<NotFoundException> { eventService.cancelEvent(eventId, UUID.randomUUID(), null) }

        verify(exactly = 0) { eventPublisher.publishEvent(any()) }
    }

    private fun request() = CreateEventRequest(
        title = "Test event",
        description = null,
        locationText = "Bar 1",
        locationLat = 55.761216,
        locationLon = 37.646488,
        locationHint = null,
        eventDatetime = OffsetDateTime.now().plusDays(7),
        participantLimit = 20,
        votingOpensDaysBefore = 14
    )

    private fun sampleEvent(clubId: UUID, createdBy: UUID) = Event(
        id = UUID.randomUUID(),
        clubId = clubId,
        createdBy = createdBy,
        title = "Test event",
        description = null,
        locationText = "Bar 1",
        eventDatetime = OffsetDateTime.now().plusDays(7),
        participantLimit = 20,
        votingOpensDaysBefore = 14,
        status = EventStatus.upcoming,
        stage2Triggered = false,
        attendanceMarked = false,
        attendanceFinalized = false,
        photoUrl = null,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )

    private fun club(id: UUID, ownerId: UUID) = Club(
        id = id,
        ownerId = ownerId,
        name = "Test club",
        description = "desc",
        category = ClubCategory.other,
        accessType = AccessType.open,
        city = "Moscow",
        district = null,
        memberLimit = 50,
        subscriptionPrice = 0,
        avatarUrl = null,
        rules = null,
        applicationQuestion = null,
        inviteLink = null,
        memberCount = 1,
        isActive = true,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )
}
