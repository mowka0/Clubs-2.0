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
import kotlin.test.assertNull

/**
 * Integration tests for the owner «Статистика» panel against a real Postgres. Covers each lever's
 * window/trend rules, the paid/free adaptation, the owner-only attention zone, and the missing-club
 * (404) path. Acceptance criteria: docs/modules/club-quality.md §9.9.
 *
 * The default club is paid + closed so most levers apply; free/open clubs are inserted per-test.
 * Ownership (403) is enforced by `@RequiresOrganizer` at the controller and is out of scope here.
 */
@SpringBootTest(
    properties = [
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=0",
        "telegram.bot-token=test-bot-token",
    ],
)
@Testcontainers
@ActiveProfiles("test")
class ClubStatsIntegrationTest {

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

    @Autowired lateinit var clubStatsService: ClubStatsService
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
    private lateinit var clubId: UUID
    private var telegramSeq = 7000L

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM skladchina_participants")
        dsl.execute("DELETE FROM skladchinas")
        dsl.execute("DELETE FROM transactions")
        dsl.execute("DELETE FROM applications")
        dsl.execute("DELETE FROM membership_history")
        // Rows left by reputation test classes also reference clubs/users — clear them so the
        // wholesale `DELETE FROM clubs` below doesn't trip their FK constraints.
        dsl.execute("DELETE FROM reputation_ledger")
        dsl.execute("DELETE FROM user_club_reputation")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")
        telegramSeq = 7000L

        ownerId = newUser()
        clubId = insertClub(price = 300, accessType = "closed")
    }

    @Test
    fun `missing club throws NotFoundException`() {
        assertFailsWith<NotFoundException> { clubStatsService.getClubStats(UUID.randomUUID()) }
    }

    @Test
    fun `paid club reports renewal-rate retention with a trend`() {
        // Current 30d: 3 distinct renewers, 1 churn → 3/4 = 75%.
        repeat(3) { insertTransaction(newUser(), "renewal", "completed", daysAgo(5)) }
        insertMembershipHistory(newUser(), "left", daysAgo(5))
        // Prior 30-60d: 1 renewer, 1 churn → 1/2 = 50%. Baseline exists → trend up by 25pp.
        insertTransaction(newUser(), "renewal", "completed", daysAgo(45))
        insertMembershipHistory(newUser(), "expired", daysAgo(45))

        val stats = clubStatsService.getClubStats(clubId)

        assertEquals("paid", stats.clubType)
        assertEquals(75, stats.retentionPercent)
        assertEquals("up", stats.retentionTrend?.direction)
        assertEquals(25, stats.retentionTrend?.delta)
        assertEquals(1, stats.churnedThisPeriod) // 1 churn in the current 30d window
    }

    @Test
    fun `retention trend is suppressed when the prior window has no baseline`() {
        // Activity only in the current window → prior window empty → can't tell "low" from "no data".
        insertTransaction(newUser(), "renewal", "completed", daysAgo(3))
        insertMembershipHistory(newUser(), "left", daysAgo(3))

        val stats = clubStatsService.getClubStats(clubId)

        assertEquals(50, stats.retentionPercent) // 1/(1+1)
        assertNull(stats.retentionTrend)
    }

    @Test
    fun `free club has no paid retention but reports churn and rejoins`() {
        val freeClub = insertClub(price = 0, accessType = "open")
        insertMembershipHistory(newUser(), "left", daysAgo(4), club = freeClub)
        insertMembershipHistory(newUser(), "left", daysAgo(10), club = freeClub)
        insertMembershipHistory(newUser(), "rejoined", daysAgo(6), club = freeClub)

        val stats = clubStatsService.getClubStats(freeClub)

        assertEquals("free", stats.clubType)
        assertNull(stats.retentionPercent)
        assertNull(stats.retentionTrend)
        assertEquals(2, stats.churnedThisPeriod)
        assertEquals(1, stats.rejoinedThisPeriod)
    }

    @Test
    fun `engagement is distinct responders over alive members, with a trend`() {
        // 4 alive members.
        repeat(4) { insertMembership(newUser(), "active") }
        // Current 90d: 3 distinct responders → 75%.
        val e1 = insertEvent(daysAgo(10), "completed")
        repeat(3) { insertResponse(e1, attendance = null) }
        // Prior 90-180d: 1 responder → 25%. Baseline exists → trend up.
        val e2 = insertEvent(daysAgo(120), "completed")
        insertResponse(e2, attendance = null)

        val stats = clubStatsService.getClubStats(clubId)

        assertEquals(75, stats.engagementPercent)
        assertEquals("up", stats.engagementTrend?.direction)
        assertEquals(50, stats.engagementTrend?.delta)
    }

    @Test
    fun `engagement trend is suppressed when the prior window has no events`() {
        insertMembership(newUser(), "active")
        val e = insertEvent(daysAgo(5), "completed")
        insertResponse(e, attendance = null)

        assertNull(clubStatsService.getClubStats(clubId).engagementTrend)
    }

    @Test
    fun `skladchina paid share excludes pending and released, with a trend`() {
        // Current 90d: closed skladchina with 2 paid, 1 declined, 1 released, 1 pending.
        val cur = insertClosedSkladchina(daysAgo(10))
        repeat(2) { insertSkladchinaParticipant(cur, newUser(), "paid") }
        insertSkladchinaParticipant(cur, newUser(), "declined")
        insertSkladchinaParticipant(cur, newUser(), "released") // excluded from denominator
        insertSkladchinaParticipant(cur, newUser(), "pending")  // excluded from denominator
        // settled = paid(2) + declined(1) = 3 → 2/3 = 67%.
        // Prior 90-180d: 1 paid, 1 expired_no_response → 1/2 = 50%.
        val prior = insertClosedSkladchina(daysAgo(120))
        insertSkladchinaParticipant(prior, newUser(), "paid")
        insertSkladchinaParticipant(prior, newUser(), "expired_no_response")

        val stats = clubStatsService.getClubStats(clubId)

        assertEquals(67, stats.skladchinaPaidPercent)
        assertEquals("up", stats.skladchinaPaidTrend?.direction)
        assertEquals(17, stats.skladchinaPaidTrend?.delta)
    }

    @Test
    fun `skladchina lever is null when the club has no closed skladchinas in the window`() {
        val stats = clubStatsService.getClubStats(clubId)
        assertNull(stats.skladchinaPaidPercent)
        assertNull(stats.skladchinaPaidTrend)
    }

    @Test
    fun `non-closed club hides application levers`() {
        val openClub = insertClub(price = 300, accessType = "open")
        val stats = clubStatsService.getClubStats(openClub)
        assertNull(stats.pendingApplications)
        assertNull(stats.stalePendingApplications)
        assertNull(stats.autoRejectedApplications)
    }

    @Test
    fun `pending applications report a stale subset older than 24h`() {
        insertApplication(newUser(), "pending", createdAt = hoursAgo(48)) // stale
        insertApplication(newUser(), "pending", createdAt = hoursAgo(1))  // fresh

        val stats = clubStatsService.getClubStats(clubId)

        assertEquals(2, stats.pendingApplications)
        assertEquals(1, stats.stalePendingApplications)
    }

    @Test
    fun `attention zone counts disputes cumulatively, plus recent auto-rejects and cancellations`() {
        // Disputes persist as transient `disputed` OR the markers a resolution leaves behind — count all.
        val event = insertEvent(daysAgo(5), "completed", finalized = true)
        insertResponse(event, attendance = "disputed")                                  // open → counts
        insertResponse(event, attendance = "absent", disputeTerminal = true)            // resolved → counts
        insertResponse(event, attendance = "attended", disputeNote = "был на месте")    // note remains → counts
        insertResponse(event, attendance = "absent")                                    // plain mark → NOT a dispute
        // Held meetings (denominator context for «N из M»).
        insertEvent(daysAgo(7), "completed")
        // Auto-rejects: one recent (counts), one older than 90d (excluded).
        insertApplication(newUser(), "auto_rejected", createdAt = daysAgo(5), resolvedAt = daysAgo(5))
        insertApplication(newUser(), "auto_rejected", createdAt = daysAgo(100), resolvedAt = daysAgo(100))
        // Cancellations: one recent (counts), one older than 90d (excluded).
        insertEvent(daysAgo(5), "cancelled")
        insertEvent(daysAgo(120), "cancelled")

        val stats = clubStatsService.getClubStats(clubId)

        assertEquals(3, stats.attendanceDisputes) // open + terminally-resolved + note, but not the plain mark
        assertEquals(2, stats.totalMeetings) // two held events; cancelled ones excluded
        assertEquals(1, stats.autoRejectedApplications)
        assertEquals(1, stats.cancelledMeetings)
    }

    @Test
    fun `churned-members roster lists currently-gone members newest first, excluding rejoined and out-of-window`() {
        // A: left 5d ago, no membership row (genuinely gone) → in roster.
        val a = newUser("Anna")
        insertMembershipHistory(a, "left", daysAgo(5))
        // B: expired 10d ago with an expired membership row → in roster (older than A).
        val b = newUser("Boris")
        insertMembershipHistory(b, "expired", daysAgo(10))
        insertMembership(b, "expired")
        // C: left 8d ago but rejoined → currently active → excluded.
        val c = newUser("Clara")
        insertMembershipHistory(c, "left", daysAgo(8))
        insertMembership(c, "active")
        // D: left 40d ago → outside the 30d window → excluded.
        val d = newUser("Dmitry")
        insertMembershipHistory(d, "left", daysAgo(40))

        val roster = clubStatsService.getChurnedMembers(clubId)

        assertEquals(listOf(a, b), roster.map { it.userId }) // newest departure first
        assertEquals("Anna", roster.first().firstName)
        // The lever count equals the roster size by construction.
        assertEquals(roster.size, clubStatsService.getClubStats(clubId).churnedThisPeriod)
    }

    // ---- helpers ----

    private fun newUser(firstName: String = "U"): UUID {
        val id = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$id', ${telegramSeq++}, '$firstName')")
        return id
    }

    private fun insertClub(price: Int, accessType: String): UUID {
        val id = UUID.randomUUID()
        val createdAt = OffsetDateTime.now().minusMonths(14)
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city,
                               member_limit, subscription_price, is_active, created_at)
            VALUES ('$id', '$ownerId', 'Club', 'desc', 'sport', '$accessType'::access_type, 'Moscow',
                    20, $price, true, '$createdAt')
            """.trimIndent(),
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
            """.trimIndent(),
        )
        return id
    }

    private fun insertResponse(
        eventId: UUID,
        attendance: String?,
        userId: UUID = newUser(),
        disputeTerminal: Boolean = false,
        disputeNote: String? = null,
    ) {
        val id = UUID.randomUUID()
        val att = attendance?.let { "'$it'::attendance_status" } ?: "NULL"
        val note = disputeNote?.let { "'$it'" } ?: "NULL"
        dsl.execute(
            """
            INSERT INTO event_responses (id, event_id, user_id, stage_1_vote, stage_1_timestamp,
                                         stage_2_vote, final_status, attendance, dispute_terminal, dispute_note)
            VALUES ('$id', '$eventId', '$userId', 'going'::stage_1_vote, NOW(),
                    'confirmed'::stage_2_vote, 'confirmed'::final_status, $att, $disputeTerminal, $note)
            """.trimIndent(),
        )
    }

    private fun insertMembership(userId: UUID, status: String) {
        dsl.execute(
            """
            INSERT INTO memberships (id, user_id, club_id, status, role)
            VALUES ('${UUID.randomUUID()}', '$userId', '$clubId', '$status'::membership_status, 'member'::membership_role)
            """.trimIndent(),
        )
    }

    private fun insertMembershipHistory(userId: UUID, event: String, occurredAt: OffsetDateTime, club: UUID = clubId) {
        dsl.execute(
            """
            INSERT INTO membership_history (id, user_id, club_id, event, occurred_at)
            VALUES ('${UUID.randomUUID()}', '$userId', '$club', '$event'::membership_event, '$occurredAt')
            """.trimIndent(),
        )
    }

    private fun insertTransaction(userId: UUID, type: String, status: String, createdAt: OffsetDateTime) {
        dsl.execute(
            """
            INSERT INTO transactions (id, user_id, club_id, type, status, amount, created_at)
            VALUES ('${UUID.randomUUID()}', '$userId', '$clubId', '$type'::transaction_type,
                    '$status'::transaction_status, 100, '$createdAt')
            """.trimIndent(),
        )
    }

    private fun insertApplication(
        userId: UUID,
        status: String,
        createdAt: OffsetDateTime,
        resolvedAt: OffsetDateTime? = null,
    ) {
        val resolved = resolvedAt?.let { "'$it'" } ?: "NULL"
        dsl.execute(
            """
            INSERT INTO applications (id, user_id, club_id, status, created_at, resolved_at)
            VALUES ('${UUID.randomUUID()}', '$userId', '$clubId', '$status'::application_status,
                    '$createdAt', $resolved)
            """.trimIndent(),
        )
    }

    private fun insertClosedSkladchina(closedAt: OffsetDateTime): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO skladchinas (id, club_id, creator_id, title, payment_mode, payment_link,
                                     deadline, status, closed_at)
            VALUES ('$id', '$clubId', '$ownerId', 'Сбор', 'voluntary'::skladchina_mode, 'http://pay',
                    '$closedAt', 'closed_success'::skladchina_status, '$closedAt')
            """.trimIndent(),
        )
        return id
    }

    private fun insertSkladchinaParticipant(skladchinaId: UUID, userId: UUID, status: String) {
        dsl.execute(
            """
            INSERT INTO skladchina_participants (skladchina_id, user_id, status)
            VALUES ('$skladchinaId', '$userId', '$status'::skladchina_participant_status)
            """.trimIndent(),
        )
    }

    private fun daysAgo(days: Long): OffsetDateTime = OffsetDateTime.now().minusDays(days)
    private fun hoursAgo(hours: Long): OffsetDateTime = OffsetDateTime.now().minusHours(hours)
}
