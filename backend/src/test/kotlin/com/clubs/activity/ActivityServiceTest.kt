package com.clubs.activity

import com.clubs.activity.dto.ActivityItemDto
import com.clubs.activity.mapper.ActivityMapper
import com.clubs.event.Event
import com.clubs.event.EventRepository
import com.clubs.event.EventWithGoingCount
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.SkladchinaMode
import com.clubs.generated.jooq.enums.SkladchinaStatus
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
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-05-22T18:30:00Z")

    @BeforeEach
    fun setUp() {
        eventRepository = mockk()
        skladchinaRepository = mockk()
        service = ActivityService(
            eventRepository = eventRepository,
            skladchinaRepository = skladchinaRepository,
            activityMapper = ActivityMapper()
        )
    }

    @Test
    fun `merges and sorts events with skladchinas by createdAt desc`() {
        val event1 = makeEvent(createdAt = now.minusHours(1), title = "Event newer")
        val event2 = makeEvent(createdAt = now.minusHours(5), title = "Event older")
        val sklad1 = makeSkladchina(createdAt = now.minusHours(2), title = "Sklad mid")
        val sklad2 = makeSkladchina(createdAt = now.minusHours(6), title = "Sklad oldest")

        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(event1, goingCount = 3),
            EventWithGoingCount(event2, goingCount = 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns listOf(
            SkladchinaWithAggregates(sklad1, 100, 4, 1),
            SkladchinaWithAggregates(sklad2, 50, 2, 1)
        )

        val result = service.getClubActivities(clubId, null, includeCompleted = true, page = 0, size = 20)

        assertEquals(4, result.totalElements)
        val titles = result.content.map { it.title }
        assertEquals(listOf("Event newer", "Sklad mid", "Event older", "Sklad oldest"), titles)
    }

    @Test
    fun `tie on createdAt is broken by id ascending`() {
        // UUID.compareTo is signed-long comparison on (msb, lsb). Two UUIDs with
        // msb=0 fall back to lsb ordering, which matches lexicographic intuition.
        val sharedTime = now.minusMinutes(10)
        val firstId = UUID(0L, 1L)
        val secondId = UUID(0L, 2L)
        require(firstId < secondId) { "Test setup wrong: $firstId should be < $secondId" }

        val firstActivity = makeEvent(id = firstId, createdAt = sharedTime, title = "First")
        val secondActivity = makeSkladchina(id = secondId, createdAt = sharedTime, title = "Second")

        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(firstActivity, 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns listOf(
            SkladchinaWithAggregates(secondActivity, 0, 0, 0)
        )

        val result = service.getClubActivities(clubId, null, includeCompleted = true, page = 0, size = 20)

        // createdAt is equal → secondary sort by id ascending → firstId (event) before secondId (sklad)
        assertEquals(listOf("First", "Second"), result.content.map { it.title })
    }

    @Test
    fun `type filter event excludes skladchinas without hitting skladchina repository`() {
        val event = makeEvent(createdAt = now.minusHours(1))
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(event, 1)
        )

        val result = service.getClubActivities(clubId, ActivityType.EVENT, includeCompleted = true, page = 0, size = 20)

        assertEquals(1, result.totalElements)
        assertTrue(result.content.all { it is ActivityItemDto.EventActivity })
    }

    @Test
    fun `type filter skladchina excludes events without hitting event repository`() {
        val sklad = makeSkladchina(createdAt = now.minusHours(2))
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns listOf(
            SkladchinaWithAggregates(sklad, 0, 2, 0)
        )

        val result = service.getClubActivities(
            clubId, ActivityType.SKLADCHINA, includeCompleted = true, page = 0, size = 20
        )

        assertEquals(1, result.totalElements)
        assertTrue(result.content.all { it is ActivityItemDto.SkladchinaActivity })
    }

    @Test
    fun `includeCompleted false excludes completed events and closed skladchinas`() {
        val activeEvent = makeEvent(status = EventStatus.upcoming, createdAt = now.minusHours(1))
        val completedEvent = makeEvent(status = EventStatus.completed, createdAt = now.minusHours(3))
        val cancelledEvent = makeEvent(status = EventStatus.cancelled, createdAt = now.minusHours(4))
        val activeSklad = makeSkladchina(status = SkladchinaStatus.active, createdAt = now.minusHours(2))

        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(activeEvent, 0),
            EventWithGoingCount(completedEvent, 0),
            EventWithGoingCount(cancelledEvent, 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, false) } returns listOf(
            SkladchinaWithAggregates(activeSklad, 0, 0, 0)
        )

        val result = service.getClubActivities(clubId, null, includeCompleted = false, page = 0, size = 20)

        assertEquals(2, result.totalElements)
        assertTrue(result.content.none { it.isCompleted })
    }

    @Test
    fun `pagination slices in-memory merged result correctly`() {
        val all = (0 until 5).map { i ->
            makeEvent(createdAt = now.minusMinutes(i.toLong()), title = "Event $i")
        }
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns
            all.map { EventWithGoingCount(it, 0) }
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns emptyList()

        val page0 = service.getClubActivities(clubId, null, includeCompleted = true, page = 0, size = 2)
        assertEquals(5L, page0.totalElements)
        assertEquals(3, page0.totalPages)
        assertEquals(listOf("Event 0", "Event 1"), page0.content.map { it.title })

        val page1 = service.getClubActivities(clubId, null, includeCompleted = true, page = 1, size = 2)
        assertEquals(listOf("Event 2", "Event 3"), page1.content.map { it.title })

        val page2 = service.getClubActivities(clubId, null, includeCompleted = true, page = 2, size = 2)
        assertEquals(listOf("Event 4"), page2.content.map { it.title })

        val pageOutOfRange = service.getClubActivities(clubId, null, includeCompleted = true, page = 5, size = 2)
        assertTrue(pageOutOfRange.content.isEmpty())
        assertEquals(5L, pageOutOfRange.totalElements)
    }

    @Test
    fun `empty club returns empty content`() {
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns emptyList()
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns emptyList()

        val result = service.getClubActivities(clubId, null, includeCompleted = true, page = 0, size = 20)

        assertEquals(0L, result.totalElements)
        assertEquals(0, result.totalPages)
        assertTrue(result.content.isEmpty())
    }

    @Test
    fun `descriptionPreview is null when description is null`() {
        val event = makeEvent(description = null, createdAt = now.minusHours(1))
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(event, 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns emptyList()

        val result = service.getClubActivities(clubId, null, includeCompleted = true, page = 0, size = 20)
        val item = result.content.single() as ActivityItemDto.EventActivity

        assertNull(item.descriptionPreview)
    }

    @Test
    fun `descriptionPreview is null when description is blank`() {
        val event = makeEvent(description = "   \n  ", createdAt = now.minusHours(1))
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(event, 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns emptyList()

        val result = service.getClubActivities(clubId, null, includeCompleted = true, page = 0, size = 20)
        val item = result.content.single() as ActivityItemDto.EventActivity

        assertNull(item.descriptionPreview)
    }

    @Test
    fun `descriptionPreview returns trimmed full text when within limit`() {
        val event = makeEvent(description = "  short text  ", createdAt = now.minusHours(1))
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(event, 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns emptyList()

        val result = service.getClubActivities(clubId, null, includeCompleted = true, page = 0, size = 20)
        val item = result.content.single() as ActivityItemDto.EventActivity

        assertEquals("short text", item.descriptionPreview)
    }

    @Test
    fun `descriptionPreview truncates to 40 chars with ellipsis when longer`() {
        val longText = "a".repeat(60)
        val event = makeEvent(description = longText, createdAt = now.minusHours(1))
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns listOf(
            EventWithGoingCount(event, 0)
        )
        every { skladchinaRepository.findAllByClubWithAggregates(clubId, true) } returns emptyList()

        val result = service.getClubActivities(clubId, null, includeCompleted = true, page = 0, size = 20)
        val item = result.content.single() as ActivityItemDto.EventActivity

        assertNotNull(item.descriptionPreview)
        assertEquals("a".repeat(40) + "…", item.descriptionPreview)
    }

    // ---- helpers ----

    private fun makeEvent(
        id: UUID = UUID.randomUUID(),
        createdAt: OffsetDateTime = now,
        status: EventStatus = EventStatus.upcoming,
        title: String = "Event",
        description: String? = null
    ): Event = Event(
        id = id,
        clubId = clubId,
        createdBy = UUID.randomUUID(),
        title = title,
        description = description,
        locationText = "Place",
        eventDatetime = createdAt.plusDays(7),
        participantLimit = 20,
        votingOpensDaysBefore = 14,
        status = status,
        stage2Triggered = false,
        attendanceMarked = false,
        attendanceFinalized = false,
        createdAt = createdAt,
        updatedAt = createdAt
    )

    private fun makeSkladchina(
        id: UUID = UUID.randomUUID(),
        createdAt: OffsetDateTime = now,
        status: SkladchinaStatus = SkladchinaStatus.active,
        title: String = "Sklad"
    ): Skladchina = Skladchina(
        id = id,
        clubId = clubId,
        creatorId = UUID.randomUUID(),
        title = title,
        description = null,
        rules = null,
        photoUrl = null,
        paymentMode = SkladchinaMode.fixed_equal,
        totalGoalKopecks = 100000L,
        paymentLink = "https://pay.me",
        paymentMethodNote = null,
        deadline = createdAt.plusDays(7),
        affectsReputation = false,
        status = status,
        closedAt = null,
        closedBy = null,
        createdAt = createdAt,
        updatedAt = createdAt
    )
}
