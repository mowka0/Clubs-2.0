package com.clubs.event

import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.membership.Membership
import com.clubs.membership.MembershipRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * getMyVote must report the Stage-2 final_status when present (confirmed / waitlisted /
 * declined) and fall back to the stage-1 vote otherwise. The EventPage keys BOTH the
 * confirm/decline buttons and the status badge off this single field; a confirmed user
 * reading back "going" leaves the UI stuck (the bug this guards against).
 */
class VoteServiceTest {

    private val eventRepository = mockk<EventRepository>()
    private val eventResponseRepository = mockk<EventResponseRepository>()
    private val membershipRepository = mockk<MembershipRepository>()
    private val clubRepository = mockk<ClubRepository>()
    private val eventPublisher = mockk<org.springframework.context.ApplicationEventPublisher>(relaxed = true)
    // Механика набора состава (V83) проверяется отдельно (RosterServiceTest): здесь важно, что
    // голос записан и прочитан обратно.
    private val rosterService = mockk<RosterService>(relaxed = true)
    private val service = VoteService(
        eventRepository, eventResponseRepository, membershipRepository, clubRepository,
        ClubRoleGuard(clubRepository, membershipRepository), rosterService, eventPublisher
    )

    private val eventId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        every { eventRepository.findById(eventId) } returns mockk(relaxed = true)
    }

    @Test
    fun `reports final_status confirmed over the unchanged stage-1 vote`() {
        stubResponse(Stage_1Vote.going, FinalStatus.confirmed)
        assertEquals("confirmed", service.getMyVote(eventId, userId).vote)
    }

    @Test
    fun `reports final_status declined`() {
        stubResponse(Stage_1Vote.maybe, FinalStatus.declined)
        assertEquals("declined", service.getMyVote(eventId, userId).vote)
    }

    @Test
    fun `reports final_status waitlisted`() {
        stubResponse(Stage_1Vote.going, FinalStatus.waitlisted)
        assertEquals("waitlisted", service.getMyVote(eventId, userId).vote)
    }

    @Test
    fun `falls back to the stage-1 vote before Stage 2`() {
        stubResponse(Stage_1Vote.going, null)
        assertEquals("going", service.getMyVote(eventId, userId).vote)
    }

    @Test
    fun `is null when the user has not voted`() {
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns null
        assertNull(service.getMyVote(eventId, userId).vote)
    }

    // --- castVote ---

    private val clubId = UUID.randomUUID()

    private fun upcomingEvent(eventDatetime: OffsetDateTime, votingOpensDaysBefore: Int = 14) = Event(
        id = eventId, clubId = clubId, createdBy = UUID.randomUUID(), title = "E", description = null,
        locationText = "P", eventDatetime = eventDatetime, participantLimit = 10,
        votingOpensDaysBefore = votingOpensDaysBefore, status = EventStatus.upcoming,
        stage2Triggered = false, attendanceMarked = false, attendanceFinalized = false,
        photoUrl = null, createdAt = null, updatedAt = null
    )

    @Test
    fun `castVote rejects before the voting window opens (precise boundary, S1-001)`() {
        // event is 3 days + 5h away, window = 3 days → opens in 5h → must be rejected.
        // The old ChronoUnit.DAYS.between truncated to 3 and wrongly ACCEPTED this.
        every { eventRepository.findById(eventId) } returns
            upcomingEvent(OffsetDateTime.now().plusDays(3).plusHours(5), votingOpensDaysBefore = 3)
        every { membershipRepository.isMember(userId, clubId) } returns true

        val ex = assertFailsWith<ValidationException> {
            service.castVote(eventId, userId, CastVoteRequest("going"))
        }
        assertEquals("Voting has not started yet", ex.message)
    }

    @Test
    fun `castVote accepts within the voting window`() {
        every { eventRepository.findById(eventId) } returns
            upcomingEvent(OffsetDateTime.now().plusDays(2), votingOpensDaysBefore = 3)
        every { membershipRepository.isMember(userId, clubId) } returns true
        every { eventResponseRepository.upsertStage1Vote(eventId, userId, Stage_1Vote.going) } returns mockk()
        every { eventResponseRepository.countByVote(eventId) } returns mapOf("going" to 1, "maybe" to 0, "notGoing" to 0)

        val result = service.castVote(eventId, userId, CastVoteRequest("going"))

        assertEquals("going", result.vote)
        assertEquals(1, result.goingCount)
    }

    @Test
    fun `castVote rejects a non-member`() {
        every { eventRepository.findById(eventId) } returns upcomingEvent(OffsetDateTime.now().plusDays(1))
        every { membershipRepository.isMember(userId, clubId) } returns false

        assertFailsWith<ForbiddenException> { service.castVote(eventId, userId, CastVoteRequest("going")) }
    }

    @Test
    fun `castVote rejects when the event is no longer upcoming`() {
        every { eventRepository.findById(eventId) } returns
            upcomingEvent(OffsetDateTime.now().plusHours(2)).copy(status = EventStatus.stage_2)
        every { membershipRepository.isMember(userId, clubId) } returns true

        val ex = assertFailsWith<ValidationException> {
            service.castVote(eventId, userId, CastVoteRequest("going"))
        }
        assertEquals("Voting is not available for this event", ex.message)
    }

    // --- getEventResponders: dispute_note privacy (F5-06) ---

    private fun responderWithNote(
        note: String?,
        username: String? = "petr_s",
        remindedAt: OffsetDateTime? = null
    ) = EventResponderInfo(
        userId = UUID.randomUUID(), firstName = "A", lastName = null, avatarUrl = null,
        stage1Vote = Stage_1Vote.going, finalStatus = FinalStatus.confirmed,
        attendance = AttendanceStatus.disputed, disputeNote = note, telegramUsername = username,
        stage2RemindedAt = remindedAt
    )

    private fun stubRespondersWithNote(ownerId: UUID, viewerId: UUID) {
        every { eventRepository.findById(eventId) } returns upcomingEvent(OffsetDateTime.now().plusDays(1))
        every { membershipRepository.isMember(viewerId, clubId) } returns true
        val club = mockk<Club>()
        every { club.ownerId } returns ownerId
        every { club.id } returns clubId
        every { clubRepository.findById(clubId) } returns club
        // Смотрящий по умолчанию не со-орг: у guard'а нет membership-строки с ролью.
        every { membershipRepository.findByUserAndClub(viewerId, clubId) } returns null
        every { eventResponseRepository.findRespondersWithUsers(eventId) } returns listOf(responderWithNote("был там"))
    }

    @Test
    fun `getEventResponders exposes disputeNote to the club owner (F5-06)`() {
        stubRespondersWithNote(ownerId = userId, viewerId = userId)
        assertEquals("был там", service.getEventResponders(eventId, userId).single().disputeNote)
    }

    @Test
    fun `getEventResponders hides disputeNote from a non-owner member (F5-06)`() {
        stubRespondersWithNote(ownerId = UUID.randomUUID(), viewerId = userId)
        assertNull(service.getEventResponders(eventId, userId).single().disputeNote)
    }

    // --- getEventResponders: telegram_username privacy (event-stage2-composition.md § 5) ---
    // Username нужен менеджеру, чтобы открыть личный чат с не ответившим на Этапе 2. Рядовому
    // участнику контакты соседей по событию не отдаём — гейт тот же, что у dispute-note.

    @Test
    fun `getEventResponders exposes telegramUsername to the club owner`() {
        stubRespondersWithNote(ownerId = userId, viewerId = userId)
        assertEquals("petr_s", service.getEventResponders(eventId, userId).single().telegramUsername)
    }

    @Test
    fun `getEventResponders exposes telegramUsername to an active co-organizer`() {
        stubRespondersWithNote(ownerId = UUID.randomUUID(), viewerId = userId)
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns
            membership(MembershipRole.co_organizer, MembershipStatus.active)
        assertEquals("petr_s", service.getEventResponders(eventId, userId).single().telegramUsername)
    }

    @Test
    fun `getEventResponders hides telegramUsername from a plain member`() {
        stubRespondersWithNote(ownerId = UUID.randomUUID(), viewerId = userId)
        assertNull(service.getEventResponders(eventId, userId).single().telegramUsername)
    }

    /** Со-организатор без активного членства прав не имеет (fail-close, ClubRoleGuard). */
    @Test
    fun `getEventResponders hides telegramUsername from a frozen co-organizer`() {
        stubRespondersWithNote(ownerId = UUID.randomUUID(), viewerId = userId)
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns
            membership(MembershipRole.co_organizer, MembershipStatus.frozen)
        assertNull(service.getEventResponders(eventId, userId).single().telegramUsername)
    }

    /** Username не задан в Telegram — менеджер тоже получает null (личного чата не существует). */
    @Test
    fun `getEventResponders returns null telegramUsername when the user has none`() {
        stubRespondersWithNote(ownerId = userId, viewerId = userId)
        every { eventResponseRepository.findRespondersWithUsers(eventId) } returns
            listOf(responderWithNote(null, username = null))
        assertNull(service.getEventResponders(eventId, userId).single().telegramUsername)
    }

    private fun membership(role: MembershipRole, status: MembershipStatus): Membership {
        val now = OffsetDateTime.now()
        return Membership(
            id = UUID.randomUUID(), userId = userId, clubId = clubId, status = status, role = role,
            joinedAt = now, subscriptionExpiresAt = null, createdAt = now, updatedAt = now
        )
    }

    // --- «Без ответа» и напоминания (event-stage2-composition.md § 6) ---

    private fun pendingMember(vote: Stage_1Vote?, remindedAt: OffsetDateTime? = null, id: UUID = UUID.randomUUID()) =
        EventResponderInfo(
            userId = id, firstName = "M", lastName = null, avatarUrl = null,
            stage1Vote = vote, finalStatus = null, attendance = null, disputeNote = null,
            telegramUsername = null, stage2RemindedAt = remindedAt
        )

    private fun stubStage2Event(ownerId: UUID, eventDatetime: OffsetDateTime = OffsetDateTime.now().plusHours(3)) {
        every { eventRepository.findById(eventId) } returns
            upcomingEvent(eventDatetime).copy(status = EventStatus.stage_2)
        val club = mockk<Club>()
        every { club.ownerId } returns ownerId
        every { club.id } returns clubId
        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns null
    }

    /** Промолчавший на Этапе 1 — тоже «без ответа»: голоса нет, статус подменяется на no_answer. */
    @Test
    fun `getPendingMembers reports a silent member as no_answer`() {
        stubStage2Event(ownerId = userId)
        every { eventResponseRepository.findStage2PendingMembers(eventId) } returns
            listOf(pendingMember(null), pendingMember(Stage_1Vote.maybe))

        val result = service.getPendingMembers(eventId, userId)

        assertEquals(listOf("no_answer", "maybe"), result.map { it.status })
    }

    @Test
    fun `getPendingMembers is forbidden for a plain member`() {
        stubStage2Event(ownerId = UUID.randomUUID())
        assertFailsWith<ForbiddenException> { service.getPendingMembers(eventId, userId) }
    }

    @Test
    fun `remind targets everyone pending when no user is given`() {
        stubStage2Event(ownerId = userId)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        every { eventResponseRepository.findStage2PendingMembers(eventId) } returns
            listOf(pendingMember(Stage_1Vote.going, id = a), pendingMember(null, id = b))
        every { eventResponseRepository.markStage2Reminded(eventId, listOf(a, b)) } returns listOf(1L, 2L)

        assertEquals(2, service.remind(eventId, userId, null).remindedCount)
    }

    /** Чужой userId не должен попасть в рассылку и создать строку-заглушку постороннему. */
    @Test
    fun `remind ignores a target outside the pending set`() {
        stubStage2Event(ownerId = userId)
        every { eventResponseRepository.findStage2PendingMembers(eventId) } returns
            listOf(pendingMember(Stage_1Vote.going))
        every { eventResponseRepository.markStage2Reminded(eventId, emptyList()) } returns emptyList()

        assertEquals(0, service.remind(eventId, userId, UUID.randomUUID()).remindedCount)
    }

    @Test
    fun `remind is rejected before Stage 2 and after the event starts`() {
        stubStage2Event(ownerId = userId)
        every { eventRepository.findById(eventId) } returns upcomingEvent(OffsetDateTime.now().plusHours(3))
        assertEquals(
            "Confirmation is not open for this event",
            assertFailsWith<ValidationException> { service.remind(eventId, userId, null) }.message
        )

        stubStage2Event(ownerId = userId, eventDatetime = OffsetDateTime.now().minusMinutes(1))
        assertEquals(
            "Event has already started",
            assertFailsWith<ValidationException> { service.remind(eventId, userId, null) }.message
        )
    }

    @Test
    fun `remind is forbidden for a plain member`() {
        stubStage2Event(ownerId = UUID.randomUUID())
        assertFailsWith<ForbiddenException> { service.remind(eventId, userId, null) }
    }

    /** Активный со-организатор напоминает наравне с владельцем. */
    @Test
    fun `remind is allowed for an active co-organizer`() {
        stubStage2Event(ownerId = UUID.randomUUID())
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns
            membership(MembershipRole.co_organizer, MembershipStatus.active)
        val target = UUID.randomUUID()
        every { eventResponseRepository.findStage2PendingMembers(eventId) } returns
            listOf(pendingMember(Stage_1Vote.going, id = target))
        every { eventResponseRepository.markStage2Reminded(eventId, listOf(target)) } returns listOf(7L)

        assertEquals(1, service.remind(eventId, userId, target).remindedCount)
    }

    private fun stubResponse(stage1: Stage_1Vote?, final: FinalStatus?) {
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns EventResponse(
            id = UUID.randomUUID(),
            eventId = eventId,
            userId = userId,
            stage1Vote = stage1,
            stage1Timestamp = null,
            stage2Vote = null,
            stage2Timestamp = null,
            finalStatus = final,
            attendance = null,
            attendanceFinalized = false,
            createdAt = null,
            updatedAt = null
        )
    }

    @Test
    fun `roster event in collecting phase reports the stage-1 vote, not the seat status (V83)`() {
        // Голос «Иду» у встречи с порогом сразу пишет final_status=confirmed. Если отдать его
        // наружу, участник выпадет из вкладки «Идут», а его кнопка перестанет подсвечиваться:
        // состав в этой фазе показывает кольцо, а список и кнопки живут голосами.
        every { eventRepository.findById(eventId) } returns rosterEvent(EventStatus.upcoming)
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns
            responseWith(Stage_1Vote.going, FinalStatus.confirmed)

        assertEquals("going", service.getMyVote(eventId, userId).vote)
    }

    @Test
    fun `roster event with a closed roster reports the seat status`() {
        every { eventRepository.findById(eventId) } returns rosterEvent(EventStatus.stage_2)
        every { eventResponseRepository.findByEventAndUser(eventId, userId) } returns
            responseWith(Stage_1Vote.going, FinalStatus.confirmed)

        assertEquals("confirmed", service.getMyVote(eventId, userId).vote)
    }

    private fun rosterEvent(status: EventStatus) = Event(
        id = eventId,
        clubId = UUID.randomUUID(),
        createdBy = UUID.randomUUID(),
        title = "T",
        description = null,
        locationText = "Place",
        eventDatetime = OffsetDateTime.now().plusDays(1),
        participantLimit = 4,
        votingOpensDaysBefore = 14,
        status = status,
        stage2Triggered = status != EventStatus.upcoming,
        attendanceMarked = false,
        attendanceFinalized = false,
        photoUrl = null,
        createdAt = null,
        updatedAt = null
    )

    private fun responseWith(stage1: Stage_1Vote?, finalStatus: FinalStatus?) = EventResponse(
        id = UUID.randomUUID(),
        eventId = eventId,
        userId = userId,
        stage1Vote = stage1,
        stage1Timestamp = OffsetDateTime.now(),
        stage2Vote = null,
        stage2Timestamp = null,
        finalStatus = finalStatus,
        attendance = null,
        attendanceFinalized = false,
        createdAt = null,
        updatedAt = null
    )
}
