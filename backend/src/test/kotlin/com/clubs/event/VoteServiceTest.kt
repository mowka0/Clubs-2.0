package com.clubs.event

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.ValidationException
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
    private val service = VoteService(eventRepository, eventResponseRepository, membershipRepository)

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
