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

/**
 * Integration test for the auto-complete query against a real Postgres.
 * Covers the event lifecycle transition `upcoming/stage_1/stage_2 -> completed`
 * introduced by [EventCompletionService] / [EventRepository.markPastEventsCompleted].
 *
 * Grace period is 6h (COMPLETION_GRACE_HOURS); cutoff = now - 6h. Here we pass the
 * cutoff explicitly to make the boundary cases deterministic.
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
class EventCompletionRepositoryTest {

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
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
    private lateinit var clubId: UUID

    private fun cutoff(): OffsetDateTime = OffsetDateTime.now().minusHours(6)

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")

        ownerId = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$ownerId', 5001, 'Owner')")

        clubId = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price, is_active)
            VALUES ('$clubId', '$ownerId', 'Club', 'desc', 'sport', 'open', 'Moscow', 20, 0, true)
            """.trimIndent()
        )
    }

    @Test
    fun `past upcoming event becomes completed`() {
        val id = insertEvent(hoursFromNow(-10), "upcoming")

        val count = eventRepository.markPastEventsCompleted(cutoff())

        assertEquals(1, count)
        assertEquals("completed", statusOf(id))
    }

    @Test
    fun `past stage_2 event becomes completed`() {
        val id = insertEvent(hoursFromNow(-10), "stage_2")

        val count = eventRepository.markPastEventsCompleted(cutoff())

        assertEquals(1, count)
        assertEquals("completed", statusOf(id))
    }

    @Test
    fun `past stage_1 event becomes completed`() {
        val id = insertEvent(hoursFromNow(-10), "stage_1")

        val count = eventRepository.markPastEventsCompleted(cutoff())

        assertEquals(1, count)
        assertEquals("completed", statusOf(id))
    }

    @Test
    fun `future event is unchanged`() {
        val id = insertEvent(hoursFromNow(48), "upcoming")

        val count = eventRepository.markPastEventsCompleted(cutoff())

        assertEquals(0, count)
        assertEquals("upcoming", statusOf(id))
    }

    @Test
    fun `event within grace window is not completed yet`() {
        // datetime just passed (2h ago) but still inside the 6h grace -> not before cutoff
        val id = insertEvent(hoursFromNow(-2), "upcoming")

        val count = eventRepository.markPastEventsCompleted(cutoff())

        assertEquals(0, count)
        assertEquals("upcoming", statusOf(id))
    }

    @Test
    fun `cancelled event stays cancelled`() {
        val id = insertEvent(hoursFromNow(-10), "cancelled")

        val count = eventRepository.markPastEventsCompleted(cutoff())

        assertEquals(0, count)
        assertEquals("cancelled", statusOf(id))
    }

    @Test
    fun `already completed event is idempotent on second run`() {
        val id = insertEvent(hoursFromNow(-10), "upcoming")

        val first = eventRepository.markPastEventsCompleted(cutoff())
        val second = eventRepository.markPastEventsCompleted(cutoff())

        assertEquals(1, first)
        assertEquals(0, second)
        assertEquals("completed", statusOf(id))
    }

    @Test
    fun `mixed batch completes only past active events`() {
        val pastUpcoming = insertEvent(hoursFromNow(-10), "upcoming")
        val pastStage2 = insertEvent(hoursFromNow(-12), "stage_2")
        val futureUpcoming = insertEvent(hoursFromNow(48), "upcoming")
        val withinGrace = insertEvent(hoursFromNow(-1), "upcoming")
        val cancelled = insertEvent(hoursFromNow(-10), "cancelled")

        val count = eventRepository.markPastEventsCompleted(cutoff())

        assertEquals(2, count)
        assertEquals("completed", statusOf(pastUpcoming))
        assertEquals("completed", statusOf(pastStage2))
        assertEquals("upcoming", statusOf(futureUpcoming))
        assertEquals("upcoming", statusOf(withinGrace))
        assertEquals("cancelled", statusOf(cancelled))
    }

    // ---- helpers ----

    private fun insertEvent(eventDatetime: OffsetDateTime, status: String): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime, participant_limit, voting_opens_days_before, status)
            VALUES ('$id', '$clubId', '$ownerId', 'Event', 'Place', '$eventDatetime', 10, 14, '$status'::event_status)
            """.trimIndent()
        )
        return id
    }

    private fun statusOf(id: UUID): String =
        dsl.fetchOne("SELECT status FROM events WHERE id = ?", id)!!.get(0, String::class.java)

    private fun hoursFromNow(hours: Long): OffsetDateTime = OffsetDateTime.now().plusHours(hours)
}
