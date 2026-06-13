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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests against a real Postgres for the two Block-1 repository methods:
 *  - [EventRepository.neutrallyFinalizeUnmarkedBefore] (EXP-2): closes unmarked past events with
 *    finalized=true / marked=false so the reputation pipeline ignores them.
 *  - [EventResponseRepository.resolveExpiredDisputesToAbsent] (ATT-2): collapses leftover disputed
 *    marks back to absent at finalization, scoped to the given events only.
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
class AttendanceFinalizeRepositoryTest {

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
    @Autowired lateinit var eventResponseRepository: EventResponseRepository
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
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

        ownerId = newUser()
        clubId = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price, is_active)
            VALUES ('$clubId', '$ownerId', 'Club', 'desc', 'sport', 'open', 'Moscow', 20, 0, true)
            """.trimIndent()
        )
    }

    // ---- EXP-2: neutrallyFinalizeUnmarkedBefore ----

    @Test
    fun `neutral finalize closes an unmarked past event and leaves it unmarked`() {
        val event = insertEvent(hoursFromNow(-49), "completed", marked = false, finalized = false)

        val ids = eventRepository.neutrallyFinalizeUnmarkedBefore(OffsetDateTime.now())

        assertEquals(listOf(event), ids)
        assertTrue(boolField(event, "attendance_finalized"), "event is finalized")
        assertFalse(boolField(event, "attendance_marked"), "but it stays UNMARKED so reputation ignores it")
    }

    @Test
    fun `neutral finalize skips a marked event (the normal finalizer owns it)`() {
        val event = insertEvent(hoursFromNow(-49), "completed", marked = true, finalized = false)

        val ids = eventRepository.neutrallyFinalizeUnmarkedBefore(OffsetDateTime.now())

        assertTrue(ids.isEmpty())
        assertFalse(boolField(event, "attendance_finalized"))
    }

    @Test
    fun `neutral finalize skips cancelled, future and already-finalized events`() {
        val cancelled = insertEvent(hoursFromNow(-49), "cancelled", marked = false, finalized = false)
        val future = insertEvent(hoursFromNow(5), "stage_2", marked = false, finalized = false)
        val done = insertEvent(hoursFromNow(-49), "completed", marked = false, finalized = true)

        val ids = eventRepository.neutrallyFinalizeUnmarkedBefore(OffsetDateTime.now())

        assertTrue(ids.isEmpty())
        assertFalse(boolField(cancelled, "attendance_finalized"))
        assertFalse(boolField(future, "attendance_finalized"))
        assertTrue(boolField(done, "attendance_finalized"), "stays finalized, not re-touched")
    }

    @Test
    fun `neutral finalize is idempotent on a second run`() {
        insertEvent(hoursFromNow(-49), "completed", marked = false, finalized = false)

        val first = eventRepository.neutrallyFinalizeUnmarkedBefore(OffsetDateTime.now())
        val second = eventRepository.neutrallyFinalizeUnmarkedBefore(OffsetDateTime.now())

        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `neutral finalize includes the exact cutoff boundary (lessOrEqual)`() {
        val boundary = OffsetDateTime.now()
        val event = insertEvent(boundary, "completed", marked = false, finalized = false)

        val ids = eventRepository.neutrallyFinalizeUnmarkedBefore(boundary)

        assertEquals(listOf(event), ids)
    }

    // ---- ATT-2: resolveExpiredDisputesToAbsent ----

    @Test
    fun `expired disputes collapse to absent, other marks untouched`() {
        val event = insertEvent(hoursFromNow(-49), "completed", marked = true, finalized = false)
        val disputed = insertResponseWithAttendance(event, "going", "confirmed", "disputed")
        val attended = insertResponseWithAttendance(event, "going", "confirmed", "attended")
        val absent = insertResponseWithAttendance(event, "going", "confirmed", "absent")
        val unmarked = insertResponseWithAttendance(event, "maybe", "confirmed", null)

        val updated = eventResponseRepository.resolveExpiredDisputesToAbsent(listOf(event))

        assertEquals(1, updated)
        assertEquals("absent", attendanceOf(disputed))
        assertEquals("attended", attendanceOf(attended))
        assertEquals("absent", attendanceOf(absent))
        assertNull(attendanceOf(unmarked))
    }

    @Test
    fun `dispute conversion is scoped to the given events only`() {
        val finalizing = insertEvent(hoursFromNow(-49), "completed", marked = true, finalized = false)
        val other = insertEvent(hoursFromNow(-49), "completed", marked = true, finalized = false)
        val inScope = insertResponseWithAttendance(finalizing, "going", "confirmed", "disputed")
        val outOfScope = insertResponseWithAttendance(other, "going", "confirmed", "disputed")

        val updated = eventResponseRepository.resolveExpiredDisputesToAbsent(listOf(finalizing))

        assertEquals(1, updated)
        assertEquals("absent", attendanceOf(inScope))
        assertEquals("disputed", attendanceOf(outOfScope), "another event's dispute is still open")
    }

    @Test
    fun `dispute conversion ignores a non-confirmed disputed row (FINAL_STATUS guard)`() {
        val event = insertEvent(hoursFromNow(-49), "completed", marked = true, finalized = false)
        // Defensive: only the confirmed roster feeds reputation, so a disputed mark on a
        // non-confirmed row (corruption / future change) must NOT be silently converted.
        val waitlistedDisputed = insertResponseWithAttendance(event, "going", "waitlisted", "disputed")
        val confirmedDisputed = insertResponseWithAttendance(event, "going", "confirmed", "disputed")

        val updated = eventResponseRepository.resolveExpiredDisputesToAbsent(listOf(event))

        assertEquals(1, updated)
        assertEquals("disputed", attendanceOf(waitlistedDisputed), "non-confirmed disputed row untouched")
        assertEquals("absent", attendanceOf(confirmedDisputed))
    }

    @Test
    fun `empty event list performs no update`() {
        assertEquals(0, eventResponseRepository.resolveExpiredDisputesToAbsent(emptyList()))
    }

    // ---- finalizeAttendanceBefore: cancelled events must not finalize (club-delete cascade) ----

    @Test
    fun `finalize finalizes a marked past event`() {
        val event = insertEvent(hoursFromNow(-1), "stage_2", marked = true, finalized = false)

        val ids = eventRepository.finalizeAttendanceBefore(OffsetDateTime.now())

        assertEquals(listOf(event), ids)
        assertTrue(boolField(event, "attendance_finalized"))
    }

    @Test
    fun `finalize skips a cancelled but marked event so reputation never accrues`() {
        // Club-delete cascade can cancel a stage_2 event that was already attendance-marked
        // (marking is allowed in the ~6h before the completion sweep). Such an event must NOT
        // finalize — otherwise AttendanceFinalizedEvent → claimEvent would write a ledger row
        // for a deleted club, violating "keep reputation untouched".
        val event = insertEvent(hoursFromNow(-1), "cancelled", marked = true, finalized = false)

        val ids = eventRepository.finalizeAttendanceBefore(OffsetDateTime.now())

        assertTrue(ids.isEmpty())
        assertFalse(boolField(event, "attendance_finalized"))
    }

    // ---- helpers ----

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$id', ${telegramSeq++}, 'U')")
        return id
    }

    private fun insertEvent(eventDatetime: OffsetDateTime, status: String, marked: Boolean, finalized: Boolean): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime,
                                participant_limit, voting_opens_days_before, status,
                                stage_2_triggered, attendance_marked, attendance_finalized)
            VALUES ('$id', '$clubId', '$ownerId', 'Event', 'Place', '$eventDatetime', 10, 14,
                    '$status'::event_status, true, $marked, $finalized)
            """.trimIndent()
        )
        return id
    }

    private fun insertResponseWithAttendance(eventId: UUID, stage1: String, finalStatus: String, attendance: String?): UUID {
        val id = UUID.randomUUID()
        val userId = newUser()
        val att = attendance?.let { "'$it'::attendance_status" } ?: "NULL"
        dsl.execute(
            """
            INSERT INTO event_responses (id, event_id, user_id, stage_1_vote, stage_1_timestamp, stage_2_vote, final_status, attendance)
            VALUES ('$id', '$eventId', '$userId', '$stage1'::stage_1_vote, NOW(),
                    '$finalStatus'::stage_2_vote, '$finalStatus'::final_status, $att)
            """.trimIndent()
        )
        return id
    }

    private fun boolField(eventId: UUID, column: String): Boolean =
        dsl.fetchOne("SELECT $column FROM events WHERE id = ?", eventId)!!.get(0, Boolean::class.java)

    private fun attendanceOf(responseId: UUID): String? =
        dsl.fetchOne("SELECT attendance FROM event_responses WHERE id = ?", responseId)!!.get(0, String::class.java)

    private fun hoursFromNow(hours: Long): OffsetDateTime = OffsetDateTime.now().plusHours(hours)
}
