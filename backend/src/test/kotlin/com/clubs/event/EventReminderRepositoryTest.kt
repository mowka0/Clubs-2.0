package com.clubs.event

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
import kotlin.test.assertTrue

/**
 * Integration test for the reminder queries (EventReminderScheduler) against a real Postgres:
 * which events are due for the confirm reminder (A) / attendance reminder (B), the dedup flags,
 * the unconfirmed-voter recipient set, and the organizer telegram lookup.
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
class EventReminderRepositoryTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("clubs_test").withUsername("test").withPassword("test")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var eventRepository: EventRepository
    @Autowired lateinit var eventResponseRepository: EventResponseRepository
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
    private var ownerTelegramId = 0L
    private lateinit var clubId: UUID
    private var telegramSeq = 7000L

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")
        telegramSeq = 7000L

        ownerTelegramId = telegramSeq
        ownerId = newUser()
        clubId = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price, is_active)
            VALUES ('$clubId', '$ownerId', 'Club', 'desc', 'sport', 'open', 'Moscow', 20, 0, true)
            """.trimIndent()
        )
    }

    // --- A: confirm reminder ---

    @Test
    fun `confirm reminder finds stage_2 events starting within the window, not yet reminded`() {
        val now = OffsetDateTime.now()
        val due = insertEvent(now.plusHours(1), "stage_2")
        insertEvent(now.plusHours(3), "stage_2")                       // outside window
        insertEvent(now.minusHours(1), "stage_2")                      // already started
        insertEvent(now.plusHours(1), "upcoming")                      // not stage_2
        insertEvent(now.plusHours(1), "stage_2", confirmReminderSent = true) // already reminded

        val result = eventRepository.findEventsNeedingConfirmReminder(now, now.plusHours(2)).map { it.id }

        assertEquals(listOf(due), result)
    }

    @Test
    fun `markConfirmReminderSent flips the flag (dedup)`() {
        val now = OffsetDateTime.now()
        val id = insertEvent(now.plusHours(1), "stage_2")

        eventRepository.markConfirmReminderSent(id)

        assertTrue(flag(id, "confirm_reminder_sent"))
        assertTrue(eventRepository.findEventsNeedingConfirmReminder(now, now.plusHours(2)).isEmpty())
    }

    @Test
    fun `unconfirmed voter recipients = going_maybe with null stage_2_vote only`() {
        val event = insertEvent(OffsetDateTime.now().plusHours(1), "stage_2")
        val goingNull = insertResponseUser(event, "going", null)
        val maybeNull = insertResponseUser(event, "maybe", null)
        insertResponseUser(event, "going", "confirmed")   // excluded
        insertResponseUser(event, "going", "declined")    // excluded
        insertResponseUser(event, "not_going", null)      // excluded

        val ids = eventResponseRepository.findUnconfirmedVoterTelegramIds(event).toSet()

        assertEquals(setOf(goingNull, maybeNull), ids)
    }

    // --- B: attendance reminder ---

    @Test
    fun `attendance reminder finds past unmarked non-cancelled events with a confirmed roster, not yet reminded`() {
        val now = OffsetDateTime.now()
        val due = insertEvent(now.minusHours(25), "completed")
        insertResponseUser(due, "going", "confirmed")                            // has a roster to mark
        insertEvent(now.minusHours(10), "completed").also { insertResponseUser(it, "going", "confirmed") } // within 24h
        insertEvent(now.minusHours(25), "completed", attendanceMarked = true).also { insertResponseUser(it, "going", "confirmed") }       // already marked
        insertEvent(now.minusHours(25), "completed", attendanceReminderSent = true).also { insertResponseUser(it, "going", "confirmed") } // already reminded
        insertEvent(now.minusHours(25), "cancelled").also { insertResponseUser(it, "going", "confirmed") } // cancelled
        insertEvent(now.minusHours(25), "completed")                             // CC-2: no confirmed roster → skip

        val result = eventRepository.findEventsNeedingAttendanceReminder(now.minusHours(24)).map { it.id }

        assertEquals(listOf(due), result)
    }

    @Test
    fun `attendance reminder excludes a neutrally-finalized event (F5-17)`() {
        val now = OffsetDateTime.now()
        // EXP-2 neutral finalize leaves marked=false, finalized=true. Without the finalized guard
        // the reminder still fires → organizer taps "mark" → markAttendance throws finalized → 400.
        val neutrallyFinalized = insertEvent(now.minusHours(25), "completed", attendanceFinalized = true)
        insertResponseUser(neutrallyFinalized, "going", "confirmed")
        // regression guard: a still-open unmarked event is still reminded
        val stillDue = insertEvent(now.minusHours(25), "completed")
        insertResponseUser(stillDue, "going", "confirmed")

        val result = eventRepository.findEventsNeedingAttendanceReminder(now.minusHours(24)).map { it.id }

        assertEquals(listOf(stillDue), result)
    }

    @Test
    fun `markAttendanceReminderSent flips the flag (dedup)`() {
        val now = OffsetDateTime.now()
        val id = insertEvent(now.minusHours(25), "completed")
        insertResponseUser(id, "going", "confirmed") // qualifies, so the flag is the only thing stopping it

        eventRepository.markAttendanceReminderSent(id)

        assertTrue(flag(id, "attendance_reminder_sent"))
        assertTrue(eventRepository.findEventsNeedingAttendanceReminder(now.minusHours(24)).isEmpty())
    }

    @Test
    fun `findOrganizerTelegramId returns the club owner telegram id`() {
        val event = insertEvent(OffsetDateTime.now().minusHours(25), "completed")

        assertEquals(ownerTelegramId, eventRepository.findOrganizerTelegramId(event))
    }

    // --- helpers ---

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$id', ${telegramSeq++}, 'U')")
        return id
    }

    private fun insertEvent(
        eventDatetime: OffsetDateTime,
        status: String,
        confirmReminderSent: Boolean = false,
        attendanceMarked: Boolean = false,
        attendanceReminderSent: Boolean = false,
        attendanceFinalized: Boolean = false
    ): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime,
                participant_limit, voting_opens_days_before, status, stage_2_triggered,
                attendance_marked, confirm_reminder_sent, attendance_reminder_sent, attendance_finalized)
            VALUES ('$id', '$clubId', '$ownerId', 'Event', 'Place', '$eventDatetime', 10, 14,
                '$status'::event_status, true, $attendanceMarked, $confirmReminderSent, $attendanceReminderSent, $attendanceFinalized)
            """.trimIndent()
        )
        return id
    }

    /** Inserts a fresh user + their response; returns the user's telegram id. */
    private fun insertResponseUser(eventId: UUID, stage1: String, stage2: String?): Long {
        val tgId = telegramSeq
        val userId = newUser()
        val s2 = stage2?.let { "'$it'::stage_2_vote" } ?: "NULL"
        val fs = stage2?.let { "'$it'::final_status" } ?: "NULL"
        dsl.execute(
            """
            INSERT INTO event_responses (id, event_id, user_id, stage_1_vote, stage_1_timestamp, stage_2_vote, final_status)
            VALUES ('${UUID.randomUUID()}', '$eventId', '$userId', '$stage1'::stage_1_vote, NOW(), $s2, $fs)
            """.trimIndent()
        )
        return tgId
    }

    private fun flag(id: UUID, col: String): Boolean =
        dsl.fetchOne("SELECT $col FROM events WHERE id = ?", id)!!.get(0, Boolean::class.java)
}
