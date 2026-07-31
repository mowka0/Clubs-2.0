package com.clubs.user

import com.clubs.auth.JwtService
import org.jooq.DSLContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
    private lateinit var freshToken: String

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM users")
        freshUserId = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$freshUserId', 5001, 'Fresh')")
        freshToken = jwtService.generateToken(freshUserId, 5001L)
    }

    /** Ключи пройденных туров прямо из БД, минуя сервис. */
    private fun toursOf(userId: UUID): Set<String> =
        dsl.fetch("SELECT tour_key FROM user_onboarding_tours WHERE user_id = ?", userId)
            .map { it.get(0, String::class.java) }
            .toSet()

    private fun completeTour(tour: String, token: String = freshToken) =
        post("/api/users/me/onboarding/$tour").header("Authorization", "Bearer $token")

    @Test
    fun `POST marks the tour and returns it in the profile`() {
        assertTrue(toursOf(freshUserId).isEmpty(), "предусловие: новичок не прошёл ничего")

        mockMvc.perform(completeTour("INTRO"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(freshUserId.toString()))
            .andExpect(jsonPath("$.onboardingTours").value("INTRO"))

        assertEquals(setOf("INTRO"), toursOf(freshUserId), "строка тура появилась в БД")
    }

    @Test
    fun `tours are independent — closing one leaves the others open`() {
        mockMvc.perform(completeTour("CLUB")).andExpect(status().isOk)

        // Суть новой модели: пройденный тур клуба ничего не говорит про остальные экраны.
        assertEquals(setOf("CLUB"), toursOf(freshUserId))

        mockMvc.perform(completeTour("PROFILE")).andExpect(status().isOk)
        assertEquals(setOf("CLUB", "PROFILE"), toursOf(freshUserId))
    }

    @Test
    fun `repeat call is idempotent — 200 and still a single row`() {
        // На едином флаге повтор давал 409; у туров отметка идемпотентна: «уже пройден»
        // и «отметили сейчас» означают для клиента одно и то же — тур закрыт.
        mockMvc.perform(completeTour("INTRO")).andExpect(status().isOk)
        mockMvc.perform(completeTour("INTRO")).andExpect(status().isOk)

        assertEquals(setOf("INTRO"), toursOf(freshUserId), "второй вызов не создал дубль")
    }

    @Test
    fun `tour key is case-insensitive`() {
        mockMvc.perform(completeTour("my_clubs")).andExpect(status().isOk)
        assertEquals(setOf("MY_CLUBS"), toursOf(freshUserId), "в БД ключ нормализован")
    }

    @Test
    fun `unknown tour returns 400 and marks nothing`() {
        // Ключ разбирается в сервисе именно ради этого: enum в @PathVariable дал бы 500.
        mockMvc.perform(completeTour("TOURIST")).andExpect(status().isBadRequest)
        assertTrue(toursOf(freshUserId).isEmpty(), "неизвестный тур ничего не помечает")
    }

    @Test
    fun `POST requires authentication`() {
        mockMvc.perform(post("/api/users/me/onboarding/INTRO")).andExpect(status().isUnauthorized)
        assertTrue(toursOf(freshUserId).isEmpty(), "без токена ничего не помечается")
    }

    @Test
    fun `POST touches only the caller — foreign profile stays untouched`() {
        mockMvc.perform(completeTour("INTRO")).andExpect(status().isOk)

        // Цель — не «чужой userId отклоняется» (его негде передать: id берётся из JWT),
        // а что вызов не задевает соседние строки: онбординг строго свой.
        val foreignId = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$foreignId', 5003, 'Foreign')")
        assertTrue(toursOf(foreignId).isEmpty(), "чужой профиль не тронут")
    }

    @Test
    fun `GET me exposes an empty tour set for a fresh user`() {
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer $freshToken"))
            .andExpect(status().isOk)
            // Пустой массив, а не отсутствующее поле: фронт различает новичка именно по нему.
            .andExpect(jsonPath("$.onboardingTours").isArray)
            .andExpect(jsonPath("$.onboardingTours").isEmpty)
    }
}
