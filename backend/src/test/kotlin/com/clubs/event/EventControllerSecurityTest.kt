package com.clubs.event

import com.clubs.auth.JwtService
import com.fasterxml.jackson.databind.ObjectMapper
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
import kotlin.test.assertEquals
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
    private lateinit var nonMemberId: UUID
    private lateinit var organizerId: UUID
    private lateinit var memberId: UUID
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

        nonMemberId = UUID.randomUUID()
        memberId = UUID.randomUUID()
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
              "format": "max",
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
    fun `POST remind as non-organizer should return 403`() {
        // Гейт MANAGE_EVENTS живёт в Stage2ReminderService (аннотация не подходит: в пути id
        // СОБЫТИЯ, а не клуба) — тест держит проводку контроллер→сервис, чтобы рядовой участник
        // не смог разослать DM от имени клуба.
        val eventId = insertEvent(OffsetDateTime.now().plusHours(3), status = "stage_2")
        mockMvc.perform(
            post("/api/events/$eventId/remind")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{}""")
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("FORBIDDEN"))
    }

    @Test
    fun `POST remind without token should return 401`() {
        val eventId = insertEvent(OffsetDateTime.now().plusHours(3), status = "stage_2")
        mockMvc.perform(
            post("/api/events/$eventId/remind")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{}""")
        )
            .andExpect(status().isUnauthorized)
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

    // ---- Карточка встречи: приватные поля только своим (bugfix 2026-09-15) ----

    private fun insertEventWithPhoto(eventDatetime: OffsetDateTime, status: String): UUID {
        val eventId = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, description, location_text, location_lat, location_lon, location_hint, photo_url, event_datetime, participant_limit, voting_opens_days_before, status)
            VALUES ('$eventId', '$clubId', '$organizerId', 'Private Event', 'Берём мангал', 'Тверская 1', 55.76, 37.64, 'Домофон 12', 'https://cdn.example.com/c.jpg', '$eventDatetime', 10, 14, '$status'::event_status)
            """.trimIndent()
        )
        return eventId
    }

    @Test
    fun `GET event as member returns the private fields`() {
        val eventId = insertEventWithPhoto(OffsetDateTime.now().plusDays(3), status = "upcoming")

        mockMvc.perform(get("/api/events/$eventId").header("Authorization", "Bearer $memberToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.locationText").value("Тверская 1"))
            .andExpect(jsonPath("$.locationLat").value(55.76))
            .andExpect(jsonPath("$.locationHint").value("Домофон 12"))
            .andExpect(jsonPath("$.photoUrl").value("https://cdn.example.com/c.jpg"))
            .andExpect(jsonPath("$.description").value("Берём мангал"))
            .andExpect(jsonPath("$.creator.id").value(organizerId.toString()))
    }

    @Test
    fun `GET event as non-member hides location, photo and organizer but keeps the club link`() {
        // A01: до фикса любой авторизованный читал по UUID адрес и уточнение чужой встречи.
        // Отказа здесь нет намеренно — по clubId страница уводит гостя из чата на клуб.
        val eventId = insertEventWithPhoto(OffsetDateTime.now().plusDays(3), status = "upcoming")

        mockMvc.perform(get("/api/events/$eventId").header("Authorization", "Bearer $nonMemberToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Private Event"))
            .andExpect(jsonPath("$.clubId").value(clubId.toString()))
            .andExpect(jsonPath("$.locationText").value(nullValue()))
            .andExpect(jsonPath("$.locationLat").value(nullValue()))
            .andExpect(jsonPath("$.locationLon").value(nullValue()))
            .andExpect(jsonPath("$.locationHint").value(nullValue()))
            .andExpect(jsonPath("$.photoUrl").value(nullValue()))
            .andExpect(jsonPath("$.description").value(nullValue()))
            .andExpect(jsonPath("$.creator").value(nullValue()))
    }

    @Test
    fun `GET event as club owner returns the private fields`() {
        val eventId = insertEventWithPhoto(OffsetDateTime.now().plusDays(3), status = "upcoming")

        mockMvc.perform(get("/api/events/$eventId").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.locationText").value("Тверская 1"))
    }

    @Test
    fun `GET past event as a former participant returns the private fields`() {
        // F5-04: вышедший из клуба открывает встречу по ссылке из DM ради окна спора явки.
        val eventId = insertEventWithPhoto(OffsetDateTime.now().minusDays(1), status = "completed")
        dsl.execute(
            "INSERT INTO event_responses (event_id, user_id, stage_1_vote, final_status, attendance) " +
                "VALUES ('$eventId', '$nonMemberId', 'going', 'confirmed', 'absent')"
        )

        mockMvc.perform(get("/api/events/$eventId").header("Authorization", "Bearer $nonMemberToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.locationText").value("Тверская 1"))
    }

    @Test
    fun `GET past event with a response but no attendance mark stays redacted`() {
        // Спорить не о чем, пока организатор не отметил состав: одного отклика мало.
        val eventId = insertEventWithPhoto(OffsetDateTime.now().minusDays(1), status = "completed")
        dsl.execute(
            "INSERT INTO event_responses (event_id, user_id, stage_1_vote, final_status) " +
                "VALUES ('$eventId', '$nonMemberId', 'going', 'confirmed')"
        )

        mockMvc.perform(get("/api/events/$eventId").header("Authorization", "Bearer $nonMemberToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.locationText").value(nullValue()))
    }

    @Test
    fun `GET future event as a former participant stays redacted`() {
        // Отклик на БУДУЩУЮ встречу доступа не даёт: покинувшему клуб её место знать незачем.
        val eventId = insertEventWithPhoto(OffsetDateTime.now().plusDays(3), status = "upcoming")
        dsl.execute(
            "INSERT INTO event_responses (event_id, user_id, stage_1_vote) " +
                "VALUES ('$eventId', '$nonMemberId', 'going')"
        )

        mockMvc.perform(get("/api/events/$eventId").header("Authorization", "Bearer $nonMemberToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.locationText").value(nullValue()))
    }

    @Test
    fun `GET event without token should return 401`() {
        val eventId = insertEventWithPhoto(OffsetDateTime.now().plusDays(3), status = "upcoming")
        mockMvc.perform(get("/api/events/$eventId"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET past event without any response stays redacted`() {
        // Ловит инверсию условия окна спора: прошедшая встреча сама по себе доступа не даёт.
        val eventId = insertEventWithPhoto(OffsetDateTime.now().minusDays(1), status = "completed")

        mockMvc.perform(get("/api/events/$eventId").header("Authorization", "Bearer $nonMemberToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.locationText").value(nullValue()))
            .andExpect(jsonPath("$.photoUrl").value(nullValue()))
    }

    @Test
    fun `GET cancelled event as non-member hides the cancellation reason`() {
        val eventId = insertEventWithPhoto(OffsetDateTime.now().plusDays(3), status = "cancelled")
        dsl.execute("UPDATE events SET cancellation_reason = 'Переносим в Тверская 1, кв 5' WHERE id = '$eventId'")

        mockMvc.perform(get("/api/events/$eventId").header("Authorization", "Bearer $nonMemberToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.cancellationReason").value(nullValue()))

        mockMvc.perform(get("/api/events/$eventId").header("Authorization", "Bearer $memberToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.cancellationReason").value("Переносим в Тверская 1, кв 5"))
    }

    @Test
    fun `GET event as club owner whose own membership expired still returns the private fields`() {
        // Ветка owner-bypass: у владельца нет активного членства, но встречу своего клуба он
        // обязан открывать — тот же принцип, что в капабилити-гейте ClubRoleGuard.
        dsl.execute("UPDATE memberships SET status = 'expired' WHERE user_id = '$organizerId' AND club_id = '$clubId'")
        val eventId = insertEventWithPhoto(OffsetDateTime.now().plusDays(3), status = "upcoming")

        mockMvc.perform(get("/api/events/$eventId").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.locationText").value("Тверская 1"))
            .andExpect(jsonPath("$.creator.id").value(organizerId.toString()))
    }

    @Test
    fun `redacted card exposes exactly the agreed set of fields`() {
        // Страж списка полей: урезание построено как denylist (copy(... = null)), поэтому НОВОЕ
        // поле EventDetailDto по умолчанию уехало бы наружнику. Тест валится на любом изменении
        // состава карточки — решение «приватное или нет» принимается явно, а не молча.
        val eventId = insertEventWithPhoto(OffsetDateTime.now().plusDays(3), status = "upcoming")

        val json = mockMvc.perform(get("/api/events/$eventId").header("Authorization", "Bearer $nonMemberToken"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val card = ObjectMapper().readTree(json)

        assertEquals(
            setOf(
                "id", "clubId", "creator", "createdBy", "title", "description", "locationText",
                "locationLat", "locationLon", "locationHint", "eventDatetime", "participantLimit",
                "minParticipants", "votingOpensDaysBefore", "stage2LeadMinutes", "stage2LeadMinutesOverride",
                "status", "format", "goingCount", "maybeCount", "notGoingCount", "confirmedCount",
                "noAnswerCount", "rosterDeadline", "rosterClosed", "waitlistedCount", "rosterDecided",
                "declineCostPoints", "declineConsequence", "attendanceMarked", "attendanceFinalized",
                "cancellationReason", "photoUrl", "createdAt"
            ),
            card.fieldNames().asSequence().toSet(),
            "состав EventDetailDto изменился — решить, приватное ли новое поле, и свериться с " +
                "docs/modules/events.md § «Кто видит карточку встречи целиком»"
        )

        // Приватные поля — те и только те, что перечислены в EventMapper.redactForOutsider.
        listOf(
            "description", "locationText", "locationLat", "locationLon",
            "locationHint", "photoUrl", "creator", "cancellationReason"
        ).forEach { field ->
            assertTrue(card.get(field).isNull, "поле $field уехало не-участнику")
        }
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
              "participantLimit": 20,
              "format": "max"
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
              "participantLimit": 20,
              "format": "max"
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
              "participantLimit": 20,
              "format": "max"
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
              "participantLimit": 20,
              "format": "max"
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

    // ---- «Проводим» (V86, § 4 спеки форматов): новый вход с правами менеджера ----

    private fun insertRosterEvent(minParticipants: Int, status: String = "stage_2"): UUID {
        val eventId = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime, participant_limit, min_participants, voting_opens_days_before, status, stage_2_triggered)
            VALUES ('$eventId', '$clubId', '$organizerId', 'Roster Event', 'Place', '${OffsetDateTime.now().plusDays(1)}', 10, $minParticipants, 14, '$status'::event_status, ${status == "stage_2"})
            """.trimIndent()
        )
        return eventId
    }

    private fun insertConfirmed(eventId: UUID, userId: UUID) {
        dsl.execute(
            "INSERT INTO event_responses (event_id, user_id, stage_1_vote, stage_2_vote, final_status) " +
                "VALUES ('$eventId', '$userId', 'going', 'confirmed', 'confirmed')"
        )
    }

    @Test
    fun `POST proceed as non-manager should return 403`() {
        val eventId = insertRosterEvent(minParticipants = 4)
        insertConfirmed(eventId, organizerId)

        mockMvc.perform(post("/api/events/$eventId/proceed").header("Authorization", "Bearer $memberToken"))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/api/events/$eventId/proceed").header("Authorization", "Bearer $nonMemberToken"))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/api/events/$eventId/proceed"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST proceed as organizer below the minimum marks the roster decided and is idempotent`() {
        val eventId = insertRosterEvent(minParticipants = 4)
        // Двое в составе: с одним участником последствие отказа было бы «встреча отменится»
        // (roster_empty идёт раньше seat_empty), а проверить нужно именно «место пустует».
        insertConfirmed(eventId, organizerId)
        insertConfirmed(eventId, memberId)

        mockMvc.perform(post("/api/events/$eventId/proceed").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rosterDecided").value(true))
            .andExpect(jsonPath("$.minParticipants").value(4))
            .andExpect(jsonPath("$.format").value("normal"))
            // После «Проводим» место просто пустует — организатор уже решил.
            .andExpect(jsonPath("$.declineConsequence").value("seat_empty"))

        mockMvc.perform(post("/api/events/$eventId/proceed").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rosterDecided").value(true))
    }

    @Test
    fun `POST proceed with the roster at the minimum or before close should return 400`() {
        val atMinimum = insertRosterEvent(minParticipants = 1)
        insertConfirmed(atMinimum, organizerId)
        mockMvc.perform(post("/api/events/$atMinimum/proceed").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isBadRequest)

        val collecting = insertRosterEvent(minParticipants = 4, status = "upcoming")
        mockMvc.perform(post("/api/events/$collecting/proceed").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isBadRequest)
    }

    // AC-17: старые литералы V85 принимаются на входе; наружу — только normal/open.
    @Test
    fun `POST event with legacy format literal min is accepted as normal with a minimum equal to the limit`() {
        val body = """
            {
              "title": "Legacy min",
              "locationHint": "Парк",
              "eventDatetime": "${OffsetDateTime.now().plusDays(10)}",
              "participantLimit": 6,
              "format": "min"
            }
        """.trimIndent()

        mockMvc.perform(
            post("/api/clubs/$clubId/events")
                .header("Authorization", "Bearer $organizerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.format").value("normal"))
            .andExpect(jsonPath("$.participantLimit").value(6))
            .andExpect(jsonPath("$.minParticipants").value(6))
            .andExpect(jsonPath("$.rosterDecided").value(false))
    }

    @Test
    fun `POST event with a minimum above the limit should return 400`() {
        val body = """
            {
              "title": "Bad minimum",
              "locationHint": "Парк",
              "eventDatetime": "${OffsetDateTime.now().plusDays(10)}",
              "participantLimit": 6,
              "minParticipants": 7,
              "format": "normal"
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
