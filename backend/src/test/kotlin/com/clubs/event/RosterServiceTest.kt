package com.clubs.event

import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.LimitKind
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.enums.Stage_2Vote
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Набор состава для форматов с лимитом (docs/modules/event-formats.md).
 *
 * MAX: голос кладёт в состав или очередь, дедлайн закрывает состав при любом числе участников.
 * MIN: голос всегда кладёт в состав (верхней границы нет), дедлайн либо закрывает состав, либо
 * отменяет встречу — это и есть правило, названное организатору при создании.
 */
class RosterServiceTest {

    private val eventRepository = mockk<EventRepository>(relaxed = true)
    private val eventResponseRepository = mockk<EventResponseRepository>(relaxed = true)
    private val eventService = mockk<EventService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = RosterService(
        eventRepository, eventResponseRepository, eventService, eventPublisher, defaultLeadMinutes = 1080
    )

    private val eventId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    private fun event(
        participantLimit: Int? = 6,
        limitKind: LimitKind? = LimitKind.max,
        status: EventStatus = EventStatus.upcoming,
        eventDatetime: OffsetDateTime = OffsetDateTime.now().plusDays(2),
        stage2LeadMinutes: Int? = null
    ) = Event(
        id = eventId,
        clubId = UUID.randomUUID(),
        createdBy = UUID.randomUUID(),
        title = "Настолка",
        description = null,
        locationText = "Кофейня",
        eventDatetime = eventDatetime,
        participantLimit = participantLimit,
        limitKind = limitKind,
        votingOpensDaysBefore = 14,
        stage2LeadMinutes = stage2LeadMinutes,
        status = status,
        stage2Triggered = status != EventStatus.upcoming,
        attendanceMarked = false,
        attendanceFinalized = false,
        photoUrl = null,
        createdAt = null,
        updatedAt = null
    )

    private fun response(stage2: Stage_2Vote? = null) = EventResponse(
        id = UUID.randomUUID(),
        eventId = eventId,
        userId = userId,
        stage1Vote = Stage_1Vote.going,
        stage1Timestamp = OffsetDateTime.now(),
        stage2Vote = stage2,
        stage2Timestamp = null,
        finalStatus = null,
        attendance = null,
        attendanceFinalized = false,
        createdAt = null,
        updatedAt = null
    )

    // ---- голос на наборе ----

    @Test
    fun `голос «Иду» кладёт в состав, пока есть места`() {
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns response()
        every { eventResponseRepository.countConfirmed(eventId) } returns 3

        service.applyVote(event(participantLimit = 6), userId, Stage_1Vote.going)

        verify(exactly = 1) {
            eventResponseRepository.updateStage2Vote(any(), Stage_2Vote.confirmed, FinalStatus.confirmed)
        }
    }

    @Test
    fun `MAX, голос «Иду» при полном составе ставит в очередь`() {
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns response()
        every { eventResponseRepository.countConfirmed(eventId) } returns 6

        service.applyVote(event(participantLimit = 6), userId, Stage_1Vote.going)

        verify(exactly = 1) {
            eventResponseRepository.updateStage2Vote(any(), Stage_2Vote.waitlisted, FinalStatus.waitlisted)
        }
    }

    @Test
    fun `AC-1 MIN, голос «Иду» сверх порога всё равно кладёт в состав`() {
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns response()
        every { eventResponseRepository.countConfirmed(eventId) } returns 6

        service.applyVote(event(participantLimit = 6, limitKind = LimitKind.min), userId, Stage_1Vote.going)

        // Верхней границы у формата нет: порог — это «сколько нужно», а не «сколько влезет».
        verify(exactly = 1) {
            eventResponseRepository.updateStage2Vote(any(), Stage_2Vote.confirmed, FinalStatus.confirmed)
        }
        verify(exactly = 0) {
            eventResponseRepository.updateStage2Vote(any(), Stage_2Vote.waitlisted, any())
        }
    }

    @Test
    fun `смена голоса освобождает место и поднимает первого из очереди`() {
        val queued = response(Stage_2Vote.waitlisted).copy(userId = UUID.randomUUID())
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns
            response(Stage_2Vote.confirmed)
        every { eventResponseRepository.findFirstWaitlisted(eventId) } returns queued

        service.applyVote(event(), userId, Stage_1Vote.maybe)

        // Выход из набора — не «отказ»: строка обнуляется, а не становится declined.
        verify(exactly = 1) { eventResponseRepository.clearStage2Vote(any()) }
        verify(exactly = 1) {
            eventResponseRepository.updateStage2Vote(queued.id, Stage_2Vote.confirmed, FinalStatus.confirmed)
        }
        verify(exactly = 1) { eventPublisher.publishEvent(WaitlistPromotedEvent(eventId, queued.userId)) }
    }

    @Test
    fun `выход из очереди никого не поднимает`() {
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns
            response(Stage_2Vote.waitlisted)

        service.applyVote(event(), userId, Stage_1Vote.not_going)

        verify(exactly = 1) { eventResponseRepository.clearStage2Vote(any()) }
        verify(exactly = 0) { eventResponseRepository.findFirstWaitlisted(any()) }
    }

    @Test
    fun `формат «сколько придёт» набор не трогает`() {
        service.applyVote(event(participantLimit = null, limitKind = null), userId, Stage_1Vote.going)

        verify(exactly = 0) { eventResponseRepository.updateStage2Vote(any(), any(), any()) }
        verify(exactly = 0) { eventResponseRepository.lockEventSlots(any()) }
    }

    // ---- закрытие набора ----

    @Test
    fun `AC-4 MIN, порог взят к дедлайну — состав закрыт, участникам уходит DM`() {
        every { eventResponseRepository.countConfirmed(eventId) } returns 6
        val target = event(participantLimit = 6, limitKind = LimitKind.min)

        assertTrue(service.handleRosterDeadline(target))

        verify(exactly = 1) { eventRepository.transitionToStage2(eventId) }
        verify(exactly = 1) { eventPublisher.publishEvent(RosterClosedEvent(target, 6)) }
        verify(exactly = 0) { eventService.cancelBySystem(any(), any()) }
    }

    @Test
    fun `AC-5 MIN, порог не взят — встреча отменяется с названной причиной`() {
        every { eventResponseRepository.countConfirmed(eventId) } returns 4
        val target = event(participantLimit = 6, limitKind = LimitKind.min)

        assertTrue(service.handleRosterDeadline(target))

        // Отмена идёт обычным каскадом (сбор → released, DM заинтересованным), а причина
        // называет само правило формата — участник видит её в DM и на странице встречи.
        verify(exactly = 1) {
            eventService.cancelBySystem(target, "Не набрали 6 участников к закрытию набора")
        }
        verify(exactly = 0) { eventRepository.transitionToStage2(any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<RosterClosedEvent>()) }
    }

    @Test
    fun `AC-6 MAX закрывает состав при любом числе участников, включая ноль`() {
        every { eventResponseRepository.countConfirmed(eventId) } returns 0
        val target = event(participantLimit = 6)

        assertTrue(service.handleRosterDeadline(target))

        // Недобора у формата не существует: встреча состоится в любом случае.
        verify(exactly = 1) { eventRepository.transitionToStage2(eventId) }
        verify(exactly = 1) { eventPublisher.publishEvent(RosterClosedEvent(target, 0)) }
        verify(exactly = 0) { eventService.cancelBySystem(any(), any()) }
    }

    @Test
    fun `AC-7 встреча уже началась — состав замораживается молча, MIN не отменяется`() {
        every { eventResponseRepository.countConfirmed(eventId) } returns 4
        val target = event(
            participantLimit = 6,
            limitKind = LimitKind.min,
            eventDatetime = OffsetDateTime.now().minusMinutes(1)
        )

        assertTrue(service.handleRosterDeadline(target))

        // Состав зафиксирован (иначе отметка явки читала бы пустой список), но ни «состав
        // собран» посреди встречи, ни отмена уже начавшейся встречи не происходят.
        verify(exactly = 1) { eventRepository.transitionToStage2(eventId) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<RosterClosedEvent>()) }
        verify(exactly = 0) { eventService.cancelBySystem(any(), any()) }
    }

    @Test
    fun `формат «сколько придёт» идёт мимо набора`() {
        assertEquals(false, service.handleRosterDeadline(event(participantLimit = null, limitKind = null)))
    }

    @Test
    fun `дедлайн набора — старт минус интервал, с подстановкой глобального дефолта`() {
        val start = OffsetDateTime.now().plusDays(3)
        assertEquals(start.minusMinutes(1080), service.rosterDeadline(event(eventDatetime = start)))
        assertEquals(
            start.minusMinutes(360),
            service.rosterDeadline(event(eventDatetime = start, stage2LeadMinutes = 360))
        )
    }

    // ---- освобождение места киком / выходом из клуба ----

    @Test
    fun `releaseSeat on roster collection only promotes the queue`() {
        every { eventRepository.findById(eventId) } returns event(status = EventStatus.upcoming)
        val promoted = UUID.randomUUID()
        every { eventResponseRepository.promoteFirstWaitlisted(eventId) } returns promoted

        assertEquals(promoted, service.releaseSeat(eventId))

        verify(exactly = 1) { eventPublisher.publishEvent(WaitlistPromotedEvent(eventId, promoted)) }
        verify(exactly = 1) { eventPublisher.publishEvent(EventRosterChangedEvent(eventId)) }
        // Состав ещё не объявлен — отменять и звать организатора нечего.
        verify(exactly = 0) { eventService.cancelBySystem(any(), any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(ofType(RosterBrokenEvent::class)) }
    }

    @Test
    fun `releaseSeat after close with nobody left cancels the event`() {
        val target = event(status = EventStatus.stage_2)
        every { eventRepository.findById(eventId) } returns target
        every { eventResponseRepository.promoteFirstWaitlisted(eventId) } returns null
        every { eventResponseRepository.countConfirmed(eventId) } returns 0

        service.releaseSeat(eventId)

        verify(exactly = 1) { eventService.cancelBySystem(target, Stage2Service.ROSTER_DISBANDED_REASON) }
    }

    @Test
    fun `releaseSeat after close crossing MIN threshold notifies the organizer`() {
        val target = event(participantLimit = 4, limitKind = LimitKind.min, status = EventStatus.stage_2)
        every { eventRepository.findById(eventId) } returns target
        every { eventResponseRepository.promoteFirstWaitlisted(eventId) } returns null
        every { eventResponseRepository.countConfirmed(eventId) } returns 3

        service.releaseSeat(eventId)

        verify(exactly = 1) { eventPublisher.publishEvent(RosterBrokenEvent(target, 3, 4)) }
        verify(exactly = 0) { eventService.cancelBySystem(any(), any()) }
    }

    @Test
    fun `releaseSeat after close on MAX below limit stays silent`() {
        every { eventRepository.findById(eventId) } returns event(participantLimit = 6, status = EventStatus.stage_2)
        every { eventResponseRepository.promoteFirstWaitlisted(eventId) } returns null
        every { eventResponseRepository.countConfirmed(eventId) } returns 3

        service.releaseSeat(eventId)

        // У MAX неполный состав ничего не значит: место просто пустует.
        verify(exactly = 0) { eventService.cancelBySystem(any(), any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(ofType(RosterBrokenEvent::class)) }
        verify(exactly = 1) { eventPublisher.publishEvent(EventRosterChangedEvent(eventId)) }
    }
}
