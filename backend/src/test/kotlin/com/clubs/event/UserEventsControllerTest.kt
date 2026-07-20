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
        dsl.execute("DELETE FROM membership_history")
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
            // Предстоящие события всегда isHistory=false
            .andExpect(jsonPath("$.content[*].isHistory", org.hamcrest.Matchers.everyItem(
                org.hamcrest.Matchers.`is`(false)
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
    fun `GET me-events shows events to active members and hides frozen`() {
        // De-Stars (Slice 2): feed access = MembershipAccess (status `active`). A `frozen` member
        // (organizer gated them pending the off-platform dues) has no content access, so the club's
        // events must NOT appear in their feed — staying in lockstep with the voting/DM predicate.
        val ownerId = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$ownerId', 2099, 'Owner')")
        val activeClub = UUID.randomUUID()
        val frozenClub = UUID.randomUUID()
        insertClub(activeClub, ownerId, "Echo", isActive = true)
        insertClub(frozenClub, ownerId, "Zeta", isActive = true)
        insertMembership(memberUserId, activeClub, status = "active")
        insertMembership(memberUserId, frozenClub, status = "frozen")

        insertEvent(UUID.randomUUID(), activeClub, "Echo Visible", future(2), status = "upcoming")
        insertEvent(UUID.randomUUID(), frozenClub, "Zeta Hidden", future(3), status = "upcoming")

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

    // ---- Итерация 5: секция «История» ----

    @Test
    fun `AC-H1 history section shows attended events after upcoming, most recent first`() {
        // 2 upcoming
        insertEvent(UUID.randomUUID(), clubAlphaId, "Upcoming Soon", future(1), status = "upcoming")
        insertEvent(UUID.randomUUID(), clubBetaId, "Upcoming Later", future(4), status = "upcoming")
        // 3 attended (history), different past dates
        insertAttendedEvent(clubAlphaId, "Recent", past(1))
        insertAttendedEvent(clubBetaId, "Middle", past(5))
        insertAttendedEvent(clubAlphaId, "Oldest", past(10))

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(5))
            .andExpect(jsonPath("$.totalElements").value(5))
            // upcoming bucket first, isHistory=false
            .andExpect(jsonPath("$.content[0].isHistory").value(false))
            .andExpect(jsonPath("$.content[1].isHistory").value(false))
            // history bucket last, isHistory=true, DESC by event date
            .andExpect(jsonPath("$.content[2].isHistory").value(true))
            .andExpect(jsonPath("$.content[2].title").value("Recent"))
            .andExpect(jsonPath("$.content[3].title").value("Middle"))
            .andExpect(jsonPath("$.content[4].title").value("Oldest"))
    }

    @Test
    fun `AC-H2 only marked attendance lands in history`() {
        // Voted going but attendance never marked → NOT in history
        val votedPast = UUID.randomUUID()
        insertEvent(votedPast, clubAlphaId, "Voted Not Marked", past(2), status = "completed")
        insertEventResponse(votedPast, memberUserId, stage1Vote = "going")
        // Attended → in history
        insertAttendedEvent(clubAlphaId, "Attended", past(3))

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].title").value("Attended"))
            .andExpect(jsonPath("$.content[0].isHistory").value(true))
    }

    @Test
    fun `AC-H3 absent attendance is not in history`() {
        val eventId = UUID.randomUUID()
        insertEvent(eventId, clubAlphaId, "Marked Absent", past(2), status = "completed")
        insertEventResponse(eventId, memberUserId, stage1Vote = "going", finalStatus = "confirmed", attendance = "absent")

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(0))
    }

    @Test
    fun `AC-H4 disputed attendance is hidden until resolved to attended`() {
        val eventId = UUID.randomUUID()
        insertEvent(eventId, clubAlphaId, "Disputed", past(2), status = "completed")
        insertEventResponse(eventId, memberUserId, stage1Vote = "going", finalStatus = "confirmed", attendance = "disputed")

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(0))

        // Organizer resolves the dispute in the member's favour
        dsl.execute(
            "UPDATE event_responses SET attendance = 'attended'::attendance_status " +
                "WHERE event_id = '$eventId' AND user_id = '$memberUserId'"
        )

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].title").value("Disputed"))
            .andExpect(jsonPath("$.content[0].isHistory").value(true))
    }

    @Test
    fun `AC-H5 cancelled event is excluded from history even when attended`() {
        val eventId = UUID.randomUUID()
        insertEvent(eventId, clubAlphaId, "Cancelled After Attend", past(2), status = "cancelled")
        insertEventResponse(eventId, memberUserId, stage1Vote = "going", finalStatus = "confirmed", attendance = "attended")

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(0))
    }

    @Test
    fun `AC-H6 history survives leaving the club while upcoming disappears`() {
        // Attended past event and an upcoming event, both in Alpha
        insertAttendedEvent(clubAlphaId, "Attended Alpha", past(2))
        insertEvent(UUID.randomUUID(), clubAlphaId, "Upcoming Alpha", future(3), status = "upcoming")

        // User leaves Alpha (membership removed)
        dsl.execute("DELETE FROM memberships WHERE user_id = '$memberUserId' AND club_id = '$clubAlphaId'")

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            // history stays, upcoming of the left club is gone
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].title").value("Attended Alpha"))
            .andExpect(jsonPath("$.content[0].isHistory").value(true))
    }

    @Test
    fun `AC-H6 frozen membership keeps history but hides upcoming`() {
        // Решение 2в, «побочный эффект»: frozen участник теряет ПРЕДСТОЯЩУЮ ленту клуба
        // (MembershipAccess требует active), но продолжает видеть свою ИСТОРИЮ по этому клубу —
        // история не гейтится членством. Вариант «membership есть, но не active» — отдельно от
        // «membership удалён» выше.
        val ownerId = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$ownerId', 2100, 'FrostOwner')")
        val frozenClub = UUID.randomUUID()
        insertClub(frozenClub, ownerId, "Frost", isActive = true)
        insertMembership(memberUserId, frozenClub, status = "frozen")

        insertAttendedEvent(frozenClub, "Attended While Member", past(2))
        insertEvent(UUID.randomUUID(), frozenClub, "Upcoming Frozen", future(3), status = "upcoming")

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            // history stays despite frozen; upcoming of the frozen club is hidden
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].title").value("Attended While Member"))
            .andExpect(jsonPath("$.content[0].isHistory").value(true))
    }

    @Test
    fun `AC-H7 soft-deleted club is excluded from history`() {
        // Gamma is is_active=false; an attended event there must not appear
        insertAttendedEvent(clubGammaInactiveId, "Attended Gamma", past(2))

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(0))
    }

    @Test
    fun `AC-H8 another user's attended event never leaks`() {
        // memberUser attended event E in Alpha; loneUser (no membership, no response) must not see it
        val eventE = UUID.randomUUID()
        insertEvent(eventE, clubAlphaId, "Members Private History", past(2), status = "completed")
        insertEventResponse(eventE, memberUserId, stage1Vote = "going", finalStatus = "confirmed", attendance = "attended")

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $loneToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.content[?(@.id == '$eventE')]").doesNotExist())
    }

    @Test
    fun `AC-H9 upcoming bucket always precedes history regardless of absolute dates`() {
        insertEvent(UUID.randomUUID(), clubAlphaId, "Tomorrow", future(1), status = "upcoming")
        insertAttendedEvent(clubAlphaId, "Yesterday", past(1))

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].isHistory").value(false))
            .andExpect(jsonPath("$.content[0].title").value("Tomorrow"))
            .andExpect(jsonPath("$.content[1].isHistory").value(true))
            .andExpect(jsonPath("$.content[1].title").value("Yesterday"))
    }

    @Test
    fun `AC-H10 pagination counts the union and spills history into the next page`() {
        // 2 upcoming fill page 0 (size=2); 3 attended land on later pages, all history
        insertEvent(UUID.randomUUID(), clubAlphaId, "Up1", future(1), status = "upcoming")
        insertEvent(UUID.randomUUID(), clubAlphaId, "Up2", future(2), status = "upcoming")
        insertAttendedEvent(clubAlphaId, "Hist Recent", past(1))
        insertAttendedEvent(clubAlphaId, "Hist Mid", past(3))
        insertAttendedEvent(clubAlphaId, "Hist Old", past(6))

        // Page 0 — only upcoming
        mockMvc.perform(
            get("/api/users/me/events?page=0&size=2")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(5))
            .andExpect(jsonPath("$.totalPages").value(3))
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[*].isHistory", org.hamcrest.Matchers.everyItem(
                org.hamcrest.Matchers.`is`(false)
            )))

        // Page 1 — history spilling in, most recent first
        mockMvc.perform(
            get("/api/users/me/events?page=1&size=2")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[*].isHistory", org.hamcrest.Matchers.everyItem(
                org.hamcrest.Matchers.`is`(true)
            )))
            .andExpect(jsonPath("$.content[0].title").value("Hist Recent"))
            .andExpect(jsonPath("$.content[1].title").value("Hist Mid"))
    }

    @Test
    fun `AC-H14 fresh attendance shows as history even while status is still stage_2`() {
        // Cron lag: event happened 2h ago, status still stage_2, attendance just marked attended
        val eventId = UUID.randomUUID()
        insertEvent(eventId, clubAlphaId, "Cron Lag", OffsetDateTime.now().minusHours(2), status = "stage_2")
        insertEventResponse(eventId, memberUserId, stage1Vote = "going", finalStatus = "confirmed", attendance = "attended")

        mockMvc.perform(
            get("/api/users/me/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].title").value("Cron Lag"))
            .andExpect(jsonPath("$.content[0].isHistory").value(true))
            .andExpect(jsonPath("$.content[0].status").value("stage_2"))
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

    /**
     * Прошедшее событие с личной отметкой явки memberUser'а — стандартная строка «Истории»:
     * status=completed, event_datetime в прошлом, event_response(attendance='attended', final_status='confirmed').
     */
    private fun insertAttendedEvent(clubId: UUID, title: String, eventDatetime: OffsetDateTime) {
        val eventId = UUID.randomUUID()
        insertEvent(eventId, clubId, title, eventDatetime, status = "completed")
        insertEventResponse(eventId, memberUserId, stage1Vote = "going", finalStatus = "confirmed", attendance = "attended")
    }

    private fun insertEventResponse(
        eventId: UUID,
        userId: UUID,
        stage1Vote: String? = null,
        finalStatus: String? = null,
        attendance: String? = null
    ) {
        val columns = mutableListOf("event_id", "user_id")
        val values = mutableListOf("'$eventId'", "'$userId'")
        if (stage1Vote != null) {
            columns += "stage_1_vote"; values += "'$stage1Vote'::stage_1_vote"
            columns += "stage_1_timestamp"; values += "now()"
        }
        if (finalStatus != null) {
            columns += "final_status"; values += "'$finalStatus'::final_status"
        }
        if (attendance != null) {
            columns += "attendance"; values += "'$attendance'::attendance_status"
        }
        dsl.execute(
            "INSERT INTO event_responses (${columns.joinToString(", ")}) VALUES (${values.joinToString(", ")})"
        )
    }

    private fun future(days: Long): OffsetDateTime = OffsetDateTime.now().plusDays(days)

    private fun past(days: Long): OffsetDateTime = OffsetDateTime.now().minusDays(days)
}
