package com.clubs.event

import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.FinalStatus
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
 * Набор состава формата «🎟 Встреча с местами» (docs/modules/event-roster-threshold.md):
 * голос кладёт в состав или очередь; дедлайн закрывает состав, если порог взят, и всего лишь
 * сообщает организатору о недоборе, если нет — набор при этом продолжается.
 */
class RosterServiceTest {

    private val eventRepository = mockk<EventRepository>(relaxed = true)
    private val eventResponseRepository = mockk<EventResponseRepository>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = RosterService(
        eventRepository, eventResponseRepository, eventPublisher, defaultLeadMinutes = 1080
    )

    private val eventId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    private fun event(
        participantLimit: Int? = 6,
        isUrgent: Boolean = false,
        status: EventStatus = EventStatus.upcoming,
        eventDatetime: OffsetDateTime = OffsetDateTime.now().plusDays(2),
        stage2LeadMinutes: Int? = null,
        rosterShortfallAt: OffsetDateTime? = null
    ) = Event(
        id = eventId,
        clubId = UUID.randomUUID(),
        createdBy = UUID.randomUUID(),
        title = "Настолка",
        description = null,
        locationText = "Кофейня",
        eventDatetime = eventDatetime,
        participantLimit = participantLimit,
        votingOpensDaysBefore = 14,
        stage2LeadMinutes = stage2LeadMinutes,
        isUrgent = isUrgent,
        status = status,
        stage2Triggered = status != EventStatus.upcoming,
        attendanceMarked = false,
        attendanceFinalized = false,
        rosterShortfallAt = rosterShortfallAt,
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
    fun `срочная и открытая встречи набор не трогают`() {
        service.applyVote(event(isUrgent = true), userId, Stage_1Vote.going)
        service.applyVote(event(participantLimit = null), userId, Stage_1Vote.going)

        verify(exactly = 0) { eventResponseRepository.updateStage2Vote(any(), any(), any()) }
        verify(exactly = 0) { eventResponseRepository.lockEventSlots(any()) }
    }

    // ---- закрытие набора ----

    @Test
    fun `набрали к дедлайну — состав закрыт, участникам уходит DM`() {
        every { eventResponseRepository.countConfirmed(eventId) } returns 6
        val target = event(participantLimit = 6)

        assertTrue(service.handleRosterDeadline(target))

        verify(exactly = 1) { eventRepository.transitionToStage2(eventId) }
        verify(exactly = 1) { eventPublisher.publishEvent(RosterClosedEvent(target, 6)) }
        verify(exactly = 0) { eventRepository.markRosterShortfall(any(), any()) }
    }

    @Test
    fun `не набрали — состав НЕ закрывается, организатору уходит DM`() {
        every { eventResponseRepository.countConfirmed(eventId) } returns 4
        every { eventRepository.markRosterShortfall(eventId, any()) } returns 1
        val target = event(participantLimit = 6)

        assertTrue(service.handleRosterDeadline(target))

        verify(exactly = 1) { eventRepository.markRosterShortfall(eventId, any()) }
        verify(exactly = 1) { eventPublisher.publishEvent(RosterShortfallEvent(target, 4, 6)) }
        // Набор продолжается до самой встречи: перевода в stage_2 быть не должно.
        verify(exactly = 0) { eventRepository.transitionToStage2(any()) }
    }

    @Test
    fun `повторный тик по той же встрече второго DM не шлёт`() {
        every { eventResponseRepository.countConfirmed(eventId) } returns 4
        // Гард `roster_shortfall_at IS NULL`: отметка уже стоит, апдейт трогает ноль строк.
        every { eventRepository.markRosterShortfall(eventId, any()) } returns 0
        val target = event(participantLimit = 6, rosterShortfallAt = OffsetDateTime.now().minusMinutes(30))

        assertTrue(service.handleRosterDeadline(target))

        verify(exactly = 0) { eventPublisher.publishEvent(any<RosterShortfallEvent>()) }
        verify(exactly = 0) { eventRepository.transitionToStage2(any()) }
    }

    @Test
    fun `добрали позже дедлайна — состав закрывается тем же тиком`() {
        every { eventResponseRepository.countConfirmed(eventId) } returns 6
        val target = event(participantLimit = 6, rosterShortfallAt = OffsetDateTime.now().minusHours(2))

        assertTrue(service.handleRosterDeadline(target))

        verify(exactly = 1) { eventRepository.transitionToStage2(eventId) }
        verify(exactly = 1) { eventPublisher.publishEvent(RosterClosedEvent(target, 6)) }
    }

    @Test
    fun `встреча началась неполной — состав замораживается молча`() {
        every { eventResponseRepository.countConfirmed(eventId) } returns 4
        val target = event(
            participantLimit = 6,
            eventDatetime = OffsetDateTime.now().minusMinutes(1),
            rosterShortfallAt = OffsetDateTime.now().minusHours(3)
        )

        assertTrue(service.handleRosterDeadline(target))

        // Состав зафиксирован (иначе отметка явки читала бы пустой список), но «состав собран»
        // посреди встречи никому не уходит.
        verify(exactly = 1) { eventRepository.transitionToStage2(eventId) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<RosterClosedEvent>()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<RosterShortfallEvent>()) }
    }

    @Test
    fun `срочная встреча идёт мимо набора`() {
        assertEquals(false, service.handleRosterDeadline(event(isUrgent = true)))
        assertEquals(false, service.handleRosterDeadline(event(participantLimit = null)))
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
}
