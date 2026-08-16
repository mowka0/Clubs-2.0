package com.clubs.event

import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.membership.Membership
import com.clubs.membership.MembershipRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Ручное напоминание подтвердить участие (event-stage2-composition.md § «Что дальше»).
 * Проверяем три вещи, которые легко сломать: КТО может напоминать (владелец / активный со-орг),
 * КОГДА (только открытое окно подтверждения) и что DM уходит РОВНО по числу реально помеченных
 * строк — иначе повторный тап превратил бы кнопку в спам-машину.
 */
class Stage2ReminderServiceTest {

    private val eventRepository = mockk<EventRepository>()
    private val eventResponseRepository = mockk<EventResponseRepository>(relaxed = true)
    private val clubRepository = mockk<ClubRepository>()
    private val membershipRepository = mockk<MembershipRepository>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = Stage2ReminderService(
        eventRepository, eventResponseRepository, clubRepository,
        ClubRoleGuard(clubRepository, membershipRepository), eventPublisher
    )

    private val eventId = UUID.randomUUID()
    private val clubId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()
    private val targetId = UUID.randomUUID()

    private fun stubEvent(
        status: EventStatus = EventStatus.stage_2,
        eventDatetime: OffsetDateTime = OffsetDateTime.now().plusHours(3)
    ) {
        every { eventRepository.findById(eventId) } returns Event(
            id = eventId, clubId = clubId, createdBy = ownerId, title = "Встреча", description = null,
            locationText = "Бар", eventDatetime = eventDatetime, participantLimit = 10,
            votingOpensDaysBefore = 14, status = status, stage2Triggered = true,
            attendanceMarked = false, attendanceFinalized = false, photoUrl = null,
            createdAt = null, updatedAt = null
        )
        val club = mockk<Club>()
        every { club.ownerId } returns ownerId
        every { club.id } returns clubId
        every { clubRepository.findById(clubId) } returns club
    }

    private fun membership(role: MembershipRole, status: MembershipStatus, userId: UUID): Membership {
        val now = OffsetDateTime.now()
        return Membership(
            id = UUID.randomUUID(), userId = userId, clubId = clubId, status = status, role = role,
            joinedAt = now, subscriptionExpiresAt = null, createdAt = now, updatedAt = now
        )
    }

    @Test
    fun `owner reminds a single member and the DM event carries only marked recipients`() {
        stubEvent()
        every { eventResponseRepository.markStage2Reminded(eventId, listOf(targetId)) } returns listOf(777L)

        val result = service.remind(eventId, ownerId, targetId)

        assertEquals(1, result.remindedCount)
        verify { eventPublisher.publishEvent(match<Stage2ReminderSentEvent> { it.telegramIds == listOf(777L) }) }
    }

    @Test
    fun `active co-organizer may remind`() {
        stubEvent()
        val coOrgId = UUID.randomUUID()
        every { membershipRepository.findByUserAndClub(coOrgId, clubId) } returns
            membership(MembershipRole.co_organizer, MembershipStatus.active, coOrgId)
        every { eventResponseRepository.markStage2Reminded(eventId, listOf(targetId)) } returns listOf(777L)

        assertEquals(1, service.remind(eventId, coOrgId, targetId).remindedCount)
    }

    @Test
    fun `plain member may not remind`() {
        stubEvent()
        val memberId = UUID.randomUUID()
        every { membershipRepository.findByUserAndClub(memberId, clubId) } returns
            membership(MembershipRole.member, MembershipStatus.active, memberId)

        assertFailsWith<ForbiddenException> { service.remind(eventId, memberId, targetId) }
        verify(exactly = 0) { eventResponseRepository.markStage2Reminded(any(), any()) }
    }

    /** Fail-close: замороженное членство снимает права со-организатора мгновенно. */
    @Test
    fun `frozen co-organizer may not remind`() {
        stubEvent()
        val coOrgId = UUID.randomUUID()
        every { membershipRepository.findByUserAndClub(coOrgId, clubId) } returns
            membership(MembershipRole.co_organizer, MembershipStatus.frozen, coOrgId)

        assertFailsWith<ForbiddenException> { service.remind(eventId, coOrgId, targetId) }
        verify(exactly = 0) { eventResponseRepository.markStage2Reminded(any(), any()) }
    }

    @Test
    fun `reminding is rejected before Stage 2 opens`() {
        stubEvent(status = EventStatus.upcoming)

        val ex = assertFailsWith<ValidationException> { service.remind(eventId, ownerId, targetId) }
        assertEquals("Confirmation is not open for this event", ex.message)
        verify(exactly = 0) { eventResponseRepository.markStage2Reminded(any(), any()) }
    }

    @Test
    fun `reminding is rejected once the event has started`() {
        stubEvent(eventDatetime = OffsetDateTime.now().minusMinutes(1))

        val ex = assertFailsWith<ValidationException> { service.remind(eventId, ownerId, targetId) }
        assertEquals("Event has already started", ex.message)
        verify(exactly = 0) { eventResponseRepository.markStage2Reminded(any(), any()) }
    }

    /**
     * Повторный тап по уже напомненному участнику: UPDATE не находит строк, DM-событие не
     * публикуется вовсе — иначе участник получал бы напоминание при каждом нажатии.
     */
    @Test
    fun `repeated reminder sends nothing`() {
        stubEvent()
        every { eventResponseRepository.markStage2Reminded(eventId, listOf(targetId)) } returns emptyList()

        assertEquals(0, service.remind(eventId, ownerId, targetId).remindedCount)
        verify(exactly = 0) { eventPublisher.publishEvent(any<Stage2ReminderSentEvent>()) }
    }

    /** «Напомнить всем»: цели считает сервер, клиентский список не участвует. */
    @Test
    fun `remind-all targets the server-side remindable set`() {
        stubEvent()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        every { eventResponseRepository.findStage2RemindableUserIds(eventId) } returns listOf(a, b)
        every { eventResponseRepository.markStage2Reminded(eventId, listOf(a, b)) } returns listOf(1L, 2L)

        assertEquals(2, service.remind(eventId, ownerId, null).remindedCount)
        verify { eventResponseRepository.findStage2RemindableUserIds(eventId) }
    }
}
