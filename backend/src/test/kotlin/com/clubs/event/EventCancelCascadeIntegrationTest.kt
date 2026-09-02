package com.clubs.event

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
import kotlin.test.assertNull

/**
 * F5-14 event cancellation against a real Postgres. Covers the new per-event repository sweeps:
 *  - [EventRepository.cancelEvent]: the SQL guard cancels ONLY a still-active event whose datetime
 *    is in the future, never a started/completed/already-cancelled one, and persists the reason.
 *  - [SkladchinaRepository.cancelActiveByEventId]: cancels only the target event's active split and
 *    releases its pending participants (pending → released, no reputation), leaving paid
 *    participants, closed splits, and other events' splits untouched.
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
class EventCancelCascadeIntegrationTest {

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
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
    private lateinit var club: UUID
    private var telegramSeq = 6000L

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM skladchina_participants")
        dsl.execute("DELETE FROM skladchinas")
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM membership_history")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")
        telegramSeq = 6000L

        ownerId = newUser()
        club = newClub()
    }

    @Test
    fun `cancelEvent cancels a future event and persists the reason`() {
        val future = insertEvent(club, "upcoming", OffsetDateTime.now().plusDays(2))

        val rows = eventRepository.cancelEvent(future, "Площадка закрылась")

        assertEquals(1, rows)
        assertEquals("cancelled", statusOf("events", future))
        assertEquals("Площадка закрылась", reasonOf(future))
    }

    @Test
    fun `cancelEvent refuses a started, completed, or already-cancelled event`() {
        // stage_2 but its datetime already passed → the event has started, must not be cancelled.
        val started = insertEvent(club, "stage_2", OffsetDateTime.now().minusHours(1))
        val completed = insertEvent(club, "completed", OffsetDateTime.now().minusDays(1))
        val alreadyCancelled = insertEvent(club, "cancelled", OffsetDateTime.now().plusDays(2))

        assertEquals(0, eventRepository.cancelEvent(started, null))
        assertEquals(0, eventRepository.cancelEvent(completed, null))
        assertEquals(0, eventRepository.cancelEvent(alreadyCancelled, null))

        assertEquals("stage_2", statusOf("events", started))
        assertEquals("completed", statusOf("events", completed))
        assertNull(reasonOf(started))
    }

    @Test
    fun `cancelActiveByEventId cancels the linked active split and releases pending, leaving the rest intact`() {
        val event = insertEvent(club, "stage_2", OffsetDateTime.now().plusDays(2))
        val activeSplit = insertSkladchina(club, "active", event)
        val pendingUser = newUser()
        val paidUser = newUser()
        insertParticipant(activeSplit, pendingUser, "pending")
        insertParticipant(activeSplit, paidUser, "paid")

        // A successfully-closed split for the SAME event must be left intact (money already collected).
        val closedSplit = insertSkladchina(club, "closed_success", event)
        // An active split on ANOTHER event must not be touched.
        val otherEvent = insertEvent(club, "stage_2", OffsetDateTime.now().plusDays(2))
        val otherSplit = insertSkladchina(club, "active", otherEvent)
        val otherPending = newUser()
        insertParticipant(otherSplit, otherPending, "pending")

        val cancelled = skladchinaRepository.cancelActiveByEventId(event)

        assertEquals(1, cancelled)
        assertEquals("cancelled", statusOf("skladchinas", activeSplit))
        // pending → released (reputation-neutral), NOT expired_no_response (which would penalize).
        assertEquals("released", participantStatusOf(activeSplit, pendingUser))
        assertEquals("paid", participantStatusOf(activeSplit, paidUser))
        assertEquals("closed_success", statusOf("skladchinas", closedSplit))
        assertEquals("active", statusOf("skladchinas", otherSplit))
        assertEquals("pending", participantStatusOf(otherSplit, otherPending))
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

    private fun insertEvent(clubId: UUID, status: String, datetime: OffsetDateTime, finalized: Boolean = false): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime, participant_limit, voting_opens_days_before, status, attendance_finalized)
            VALUES ('$id', '$clubId', '$ownerId', 'Event', 'Place', '$datetime', 10, 14, '$status'::event_status, $finalized)
            """.trimIndent()
        )
        return id
    }

    private fun insertSkladchina(clubId: UUID, status: String, eventId: UUID?): UUID {
        val id = UUID.randomUUID()
        val deadline = OffsetDateTime.now().plusDays(3)
        val eventValue = eventId?.let { "'$it'" } ?: "NULL"
        dsl.execute(
            """
            INSERT INTO skladchinas (id, club_id, creator_id, title, payment_mode, payment_link, deadline, status, event_id)
            VALUES ('$id', '$clubId', '$ownerId', 'Sklad', 'voluntary'::skladchina_mode, 'http://pay', '$deadline', '$status'::skladchina_status, $eventValue)
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

    private fun statusOf(table: String, id: UUID): String? =
        dsl.fetchOne("SELECT status FROM $table WHERE id = ?", id)?.get(0, String::class.java)

    private fun reasonOf(id: UUID): String? =
        dsl.fetchOne("SELECT cancellation_reason FROM events WHERE id = ?", id)?.get(0, String::class.java)

    private fun participantStatusOf(skladchinaId: UUID, userId: UUID): String? =
        dsl.fetchOne(
            "SELECT status FROM skladchina_participants WHERE skladchina_id = ? AND user_id = ?",
            skladchinaId, userId
        )?.get(0, String::class.java)
}
