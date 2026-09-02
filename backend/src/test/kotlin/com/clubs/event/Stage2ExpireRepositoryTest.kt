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
import kotlin.test.assertNull

/**
 * Integration test for Feature A auto-expire ([EventResponseRepository.expireUnconfirmedForStartedEvents])
 * against a real Postgres. Verifies: only going/maybe + stage_2_vote IS NULL rows on started,
 * stage-2-triggered, non-cancelled events become expired_no_confirm; confirmed/waitlisted/declined
 * and not_going are never touched; the sweep is status-independent (a completed event still expires)
 * and idempotent.
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
class Stage2ExpireRepositoryTest {

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

    @Autowired lateinit var eventResponseRepository: EventResponseRepository
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
    private lateinit var clubId: UUID
    private var telegramSeq = 9000L

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM membership_history")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")
        telegramSeq = 9000L

        ownerId = newUser()
        clubId = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price, is_active)
            VALUES ('$clubId', '$ownerId', 'Club', 'desc', 'sport', 'open', 'Moscow', 20, 0, true)
            """.trimIndent()
        )
    }

    @Test
    fun `started triggered event expires null going and maybe`() {
        val event = insertEvent(hoursFromNow(-1), "stage_2", triggered = true)
        val goingResp = insertResponse(event, "going", stage2 = null)
        val maybeResp = insertResponse(event, "maybe", stage2 = null)

        val count = eventResponseRepository.expireUnconfirmedForStartedEvents(OffsetDateTime.now())

        assertEquals(2, count)
        assertEquals("expired_no_confirm", stage2VoteOf(goingResp))
        assertEquals("expired_no_confirm", finalStatusOf(goingResp))
        assertEquals("expired_no_confirm", stage2VoteOf(maybeResp))
    }

    @Test
    fun `confirmed waitlisted declined and not_going are never touched`() {
        val event = insertEvent(hoursFromNow(-1), "stage_2", triggered = true)
        val confirmed = insertResponse(event, "going", stage2 = "confirmed")
        val waitlisted = insertResponse(event, "going", stage2 = "waitlisted")
        val declined = insertResponse(event, "maybe", stage2 = "declined")
        val notGoing = insertResponse(event, "not_going", stage2 = null)

        val count = eventResponseRepository.expireUnconfirmedForStartedEvents(OffsetDateTime.now())

        assertEquals(0, count)
        assertEquals("confirmed", stage2VoteOf(confirmed))
        assertEquals("waitlisted", stage2VoteOf(waitlisted))
        assertEquals("declined", stage2VoteOf(declined))
        assertNull(stage2VoteOf(notGoing))
    }

    @Test
    fun `future event is not expired`() {
        val event = insertEvent(hoursFromNow(5), "stage_2", triggered = true)
        val resp = insertResponse(event, "going", stage2 = null)

        val count = eventResponseRepository.expireUnconfirmedForStartedEvents(OffsetDateTime.now())

        assertEquals(0, count)
        assertNull(stage2VoteOf(resp))
    }

    @Test
    fun `non-triggered event is not expired`() {
        val event = insertEvent(hoursFromNow(-1), "upcoming", triggered = false)
        val resp = insertResponse(event, "going", stage2 = null)

        val count = eventResponseRepository.expireUnconfirmedForStartedEvents(OffsetDateTime.now())

        assertEquals(0, count)
        assertNull(stage2VoteOf(resp))
    }

    @Test
    fun `cancelled event is not expired`() {
        val event = insertEvent(hoursFromNow(-1), "cancelled", triggered = true)
        val resp = insertResponse(event, "going", stage2 = null)

        val count = eventResponseRepository.expireUnconfirmedForStartedEvents(OffsetDateTime.now())

        assertEquals(0, count)
        assertNull(stage2VoteOf(resp))
    }

    @Test
    fun `completed event still expires (status-independent)`() {
        // EventCompletionService flips stage_2 -> completed after a 6h grace; the sweep must
        // still finalize no-confirms on such events, so it must not gate on status = stage_2.
        val event = insertEvent(hoursFromNow(-8), "completed", triggered = true)
        val resp = insertResponse(event, "going", stage2 = null)

        val count = eventResponseRepository.expireUnconfirmedForStartedEvents(OffsetDateTime.now())

        assertEquals(1, count)
        assertEquals("expired_no_confirm", stage2VoteOf(resp))
    }

    @Test
    fun `sweep is idempotent on second run`() {
        val event = insertEvent(hoursFromNow(-1), "stage_2", triggered = true)
        insertResponse(event, "going", stage2 = null)

        val first = eventResponseRepository.expireUnconfirmedForStartedEvents(OffsetDateTime.now())
        val second = eventResponseRepository.expireUnconfirmedForStartedEvents(OffsetDateTime.now())

        assertEquals(1, first)
        assertEquals(0, second)
    }

    @Test
    fun `expires at the exact event_datetime boundary (lessOrEqual is inclusive)`() {
        // Deterministic boundary test: pass now == event_datetime. The sweep uses
        // event_datetime <= now, which agrees with Bug B's !isAfter(now) (rejects at equal).
        val boundary = OffsetDateTime.now()
        val event = insertEvent(boundary, "stage_2", triggered = true)
        val resp = insertResponse(event, "going", stage2 = null)

        val count = eventResponseRepository.expireUnconfirmedForStartedEvents(boundary)

        assertEquals(1, count)
        assertEquals("expired_no_confirm", stage2VoteOf(resp))
    }

    @Test
    fun `expired rows are not promotable from the waitlist after a sweep`() {
        val event = insertEvent(hoursFromNow(-1), "stage_2", triggered = true)
        insertResponse(event, "going", stage2 = null)       // will expire
        val waitlisted = insertResponse(event, "going", stage2 = "waitlisted")

        eventResponseRepository.expireUnconfirmedForStartedEvents(OffsetDateTime.now())

        // The genuine waitlisted row survives and is still the one a decline would promote —
        // the expired row (stage_2_vote=expired_no_confirm) is never picked by findFirstWaitlisted.
        val firstWaitlisted = eventResponseRepository.findFirstWaitlisted(event)
        assertEquals(waitlisted, firstWaitlisted?.id)
        assertEquals(0, eventResponseRepository.countConfirmed(event))
    }

    // ---- helpers ----

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$id', ${telegramSeq++}, 'U')")
        return id
    }

    private fun insertEvent(eventDatetime: OffsetDateTime, status: String, triggered: Boolean): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime, participant_limit, voting_opens_days_before, status, stage_2_triggered)
            VALUES ('$id', '$clubId', '$ownerId', 'Event', 'Place', '$eventDatetime', 10, 14, '$status'::event_status, $triggered)
            """.trimIndent()
        )
        return id
    }

    private fun insertResponse(eventId: UUID, stage1: String, stage2: String?): UUID {
        val id = UUID.randomUUID()
        val userId = newUser()
        if (stage2 == null) {
            dsl.execute(
                """
                INSERT INTO event_responses (id, event_id, user_id, stage_1_vote, stage_1_timestamp)
                VALUES ('$id', '$eventId', '$userId', '$stage1'::stage_1_vote, NOW())
                """.trimIndent()
            )
        } else {
            dsl.execute(
                """
                INSERT INTO event_responses (id, event_id, user_id, stage_1_vote, stage_1_timestamp, stage_2_vote, final_status)
                VALUES ('$id', '$eventId', '$userId', '$stage1'::stage_1_vote, NOW(), '$stage2'::stage_2_vote, '$stage2'::final_status)
                """.trimIndent()
            )
        }
        return id
    }

    private fun stage2VoteOf(id: UUID): String? =
        dsl.fetchOne("SELECT stage_2_vote FROM event_responses WHERE id = ?", id)!!.get(0, String::class.java)

    private fun finalStatusOf(id: UUID): String? =
        dsl.fetchOne("SELECT final_status FROM event_responses WHERE id = ?", id)!!.get(0, String::class.java)

    private fun hoursFromNow(hours: Long): OffsetDateTime = OffsetDateTime.now().plusHours(hours)
}
