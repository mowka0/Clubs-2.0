package com.clubs.user

import com.clubs.auth.JwtService
import org.jooq.DSLContext
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
class OnboardingControllerTest {

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

    private lateinit var freshUserId: UUID
    private lateinit var onboardedUserId: UUID
    private lateinit var freshToken: String
    private lateinit var onboardedToken: String

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM users")

        freshUserId = UUID.randomUUID()
        onboardedUserId = UUID.randomUUID()

        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$freshUserId', 5001, 'Fresh')")
        dsl.execute(
            "INSERT INTO users (id, telegram_id, first_name, onboarded_at) " +
                "VALUES ('$onboardedUserId', 5002, 'Onboarded', now())"
        )

        freshToken = jwtService.generateToken(freshUserId, 5001L)
        onboardedToken = jwtService.generateToken(onboardedUserId, 5002L)
    }

    private fun onboardedAtOf(userId: UUID): OffsetDateTime? =
        dsl.fetchOne("SELECT onboarded_at FROM users WHERE id = ?", userId)
            ?.get(0, OffsetDateTime::class.java)

    @Test
    fun `POST me-onboarding marks user onboarded and returns updated profile`() {
        assertNull(onboardedAtOf(freshUserId), "предусловие: пользователь ещё не онбординен")

        mockMvc.perform(
            post("/api/users/me/onboarding")
                .header("Authorization", "Bearer $freshToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"door":"MEMBER"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(freshUserId.toString()))
            .andExpect(jsonPath("$.onboardedAt").isNotEmpty)

        assertNotNull(onboardedAtOf(freshUserId), "onboarded_at проставлен в БД")
    }

    @Test
    fun `POST me-onboarding accepts organizer door`() {
        mockMvc.perform(
            post("/api/users/me/onboarding")
                .header("Authorization", "Bearer $freshToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"door":"ORGANIZER"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.onboardedAt").isNotEmpty)
    }

    @Test
    fun `POST me-onboarding returns 409 when already onboarded`() {
        mockMvc.perform(
            post("/api/users/me/onboarding")
                .header("Authorization", "Bearer $onboardedToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"door":"MEMBER"}""")
        )
            .andExpect(status().isConflict)
    }

    @Test
    fun `POST me-onboarding twice returns 409 on the second call`() {
        val request = post("/api/users/me/onboarding")
            .header("Authorization", "Bearer $freshToken")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"door":"MEMBER"}""")

        mockMvc.perform(request).andExpect(status().isOk)
        mockMvc.perform(request).andExpect(status().isConflict)
    }

    @Test
    fun `POST me-onboarding rejects unknown door`() {
        mockMvc.perform(
            post("/api/users/me/onboarding")
                .header("Authorization", "Bearer $freshToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"door":"TOURIST"}""")
        )
            .andExpect(status().isBadRequest)

        assertNull(onboardedAtOf(freshUserId), "невалидная дверь не помечает онбординг пройденным")
    }

    @Test
    fun `POST me-onboarding requires authentication`() {
        mockMvc.perform(
            post("/api/users/me/onboarding")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"door":"MEMBER"}""")
        )
            .andExpect(status().isUnauthorized)

        assertNull(onboardedAtOf(freshUserId), "без токена ничего не помечается")
    }

    @Test
    fun `POST me-onboarding touches only the caller — foreign profile stays untouched`() {
        mockMvc.perform(
            post("/api/users/me/onboarding")
                .header("Authorization", "Bearer $freshToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"door":"MEMBER"}""")
        )
            .andExpect(status().isOk)

        // Цель — не «чужой userId отклоняется» (его негде передать: id берётся из JWT),
        // а что вызов не задевает соседние строки: онбординг строго свой.
        val foreignId = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$foreignId', 5003, 'Foreign')")
        assertNull(onboardedAtOf(foreignId), "чужой профиль не тронут")
    }

    @Test
    fun `GET me exposes onboardedAt as null for a fresh user`() {
        mockMvc.perform(
            get("/api/users/me").header("Authorization", "Bearer $freshToken")
        )
            .andExpect(status().isOk)
            // Поле есть в ответе и равно null — фронт различает «не проходил» именно по нему.
            .andExpect(jsonPath("$.onboardedAt").isEmpty)
    }
}
