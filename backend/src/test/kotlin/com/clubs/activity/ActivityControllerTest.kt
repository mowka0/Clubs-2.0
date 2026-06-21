package com.clubs.activity

import com.clubs.auth.JwtService
import org.hamcrest.Matchers.nullValue
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=0",
        "telegram.bot-token=test-bot-token"
    ]
)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class ActivityControllerTest {

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

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var dsl: DSLContext

    private lateinit var organizerId: UUID
    private lateinit var memberId: UUID
    private lateinit var outsiderId: UUID
    private lateinit var clubId: UUID
    private lateinit var organizerToken: String
    private lateinit var memberToken: String
    private lateinit var outsiderToken: String

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM skladchina_participants")
        dsl.execute("DELETE FROM skladchinas")
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM applications")
        dsl.execute("DELETE FROM transactions")
        dsl.execute("DELETE FROM membership_history")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM user_club_reputation")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")

        organizerId = UUID.randomUUID()
        memberId = UUID.randomUUID()
        outsiderId = UUID.randomUUID()
        clubId = UUID.randomUUID()

        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$organizerId', 5001, 'Organizer')")
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$memberId', 5002, 'Member')")
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$outsiderId', 5003, 'Outsider')")

        organizerToken = jwtService.generateToken(organizerId, 5001L)
        memberToken = jwtService.generateToken(memberId, 5002L)
        outsiderToken = jwtService.generateToken(outsiderId, 5003L)

        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price)
            VALUES ('$clubId', '$organizerId', 'Test Club', 'desc', 'sport', 'closed', 'Moscow', 20, 0)
            """.trimIndent()
        )
        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$organizerId', '$clubId', 'active', 'organizer')")
        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$memberId', '$clubId', 'active', 'member')")
    }

    @Test
    fun `GET activities as non-member returns 403`() {
        mockMvc.perform(
            get("/api/clubs/$clubId/activities")
                .header("Authorization", "Bearer $outsiderToken")
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("FORBIDDEN"))
    }

    @Test
    fun `GET activities without token returns 401`() {
        mockMvc.perform(get("/api/clubs/$clubId/activities"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET activities for empty club returns 200 with empty upcoming and past`() {
        mockMvc.perform(
            get("/api/clubs/$clubId/activities")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.upcoming").isArray)
            .andExpect(jsonPath("$.upcoming.length()").value(0))
            .andExpect(jsonPath("$.past").isArray)
            .andExpect(jsonPath("$.past.length()").value(0))
    }

    @Test
    fun `GET activities partitions upcoming by relevant date and interleaves types`() {
        // upcoming: skladchina deadline +1d sorts before event datetime +3d
        val eventId = UUID.randomUUID()
        val skladchinaId = UUID.randomUUID()
        val createdAt = OffsetDateTime.now().minusHours(1)
        val eventDatetime = OffsetDateTime.now().plusDays(3)
        val skladDeadline = OffsetDateTime.now().plusDays(1)

        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, description, location_text,
                                event_datetime, participant_limit, status, photo_url, created_at, updated_at)
            VALUES ('$eventId', '$clubId', '$organizerId', 'Event +3d', 'Some description text',
                    'Park', '$eventDatetime', 20, 'upcoming', 'https://cdn.example.com/event.jpg',
                    '$createdAt', '$createdAt')
            """.trimIndent()
        )
        dsl.execute(
            """
            INSERT INTO skladchinas (id, club_id, creator_id, title, payment_mode, payment_link,
                                     deadline, status, photo_url, created_at, updated_at, affects_reputation)
            VALUES ('$skladchinaId', '$clubId', '$organizerId', 'Sklad +1d', 'voluntary',
                    'https://pay.me', '$skladDeadline', 'active', 'https://cdn.example.com/sklad.jpg',
                    '$createdAt', '$createdAt', false)
            """.trimIndent()
        )
        // The member votes stage-1 so the event is NOT action-required: an unvoted
        // upcoming event would be pinned to the top of the feed (two-stage UI-полиш),
        // and this test exercises pure relevant-date interleaving, not the pinning.
        dsl.execute(
            "INSERT INTO event_responses (id, event_id, user_id, stage_1_vote) " +
                "VALUES ('${UUID.randomUUID()}', '$eventId', '$memberId', 'going')"
        )

        mockMvc.perform(
            get("/api/clubs/$clubId/activities")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.upcoming.length()").value(2))
            .andExpect(jsonPath("$.past.length()").value(0))
            .andExpect(jsonPath("$.upcoming[0].type").value("skladchina"))
            .andExpect(jsonPath("$.upcoming[0].title").value("Sklad +1d"))
            .andExpect(jsonPath("$.upcoming[0].isCompleted").value(false))
            .andExpect(jsonPath("$.upcoming[0].photoUrl").value("https://cdn.example.com/sklad.jpg"))
            .andExpect(jsonPath("$.upcoming[1].type").value("event"))
            .andExpect(jsonPath("$.upcoming[1].title").value("Event +3d"))
            .andExpect(jsonPath("$.upcoming[1].descriptionPreview").value("Some description text"))
            .andExpect(jsonPath("$.upcoming[1].photoUrl").value("https://cdn.example.com/event.jpg"))
    }

    @Test
    fun `GET activities returns null photoUrl when event has none`() {
        val eventId = UUID.randomUUID()
        val createdAt = OffsetDateTime.now().minusHours(1)
        val eventDatetime = OffsetDateTime.now().plusDays(3)

        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text,
                                event_datetime, participant_limit, status, created_at, updated_at)
            VALUES ('$eventId', '$clubId', '$organizerId', 'No photo', 'Park',
                    '$eventDatetime', 20, 'upcoming', '$createdAt', '$createdAt')
            """.trimIndent()
        )

        mockMvc.perform(
            get("/api/clubs/$clubId/activities")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.upcoming.length()").value(1))
            .andExpect(jsonPath("$.upcoming[0].type").value("event"))
            .andExpect(jsonPath("$.upcoming[0].photoUrl").value(nullValue()))
    }

    @Test
    fun `GET activities puts completed items into past sorted most-recent-first`() {
        // past: completed event datetime -2d sorts before closed skladchina deadline -5d
        val eventId = UUID.randomUUID()
        val skladchinaId = UUID.randomUUID()
        val createdAt = OffsetDateTime.now().minusDays(10)
        val eventDatetime = OffsetDateTime.now().minusDays(2)
        val skladDeadline = OffsetDateTime.now().minusDays(5)

        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text,
                                event_datetime, participant_limit, status, created_at, updated_at)
            VALUES ('$eventId', '$clubId', '$organizerId', 'Completed Event -2d', 'Park',
                    '$eventDatetime', 20, 'completed', '$createdAt', '$createdAt')
            """.trimIndent()
        )
        dsl.execute(
            """
            INSERT INTO skladchinas (id, club_id, creator_id, title, payment_mode, payment_link,
                                     deadline, status, created_at, updated_at, affects_reputation)
            VALUES ('$skladchinaId', '$clubId', '$organizerId', 'Closed Sklad -5d', 'voluntary',
                    'https://pay.me', '$skladDeadline', 'closed_failed', '$createdAt', '$createdAt', false)
            """.trimIndent()
        )

        mockMvc.perform(
            get("/api/clubs/$clubId/activities")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.upcoming.length()").value(0))
            .andExpect(jsonPath("$.past.length()").value(2))
            .andExpect(jsonPath("$.past[0].type").value("event"))
            .andExpect(jsonPath("$.past[0].title").value("Completed Event -2d"))
            .andExpect(jsonPath("$.past[0].isCompleted").value(true))
            .andExpect(jsonPath("$.past[1].type").value("skladchina"))
            .andExpect(jsonPath("$.past[1].title").value("Closed Sklad -5d"))
            .andExpect(jsonPath("$.past[1].isCompleted").value(true))
    }

    @Test
    fun `GET activities with invalid type returns 400`() {
        mockMvc.perform(
            get("/api/clubs/$clubId/activities?type=foo")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
    }

    @Test
    fun `GET activities filters by type=event in both groups`() {
        val eventId = UUID.randomUUID()
        val skladchinaId = UUID.randomUUID()
        val createdAt = OffsetDateTime.now().minusHours(1)
        val futureDate = OffsetDateTime.now().plusDays(7)

        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text,
                                event_datetime, participant_limit, status, created_at, updated_at)
            VALUES ('$eventId', '$clubId', '$organizerId', 'E', 'Park',
                    '$futureDate', 20, 'upcoming', '$createdAt', '$createdAt')
            """.trimIndent()
        )
        dsl.execute(
            """
            INSERT INTO skladchinas (id, club_id, creator_id, title, payment_mode, payment_link,
                                     deadline, status, created_at, updated_at, affects_reputation)
            VALUES ('$skladchinaId', '$clubId', '$organizerId', 'S', 'voluntary',
                    'https://pay.me', '$futureDate', 'active', '$createdAt', '$createdAt', false)
            """.trimIndent()
        )

        mockMvc.perform(
            get("/api/clubs/$clubId/activities?type=event")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.upcoming.length()").value(1))
            .andExpect(jsonPath("$.past.length()").value(0))
            .andExpect(jsonPath("$.upcoming[0].type").value("event"))
    }
}
