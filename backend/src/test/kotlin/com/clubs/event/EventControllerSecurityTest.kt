package com.clubs.event

import com.clubs.auth.JwtService
import org.hamcrest.Matchers.nullValue
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
    private lateinit var organizerId: UUID
    private lateinit var nonMemberToken: String
    private lateinit var memberToken: String
    private lateinit var organizerToken: String

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM applications")
        dsl.execute("DELETE FROM transactions")
        dsl.execute("DELETE FROM membership_history")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM user_club_reputation")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")

        val nonMemberId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        organizerId = UUID.randomUUID()
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

    @Test
    fun `POST event with photoUrl persists and returns it in detail and list`() {
        val eventDatetime = OffsetDateTime.now().plusDays(10)
        val photoUrl = "https://cdn.example.com/event-cover.jpg"
        val body = """
            {
              "title": "Photo Event",
              "locationText": "Park",
              "locationLat": 55.761216,
              "locationLon": 37.646488,
              "locationHint": "Вход со двора, домофон 12",
              "eventDatetime": "$eventDatetime",
              "participantLimit": 20,
              "photoUrl": "$photoUrl"
            }
        """.trimIndent()

        mockMvc.perform(
            post("/api/clubs/$clubId/events")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.photoUrl").value(photoUrl))
            .andExpect(jsonPath("$.locationLat").value(55.761216))
            .andExpect(jsonPath("$.locationLon").value(37.646488))
            .andExpect(jsonPath("$.locationHint").value("Вход со двора, домофон 12"))

        // List endpoint also carries it
        mockMvc.perform(
            get("/api/clubs/$clubId/events")
                .header("Authorization", "Bearer $memberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].photoUrl").value(photoUrl))
    }

    @Test
    fun `POST attendance as non-organizer should return 403`() {
        // Owner check in AttendanceService runs before the event-time check, so a
        // plain member (not the club owner) is rejected regardless of event timing.
        val eventId = insertEvent(OffsetDateTime.now().minusDays(1), status = "completed")
        mockMvc.perform(
            post("/api/events/$eventId/attendance")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"attendance":[]}""")
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("FORBIDDEN"))
    }

    @Test
    fun `POST attendance before the event happens should return 400`() {
        val eventId = insertEvent(OffsetDateTime.now().plusDays(5), status = "upcoming")
        mockMvc.perform(
            post("/api/events/$eventId/attendance")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"attendance":[]}""")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `GET events teaser as non-member returns 200 without private fields`() {
        // Тизер-афиша (PO 2026-07-24) — единственный событийный эндпоинт клуба БЕЗ членства:
        // не-участник видит название/дату/счётчик, но ни места, ни фото в ответе нет вовсе.
        insertEvent(OffsetDateTime.now().plusDays(3), status = "upcoming")
        insertEvent(OffsetDateTime.now().minusDays(2), status = "completed")
        insertEvent(OffsetDateTime.now().plusDays(5), status = "cancelled")

        mockMvc.perform(
            get("/api/clubs/$clubId/events/teaser")
                .header("Authorization", "Bearer $nonMemberToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.upcoming.length()").value(1))
            .andExpect(jsonPath("$.upcoming[0].title").value("Att Event"))
            .andExpect(jsonPath("$.upcoming[0].locationText").doesNotExist())
            .andExpect(jsonPath("$.upcoming[0].photoUrl").doesNotExist())
            .andExpect(jsonPath("$.past.length()").value(1))
            .andExpect(jsonPath("$.totalPastCount").value(1))
    }

    @Test
    fun `GET events teaser without token should return 401`() {
        mockMvc.perform(get("/api/clubs/$clubId/events/teaser"))
            .andExpect(status().isUnauthorized)
    }

    private fun insertEvent(eventDatetime: OffsetDateTime, status: String): UUID {
        val eventId = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime, participant_limit, voting_opens_days_before, status)
            VALUES ('$eventId', '$clubId', '$organizerId', 'Att Event', 'Place', '$eventDatetime', 10, 14, '$status'::event_status)
            """.trimIndent()
        )
        return eventId
    }

    @Test
    fun `POST event without photoUrl returns null photoUrl`() {
        val eventDatetime = OffsetDateTime.now().plusDays(10)
        val body = """
            {
              "title": "No Photo Event",
              "locationText": "Park",
              "locationLat": 55.761216,
              "locationLon": 37.646488,
              "eventDatetime": "$eventDatetime",
              "participantLimit": 20
            }
        """.trimIndent()

        mockMvc.perform(
            post("/api/clubs/$clubId/events")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.photoUrl").value(nullValue()))
            .andExpect(jsonPath("$.locationHint").value(nullValue()))
    }

    @Test
    fun `POST event without point AND without hint should return 400`() {
        val eventDatetime = OffsetDateTime.now().plusDays(10)
        // Правило PO (V58): место опционально, но точка ИЛИ уточнение обязательны.
        val body = """
            {
              "title": "No Location Event",
              "eventDatetime": "$eventDatetime",
              "participantLimit": 20
            }
        """.trimIndent()

        mockMvc.perform(
            post("/api/clubs/$clubId/events")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST event with hint only (no point) should return 201 with null location`() {
        val eventDatetime = OffsetDateTime.now().plusDays(10)
        val body = """
            {
              "title": "Online Event",
              "locationHint": "Встречаемся в зуме",
              "eventDatetime": "$eventDatetime",
              "participantLimit": 20
            }
        """.trimIndent()

        mockMvc.perform(
            post("/api/clubs/$clubId/events")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.locationText").value(nullValue()))
            .andExpect(jsonPath("$.locationLat").value(nullValue()))
            .andExpect(jsonPath("$.locationHint").value("Встречаемся в зуме"))
    }

    @Test
    fun `POST event with half a coordinate pair should return 400`() {
        val eventDatetime = OffsetDateTime.now().plusDays(10)
        val body = """
            {
              "title": "Half Pair Event",
              "locationLat": 55.761216,
              "locationHint": "х",
              "eventDatetime": "$eventDatetime",
              "participantLimit": 20
            }
        """.trimIndent()

        mockMvc.perform(
            post("/api/clubs/$clubId/events")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest)
    }
}
