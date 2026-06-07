package com.clubs.event

import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.membership.MembershipRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
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
