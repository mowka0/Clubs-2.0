package com.clubs.skladchina

import com.clubs.auth.JwtService
import com.fasterxml.jackson.databind.ObjectMapper
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
class SkladchinaControllerTest {

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
    @Autowired lateinit var objectMapper: ObjectMapper

    private lateinit var organizerId: UUID
    private lateinit var memberAId: UUID
    private lateinit var memberBId: UUID
    private lateinit var outsiderId: UUID
    private lateinit var clubId: UUID
    private lateinit var organizerToken: String
    private lateinit var memberAToken: String
    private lateinit var memberBToken: String
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
        memberAId = UUID.randomUUID()
        memberBId = UUID.randomUUID()
        outsiderId = UUID.randomUUID()
        clubId = UUID.randomUUID()

        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$organizerId', 3001, 'Organizer')")
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$memberAId', 3002, 'MemberA')")
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$memberBId', 3003, 'MemberB')")
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$outsiderId', 3004, 'Outsider')")

        organizerToken = jwtService.generateToken(organizerId, 3001L)
        memberAToken = jwtService.generateToken(memberAId, 3002L)
        memberBToken = jwtService.generateToken(memberBId, 3003L)
        outsiderToken = jwtService.generateToken(outsiderId, 3004L)

        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price)
            VALUES ('$clubId', '$organizerId', 'Test Club', 'desc', 'sport', 'open', 'Moscow', 20, 0)
            """.trimIndent()
        )
        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$organizerId', '$clubId', 'active', 'organizer')")
        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$memberAId', '$clubId', 'active', 'member')")
        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$memberBId', '$clubId', 'active', 'member')")
    }

    @Test
    fun `POST create skladchina as organizer returns 201 and DTO with split amounts`() {
        val body = """
            {
              "title": "Booking the court",
              "description": "Saturday morning",
              "paymentMode": "fixed_equal",
              "totalGoalKopecks": 100000,
              "paymentLink": "https://tinkoff.ru/pay/xyz",
              "deadline": "${OffsetDateTime.now().plusDays(2)}",
              "affectsReputation": false,
              "participants": [
                {"userId": "$memberAId"},
                {"userId": "$memberBId"}
              ]
            }
        """.trimIndent()
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.paymentMode").value("fixed_equal"))
            .andExpect(jsonPath("$.totalGoalKopecks").value(100000))
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.isOrganizerView").value(true))
            .andExpect(jsonPath("$.participants.length()").value(2))
            .andExpect(jsonPath("$.participantCount").value(2))
            .andExpect(jsonPath("$.paidCount").value(0))
    }

    @Test
    fun `POST create as non-organizer member returns 403`() {
        val body = createBodyFor(listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `POST create with non-member participant returns 403`() {
        val body = createBodyFor(listOf(memberAId, outsiderId))
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `mark-paid sets status returns updated DTO and is idempotent on repeat`() {
        val id = createSkladchina(listOf(memberAId, memberBId))

        // First mark-paid
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"declaredAmountKopecks": 50000}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myStatus").value("paid"))
            .andExpect(jsonPath("$.myDeclaredAmountKopecks").value(50000))
            .andExpect(jsonPath("$.collectedKopecks").value(50000))

        // Idempotent repeat — no error, current state
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"declaredAmountKopecks": 50000}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myStatus").value("paid"))
    }

    @Test
    fun `decline transitions to declined and cannot mark-paid afterwards`() {
        val id = createSkladchina(listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/skladchinas/$id/decline")
                .header("Authorization", "Bearer $memberAToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myStatus").value("declined"))

        // Cannot mark-paid after decline
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"declaredAmountKopecks": 50000}""")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `GET detail as non-participant non-creator returns 403`() {
        val id = createSkladchina(listOf(memberAId))  // memberB not participant
        mockMvc.perform(
            get("/api/skladchinas/$id")
                .header("Authorization", "Bearer $memberBToken")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `GET detail as member hides participants list`() {
        val id = createSkladchina(listOf(memberAId, memberBId))
        mockMvc.perform(
            get("/api/skladchinas/$id")
                .header("Authorization", "Bearer $memberAToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isOrganizerView").value(false))
            .andExpect(jsonPath("$.participants").doesNotExist())
            .andExpect(jsonPath("$.participantCount").value(2))
    }

    @Test
    fun `goal-reached triggers auto-close to closed_success`() {
        // Goal = 100, two members fixed_equal → 50 each. Both pay → closed.
        val id = createSkladchina(listOf(memberAId, memberBId))
        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberAToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"declaredAmountKopecks": 50000}""")
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/skladchinas/$id/mark-paid")
                .header("Authorization", "Bearer $memberBToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"declaredAmountKopecks": 50000}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("closed_success"))
    }

    @Test
    fun `manual close as creator works but non-creator gets 403`() {
        val id = createSkladchina(listOf(memberAId))
        // Non-creator cannot close
        mockMvc.perform(
            post("/api/skladchinas/$id/close")
                .header("Authorization", "Bearer $memberAToken")
        )
            .andExpect(status().isForbidden)

        // Creator closes — collected = 0, no goal reached → cancelled
        mockMvc.perform(
            post("/api/skladchinas/$id/close")
                .header("Authorization", "Bearer $organizerToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("cancelled"))
    }

    @Test
    fun `GET me-skladchinas returns active skladchinas only for participant`() {
        val id1 = createSkladchina(listOf(memberAId))   // memberA participant
        val id2 = createSkladchina(listOf(memberBId))   // memberB participant, memberA not

        mockMvc.perform(
            get("/api/users/me/skladchinas")
                .header("Authorization", "Bearer $memberAToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(id1.toString()))
    }

    @Test
    fun `GET clubs id skladchinas active as organizer returns list`() {
        createSkladchina(listOf(memberAId))
        createSkladchina(listOf(memberBId))
        mockMvc.perform(
            get("/api/clubs/$clubId/skladchinas/active")
                .header("Authorization", "Bearer $organizerToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `validation - deadline in past returns 400`() {
        val body = """
            {
              "title": "Bad",
              "paymentMode": "fixed_equal",
              "totalGoalKopecks": 100000,
              "paymentLink": "https://x.com",
              "deadline": "${OffsetDateTime.now().minusDays(1)}",
              "participants": [{"userId": "$memberAId"}]
            }
        """.trimIndent()
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `validation - voluntary mode allows missing goal`() {
        val body = """
            {
              "title": "Voluntary fund",
              "paymentMode": "voluntary",
              "paymentLink": "https://x.com",
              "deadline": "${OffsetDateTime.now().plusDays(2)}",
              "participants": [{"userId": "$memberAId"}]
            }
        """.trimIndent()
        mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.paymentMode").value("voluntary"))
            .andExpect(jsonPath("$.totalGoalKopecks").doesNotExist())
    }

    // ---- helpers ----

    private fun createBodyFor(participantIds: List<UUID>): String {
        val participants = participantIds.joinToString(", ") { """{"userId": "$it"}""" }
        return """
            {
              "title": "Test",
              "paymentMode": "fixed_equal",
              "totalGoalKopecks": 100000,
              "paymentLink": "https://tinkoff.ru/pay/xyz",
              "deadline": "${OffsetDateTime.now().plusDays(2)}",
              "participants": [$participants]
            }
        """.trimIndent()
    }

    private fun createSkladchina(participantIds: List<UUID>): UUID {
        val body = createBodyFor(participantIds)
        val result = mockMvc.perform(
            post("/api/clubs/$clubId/skladchinas")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated)
            .andReturn()
        val json = objectMapper.readTree(result.response.contentAsString)
        return UUID.fromString(json.get("id").asText())
    }
}
