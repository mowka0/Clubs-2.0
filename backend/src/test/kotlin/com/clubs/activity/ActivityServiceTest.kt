package com.clubs.activity

import com.clubs.activity.dto.ActivityItemDto
import com.clubs.activity.mapper.ActivityMapper
import com.clubs.event.Event
import com.clubs.event.EventRepository
import com.clubs.event.EventWithGoingCount
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.SkladchinaMode
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.generated.jooq.enums.SkladchinaTemplate
import com.clubs.skladchina.Skladchina
import com.clubs.skladchina.SkladchinaRepository
import com.clubs.skladchina.SkladchinaWithAggregates
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityServiceTest {

    private lateinit var eventRepository: EventRepository
    private lateinit var skladchinaRepository: SkladchinaRepository
    private lateinit var service: ActivityService

    private val clubId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-05-22T18:30:00Z")

    @BeforeEach
    fun setUp() {
        eventRepository = mockk()
        skladchinaRepository = mockk()
        // Default: nothing awaits the user's action — existing ordering tests are unaffected.
        every { eventRepository.findActionRequiredEventIds(any(), any(), any()) } returns emptySet()
        service = ActivityService(
            eventRepository = eventRepository,
            skladchinaRepository = skladchinaRepository,
            activityMapper = ActivityMapper()
        )
    }

    @Test
    fun `upcoming sorted soonest-first by relevant date, interleaving events and skladchinas`() {
        // relevantDate: event -> eventDatetime, skladchina -> deadline
        val event1 = makeEvent(eventDatetime = now.plusDays(3), title = "Event +3d")
        val event2 = makeEvent(eventDatetime = now.plusDays(7), title = "Event +7d")
        val sklad1 = makeSkladchina(deadline = now.plusDays(1), title = "Sklad +1d")
        val sklad2 = makeSkladchina(deadline = now.plusDays(5), title = "Sklad +5d")

        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(event1, goingCount = 3),
            EventWithGoingCount(event2, goingCount = 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns listOf(
            SkladchinaWithAggregates(sklad1, 100, 4, 1),
            SkladchinaWithAggregates(sklad2, 50, 2, 1)
        )

        val result = service.getClubActivities(clubId, userId, null)

        assertTrue(result.past.isEmpty())
        val titles = result.upcoming.map { it.title }
        assertEquals(listOf("Sklad +1d", "Event +3d", "Sklad +5d", "Event +7d"), titles)
    }

    @Test
    fun `upcoming pins action-required events to the top, ahead of sooner non-action items`() {
        val actionEventId = UUID.randomUUID()
        // Action-required event is the LATEST by date, yet must surface first.
        val actionEvent = makeEvent(id = actionEventId, eventDatetime = now.plusDays(7), title = "Needs vote +7d")
        val plainEvent = makeEvent(eventDatetime = now.plusDays(3), title = "Event +3d")
        val sklad = makeSkladchina(deadline = now.plusDays(1), title = "Sklad +1d")

        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(actionEvent, 0),
            EventWithGoingCount(plainEvent, 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns listOf(
            SkladchinaWithAggregates(sklad, 0, 0, 0)
        )
        every { eventRepository.findActionRequiredEventIds(clubId, userId, any()) } returns setOf(actionEventId)

        val result = service.getClubActivities(clubId, userId, null)

        // Pinned first; the remainder keeps soonest-first by date.
        assertEquals(listOf("Needs vote +7d", "Sklad +1d", "Event +3d"), result.upcoming.map { it.title })
    }

    @Test
    fun `past sorted most-recent-first by relevant date, interleaving events and skladchinas`() {
        val event1 = makeEvent(
            status = EventStatus.completed, eventDatetime = now.minusDays(2), title = "Event -2d"
        )
        val event2 = makeEvent(
            status = EventStatus.cancelled, eventDatetime = now.minusDays(8), title = "Event -8d"
        )
        val sklad1 = makeSkladchina(
            status = SkladchinaStatus.closed_success, deadline = now.minusDays(1), title = "Sklad -1d"
        )
        val sklad2 = makeSkladchina(
            status = SkladchinaStatus.closed_failed, deadline = now.minusDays(5), title = "Sklad -5d"
        )

        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(event1, 0),
            EventWithGoingCount(event2, 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns listOf(
            SkladchinaWithAggregates(sklad1, 0, 0, 0),
            SkladchinaWithAggregates(sklad2, 0, 0, 0)
        )

        val result = service.getClubActivities(clubId, userId, null)

        assertTrue(result.upcoming.isEmpty())
        val titles = result.past.map { it.title }
        assertEquals(listOf("Sklad -1d", "Event -2d", "Sklad -5d", "Event -8d"), titles)
    }

    @Test
    fun `completed items go to past, others to upcoming`() {
        val upcomingEvent = makeEvent(status = EventStatus.upcoming, eventDatetime = now.plusDays(2))
        val completedEvent = makeEvent(status = EventStatus.completed, eventDatetime = now.minusDays(2))
        val cancelledEvent = makeEvent(status = EventStatus.cancelled, eventDatetime = now.minusDays(3))
        val activeSklad = makeSkladchina(status = SkladchinaStatus.active, deadline = now.plusDays(1))
        val closedSklad = makeSkladchina(status = SkladchinaStatus.closed_success, deadline = now.minusDays(1))

        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(upcomingEvent, 0),
            EventWithGoingCount(completedEvent, 0),
            EventWithGoingCount(cancelledEvent, 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns listOf(
            SkladchinaWithAggregates(activeSklad, 0, 0, 0),
            SkladchinaWithAggregates(closedSklad, 0, 0, 0)
        )

        val result = service.getClubActivities(clubId, userId, null)

        assertEquals(2, result.upcoming.size)
        assertEquals(3, result.past.size)
        assertTrue(result.upcoming.none { it.isCompleted })
        assertTrue(result.past.all { it.isCompleted })
    }

    @Test
    fun `upcoming tie on relevant date is broken by id ascending`() {
        // Same eventDatetime/deadline → secondary sort by id ASC.
        val sharedDate = now.plusDays(4)
        val firstId = UUID(0L, 1L)
        val secondId = UUID(0L, 2L)
        require(firstId < secondId) { "Test setup wrong: $firstId should be < $secondId" }

        val firstActivity = makeEvent(id = firstId, eventDatetime = sharedDate, title = "First")
        val secondActivity = makeSkladchina(id = secondId, deadline = sharedDate, title = "Second")

        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(firstActivity, 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns listOf(
            SkladchinaWithAggregates(secondActivity, 0, 0, 0)
        )

        val result = service.getClubActivities(clubId, userId, null)

        assertEquals(listOf("First", "Second"), result.upcoming.map { it.title })
    }

    @Test
    fun `type filter event returns only events in both groups without hitting skladchina repository`() {
        val upcomingEvent = makeEvent(eventDatetime = now.plusDays(2), title = "Upcoming")
        val pastEvent = makeEvent(status = EventStatus.completed, eventDatetime = now.minusDays(2), title = "Past")
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(upcomingEvent, 1),
            EventWithGoingCount(pastEvent, 1)
        )

        val result = service.getClubActivities(clubId, userId, ActivityType.EVENT)

        assertTrue(result.upcoming.all { it is ActivityItemDto.EventActivity })
        assertTrue(result.past.all { it is ActivityItemDto.EventActivity })
        assertEquals(listOf("Upcoming"), result.upcoming.map { it.title })
        assertEquals(listOf("Past"), result.past.map { it.title })
    }

    @Test
    fun `type filter skladchina returns only skladchinas without hitting event repository`() {
        val activeSklad = makeSkladchina(deadline = now.plusDays(2), title = "Active")
        val closedSklad = makeSkladchina(
            status = SkladchinaStatus.closed_failed, deadline = now.minusDays(2), title = "Closed"
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns listOf(
            SkladchinaWithAggregates(activeSklad, 0, 2, 0),
            SkladchinaWithAggregates(closedSklad, 0, 2, 0)
        )

        val result = service.getClubActivities(clubId, userId, ActivityType.SKLADCHINA)

        assertTrue(result.upcoming.all { it is ActivityItemDto.SkladchinaActivity })
        assertTrue(result.past.all { it is ActivityItemDto.SkladchinaActivity })
        assertEquals(listOf("Active"), result.upcoming.map { it.title })
        assertEquals(listOf("Closed"), result.past.map { it.title })
    }

    @Test
    fun `empty club returns both arrays empty`() {
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns emptyList()
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns emptyList()

        val result = service.getClubActivities(clubId, userId, null)

        assertTrue(result.upcoming.isEmpty())
        assertTrue(result.past.isEmpty())
    }

    @Test
    fun `descriptionPreview is null when description is null`() {
        val event = makeEvent(description = null, eventDatetime = now.plusDays(1))
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(event, 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns emptyList()

        val result = service.getClubActivities(clubId, userId, null)
        val item = result.upcoming.single() as ActivityItemDto.EventActivity

        assertNull(item.descriptionPreview)
    }

    @Test
    fun `descriptionPreview is null when description is blank`() {
        val event = makeEvent(description = "   \n  ", eventDatetime = now.plusDays(1))
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(event, 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns emptyList()

        val result = service.getClubActivities(clubId, userId, null)
        val item = result.upcoming.single() as ActivityItemDto.EventActivity

        assertNull(item.descriptionPreview)
    }

    @Test
    fun `descriptionPreview returns trimmed full text when within limit`() {
        val event = makeEvent(description = "  short text  ", eventDatetime = now.plusDays(1))
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(event, 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns emptyList()

        val result = service.getClubActivities(clubId, userId, null)
        val item = result.upcoming.single() as ActivityItemDto.EventActivity

        assertEquals("short text", item.descriptionPreview)
    }

    @Test
    fun `descriptionPreview truncates to 40 chars with ellipsis when longer`() {
        val longText = "a".repeat(60)
        val event = makeEvent(description = longText, eventDatetime = now.plusDays(1))
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(event, 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns emptyList()

        val result = service.getClubActivities(clubId, userId, null)
        val item = result.upcoming.single() as ActivityItemDto.EventActivity

        assertNotNull(item.descriptionPreview)
        assertEquals("a".repeat(40) + "…", item.descriptionPreview)
    }

    @Test
    fun `event photoUrl propagates from domain to activity dto`() {
        val event = makeEvent(eventDatetime = now.plusDays(1), photoUrl = "https://cdn.example.com/event.jpg")
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(event, 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns emptyList()

        val result = service.getClubActivities(clubId, userId, null)
        val item = result.upcoming.single() as ActivityItemDto.EventActivity

        assertEquals("https://cdn.example.com/event.jpg", item.photoUrl)
    }

    @Test
    fun `event photoUrl is null when domain has none`() {
        val event = makeEvent(eventDatetime = now.plusDays(1), photoUrl = null)
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(event, 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns emptyList()

        val result = service.getClubActivities(clubId, userId, null)
        val item = result.upcoming.single() as ActivityItemDto.EventActivity

        assertNull(item.photoUrl)
    }

    @Test
    fun `skladchina photoUrl propagates from domain to activity dto`() {
        val sklad = makeSkladchina(deadline = now.plusDays(1), photoUrl = "https://cdn.example.com/sklad.jpg")
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns emptyList()
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns listOf(
            SkladchinaWithAggregates(sklad, 0, 0, 0)
        )

        val result = service.getClubActivities(clubId, userId, null)
        val item = result.upcoming.single() as ActivityItemDto.SkladchinaActivity

        assertEquals("https://cdn.example.com/sklad.jpg", item.photoUrl)
    }

    // ---- helpers ----

    private fun makeEvent(
        id: UUID = UUID.randomUUID(),
        createdAt: OffsetDateTime = now,
        eventDatetime: OffsetDateTime = now.plusDays(7),
        status: EventStatus = EventStatus.upcoming,
        title: String = "Event",
        description: String? = null,
        photoUrl: String? = null
    ): Event = Event(
        id = id,
        clubId = clubId,
        createdBy = UUID.randomUUID(),
        title = title,
        description = description,
        locationText = "Place",
        eventDatetime = eventDatetime,
        participantLimit = 20,
        votingOpensDaysBefore = 14,
        status = status,
        stage2Triggered = false,
        attendanceMarked = false,
        attendanceFinalized = false,
        photoUrl = photoUrl,
        createdAt = createdAt,
        updatedAt = createdAt
    )

    private fun makeSkladchina(
        id: UUID = UUID.randomUUID(),
        createdAt: OffsetDateTime = now,
        deadline: OffsetDateTime = now.plusDays(7),
        status: SkladchinaStatus = SkladchinaStatus.active,
        title: String = "Sklad",
        photoUrl: String? = null
    ): Skladchina = Skladchina(
        id = id,
        clubId = clubId,
        creatorId = UUID.randomUUID(),
        title = title,
        description = null,
        rules = null,
        photoUrl = photoUrl,
        template = SkladchinaTemplate.custom,
        paymentMode = SkladchinaMode.fixed_equal,
        totalGoalKopecks = 100000L,
        paymentLink = "https://pay.me",
        paymentMethodNote = null,
        eventId = null,
        deadline = deadline,
        affectsReputation = false,
        status = status,
        closedAt = null,
        closedBy = null,
        createdAt = createdAt,
        updatedAt = createdAt
    )
}
