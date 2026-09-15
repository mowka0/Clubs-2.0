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
 * Открытая встреча одноэтапна (модель v3, event-formats.md § 16) на живом Postgres: выборка тика,
 * закрывающего наборы.
 *
 * AC-OPEN4: открытая встреча не попадает в неё НИКОГДА. Это единственная точка, из-за которой она
 * вообще становилась двухэтапной; без предиката `participant_limit IS NOT NULL` вся модель v3
 * молча не работает.
 *
 * Бэкфилла данных нет: миграция данных отменена решением PO 2026-09-15 (§ 16.7) — открытых встреч
 * в проде практически нет, V96 несёт только COMMENT ON.
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
class OpenEventSingleStageRepositoryTest {

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

        // Дефолтный интервал набора: 18 часов до старта — тот же, что в application.yml.
        private const val DEFAULT_LEAD_MINUTES = 1080L
    }

    @Autowired lateinit var eventRepository: EventRepository
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
    private lateinit var clubId: UUID
    private var telegramSeq = 7000L

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM membership_history")
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

    // ---- AC-OPEN4: выборка тика ----

    @Test
    fun `AC-OPEN4 открытая встреча в выборку тика не попадает, встреча с местами попадает`() {
        // Обе встречи начинаются через час — по времени «пора» и той, и другой.
        val open = insertEvent(hoursFromNow(1), participantLimit = null)
        val withSeats = insertEvent(hoursFromNow(1), participantLimit = 10)

        val ready = eventRepository.findEventsToTriggerStage2(OffsetDateTime.now(), DEFAULT_LEAD_MINUTES).map { it.id }

        assertEquals(listOf(withSeats), ready)
        assertEquals("upcoming", statusOf(open))
        assertEquals(false, triggeredFlagOf(open))
    }

    @Test
    fun `встреча с местами дальше своего интервала набора ещё не готова`() {
        insertEvent(hoursFromNow(30), participantLimit = 10)

        assertEquals(
            emptyList(),
            eventRepository.findEventsToTriggerStage2(OffsetDateTime.now(), DEFAULT_LEAD_MINUTES).map { it.id }
        )
    }

    @Test
    fun `уже закрытый набор второй раз не возвращается`() {
        insertEvent(hoursFromNow(1), participantLimit = 10, triggered = true, status = "stage_2")

        assertEquals(
            emptyList(),
            eventRepository.findEventsToTriggerStage2(OffsetDateTime.now(), DEFAULT_LEAD_MINUTES).map { it.id }
        )
    }

    // ---- helpers ----

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$id', ${telegramSeq++}, 'U')")
        return id
    }

    private fun insertEvent(
        eventDatetime: OffsetDateTime,
        participantLimit: Int?,
        triggered: Boolean = false,
        status: String = "upcoming"
    ): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime, participant_limit, voting_opens_days_before, status, stage_2_triggered)
            VALUES ('$id', '$clubId', '$ownerId', 'Event', 'Place', '$eventDatetime', ${participantLimit ?: "NULL"}, 14, '$status'::event_status, $triggered)
            """.trimIndent()
        )
        return id
    }

    private fun statusOf(id: UUID): String =
        dsl.fetchOne("SELECT status FROM events WHERE id = ?", id)!!.get(0, String::class.java)

    private fun triggeredFlagOf(id: UUID): Boolean =
        dsl.fetchOne("SELECT stage_2_triggered FROM events WHERE id = ?", id)!!.get(0, Boolean::class.java)

    private fun hoursFromNow(hours: Long): OffsetDateTime = OffsetDateTime.now().plusHours(hours)
}
