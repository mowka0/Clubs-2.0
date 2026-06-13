package com.clubs.club

import com.clubs.application.ApplicationRepository
import com.clubs.event.EventRepository
import com.clubs.skladchina.SkladchinaRepository
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

/**
 * Club soft-delete cascade against a real Postgres. Verifies the two repository sweeps used by
 * ClubService.deleteClub:
 *  - [EventRepository.cancelActiveEventsByClub] cancels only the club's non-finalized events,
 *    never a completed/finalized one, never another club's events.
 *  - [SkladchinaRepository.cancelActiveByClub] cancels only the club's active skladchinas and
 *    releases their pending participants (pending → released, NOT expired_no_response — so no
 *    reputation penalty), never touching paid participants, closed skladchinas, or other clubs.
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
class ClubDeleteCascadeIntegrationTest {

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

    @Autowired lateinit var eventRepository: EventRepository
    @Autowired lateinit var skladchinaRepository: SkladchinaRepository
    @Autowired lateinit var applicationRepository: ApplicationRepository
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
    private lateinit var clubA: UUID
    private lateinit var clubB: UUID
    private var telegramSeq = 5000L

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM skladchina_participants")
        dsl.execute("DELETE FROM skladchinas")
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM applications")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")
        telegramSeq = 5000L

        ownerId = newUser()
        clubA = newClub()
        clubB = newClub()
    }

    @Test
    fun `cancelActiveEventsByClub cancels only the club's non-finalized events`() {
        val upcoming = insertEvent(clubA, "upcoming")
        val stage2 = insertEvent(clubA, "stage_2")
        val completed = insertEvent(clubA, "completed")
        val finalized = insertEvent(clubA, "stage_2", finalized = true) // defensive guard target
        val otherClub = insertEvent(clubB, "upcoming")

        val cancelled = eventRepository.cancelActiveEventsByClub(clubA)

        assertEquals(2, cancelled)
        assertEquals("cancelled", statusOf("events", upcoming))
        assertEquals("cancelled", statusOf("events", stage2))
        // Completed events keep their terminal status (reputation already locked).
        assertEquals("completed", statusOf("events", completed))
        // attendance_finalized guard: never reopened even if status would otherwise match.
        assertEquals("stage_2", statusOf("events", finalized))
        // Another club is untouched.
        assertEquals("upcoming", statusOf("events", otherClub))
    }

    @Test
    fun `cancelActiveByClub cancels active skladchinas and releases pending participants without penalty`() {
        val active = insertSkladchina(clubA, "active")
        val pendingUser = newUser()
        val paidUser = newUser()
        insertParticipant(active, pendingUser, "pending")
        insertParticipant(active, paidUser, "paid")

        val alreadyClosed = insertSkladchina(clubA, "closed_success")
        val closedPending = newUser()
        insertParticipant(alreadyClosed, closedPending, "pending")

        val otherClubSkladchina = insertSkladchina(clubB, "active")
        val otherPending = newUser()
        insertParticipant(otherClubSkladchina, otherPending, "pending")

        val cancelled = skladchinaRepository.cancelActiveByClub(clubA)

        assertEquals(1, cancelled)
        assertEquals("cancelled", statusOf("skladchinas", active))
        // pending → released (reputation-neutral), NOT expired_no_response (which would penalize).
        assertEquals("released", participantStatusOf(active, pendingUser))
        // paid participants keep their status.
        assertEquals("paid", participantStatusOf(active, paidUser))
        // An already-closed skladchina and its participants are untouched.
        assertEquals("closed_success", statusOf("skladchinas", alreadyClosed))
        assertEquals("pending", participantStatusOf(alreadyClosed, closedPending))
        // Another club's active skladchina and its pending participant are untouched.
        assertEquals("active", statusOf("skladchinas", otherClubSkladchina))
        assertEquals("pending", participantStatusOf(otherClubSkladchina, otherPending))
    }

    @Test
    fun `deleteActiveByClub deletes pending and approved applications but preserves terminal ones`() {
        val pending = insertApplication(clubA, newUser(), "pending")
        val approved = insertApplication(clubA, newUser(), "approved")
        val rejected = insertApplication(clubA, newUser(), "rejected")
        val autoRejected = insertApplication(clubA, newUser(), "auto_rejected")
        val otherClubPending = insertApplication(clubB, newUser(), "pending")

        val deleted = applicationRepository.deleteActiveByClub(clubA)

        assertEquals(2, deleted)
        assertEquals(0, applicationCount(pending))
        assertEquals(0, applicationCount(approved))
        // Terminal applications survive as audit history.
        assertEquals(1, applicationCount(rejected))
        assertEquals(1, applicationCount(autoRejected))
        // Another club's application is untouched.
        assertEquals(1, applicationCount(otherClubPending))
    }

    // ---- helpers ----

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$id', ${telegramSeq++}, 'U')")
        return id
    }

    private fun newClub(): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price, is_active)
            VALUES ('$id', '$ownerId', 'Club', 'desc', 'sport', 'open', 'Moscow', 20, 0, true)
            """.trimIndent()
        )
        return id
    }

    private fun insertEvent(clubId: UUID, status: String, finalized: Boolean = false): UUID {
        val id = UUID.randomUUID()
        val datetime = OffsetDateTime.now().plusDays(3)
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime, participant_limit, voting_opens_days_before, status, attendance_finalized)
            VALUES ('$id', '$clubId', '$ownerId', 'Event', 'Place', '$datetime', 10, 14, '$status'::event_status, $finalized)
            """.trimIndent()
        )
        return id
    }

    private fun insertSkladchina(clubId: UUID, status: String): UUID {
        val id = UUID.randomUUID()
        val deadline = OffsetDateTime.now().plusDays(3)
        dsl.execute(
            """
            INSERT INTO skladchinas (id, club_id, creator_id, title, payment_mode, payment_link, deadline, status)
            VALUES ('$id', '$clubId', '$ownerId', 'Sklad', 'voluntary'::skladchina_mode, 'http://pay', '$deadline', '$status'::skladchina_status)
            """.trimIndent()
        )
        return id
    }

    private fun insertParticipant(skladchinaId: UUID, userId: UUID, status: String) {
        dsl.execute(
            """
            INSERT INTO skladchina_participants (skladchina_id, user_id, status)
            VALUES ('$skladchinaId', '$userId', '$status'::skladchina_participant_status)
            """.trimIndent()
        )
    }

    private fun insertApplication(clubId: UUID, userId: UUID, status: String): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO applications (id, user_id, club_id, status)
            VALUES ('$id', '$userId', '$clubId', '$status'::application_status)
            """.trimIndent()
        )
        return id
    }

    private fun applicationCount(id: UUID): Int =
        dsl.fetchOne("SELECT count(*) FROM applications WHERE id = ?", id)!!.get(0, Int::class.java)!!

    private fun statusOf(table: String, id: UUID): String? =
        dsl.fetchOne("SELECT status FROM $table WHERE id = ?", id)?.get(0, String::class.java)

    private fun participantStatusOf(skladchinaId: UUID, userId: UUID): String? =
        dsl.fetchOne(
            "SELECT status FROM skladchina_participants WHERE skladchina_id = ? AND user_id = ?",
            skladchinaId, userId
        )?.get(0, String::class.java)
}
