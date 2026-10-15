package com.clubs.analytics

import com.clubs.auth.JwtService
import com.clubs.common.security.RateLimitFilter
import org.jooq.DSLContext
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals

/**
 * POST /api/track на реальном Postgres (product-analytics.md AC-2, AC-3, AC-5): строки с автором
 * из JWT, источником app и props в JSONB; любое нарушение — 400 без единой строки.
 */
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
class TrackControllerTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("clubs_test").withUsername("test").withPassword("test")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var dsl: DSLContext
    @Autowired lateinit var rateLimitFilter: RateLimitFilter

    private val userId: UUID = UUID.randomUUID()
    private lateinit var token: String

    @BeforeEach
    fun setUp() {
        rateLimitFilter.resetBuckets()
        dsl.execute("DELETE FROM product_event")
        dsl.execute("DELETE FROM users")
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$userId', 6001, 'Tracker')")
        token = jwtService.generateToken(userId, 6001L)
    }

    private fun track(body: String, withToken: Boolean = true) =
        post("/api/track")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .apply { if (withToken) header("Authorization", "Bearer $token") }

    private data class Row(val kind: String, val source: String, val userId: UUID?, val clubId: UUID?, val props: String?)

    private fun rows(): List<Row> =
        dsl.fetch("SELECT kind, source, user_id, club_id, props::text FROM product_event ORDER BY kind")
            .map { Row(it.get(0, String::class.java), it.get(1, String::class.java), it.get(2, UUID::class.java), it.get(3, UUID::class.java), it.get(4, String::class.java)) }

    @Test
    fun `пачка из двух событий - 204 и две строки с автором из токена и props в JSONB`() {
        val clubId = UUID.randomUUID()
        val body = """
            {"events":[
              {"kind":"screen_opened","clubId":"$clubId","props":{"screen":"club"}},
              {"kind":"app_opened","props":{"entry":"none","platform":"ios"}},
              {"kind":"pay_sheet_opened","props":{"reason":"none","mode":null}}
            ]}
        """.trimIndent()

        mockMvc.perform(track(body)).andExpect(status().isNoContent)

        assertEquals(
            listOf(
                Row("app_opened", "app", userId, null, """{"entry": "none", "platform": "ios"}"""),
                // null-значение выброшено ещё до записи.
                Row("pay_sheet_opened", "app", userId, null, """{"reason": "none"}"""),
                Row("screen_opened", "app", userId, clubId, """{"screen": "club"}"""),
            ),
            rows(),
        )
    }

    @Test
    fun `нарушения - 400 и ни одной строки`() {
        val tooMany = (1..21).joinToString(",") { """{"kind":"onboarding_step","props":{"step":$it}}""" }
        val bodies = listOf(
            """{"events":[]}""",
            """{"events":[$tooMany]}""",
            """{"events":[{"kind":"payment_succeeded"}]}""",
            """{"events":[{"kind":"screen_opened","props":{"screen":"home"}},{"kind":"nope"}]}""",
            """{"events":[{"kind":"screen_opened","props":{"entry":"none"}}]}""",
            """{"events":[{"kind":"screen_opened","clubId":"abc","props":{"screen":"home"}}]}""",
            """{"events":[{"kind":"screen_opened","props":{"screen":{"a":1}}}]}""",
            """{"events":[{"kind":"screen_opened","props":{"screen":"a\u0000b"}}]}""",
            """{"events":[{"kind":"onboarding_step","props":{"step":${"1".repeat(30)}}}]}""",
        )
        bodies.forEach { body ->
            mockMvc.perform(track(body)).andExpect(status().isBadRequest)
        }
        assertEquals(emptyList(), rows())
    }

    @Test
    fun `без токена - 401`() {
        mockMvc.perform(track("""{"events":[{"kind":"onboarding_completed"}]}""", withToken = false))
            .andExpect(status().isUnauthorized)
        assertEquals(emptyList(), rows())
    }
}
