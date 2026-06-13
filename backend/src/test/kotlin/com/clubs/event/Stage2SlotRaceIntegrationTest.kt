package com.clubs.event

import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S2-01/F5-07 + F5-11 against a real Postgres: the per-event advisory lock
 * ([EventResponseRepository.lockEventSlots]) must serialize Stage 2 slot mutations.
 * Without it, concurrent confirms on the last slot both pass the capacity check
 * (overbooking), and concurrent declines both promote the same waitlisted user
 * (a freed slot is lost forever). Threads call the @Transactional service through
 * the Spring proxy, so every call runs in its own transaction — the real race.
 *
 * Also covers GAP-009: the Stage-2-started DM targets only going/maybe voters.
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
class Stage2SlotRaceIntegrationTest {

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

    @Autowired lateinit var stage2Service: Stage2Service
    @Autowired lateinit var eventResponseRepository: EventResponseRepository
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
    private lateinit var clubId: UUID
    private var telegramSeq = 7000L
    private val executor = Executors.newCachedThreadPool()

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
            VALUES ('$clubId', '$ownerId', 'Club', 'desc', 'sport', 'open', 'Moscow', 50, 0, true)
            """.trimIndent()
        )
        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$ownerId', '$clubId', 'active', 'organizer')")
    }

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun `concurrent confirms on the last slot never overbook`() {
        val eventId = insertStage2Event(participantLimit = 1)
        val racers = List(4) { newMember() }
        racers.forEachIndexed { i, userId -> insertGoingResponse(eventId, userId, minutesAgo = (10 - i).toLong()) }

        runConcurrently(racers.map { userId -> { stage2Service.confirmParticipation(eventId, userId) } })

        assertEquals(1, eventResponseRepository.countConfirmed(eventId))
        assertEquals(3, countByStage2(eventId, "waitlisted"))
    }

    @Test
    fun `concurrent declines promote distinct waitlisted users`() {
        val eventId = insertStage2Event(participantLimit = 2)
        val confirmedA = newMember()
        val confirmedB = newMember()
        val waitlistedC = newMember()
        val waitlistedD = newMember()
        insertGoingResponse(eventId, confirmedA, minutesAgo = 40, stage2 = "confirmed")
        insertGoingResponse(eventId, confirmedB, minutesAgo = 30, stage2 = "confirmed")
        insertGoingResponse(eventId, waitlistedC, minutesAgo = 20, stage2 = "waitlisted")
        insertGoingResponse(eventId, waitlistedD, minutesAgo = 10, stage2 = "waitlisted")

        runConcurrently(
            listOf(
                { stage2Service.declineParticipation(eventId, confirmedA) },
                { stage2Service.declineParticipation(eventId, confirmedB) }
            )
        )

        // Two freed slots → BOTH waitlisted users get promoted. The unserialized race
        // promotes C twice and strands D, losing a slot.
        assertEquals(2, eventResponseRepository.countConfirmed(eventId))
        assertEquals("confirmed", stage2VoteOf(eventId, waitlistedC))
        assertEquals("confirmed", stage2VoteOf(eventId, waitlistedD))
        assertEquals(0, countByStage2(eventId, "waitlisted"))
    }

    @Test
    fun `stage 2 DM targets only going and maybe voters`() {
        val eventId = insertStage2Event(participantLimit = 10)
        val going = newMember()
        val maybe = newMember()
        val notGoing = newMember()
        insertResponse(eventId, going, "going")
        insertResponse(eventId, maybe, "maybe")
        insertResponse(eventId, notGoing, "not_going")

        val targets = eventResponseRepository.findStage2TargetTelegramIds(eventId)

        assertEquals(setOf(telegramIdOf(going), telegramIdOf(maybe)), targets.toSet())
    }

    // ---- helpers ----

    /** Runs all actions as simultaneously as the scheduler allows and rethrows any failure. */
    private fun runConcurrently(actions: List<() -> Unit>) {
        val startGate = CountDownLatch(1)
        val futures = actions.map { action ->
            executor.submit {
                startGate.await()
                action()
            }
        }
        startGate.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Concurrent calls timed out")
        futures.forEach { it.get() } // surfaces exceptions from the racing threads
    }

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$id', ${telegramSeq++}, 'U')")
        return id
    }

    private fun newMember(): UUID {
        val id = newUser()
        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$id', '$clubId', 'active', 'member')")
        return id
    }

    private fun insertStage2Event(participantLimit: Int): UUID {
        val id = UUID.randomUUID()
        val eventDatetime = OffsetDateTime.now().plusHours(5)
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime, participant_limit, voting_opens_days_before, status, stage_2_triggered)
            VALUES ('$id', '$clubId', '$ownerId', 'Event', 'Place', '$eventDatetime', $participantLimit, 14, 'stage_2'::event_status, true)
            """.trimIndent()
        )
        return id
    }

    /** stage_1_timestamp is staggered via [minutesAgo] so the waitlist FIFO order is deterministic. */
    private fun insertGoingResponse(eventId: UUID, userId: UUID, minutesAgo: Long, stage2: String? = null) {
        if (stage2 == null) {
            dsl.execute(
                """
                INSERT INTO event_responses (id, event_id, user_id, stage_1_vote, stage_1_timestamp)
                VALUES ('${UUID.randomUUID()}', '$eventId', '$userId', 'going'::stage_1_vote, NOW() - INTERVAL '$minutesAgo minutes')
                """.trimIndent()
            )
        } else {
            dsl.execute(
                """
                INSERT INTO event_responses (id, event_id, user_id, stage_1_vote, stage_1_timestamp, stage_2_vote, final_status)
                VALUES ('${UUID.randomUUID()}', '$eventId', '$userId', 'going'::stage_1_vote, NOW() - INTERVAL '$minutesAgo minutes', '$stage2'::stage_2_vote, '$stage2'::final_status)
                """.trimIndent()
            )
        }
    }

    private fun insertResponse(eventId: UUID, userId: UUID, stage1: String) {
        dsl.execute(
            """
            INSERT INTO event_responses (id, event_id, user_id, stage_1_vote, stage_1_timestamp)
            VALUES ('${UUID.randomUUID()}', '$eventId', '$userId', '$stage1'::stage_1_vote, NOW())
            """.trimIndent()
        )
    }

    private fun stage2VoteOf(eventId: UUID, userId: UUID): String? =
        dsl.fetchOne("SELECT stage_2_vote FROM event_responses WHERE event_id = ? AND user_id = ?", eventId, userId)
            ?.get(0, String::class.java)

    private fun countByStage2(eventId: UUID, stage2: String): Int =
        dsl.fetchOne("SELECT count(*) FROM event_responses WHERE event_id = ? AND stage_2_vote = ?::stage_2_vote", eventId, stage2)!!
            .get(0, Int::class.java)!!

    private fun telegramIdOf(userId: UUID): Long =
        dsl.fetchOne("SELECT telegram_id FROM users WHERE id = ?", userId)!!.get(0, Long::class.java)!!
}
