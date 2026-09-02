package com.clubs.event

import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.enums.Stage_2Vote
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Набор состава обычной встречи v2 (docs/modules/event-formats.md § 3, § 4, § 7).
 *
 * Потолок — всегда потолок: голос кладёт в состав или очередь. Минимум по желанию: ① дедлайн
 * отменяет при недоборе, ② предупреждение до дедлайна, ③ распад после закрытия зовёт
 * организатора, «Проводим» глушит ③.
 */
class RosterServiceTest {

    private val eventRepository = mockk<EventRepository>(relaxed = true)
    private val eventResponseRepository = mockk<EventResponseRepository>(relaxed = true)
    private val eventService = mockk<EventService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val clubRepository = mockk<ClubRepository>()
    private val clubRoleGuard = mockk<ClubRoleGuard>(relaxed = true)
    private val service = RosterService(
        eventRepository, eventResponseRepository, eventService, eventPublisher, clubRepository, clubRoleGuard,
        defaultLeadMinutes = 1080, warningMinutes = 180
    )

    private val eventId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    private fun event(
        participantLimit: Int? = 6,
        minParticipants: Int? = null,
        status: EventStatus = EventStatus.upcoming,
        eventDatetime: OffsetDateTime = OffsetDateTime.now().plusDays(2),
        stage2LeadMinutes: Int? = null,
        rosterDecidedAt: OffsetDateTime? = null
    ) = Event(
        id = eventId,
        clubId = UUID.randomUUID(),
        createdBy = UUID.randomUUID(),
        title = "Настолка",
        description = null,
        locationText = "Кофейня",
        eventDatetime = eventDatetime,
        participantLimit = participantLimit,
        minParticipants = minParticipants,
        votingOpensDaysBefore = 14,
        stage2LeadMinutes = stage2LeadMinutes,
        status = status,
        stage2Triggered = status != EventStatus.upcoming,
        attendanceMarked = false,
        attendanceFinalized = false,
        rosterDecidedAt = rosterDecidedAt,
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
    fun `голос «Иду» при полном составе ставит в очередь`() {
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns response()
        every { eventResponseRepository.countConfirmed(eventId) } returns 6

        service.applyVote(event(participantLimit = 6), userId, Stage_1Vote.going)

        verify(exactly = 1) {
            eventResponseRepository.updateStage2Vote(any(), Stage_2Vote.waitlisted, FinalStatus.waitlisted)
        }
    }

    @Test
    fun `AC-2 потолок работает и при минимуме — седьмой на «4–6» встаёт в очередь`() {
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns response()
        every { eventResponseRepository.countConfirmed(eventId) } returns 6

        service.applyVote(event(participantLimit = 6, minParticipants = 4), userId, Stage_1Vote.going)

        verify(exactly = 1) {
            eventResponseRepository.updateStage2Vote(any(), Stage_2Vote.waitlisted, FinalStatus.waitlisted)
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
    fun `AC-12 открытая встреча набор не трогает`() {
        service.applyVote(event(participantLimit = null), userId, Stage_1Vote.going)

        verify(exactly = 0) { eventResponseRepository.updateStage2Vote(any(), any(), any()) }
        verify(exactly = 0) { eventResponseRepository.lockEventSlots(any()) }
    }

    // ---- правило ①: дедлайн набора ----

    @Test
    fun `AC-1 без минимума состав закрывается при любом числе участников, включая ноль`() {
        every { eventResponseRepository.countConfirmed(eventId) } returns 0
        val target = event(participantLimit = 6)

        assertTrue(service.handleRosterDeadline(target))

        verify(exactly = 1) { eventRepository.transitionToStage2(eventId) }
        verify(exactly = 1) { eventPublisher.publishEvent(RosterClosedEvent(target, 0)) }
        verify(exactly = 0) { eventService.cancelBySystem(any(), any()) }
    }

    @Test
    fun `AC-3 минимум набран к дедлайну — состав закрыт, напоминания сброшены под новый этап`() {
        every { eventResponseRepository.countConfirmed(eventId) } returns 4
        val target = event(participantLimit = 6, minParticipants = 4)

        assertTrue(service.handleRosterDeadline(target))

        verify(exactly = 1) { eventRepository.transitionToStage2(eventId) }
        verify(exactly = 1) { eventPublisher.publishEvent(RosterClosedEvent(target, 4)) }
        // AC-10: одно напоминание на человека НА ЭТАП — после закрытия отметки набора обнуляются.
        verify(exactly = 1) { eventResponseRepository.clearStage2Reminders(eventId) }
        verify(exactly = 0) { eventService.cancelBySystem(any(), any()) }
    }

    @Test
    fun `AC-3 минимум не набран — встреча отменяется с названной причиной`() {
        every { eventResponseRepository.countConfirmed(eventId) } returns 3
        val target = event(participantLimit = 10, minParticipants = 4)

        assertTrue(service.handleRosterDeadline(target))

        // Отмена идёт обычным каскадом (сбор → released, DM заинтересованным), а причина
        // называет само правило — участник видит её в DM и на странице встречи.
        verify(exactly = 1) {
            eventService.cancelBySystem(target, "Не набрали 4 участников к закрытию набора")
        }
        verify(exactly = 0) { eventRepository.transitionToStage2(any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(ofType(RosterClosedEvent::class)) }
    }

    @Test
    fun `встреча уже началась — состав замораживается молча, недобор не отменяет`() {
        every { eventResponseRepository.countConfirmed(eventId) } returns 2
        val target = event(
            participantLimit = 6,
            minParticipants = 4,
            eventDatetime = OffsetDateTime.now().minusMinutes(1)
        )

        assertTrue(service.handleRosterDeadline(target))

        // Состав зафиксирован (иначе отметка явки читала бы пустой список), но ни «состав
        // собран» посреди встречи, ни отмена уже начавшейся встречи не происходят.
        verify(exactly = 1) { eventRepository.transitionToStage2(eventId) }
        verify(exactly = 0) { eventPublisher.publishEvent(ofType(RosterClosedEvent::class)) }
        verify(exactly = 0) { eventService.cancelBySystem(any(), any()) }
    }

    @Test
    fun `открытая встреча идёт мимо набора`() {
        assertFalse(service.handleRosterDeadline(event(participantLimit = null)))
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

    // ---- правило ②: предупреждение о недоборе ----

    @Test
    fun `AC-4 предупреждение уходит при недоборе и ставит отметку`() {
        val now = OffsetDateTime.now()
        val target = event(participantLimit = 10, minParticipants = 4)
        every { eventRepository.markRosterWarningSent(eventId, now) } returns 1
        every { eventResponseRepository.countConfirmed(eventId) } returns 2

        service.handleRosterWarning(target, now)

        verify(exactly = 1) {
            eventPublisher.publishEvent(RosterWarningEvent(target, 2, 4, target.eventDatetime.minusMinutes(1080)))
        }
    }

    @Test
    fun `AC-4 минимум уже набран — отметка ставится, DM нет`() {
        val now = OffsetDateTime.now()
        every { eventRepository.markRosterWarningSent(eventId, now) } returns 1
        every { eventResponseRepository.countConfirmed(eventId) } returns 4

        service.handleRosterWarning(event(participantLimit = 10, minParticipants = 4), now)

        verify(exactly = 1) { eventRepository.markRosterWarningSent(eventId, now) }
        verify(exactly = 0) { eventPublisher.publishEvent(ofType(RosterWarningEvent::class)) }
    }

    @Test
    fun `AC-4 отметка уже стоит — второго предупреждения нет`() {
        val now = OffsetDateTime.now()
        every { eventRepository.markRosterWarningSent(eventId, now) } returns 0
        every { eventResponseRepository.countConfirmed(eventId) } returns 1

        service.handleRosterWarning(event(participantLimit = 10, minParticipants = 4), now)

        verify(exactly = 0) { eventPublisher.publishEvent(ofType(RosterWarningEvent::class)) }
    }

    @Test
    fun `без минимума правило ② молчит`() {
        service.handleRosterWarning(event(participantLimit = 10), OffsetDateTime.now())

        verify(exactly = 0) { eventRepository.markRosterWarningSent(any(), any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(ofType(RosterWarningEvent::class)) }
    }

    @Test
    fun `проход тика берёт встречи из отдельной выборки с окном предупреждения`() {
        val now = OffsetDateTime.now()
        val target = event(participantLimit = 10, minParticipants = 4)
        every { eventRepository.findEventsForRosterWarning(now, 1080, 180) } returns listOf(target)
        every { eventRepository.markRosterWarningSent(eventId, now) } returns 1
        every { eventResponseRepository.countConfirmed(eventId) } returns 1

        service.sendDueRosterWarnings(now)

        verify(exactly = 1) { eventPublisher.publishEvent(ofType(RosterWarningEvent::class)) }
    }

    // ---- «Проводим» ----

    private fun stubManager(target: Event) {
        every { eventRepository.findById(eventId) } returns target
        every { clubRepository.findById(target.clubId) } returns mockk<Club>(relaxed = true)
    }

    @Test
    fun `AC-6 «Проводим» ставит отметку и перерисовывает закреп`() {
        val target = event(participantLimit = 6, minParticipants = 4, status = EventStatus.stage_2)
        stubManager(target)
        every { eventResponseRepository.countConfirmed(eventId) } returns 3
        every { eventRepository.markRosterDecided(eventId) } returns 1

        val result = service.proceed(eventId, userId)

        assertEquals(ProceedResult(3, alreadyDecided = false), result)
        verify(exactly = 1) { eventRepository.markRosterDecided(eventId) }
        verify(exactly = 1) { eventPublisher.publishEvent(EventRosterChangedEvent(eventId)) }
    }

    @Test
    fun `AC-6 повторный «Проводим» — no-op`() {
        val target = event(
            participantLimit = 6, minParticipants = 4, status = EventStatus.stage_2,
            rosterDecidedAt = OffsetDateTime.now().minusHours(1)
        )
        stubManager(target)
        every { eventResponseRepository.countConfirmed(eventId) } returns 3

        assertEquals(ProceedResult(3, alreadyDecided = true), service.proceed(eventId, userId))

        verify(exactly = 0) { eventRepository.markRosterDecided(any()) }
    }

    @Test
    fun `AC-6 «Проводим» отвергается при составе не ниже минимума, до закрытия и после старта`() {
        stubManager(event(participantLimit = 6, minParticipants = 4, status = EventStatus.stage_2))
        every { eventResponseRepository.countConfirmed(eventId) } returns 4
        assertEquals(
            "Состав не ниже минимума — подтверждать нечего",
            assertThrows<ValidationException> { service.proceed(eventId, userId) }.message
        )

        stubManager(event(participantLimit = 6, minParticipants = 4, status = EventStatus.upcoming))
        assertThrows<ValidationException> { service.proceed(eventId, userId) }

        stubManager(
            event(
                participantLimit = 6, minParticipants = 4, status = EventStatus.stage_2,
                eventDatetime = OffsetDateTime.now().minusMinutes(1)
            )
        )
        assertEquals("Встреча уже началась", assertThrows<ValidationException> { service.proceed(eventId, userId) }.message)

        stubManager(event(participantLimit = 6, minParticipants = 4, status = EventStatus.cancelled))
        assertEquals("Встреча отменена", assertThrows<ValidationException> { service.proceed(eventId, userId) }.message)

        stubManager(event(participantLimit = 6, status = EventStatus.stage_2))
        assertThrows<ValidationException> { service.proceed(eventId, userId) }

        verify(exactly = 0) { eventRepository.markRosterDecided(any()) }
    }

    @Test
    fun `AC-6 «Проводим» без права вести встречи — 403, до любых проверок состава`() {
        stubManager(event(participantLimit = 6, minParticipants = 4, status = EventStatus.stage_2))
        every { clubRoleGuard.requireCapability(any<Club>(), any(), any()) } throws ForbiddenException("no")

        assertThrows<ForbiddenException> { service.proceed(eventId, userId) }

        verify(exactly = 0) { eventResponseRepository.countConfirmed(any()) }
        verify(exactly = 0) { eventRepository.markRosterDecided(any()) }
    }

    // ---- освобождение места киком / выходом из клуба (AC-9) ----

    @Test
    fun `releaseSeat на наборе только двигает очередь`() {
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
    fun `AC-8 releaseSeat после закрытия с опустевшим составом отменяет встречу`() {
        val target = event(status = EventStatus.stage_2)
        every { eventRepository.findById(eventId) } returns target
        every { eventResponseRepository.promoteFirstWaitlisted(eventId) } returns null
        every { eventResponseRepository.countConfirmed(eventId) } returns 0

        service.releaseSeat(eventId)

        verify(exactly = 1) { eventService.cancelBySystem(target, RosterService.ROSTER_DISBANDED_REASON) }
    }

    @Test
    fun `AC-8 опустевший состав отменяется и после «Проводим»`() {
        val target = event(
            participantLimit = 6, minParticipants = 4, status = EventStatus.stage_2,
            rosterDecidedAt = OffsetDateTime.now()
        )

        service.settleClosedRoster(target, 0)

        verify(exactly = 1) { eventService.cancelBySystem(target, RosterService.ROSTER_DISBANDED_REASON) }
    }

    @Test
    fun `AC-5 пересечение минимума вниз зовёт организатора`() {
        val target = event(participantLimit = 6, minParticipants = 4, status = EventStatus.stage_2)
        every { eventRepository.findById(eventId) } returns target
        every { eventResponseRepository.promoteFirstWaitlisted(eventId) } returns null
        every { eventResponseRepository.countConfirmed(eventId) } returns 3

        service.releaseSeat(eventId)

        verify(exactly = 1) { eventPublisher.publishEvent(RosterBrokenEvent(target, 3, 4)) }
        verify(exactly = 0) { eventService.cancelBySystem(any(), any()) }
    }

    @Test
    fun `AC-5 повторное пересечение после возврата состава снова зовёт, а после «Проводим» — нет`() {
        val target = event(participantLimit = 6, minParticipants = 4, status = EventStatus.stage_2)

        service.settleClosedRoster(target, 3)
        service.settleClosedRoster(target, 3)
        verify(exactly = 2) { eventPublisher.publishEvent(RosterBrokenEvent(target, 3, 4)) }

        service.settleClosedRoster(target.copy(rosterDecidedAt = OffsetDateTime.now()), 3)
        verify(exactly = 2) { eventPublisher.publishEvent(ofType(RosterBrokenEvent::class)) }

        // Ниже черты, но не на ней: уже сигналили, повторных DM на каждый отказ нет.
        service.settleClosedRoster(target, 2)
        verify(exactly = 2) { eventPublisher.publishEvent(ofType(RosterBrokenEvent::class)) }
    }

    @Test
    fun `releaseSeat после закрытия без минимума ниже потолка молчит`() {
        every { eventRepository.findById(eventId) } returns event(participantLimit = 6, status = EventStatus.stage_2)
        every { eventResponseRepository.promoteFirstWaitlisted(eventId) } returns null
        every { eventResponseRepository.countConfirmed(eventId) } returns 3

        service.releaseSeat(eventId)

        // Без минимума неполный состав ничего не значит: место просто пустует.
        verify(exactly = 0) { eventService.cancelBySystem(any(), any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(ofType(RosterBrokenEvent::class)) }
        verify(exactly = 1) { eventPublisher.publishEvent(EventRosterChangedEvent(eventId)) }
    }
}
