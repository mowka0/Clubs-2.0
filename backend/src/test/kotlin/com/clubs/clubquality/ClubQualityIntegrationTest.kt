package com.clubs.clubquality

import com.clubs.common.exception.NotFoundException
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Integration tests for club-quality L1 facts against a real Postgres. Covers each fact's
 * window/threshold rules + the empty-club and missing-club (404) paths. Acceptance criteria:
 * docs/modules/club-quality.md §7.
 */
@SpringBootTest(
    properties = [
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=0",
        "telegram.bot-token=test-bot-token"
    ]
)
@Testcontainers
@ActiveProfiles("test")
class ClubQualityIntegrationTest {

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

    @Autowired lateinit var clubQualityService: ClubQualityService
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
    private lateinit var clubId: UUID
    private var telegramSeq = 9000L

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM skladchina_participants")
        dsl.execute("DELETE FROM skladchinas")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")
        telegramSeq = 9000L

        ownerId = newUser()
        clubId = insertClub(createdAt = OffsetDateTime.now().minusMonths(14).minusDays(5))
    }

    @Test
    fun `empty club yields zero facts and a correct age`() {
        val facts = clubQualityService.getClubFacts(clubId)

        assertEquals(0.0, facts.meetingsPerMonth, 0.001)
        assertEquals(0, facts.avgAttendance)
        assertEquals(0, facts.coreSize)
        assertEquals(14, facts.ageMonths)
        assertEquals(0, facts.totalMeetings)
        assertEquals(0, facts.successfulSkladchinas)
    }

    @Test
    fun `missing club throws NotFoundException (404, not 500)`() {
        assertFailsWith<NotFoundException> { clubQualityService.getClubFacts(UUID.randomUUID()) }
    }

    @Test
    fun `meetingsPerMonth counts only held events in the 90-day window`() {
        insertEvent(daysFromNow(-2), "completed")   // held, in window
        insertEvent(daysFromNow(-30), "completed")  // held, in window
        insertEvent(daysFromNow(-60), "stage_2")    // held, in window (non-cancelled)
        insertEvent(daysFromNow(-89), "completed")  // held, in window
        insertEvent(daysFromNow(3), "upcoming")     // future → excluded
        insertEvent(daysFromNow(-10), "cancelled")  // cancelled → excluded
        insertEvent(daysFromNow(-100), "completed") // older than 90d → excluded

        // 4 held in window ÷ 3 = 1.333 → 1.3
        assertEquals(1.3, clubQualityService.getClubFacts(clubId).meetingsPerMonth, 0.001)
    }

    @Test
    fun `avgAttendance averages distinct attendees over finalized meetings only`() {
        val a = insertEvent(daysFromNow(-5), "completed", finalized = true)
        insertResponse(a, attendance = "attended")
        insertResponse(a, attendance = "attended")
        insertResponse(a, attendance = "attended")
        insertResponse(a, attendance = "absent")

        val b = insertEvent(daysFromNow(-6), "completed", finalized = true)
        insertResponse(b, attendance = "attended")

        // A non-finalized meeting with attendance must not feed the average at all.
        val c = insertEvent(daysFromNow(-7), "completed", finalized = false)
        insertResponse(c, attendance = "attended")
        insertResponse(c, attendance = "attended")

        // (3 + 1) attended ÷ 2 finalized meetings = 2
        assertEquals(2, clubQualityService.getClubFacts(clubId).avgAttendance)
    }

    @Test
    fun `a finalized meeting nobody attended lowers the average (counts in denominator)`() {
        val a = insertEvent(daysFromNow(-5), "completed", finalized = true)
        repeat(4) { insertResponse(a, attendance = "attended") }
        insertEvent(daysFromNow(-6), "completed", finalized = true) // finalized, zero attendance

        // 4 attended ÷ 2 finalized meetings = 2 (not 4)
        assertEquals(2, clubQualityService.getClubFacts(clubId).avgAttendance)
    }

    @Test
    fun `coreSize counts distinct users with at least 3 attended events`() {
        val events = (1..4).map { insertEvent(daysFromNow(-it.toLong()), "completed", finalized = true) }
        val core1 = newUser()
        val core2 = newUser()
        val casual = newUser()

        events.take(3).forEach { insertResponse(it, attendance = "attended", userId = core1) } // 3 → core
        events.forEach { insertResponse(it, attendance = "attended", userId = core2) }          // 4 → core
        events.take(2).forEach { insertResponse(it, attendance = "attended", userId = casual) } // 2 → not core

        assertEquals(2, clubQualityService.getClubFacts(clubId).coreSize)
    }

    @Test
    fun `coreSize ignores attended responses on cancelled events`() {
        // A cancelled event can still hold attended rows (club-delete cascade cancels an already
        // attendance-marked stage_2 event) — those must not inflate the core.
        val cancelled = (1..3).map { insertEvent(daysFromNow(-it.toLong()), "cancelled") }
        val ghost = newUser()
        cancelled.forEach { insertResponse(it, attendance = "attended", userId = ghost) } // 3 attended, all cancelled

        assertEquals(0, clubQualityService.getClubFacts(clubId).coreSize)
    }

    @Test
    fun `totalMeetings counts all-time held events, excluding future and cancelled`() {
        insertEvent(daysFromNow(-200), "completed") // older than the 90-day window, but all-time → counts
        insertEvent(daysFromNow(-2), "completed")   // held → counts
        insertEvent(daysFromNow(3), "upcoming")     // future → excluded
        insertEvent(daysFromNow(-5), "cancelled")   // cancelled → excluded

        assertEquals(2, clubQualityService.getClubFacts(clubId).totalMeetings)
    }

    @Test
    fun `successfulSkladchinas counts only closed_success`() {
        insertSkladchina("closed_success")
        insertSkladchina("closed_success")
        insertSkladchina("active")
        insertSkladchina("closed_failed")
        insertSkladchina("cancelled")

        assertEquals(2, clubQualityService.getClubFacts(clubId).successfulSkladchinas)
    }

    // ---- batch (Discovery card) ----

    @Test
    fun `card facts batch returns frequency, engagement, age and milestones`() {
        // 4 alive members (denominator); an expired one is excluded.
        val m1 = newUser(); val m2 = newUser()
        listOf(m1, m2, newUser(), newUser()).forEach { insertMembership(it, "active") }
        insertMembership(newUser(), "expired")

        // 3 held events in window → meetingsPerMonth = 3/3 = 1.0; totalMeetings = 3.
        val e1 = insertEvent(daysFromNow(-3), "completed")
        val e2 = insertEvent(daysFromNow(-10), "completed")
        insertEvent(daysFromNow(-20), "completed")
        insertResponse(e1, attendance = null, userId = m1)
        insertResponse(e1, attendance = null, userId = m2)
        insertResponse(e2, attendance = null, userId = m1) // m1 again → distinct responders = {m1, m2}

        insertSkladchina("closed_success")

        val facts = clubQualityService.getClubCardFacts(listOf(clubId)).single()
        assertEquals(clubId, facts.clubId)
        assertEquals(1.0, facts.meetingsPerMonth, 0.001)
        assertEquals(50, facts.engagementPercent) // 2 distinct responders ÷ 4 alive members
        assertEquals(14, facts.ageMonths)
        assertEquals(3, facts.totalMeetings)
        assertEquals(1, facts.successfulSkladchinas)
    }

    @Test
    fun `card facts batch skips ids with no club row`() {
        val facts = clubQualityService.getClubCardFacts(listOf(clubId, UUID.randomUUID()))
        assertEquals(setOf(clubId), facts.map { it.clubId }.toSet())
    }

    @Test
    fun `card facts engagement is zero when club has no alive members`() {
        val e = insertEvent(daysFromNow(-3), "completed")
        insertResponse(e, attendance = null) // a responder exists, but zero alive members
        assertEquals(0, clubQualityService.getClubCardFacts(listOf(clubId)).single().engagementPercent)
    }

    @Test
    fun `card facts engagement clamps at 100 percent`() {
        insertMembership(newUser(), "active") // 1 alive member
        val e = insertEvent(daysFromNow(-3), "completed")
        repeat(3) { insertResponse(e, attendance = null) } // 3 distinct responders > 1 member
        assertEquals(100, clubQualityService.getClubCardFacts(listOf(clubId)).single().engagementPercent)
    }

    @Test
    fun `card facts batch returns empty for empty input`() {
        assertEquals(emptyList<ClubCardFactsDto>(), clubQualityService.getClubCardFacts(emptyList()))
    }

    // ---- helpers ----

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$id', ${telegramSeq++}, 'U')")
        return id
    }

    private fun insertClub(createdAt: OffsetDateTime): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city,
                               member_limit, subscription_price, is_active, created_at)
            VALUES ('$id', '$ownerId', 'Club', 'desc', 'sport', 'open', 'Moscow', 20, 0, true, '$createdAt')
            """.trimIndent()
        )
        return id
    }

    private fun insertEvent(eventDatetime: OffsetDateTime, status: String, finalized: Boolean = false): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime,
                                participant_limit, voting_opens_days_before, status,
                                stage_2_triggered, attendance_marked, attendance_finalized)
            VALUES ('$id', '$clubId', '$ownerId', 'Event', 'Place', '$eventDatetime', 10, 14,
                    '$status'::event_status, true, $finalized, $finalized)
            """.trimIndent()
        )
        return id
    }

    private fun insertResponse(eventId: UUID, attendance: String?, userId: UUID = newUser()): UUID {
        val id = UUID.randomUUID()
        val att = attendance?.let { "'$it'::attendance_status" } ?: "NULL"
        dsl.execute(
            """
            INSERT INTO event_responses (id, event_id, user_id, stage_1_vote, stage_1_timestamp,
                                         stage_2_vote, final_status, attendance)
            VALUES ('$id', '$eventId', '$userId', 'going'::stage_1_vote, NOW(),
                    'confirmed'::stage_2_vote, 'confirmed'::final_status, $att)
            """.trimIndent()
        )
        return id
    }

    private fun insertMembership(userId: UUID, status: String): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO memberships (id, user_id, club_id, status, role)
            VALUES ('$id', '$userId', '$clubId', '$status'::membership_status, 'member'::membership_role)
            """.trimIndent()
        )
        return id
    }

    private fun insertSkladchina(status: String): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO skladchinas (id, club_id, creator_id, title, payment_mode, payment_link, deadline, status)
            VALUES ('$id', '$clubId', '$ownerId', 'Сбор', 'voluntary'::skladchina_mode, 'http://pay',
                    NOW() + INTERVAL '7 days', '$status'::skladchina_status)
            """.trimIndent()
        )
        return id
    }

    private fun daysFromNow(days: Long): OffsetDateTime = OffsetDateTime.now().plusDays(days)
}
