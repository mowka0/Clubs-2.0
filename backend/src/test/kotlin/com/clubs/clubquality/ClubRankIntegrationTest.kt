package com.clubs.clubquality

import com.clubs.reputation.LedgerReadPort
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the L3 rank against a real Postgres — proves the repository SQL executes and
 * that the two critical anti-farm holes the red-team found are closed IN CODE (not just in the pure
 * policy): (1) attendance without a member vote-before-event cannot qualify a club's core, and
 * (2) the owner-authorable `skladchina_paid` is excluded from the credibility footprint.
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
class ClubRankIntegrationTest {

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

    @Autowired lateinit var clubRankService: ClubRankService
    @Autowired lateinit var clubRankRepository: ClubRankRepository
    @Autowired lateinit var ledgerReadPort: LedgerReadPort
    @Autowired lateinit var dsl: DSLContext

    private val now: OffsetDateTime = OffsetDateTime.now()
    private var seq = 7000L

    @BeforeEach
    fun setUp() {
        listOf(
            "club_rank", "reputation_ledger", "transactions", "applications", "event_responses", "events",
            "membership_history", "memberships", "clubs", "users",
        ).forEach { dsl.execute("DELETE FROM $it") }
        seq = 7000L
    }

    @Test
    fun `recompute runs over every query branch without error and stores a row per club`() {
        val owner = newUser()
        val club = insertClub(owner)
        val member = newUser()
        val e = insertEvent(club, owner, now.minusDays(5), finalized = true, marked = true)
        insertAttended(e, member, now.minusDays(6)) // qualifying-shaped vote-before-event
        insertDispute(e, newUser())
        insertEvent(club, owner, now.minusDays(8), finalized = true, marked = false) // ghosting branch
        insertTransaction(club, member)
        insertMembershipHistory(club, member, "left", now.minusDays(3))
        insertApplication(club, newUser()) // auto-reject branch

        clubRankService.recomputeAll() // must not throw across all 11 grouped queries

        val stored = dsl.fetchCount(
            com.clubs.generated.jooq.tables.references.CLUB_RANK,
            com.clubs.generated.jooq.tables.references.CLUB_RANK.CLUB_ID.eq(club),
        )
        assertEquals(1, stored)
    }

    @Test
    fun `a credible qualifying core passes the gate and the club is ranked`() {
        val owner = newUser()
        val club = insertClub(owner)
        val e1dt = now.minusDays(20)
        val e2dt = now.minusDays(12) // 8 days apart → clears the 7-day temporal spread
        val e1 = insertEvent(club, owner, e1dt)
        val e2 = insertEvent(club, owner, e2dt)
        val footprintClubs = (1..3).map { insertClub(newUser()) } // 3 distinct owners → footprintW 1.0

        repeat(8) {
            val u = newUser(maturedDays = 200, username = true, avatar = true)
            insertAttended(e1, u, e1dt.minusDays(1))
            insertAttended(e2, u, e2dt.minusDays(1))
            footprintClubs.forEach { fc -> insertLedger(u, fc, "ironclad") }
        }

        clubRankService.recomputeAll()

        assertTrue(clubRankRepository.findRankedClubs().any { it.clubId == club })
    }

    @Test
    fun `attendance without a member vote-before-event does not qualify the core (owner-authored leak closed)`() {
        val owner = newUser()
        val club = insertClub(owner)
        val e1dt = now.minusDays(20)
        val e2dt = now.minusDays(12)
        val e1 = insertEvent(club, owner, e1dt)
        val e2 = insertEvent(club, owner, e2dt)
        val footprintClubs = (1..3).map { insertClub(newUser()) }

        repeat(8) {
            val u = newUser(maturedDays = 200, username = true, avatar = true)
            // Vote stamped AFTER the event (owner-marked attendance with no genuine prior member vote).
            insertAttended(e1, u, now)
            insertAttended(e2, u, now)
            footprintClubs.forEach { fc -> insertLedger(u, fc, "ironclad") }
        }

        clubRankService.recomputeAll()

        assertFalse(clubRankRepository.findRankedClubs().any { it.clubId == club })
    }

    @Test
    fun `the ledger footprint excludes owner-authorable skladchina_paid`() {
        val user = newUser()
        val ironcladOwner = newUser()
        val skladchinaOwner = newUser()
        val ironcladClub = insertClub(ironcladOwner)
        val skladchinaClub = insertClub(skladchinaOwner)
        insertLedger(user, ironcladClub, "ironclad")
        insertLedger(user, skladchinaClub, "skladchina_paid")

        val footprint = ledgerReadPort.footprintByUser(listOf(user))

        // Only the ironclad club's owner counts; the skladchina_paid owner is absent.
        assertEquals(mapOf(ironcladOwner to 1), footprint[user])
    }

    // ---- helpers ----

    private fun newUser(maturedDays: Long = 0, username: Boolean = false, avatar: Boolean = false): UUID {
        val id = UUID.randomUUID()
        val createdAt = now.minusDays(maturedDays)
        val uname = if (username) "'u${seq}'" else "NULL"
        val av = if (avatar) "'http://a/${seq}'" else "NULL"
        dsl.execute(
            "INSERT INTO users (id, telegram_id, telegram_username, first_name, avatar_url, created_at) " +
                "VALUES ('$id', ${seq++}, $uname, 'U', $av, '$createdAt')",
        )
        return id
    }

    private fun insertClub(owner: UUID, category: String = "sport", price: Int = 0, access: String = "open"): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city,
                               member_limit, subscription_price, is_active, created_at)
            VALUES ('$id', '$owner', 'Club', 'desc', '$category'::club_category, '$access'::access_type,
                    'Moscow', 20, $price, true, '${now.minusDays(400)}')
            """.trimIndent(),
        )
        return id
    }

    private fun insertEvent(
        club: UUID,
        owner: UUID,
        datetime: OffsetDateTime,
        finalized: Boolean = true,
        marked: Boolean = true,
    ): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime,
                                participant_limit, status, attendance_marked, attendance_finalized)
            VALUES ('$id', '$club', '$owner', 'E', 'P', '$datetime', 20, 'completed'::event_status, $marked, $finalized)
            """.trimIndent(),
        )
        return id
    }

    private fun insertAttended(event: UUID, user: UUID, stage1: OffsetDateTime) {
        dsl.execute(
            """
            INSERT INTO event_responses (id, event_id, user_id, stage_1_vote, stage_1_timestamp, attendance)
            VALUES ('${UUID.randomUUID()}', '$event', '$user', 'going'::stage_1_vote, '$stage1', 'attended'::attendance_status)
            """.trimIndent(),
        )
    }

    private fun insertDispute(event: UUID, user: UUID) {
        dsl.execute(
            "INSERT INTO event_responses (id, event_id, user_id, attendance) " +
                "VALUES ('${UUID.randomUUID()}', '$event', '$user', 'disputed'::attendance_status)",
        )
    }

    private fun insertLedger(user: UUID, club: UUID, kind: String) {
        val axis = if (kind == "skladchina_paid") "finance" else "attendance"
        val source = if (kind == "skladchina_paid") "skladchina" else "event"
        dsl.execute(
            """
            INSERT INTO reputation_ledger (user_id, club_id, axis, kind, points, occurred_at, source_type, source_id)
            VALUES ('$user', '$club', '$axis'::reputation_axis, '$kind'::reputation_kind, 10,
                    '${now.minusDays(30)}', '$source'::reputation_source, '${UUID.randomUUID()}')
            """.trimIndent(),
        )
    }

    private fun insertTransaction(club: UUID, user: UUID) {
        dsl.execute(
            """
            INSERT INTO transactions (id, user_id, club_id, type, status, amount, telegram_payment_charge_id, created_at)
            VALUES ('${UUID.randomUUID()}', '$user', '$club', 'subscription'::transaction_type,
                    'completed'::transaction_status, 100, 'charge_${UUID.randomUUID()}', '${now.minusDays(15)}')
            """.trimIndent(),
        )
    }

    private fun insertMembershipHistory(club: UUID, user: UUID, event: String, at: OffsetDateTime) {
        dsl.execute(
            "INSERT INTO membership_history (id, user_id, club_id, event, occurred_at) " +
                "VALUES ('${UUID.randomUUID()}', '$user', '$club', '$event'::membership_event, '$at')",
        )
    }

    private fun insertApplication(club: UUID, user: UUID) {
        dsl.execute(
            """
            INSERT INTO applications (id, user_id, club_id, status, resolved_at)
            VALUES ('${UUID.randomUUID()}', '$user', '$club', 'auto_rejected'::application_status, '${now.minusDays(5)}')
            """.trimIndent(),
        )
    }
}
