package com.clubs.club

import com.clubs.auth.JwtService
import com.clubs.common.security.AuthenticatedUser
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
class ClubIntegrationTest {

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

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var jwtService: JwtService

    @Autowired
    lateinit var dsl: DSLContext

    private lateinit var testUserId: UUID
    private lateinit var testToken: String

    @BeforeEach
    fun setUp() {
        // Clean up test data in reverse dependency order
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM applications")
        dsl.execute("DELETE FROM transactions")
        dsl.execute("DELETE FROM membership_history")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM user_club_reputation")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")

        // Create a test user directly in the database
        testUserId = UUID.randomUUID()
        val telegramId = 123456789L
        dsl.execute(
            "INSERT INTO users (id, telegram_id, first_name) VALUES ('$testUserId', $telegramId, 'TestUser')"
        )

        testToken = jwtService.generateToken(testUserId, telegramId)
    }

    @Test
    fun `POST api clubs with valid JWT should return 201 and club is persisted`() {
        val request = CreateClubRequest(
            name = "Integration Test Club",
            description = "A club created during integration test",
            category = "sport",
            accessType = "open",
            city = "Moscow",
            memberLimit = 30,
            subscriptionPrice = 100
        )

        val response = mockMvc.perform(
            post("/api/clubs")
                .header("Authorization", "Bearer $testToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Integration Test Club"))
            .andExpect(jsonPath("$.category").value("sport"))
            .andExpect(jsonPath("$.accessType").value("open"))
            .andExpect(jsonPath("$.city").value("Moscow"))
            .andExpect(jsonPath("$.memberLimit").value(30))
            .andExpect(jsonPath("$.subscriptionPrice").value(100))
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.ownerId").value(testUserId.toString()))
            .andReturn()

        // Verify the club is persisted in the database
        val clubCount = dsl.fetchCount(
            dsl.selectFrom("clubs").where("owner_id = '$testUserId'")
        )
        assertEquals(1, clubCount, "Club must be persisted in the database")
    }

    @Test
    fun `POST api clubs without token should return 401`() {
        val request = CreateClubRequest(
            name = "No Auth Club",
            description = "Should fail",
            category = "sport",
            accessType = "open",
            city = "Moscow",
            memberLimit = 30,
            subscriptionPrice = 0
        )

        mockMvc.perform(
            post("/api/clubs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST api clubs with invalid body should return 400 with error JSON`() {
        // Missing required fields: name, description, category, accessType, city
        val invalidBody = """{"memberLimit": 30, "subscriptionPrice": 0}"""

        mockMvc.perform(
            post("/api/clubs")
                .header("Authorization", "Bearer $testToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `POST api clubs should auto-create organizer membership`() {
        val request = CreateClubRequest(
            name = "Membership Test Club",
            description = "Testing organizer membership auto-creation",
            category = "creative",
            accessType = "open",
            city = "SPb",
            memberLimit = 20,
            subscriptionPrice = 50
        )

        mockMvc.perform(
            post("/api/clubs")
                .header("Authorization", "Bearer $testToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)

        // Verify organizer membership was created
        val membershipCount = dsl.fetchCount(
            dsl.selectFrom("memberships").where("user_id = '$testUserId'")
        )
        assertEquals(1, membershipCount, "Organizer membership must be auto-created")

        // Verify it's an organizer role
        val role = dsl.fetchOne(
            "SELECT role FROM memberships WHERE user_id = '$testUserId'"
        )?.get("role")?.toString()
        assertEquals("organizer", role, "Auto-created membership must have organizer role")
    }

    @Test
    fun `GET api clubs id should return 200 with full response structure`() {
        // First create a club
        val createRequest = CreateClubRequest(
            name = "Get Test Club",
            description = "Testing GET endpoint",
            category = "food",
            accessType = "closed",
            city = "Kazan",
            district = "Center",
            memberLimit = 40,
            subscriptionPrice = 200,
            rules = "Be kind",
            applicationQuestion = "Why join?"
        )

        val createResult = mockMvc.perform(
            post("/api/clubs")
                .header("Authorization", "Bearer $testToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest))
        )
            .andExpect(status().isCreated)
            .andReturn()

        val responseBody = objectMapper.readTree(createResult.response.contentAsString)
        val clubId = responseBody.get("id").asText()

        // Now GET the club and validate full response structure
        mockMvc.perform(
            get("/api/clubs/$clubId")
                .header("Authorization", "Bearer $testToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(clubId))
            .andExpect(jsonPath("$.ownerId").value(testUserId.toString()))
            .andExpect(jsonPath("$.name").value("Get Test Club"))
            .andExpect(jsonPath("$.description").value("Testing GET endpoint"))
            .andExpect(jsonPath("$.category").value("food"))
            .andExpect(jsonPath("$.accessType").value("closed"))
            .andExpect(jsonPath("$.city").value("Kazan"))
            .andExpect(jsonPath("$.district").value("Center"))
            .andExpect(jsonPath("$.memberLimit").value(40))
            .andExpect(jsonPath("$.subscriptionPrice").value(200))
            .andExpect(jsonPath("$.rules").value("Be kind"))
            .andExpect(jsonPath("$.applicationQuestion").value("Why join?"))
            .andExpect(jsonPath("$.memberCount").isNumber)
            .andExpect(jsonPath("$.isActive").value(true))
    }

    @Test
    fun `GET api clubs id should return 404 for non-existent club`() {
        val fakeId = UUID.randomUUID()

        mockMvc.perform(
            get("/api/clubs/$fakeId")
                .header("Authorization", "Bearer $testToken")
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Club not found"))
    }

    @Test
    fun `POST api clubs with invalid category should return 400`() {
        val request = mapOf(
            "name" to "Bad Category Club",
            "description" to "Bad category",
            "category" to "nonexistent_category",
            "accessType" to "open",
            "city" to "Moscow",
            "memberLimit" to 30,
            "subscriptionPrice" to 0
        )

        mockMvc.perform(
            post("/api/clubs")
                .header("Authorization", "Bearer $testToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `GET api clubs orders by recent event activity ahead of member count`() {
        // No-events club has the MOST members — it must still rank last, proving the discovery
        // sort is driven by recent activity (the derived replacement for the retired
        // activity_rating column), not by member_count.
        val noEvents = UUID.randomUUID()
        val oneEvent = UUID.randomUUID()
        val twoEvents = UUID.randomUUID()
        insertClub(noEvents, "No Events", memberCount = 9)
        insertClub(oneEvent, "One Event", memberCount = 1)
        insertClub(twoEvents, "Two Events", memberCount = 1)
        insertRecentEvent(oneEvent)
        insertRecentEvent(twoEvents)
        insertRecentEvent(twoEvents)

        val orderedIds = clubTags().keys.toList()

        assertEquals(
            listOf(twoEvents.toString(), oneEvent.toString(), noEvents.toString()),
            orderedIds,
            "Clubs must order by recent event count desc (2 > 1 > 0), regardless of member_count"
        )
    }

    @Test
    fun `GET api clubs tags Популярный by member count with a zero-threshold guard`() {
        // Phase 1: ten zero-member clubs → the top-decile member count is 0, so the guard must
        // tag no one. This is the exact regression the dead, always-0 activity_rating produced.
        repeat(10) { i -> insertClub(UUID.randomUUID(), "Zero $i", memberCount = 0) }

        assertTrue(
            clubTags().values.none { it.contains("Популярный") },
            "No club may be tagged Популярный when the top-decile member count is 0"
        )

        // Phase 2: add one genuinely-joined club → it alone crosses the top-decile threshold.
        val popular = UUID.randomUUID()
        insertClub(popular, "Popular", memberCount = 20)

        assertEquals(
            setOf(popular.toString()),
            clubTags().filterValues { it.contains("Популярный") }.keys,
            "Only the top-member club may be tagged Популярный"
        )
    }

    private fun insertClub(id: UUID, name: String, memberCount: Int) {
        dsl.execute(
            "INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, " +
                "member_limit, member_count) VALUES ('$id', '$testUserId', '$name', 'desc', " +
                "'sport', 'open', 'Moscow', 30, $memberCount)"
        )
    }

    /** A recent (yesterday), non-cancelled event — counted by the discovery activity signal. */
    private fun insertRecentEvent(clubId: UUID) {
        dsl.execute(
            "INSERT INTO events (club_id, created_by, title, location_text, event_datetime, " +
                "participant_limit, status) VALUES ('$clubId', '$testUserId', 'Event', 'Somewhere', " +
                "NOW() - INTERVAL '1 day', 10, 'completed')"
        )
    }

    /** Discovery list as an ordered map of clubId → tags (insertion order = response order). */
    private fun clubTags(): Map<String, List<String>> {
        val result = mockMvc.perform(
            get("/api/clubs").header("Authorization", "Bearer $testToken")
        )
            .andExpect(status().isOk)
            .andReturn()
        val content = objectMapper.readTree(result.response.contentAsString).get("content")
        return content.associate { node ->
            node.get("id").asText() to node.get("tags").map { it.asText() }
        }
    }
}
