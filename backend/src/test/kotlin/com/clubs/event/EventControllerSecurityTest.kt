package com.clubs.event

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
class EventControllerSecurityTest {

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

    private lateinit var clubId: UUID
    private lateinit var nonMemberToken: String
    private lateinit var memberToken: String
    private lateinit var organizerToken: String

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM applications")
        dsl.execute("DELETE FROM transactions")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM user_club_reputation")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")

        val nonMemberId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val organizerId = UUID.randomUUID()
        clubId = UUID.randomUUID()

        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$nonMemberId', 1001, 'NonMember')")
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$memberId', 1002, 'Member')")
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$organizerId', 1003, 'Organizer')")

        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price)
            VALUES ('$clubId', '$organizerId', 'Test Club', 'desc', 'sport', 'closed', 'Moscow', 20, 0)
            """.trimIndent()
        )

        dsl.execute(
            "INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$organizerId', '$clubId', 'active', 'organizer')"
        )
        dsl.execute(
            "INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$memberId', '$clubId', 'active', 'member')"
        )

        nonMemberToken = jwtService.generateToken(nonMemberId, 1001L)
        memberToken = jwtService.generateToken(memberId, 1002L)
        organizerToken = jwtService.generateToken(organizerId, 1003L)
    }

    @Test
    fun `GET club events as non-member should return 403`() {
        mockMvc.perform(
            get("/api/clubs/$clubId/events")
                .header("Authorization", "Bearer $nonMemberToken")
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("FORBIDDEN"))
    }

    @Test
    fun `GET club events as active member should return 200`() {
        mockMvc.perform(
            get("/api/clubs/$clubId/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
    }

    @Test
    fun `GET club events as organizer should return 200`() {
        mockMvc.perform(
            get("/api/clubs/$clubId/events")
                .header("Authorization", "Bearer $organizerToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
    }

    @Test
    fun `GET club events without token should return 401`() {
        mockMvc.perform(get("/api/clubs/$clubId/events"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET events for non-existent club should return 403 not 404`() {
        val unknownClubId = UUID.randomUUID()
        mockMvc.perform(
            get("/api/clubs/$unknownClubId/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isForbidden)
    }
}
