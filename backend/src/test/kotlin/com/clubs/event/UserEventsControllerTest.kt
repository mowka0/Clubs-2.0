package com.clubs.event

import com.clubs.auth.JwtService
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=0",
        "telegram.bot-token=test-bot-token"
    ]
)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class UserEventsControllerTest {

    companion object {

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("clubs_test")
            .withUsername("test")
            .withPassword("test")

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var dsl: DSLContext

    private lateinit var memberUserId: UUID
    private lateinit var loneUserId: UUID
    private lateinit var memberToken: String
    private lateinit var loneToken: String
    private lateinit var clubAlphaId: UUID
    private lateinit var clubBetaId: UUID
    private lateinit var clubGammaInactiveId: UUID
    private lateinit var clubDeltaForeignId: UUID

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM applications")
        dsl.execute("DELETE FROM transactions")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM user_club_reputation")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")

        memberUserId = UUID.randomUUID()
        loneUserId = UUID.randomUUID()
        val foreignOwnerId = UUID.randomUUID()

        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$memberUserId', 2001, 'Member')")
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$loneUserId', 2002, 'Lone')")
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$foreignOwnerId', 2003, 'Foreign')")

        memberToken = jwtService.generateToken(memberUserId, 2001L)
        loneToken = jwtService.generateToken(loneUserId, 2002L)

        clubAlphaId = UUID.randomUUID()
        clubBetaId = UUID.randomUUID()
        clubGammaInactiveId = UUID.randomUUID()
        clubDeltaForeignId = UUID.randomUUID()

        // Two active clubs where memberUser is active
        insertClub(clubAlphaId, foreignOwnerId, "Alpha", isActive = true)
        insertClub(clubBetaId, foreignOwnerId, "Beta", isActive = true)
        // Soft-deleted club where memberUser is/was active — must be filtered out
        insertClub(clubGammaInactiveId, foreignOwnerId, "Gamma", isActive = false)
        // Foreign club without memberUser membership — must not leak
        insertClub(clubDeltaForeignId, foreignOwnerId, "Delta", isActive = true)

        insertMembership(memberUserId, clubAlphaId, status = "active")
        insertMembership(memberUserId, clubBetaId, status = "active")
        insertMembership(memberUserId, clubGammaInactiveId, status = "active")
    }

    @Test
    fun `GET me-events returns only events from active clubs where user is active member`() {
        val alphaEventId = UUID.randomUUID()
        val betaEventId = UUID.randomUUID()
        val gammaEventId = UUID.randomUUID()
        val deltaEventId = UUID.randomUUID()

        insertEvent(alphaEventId, clubAlphaId, "Alpha Upcoming", future(2), status = "upcoming")
        insertEvent(betaEventId, clubBetaId, "Beta Upcoming", future(5), status = "upcoming")
        // Soft-deleted club's event — must NOT appear
        insertEvent(gammaEventId, clubGammaInactiveId, "Gamma Event", future(3), status = "upcoming")
        // Foreign club (no membership) — must NOT appear (privacy)
        insertEvent(deltaEventId, clubDeltaForeignId, "Delta Foreign", future(1), status = "upcoming")

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[*].clubId", org.hamcrest.Matchers.everyItem(
                org.hamcrest.Matchers.`in`(listOf(clubAlphaId.toString(), clubBetaId.toString()))
            )))
    }

    @Test
    fun `GET me-events excludes completed, cancelled and past events`() {
        insertEvent(UUID.randomUUID(), clubAlphaId, "Future Upcoming", future(3), status = "upcoming")
        insertEvent(UUID.randomUUID(), clubAlphaId, "Completed Event", future(4), status = "completed")
        insertEvent(UUID.randomUUID(), clubAlphaId, "Cancelled Event", future(5), status = "cancelled")
        insertEvent(UUID.randomUUID(), clubAlphaId, "Past Event", future(-2), status = "upcoming")

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].title").value("Future Upcoming"))
    }

    @Test
    fun `GET me-events shows events to cancelled-but-still-paid members and hides grace_period`() {
        // Feed access must match the voting/DM predicate (MembershipAccess): a
        // cancelled membership with a future subscription_expires_at still has
        // access; grace_period does not. Regression: feed used status=active only,
        // so a cancelled-but-paid member could vote on an event missing from feed.
        val ownerId = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$ownerId', 2099, 'Owner')")
        val cancelledPaidClub = UUID.randomUUID()
        val gracePeriodClub = UUID.randomUUID()
        insertClub(cancelledPaidClub, ownerId, "Echo", isActive = true)
        insertClub(gracePeriodClub, ownerId, "Zeta", isActive = true)
        insertMembership(memberUserId, cancelledPaidClub, status = "cancelled", subscriptionExpiresAt = future(10))
        insertMembership(memberUserId, gracePeriodClub, status = "grace_period")

        insertEvent(UUID.randomUUID(), cancelledPaidClub, "Echo Visible", future(2), status = "upcoming")
        insertEvent(UUID.randomUUID(), gracePeriodClub, "Zeta Hidden", future(3), status = "upcoming")

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].title").value("Echo Visible"))
    }

    @Test
    fun `GET me-events returns empty for user without memberships`() {
        insertEvent(UUID.randomUUID(), clubAlphaId, "Anything", future(2), status = "upcoming")

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $loneToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(0))
    }

    @Test
    fun `GET me-events sorts action-required events first`() {
        // Event A: upcoming, voting open, no vote → actionRequired = true
        val eventA = UUID.randomUUID()
        insertEvent(eventA, clubAlphaId, "Action Required", future(1), status = "upcoming", votingOpensDaysBefore = 14)

        // Event B: upcoming, voting open, user voted "going" → actionRequired = false
        val eventB = UUID.randomUUID()
        insertEvent(eventB, clubAlphaId, "Already Voted", future(2), status = "upcoming", votingOpensDaysBefore = 14)
        insertEventResponse(eventB, memberUserId, stage1Vote = "going")

        // Event C: stage_2, user voted going but hasn't confirmed → actionRequired = true (but later in time)
        val eventC = UUID.randomUUID()
        insertEvent(eventC, clubBetaId, "Stage 2 Action", future(5), status = "stage_2", votingOpensDaysBefore = 14)
        insertEventResponse(eventC, memberUserId, stage1Vote = "going")

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(3))
            // First two are actionRequired=true (sorted within by event_datetime ASC)
            .andExpect(jsonPath("$.content[0].actionRequired").value(true))
            .andExpect(jsonPath("$.content[0].title").value("Action Required"))
            .andExpect(jsonPath("$.content[1].actionRequired").value(true))
            .andExpect(jsonPath("$.content[1].title").value("Stage 2 Action"))
            // Third is actionRequired=false
            .andExpect(jsonPath("$.content[2].actionRequired").value(false))
            .andExpect(jsonPath("$.content[2].title").value("Already Voted"))
    }

    @Test
    fun `GET me-events without token returns 401`() {
        mockMvc.perform(get("/api/users/me/events"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET me-events includes club info and my response state`() {
        val eventId = UUID.randomUUID()
        insertEvent(eventId, clubAlphaId, "With Vote", future(2), status = "upcoming")
        insertEventResponse(eventId, memberUserId, stage1Vote = "maybe")

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].clubName").value("Alpha"))
            .andExpect(jsonPath("$.content[0].myVote").value("maybe"))
            .andExpect(jsonPath("$.content[0].myParticipationStatus").doesNotExist())
            .andExpect(jsonPath("$.content[0].goingCount").value(0))
            .andExpect(jsonPath("$.content[0].confirmedCount").value(0))
    }

    @Test
    fun `GET me-events caps page size at 50`() {
        repeat(3) { idx ->
            insertEvent(UUID.randomUUID(), clubAlphaId, "Event $idx", future((2 + idx).toLong()), status = "upcoming")
        }
        mockMvc.perform(
            get("/api/users/me/events?size=1000")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.size").value(50))
    }

    // ---- helpers ----

    private fun insertClub(id: UUID, ownerId: UUID, name: String, isActive: Boolean) {
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price, is_active)
            VALUES ('$id', '$ownerId', '$name', 'desc', 'sport', 'open', 'Moscow', 20, 0, $isActive)
            """.trimIndent()
        )
    }

    private fun insertMembership(
        userId: UUID,
        clubId: UUID,
        status: String,
        subscriptionExpiresAt: OffsetDateTime? = null
    ) {
        if (subscriptionExpiresAt == null) {
            dsl.execute(
                "INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$userId', '$clubId', '$status', 'member')"
            )
        } else {
            dsl.execute(
                "INSERT INTO memberships (user_id, club_id, status, role, subscription_expires_at) " +
                    "VALUES ('$userId', '$clubId', '$status', 'member', '$subscriptionExpiresAt')"
            )
        }
    }

    private fun insertEvent(
        id: UUID,
        clubId: UUID,
        title: String,
        eventDatetime: OffsetDateTime,
        status: String,
        votingOpensDaysBefore: Int = 14
    ) {
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime, participant_limit, voting_opens_days_before, status)
            VALUES ('$id', '$clubId', '$memberUserId', '$title', 'Place', '$eventDatetime', 10, $votingOpensDaysBefore, '$status'::event_status)
            """.trimIndent()
        )
    }

    private fun insertEventResponse(eventId: UUID, userId: UUID, stage1Vote: String) {
        dsl.execute(
            """
            INSERT INTO event_responses (event_id, user_id, stage_1_vote, stage_1_timestamp)
            VALUES ('$eventId', '$userId', '$stage1Vote'::stage_1_vote, now())
            """.trimIndent()
        )
    }

    private fun future(days: Long): OffsetDateTime = OffsetDateTime.now().plusDays(days)
}
