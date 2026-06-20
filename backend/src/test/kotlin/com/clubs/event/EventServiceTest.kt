package com.clubs.event

import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.EventStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    private lateinit var eventMapper: EventMapper
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var eventService: EventService

    @BeforeEach
    fun setUp() {
        eventRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        eventMapper = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        eventService = EventService(eventRepository, clubRepository, eventMapper, eventPublisher)
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

    private fun request() = CreateEventRequest(
        title = "Test event",
        description = null,
        locationText = "Bar 1",
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
        telegramGroupId = null,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )
}
