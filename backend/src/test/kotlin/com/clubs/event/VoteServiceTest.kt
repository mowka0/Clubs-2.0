package com.clubs.event

import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubManagerGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
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
    private val service = VoteService(
        eventRepository, eventResponseRepository, membershipRepository, clubRepository,
        ClubManagerGuard(clubRepository, membershipRepository), eventPublisher
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

    private fun responderWithNote(note: String?) = EventResponderInfo(
        userId = UUID.randomUUID(), firstName = "A", lastName = null, avatarUrl = null,
        stage1Vote = Stage_1Vote.going, finalStatus = FinalStatus.confirmed,
        attendance = AttendanceStatus.disputed, disputeNote = note
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
}
