package com.clubs.activity

import com.clubs.auth.JwtService
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
    fun `GET activities for empty club returns 200 with empty content`() {
        mockMvc.perform(
            get("/api/clubs/$clubId/activities")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(0))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
    }

    @Test
    fun `GET activities returns merged event and skladchina sorted by createdAt desc`() {
        val eventId = UUID.randomUUID()
        val skladchinaId = UUID.randomUUID()
        val olderTs = OffsetDateTime.now().minusHours(5)
        val newerTs = OffsetDateTime.now().minusHours(1)
        val futureDeadline = OffsetDateTime.now().plusDays(7)

        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, description, location_text,
                                event_datetime, participant_limit, status, created_at, updated_at)
            VALUES ('$eventId', '$clubId', '$organizerId', 'Older Event', 'Some description text',
                    'Park', '$futureDeadline', 20, 'upcoming', '$olderTs', '$olderTs')
            """.trimIndent()
        )
        dsl.execute(
            """
            INSERT INTO skladchinas (id, club_id, creator_id, title, payment_mode, payment_link,
                                     deadline, status, created_at, updated_at, affects_reputation)
            VALUES ('$skladchinaId', '$clubId', '$organizerId', 'Newer Skladchina', 'voluntary',
                    'https://pay.me', '$futureDeadline', 'active', '$newerTs', '$newerTs', false)
            """.trimIndent()
        )

        mockMvc.perform(
            get("/api/clubs/$clubId/activities")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].type").value("skladchina"))
            .andExpect(jsonPath("$.content[0].title").value("Newer Skladchina"))
            .andExpect(jsonPath("$.content[0].isCompleted").value(false))
            .andExpect(jsonPath("$.content[1].type").value("event"))
            .andExpect(jsonPath("$.content[1].title").value("Older Event"))
            .andExpect(jsonPath("$.content[1].descriptionPreview").value("Some description text"))
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
    fun `GET activities with size over 50 returns 400`() {
        mockMvc.perform(
            get("/api/clubs/$clubId/activities?size=51")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
    }

    @Test
    fun `GET activities filters by type=event`() {
        val eventId = UUID.randomUUID()
        val skladchinaId = UUID.randomUUID()
        val ts = OffsetDateTime.now().minusHours(1)
        val futureDeadline = OffsetDateTime.now().plusDays(7)

        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text,
                                event_datetime, participant_limit, status, created_at, updated_at)
            VALUES ('$eventId', '$clubId', '$organizerId', 'E', 'Park',
                    '$futureDeadline', 20, 'upcoming', '$ts', '$ts')
            """.trimIndent()
        )
        dsl.execute(
            """
            INSERT INTO skladchinas (id, club_id, creator_id, title, payment_mode, payment_link,
                                     deadline, status, created_at, updated_at, affects_reputation)
            VALUES ('$skladchinaId', '$clubId', '$organizerId', 'S', 'voluntary',
                    'https://pay.me', '$futureDeadline', 'active', '$ts', '$ts', false)
            """.trimIndent()
        )

        mockMvc.perform(
            get("/api/clubs/$clubId/activities?type=event")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].type").value("event"))
    }

    @Test
    fun `GET activities with includeCompleted false excludes completed events`() {
        val activeEventId = UUID.randomUUID()
        val completedEventId = UUID.randomUUID()
        val ts = OffsetDateTime.now().minusHours(1)
        val futureDeadline = OffsetDateTime.now().plusDays(7)

        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text,
                                event_datetime, participant_limit, status, created_at, updated_at)
            VALUES ('$activeEventId', '$clubId', '$organizerId', 'Active', 'Park',
                    '$futureDeadline', 20, 'upcoming', '$ts', '$ts')
            """.trimIndent()
        )
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text,
                                event_datetime, participant_limit, status, created_at, updated_at)
            VALUES ('$completedEventId', '$clubId', '$organizerId', 'Done', 'Park',
                    '${OffsetDateTime.now().minusDays(1)}', 20, 'completed', '$ts', '$ts')
            """.trimIndent()
        )

        mockMvc.perform(
            get("/api/clubs/$clubId/activities?includeCompleted=false")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].title").value("Active"))
            .andExpect(jsonPath("$.content[0].isCompleted").value(false))
    }
}
