package com.clubs.event

import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
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
            eventMapper, eventPublisher, skladchinaRepository, stage2TriggerMinutesBefore = 1080L,
            rosterWarningMinutes = 180L
        )
    }

    @Test
    fun `createEvent publishes EventCreatedEvent after persisting`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)
        every { eventRepository.create(any(), clubId, ownerId, any()) } returns event

        eventService.createEvent(clubId, request(), ownerId)

        verify(exactly = 1) { eventPublisher.publishEvent(EventCreatedEvent(event)) }
    }

    @Test
    fun `AC-11 createEvent rejects a minimum when the roster deadline is already in the past`() {
        // Дедлайн набора оказался бы в прошлом, и ближайший тик отменил бы встречу, не дав
        // никому проголосовать. Правило живёт в сервисе: дефолт интервала известен только ему.
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)

        assertThrows<ValidationException> {
            eventService.createEvent(
                clubId,
                request().copy(
                    minParticipants = 4,
                    eventDatetime = OffsetDateTime.now().plusHours(2),
                    stage2LeadMinutes = 360
                ),
                ownerId
            )
        }

        verify(exactly = 0) { eventRepository.create(any(), any(), any(), any()) }
    }

    @Test
    fun `AC-11 createEvent without a minimum rejects a date inside the roster interval too`() {
        // Режим «состав закроется сразу» (бывшая «срочная») убран целиком (PO 2026-09-05):
        // у любой встречи с местами набор обязан помещаться до старта.
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)

        assertThrows<ValidationException> {
            eventService.createEvent(
                clubId,
                request().copy(
                    eventDatetime = OffsetDateTime.now().plusHours(2),
                    stage2LeadMinutes = 360
                ),
                ownerId
            )
        }

        verify(exactly = 0) { eventRepository.create(any(), any(), any(), any()) }
    }

    @Test
    fun `AC-11 createEvent for an open event ignores the roster interval`() {
        // У открытой встречи набора нет — близкая дата законна.
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId).copy(participantLimit = null)
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)
        every { eventRepository.create(any(), clubId, ownerId, any()) } returns event

        eventService.createEvent(
            clubId,
            request().copy(
                format = EventFormatInput.OPEN,
                participantLimit = null,
                eventDatetime = OffsetDateTime.now().plusHours(2)
            ),
            ownerId
        )

        verify(exactly = 1) { eventRepository.create(any(), clubId, ownerId, any()) }
    }

    @Test
    fun `createEvent for a regular event does not touch stage 2`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)
        every { eventRepository.create(any(), clubId, ownerId, any()) } returns event

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
        every { eventRepository.create(capture(requestSlot), clubId, ownerId, any()) } returns event

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
        every { eventRepository.create(capture(requestSlot), clubId, ownerId, any()) } returns event

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
        verify(exactly = 0) { eventRepository.create(any(), any(), any(), any()) }
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

    @Test
    fun `updateEvent moving the date publishes EventEditedEvent with old and new state`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        val newDatetime = event.eventDatetime.plusDays(2)
        every { eventRepository.findById(event.id) } returns event
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)
        every { eventRepository.updateEvent(event.id, any()) } returns 1

        eventService.updateEvent(event.id, ownerId, editRequest(event, eventDatetime = newDatetime))

        verify(exactly = 1) { eventRepository.updateEvent(event.id, match { it.eventDatetime == newDatetime }) }
        verify(exactly = 1) {
            eventPublisher.publishEvent(
                EventEditedEvent(event.copy(eventDatetime = newDatetime), oldEvent = event)
            )
        }
    }

    @Test
    fun `updateEvent changing the location notifies members`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { eventRepository.findById(event.id) } returns event
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)
        every { eventRepository.updateEvent(event.id, any()) } returns 1

        eventService.updateEvent(event.id, ownerId, editRequest(event, locationText = "Bar 2"))

        verify(exactly = 1) {
            eventPublisher.publishEvent(
                match<EventEditedEvent> { it.isLocationChanged && !it.isDatetimeChanged }
            )
        }
    }

    @Test
    fun `updateEvent marks non-critical changes as quiet`() {
        // Решение PO 2026-07-26: правка названия/описания/фото/лимита не дёргает весь клуб
        // звуком, но событие всё равно публикуется — по нему слушатель тихо перерисовывает
        // живой закреп в чате (в нём висит и название).
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { eventRepository.findById(event.id) } returns event
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)
        every { eventRepository.updateEvent(event.id, any()) } returns 1

        eventService.updateEvent(
            event.id,
            ownerId,
            editRequest(event, title = "Другое название", description = "новое описание", participantLimit = 30)
        )

        verify(exactly = 1) { eventRepository.updateEvent(event.id, any()) }
        verify(exactly = 1) {
            eventPublisher.publishEvent(
                match<EventEditedEvent> { !it.hasCriticalChanges && !it.isDatetimeChanged && !it.isLocationChanged }
            )
        }
    }

    @Test
    fun `updateEvent throws Forbidden when caller is not the organizer`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { eventRepository.findById(event.id) } returns event
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)

        assertThrows<ForbiddenException> {
            eventService.updateEvent(event.id, UUID.randomUUID(), editRequest(event, title = "Взлом"))
        }

        verify(exactly = 0) { eventRepository.updateEvent(any(), any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any()) }
    }

    @Test
    fun `updateEvent throws Conflict when the event is not editable (guard yields 0 rows)`() {
        // 0 строк от SQL-guard: событие в stage_2 (в т.ч. срочное), начавшееся, completed или cancelled.
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { eventRepository.findById(event.id) } returns event
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)
        every { eventRepository.updateEvent(event.id, any()) } returns 0

        assertThrows<ConflictException> {
            eventService.updateEvent(event.id, ownerId, editRequest(event, title = "Поздно"))
        }

        verify(exactly = 0) { eventPublisher.publishEvent(any()) }
    }

    @Test
    fun `updateEvent throws NotFound when the event is missing`() {
        val eventId = UUID.randomUUID()
        every { eventRepository.findById(eventId) } returns null

        assertThrows<NotFoundException> {
            eventService.updateEvent(
                eventId,
                UUID.randomUUID(),
                UpdateEventRequest(
                    title = "Нет такого",
                    locationHint = "у входа",
                    eventDatetime = OffsetDateTime.now().plusDays(2),
                    participantLimit = 20
                )
            )
        }

        verify(exactly = 0) { eventRepository.updateEvent(any(), any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any()) }
    }

    @Test
    fun `updateEvent rejects a participant limit on an open event`() {
        // Формат неизменяем: лимит у открытой встречи означал бы смену продуктового типа.
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val openEvent = sampleEvent(clubId, ownerId).copy(participantLimit = null)
        every { eventRepository.findById(openEvent.id) } returns openEvent
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)

        assertThrows<ValidationException> {
            eventService.updateEvent(openEvent.id, ownerId, editRequest(openEvent, participantLimit = 10))
        }

        verify(exactly = 0) { eventRepository.updateEvent(any(), any()) }
    }

    @Test
    fun `updateEvent rejects dropping the limit of a seated event`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { eventRepository.findById(event.id) } returns event
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)

        assertThrows<ValidationException> {
            eventService.updateEvent(event.id, ownerId, editRequest(event, participantLimit = null))
        }

        verify(exactly = 0) { eventRepository.updateEvent(any(), any()) }
    }

    @Test
    fun `updateEvent rejects a date that leaves no room for the roster`() {
        // AC-11: набор обязан помещаться до старта у любой встречи с местами, минимум не при чём
        // (PO 2026-09-05). Без минимума перенос так близко тоже запрещён.
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val event = sampleEvent(clubId, ownerId)
        every { eventRepository.findById(event.id) } returns event
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)

        assertThrows<ValidationException> {
            eventService.updateEvent(
                event.id, ownerId,
                editRequest(event, stage2LeadMinutes = 2160)
                    .copy(eventDatetime = OffsetDateTime.now().plusHours(2))
            )
        }

        verify(exactly = 0) { eventRepository.updateEvent(any(), any()) }
    }

    @Test
    fun `updateEvent rejects a date that leaves no room for a roster with a minimum`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val minEvent = sampleEvent(clubId, ownerId).copy(minParticipants = 4)
        every { eventRepository.findById(minEvent.id) } returns minEvent
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)

        assertThrows<ValidationException> {
            eventService.updateEvent(
                minEvent.id, ownerId,
                editRequest(minEvent, stage2LeadMinutes = 2160)
                    .copy(eventDatetime = OffsetDateTime.now().plusHours(2))
            )
        }

        verify(exactly = 0) { eventRepository.updateEvent(any(), any()) }
    }

    /**
     * Запрос-правка «всё как у события, кроме перечисленного»: PUT требует полный набор полей,
     * а в тестах интересна ровно одна изменённая величина.
     */
    private fun editRequest(
        event: Event,
        title: String = event.title,
        description: String? = event.description,
        locationText: String? = event.locationText,
        locationHint: String? = event.locationHint,
        eventDatetime: OffsetDateTime = event.eventDatetime,
        participantLimit: Int? = event.participantLimit,
        minParticipants: Int? = event.minParticipants,
        stage2LeadMinutes: Int? = event.stage2LeadMinutes
    ) = UpdateEventRequest(
        title = title,
        description = description,
        locationText = locationText,
        locationLat = event.locationLat,
        locationLon = event.locationLon,
        locationHint = locationHint,
        eventDatetime = eventDatetime,
        participantLimit = participantLimit,
        minParticipants = minParticipants,
        stage2LeadMinutes = stage2LeadMinutes,
        photoUrl = event.photoUrl
    )

    // Тизер-тесты строят сервис с НАСТОЯЩИМ маппером: относительный порядок и содержимое
    // проекции — часть контракта, relaxed-мок вернул бы неразличимые заглушки.
    private fun teaserService() = EventService(
        eventRepository, clubRepository, ClubRoleGuard(clubRepository, guardMembershipRepository),
        EventMapper(240L, 1080L), eventPublisher, skladchinaRepository, stage2TriggerMinutesBefore = 1080L,
        rosterWarningMinutes = 180L
    )

    @Test
    fun `getClubEventsTeaser splits by time, drops cancelled and applies limits`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val now = OffsetDateTime.now()
        every { clubRepository.findById(clubId) } returns club(clubId, ownerId)
        val events = buildList {
            // 4 будущих (лимит 3), 4 прошедших (лимит 3), 1 отменённое будущее (должно выпасть)
            repeat(4) { i ->
                add(EventWithGoingCount(
                    sampleEvent(clubId, ownerId).copy(id = UUID.randomUUID(), eventDatetime = now.plusDays(i + 1L)),
                    goingCount = i, confirmedCount = 0
                ))
            }
            repeat(4) { i ->
                add(EventWithGoingCount(
                    sampleEvent(clubId, ownerId).copy(
                        id = UUID.randomUUID(),
                        eventDatetime = now.minusDays(i + 1L),
                        status = EventStatus.completed
                    ),
                    goingCount = 0, confirmedCount = 5
                ))
            }
            add(EventWithGoingCount(
                sampleEvent(clubId, ownerId).copy(id = UUID.randomUUID(), eventDatetime = now.plusDays(9), status = EventStatus.cancelled),
                goingCount = 0, confirmedCount = 0
            ))
        }
        every { eventRepository.findAllByClubWithGoingCount(clubId) } returns events
        every { eventRepository.countPastEvents(clubId, any()) } returns 4

        val teaser = teaserService().getClubEventsTeaser(clubId)

        assertEquals(3, teaser.upcoming.size)
        assertEquals(3, teaser.past.size)
        assertEquals(4, teaser.totalPastCount)
        // Ближайшее будущее — первым; недавнее прошедшее — первым.
        assertEquals(events[0].event.id, teaser.upcoming.first().id)
        assertEquals(events[4].event.id, teaser.past.first().id)
    }

    @Test
    fun `getClubEventsTeaser throws NotFound for a missing or soft-deleted club`() {
        val missingClubId = UUID.randomUUID()
        every { clubRepository.findById(missingClubId) } returns null
        assertThrows<NotFoundException> { teaserService().getClubEventsTeaser(missingClubId) }

        val inactiveClubId = UUID.randomUUID()
        every { clubRepository.findById(inactiveClubId) } returns
            club(inactiveClubId, UUID.randomUUID()).copy(isActive = false)
        assertThrows<NotFoundException> { teaserService().getClubEventsTeaser(inactiveClubId) }
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
        format = EventFormatInput.NORMAL,
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
