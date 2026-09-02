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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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

    /** Город из справочника, засеянного миграцией V74: клуб создаётся только по существующему id. */
    private lateinit var moscowId: UUID

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

        moscowId = dsl.fetchValue(
            "SELECT id FROM cities WHERE country_code = 'RU' AND normalized_name = 'москва'"
        ) as UUID
    }

    @Test
    fun `POST api clubs with valid JWT should return 201 and club is persisted`() {
        val request = CreateClubRequest(
            name = "Integration Test Club",
            description = "A club created during integration test",
            category = "sport",
            accessType = "open",
            cityId = moscowId,
            memberLimit = 30,
            subscriptionPrice = 100,
            paymentLink = "https://sbp.example/pay" // paid club requires SBP requisites
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
            .andExpect(jsonPath("$.city").value("Москва"))
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
            cityId = moscowId,
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
        // Missing required fields: name, description, category, accessType, cityId
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
            cityId = moscowId,
            memberLimit = 20,
            subscriptionPrice = 50,
            paymentLink = "https://sbp.example/pay" // paid club requires SBP requisites
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
            cityId = moscowId,
            district = "Center",
            memberLimit = 40,
            subscriptionPrice = 200,
            rules = "Be kind",
            applicationQuestion = "Why join?",
            paymentLink = "https://sbp.example/pay" // paid club requires SBP requisites
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
            .andExpect(jsonPath("$.city").value("Москва"))
            .andExpect(jsonPath("$.district").value("Center"))
            .andExpect(jsonPath("$.memberLimit").value(40))
            .andExpect(jsonPath("$.subscriptionPrice").value(200))
            .andExpect(jsonPath("$.rules").value("Be kind"))
            .andExpect(jsonPath("$.applicationQuestion").value("Why join?"))
            .andExpect(jsonPath("$.memberCount").isNumber)
            .andExpect(jsonPath("$.isActive").value(true))
    }

    @Test
    fun `invite from Telegram needs an application in a closed club, copied link does not (V71)`() {
        // Причина фичи: приглашение в клуб «по заявке» пускало сразу в состав, минуя одобрение.
        // Теперь у клуба два кода: заявочный (уходит в prepared message) и прямой (копирует
        // менеджер). Проверяем оба на живой БД, включая то, что прямой код НЕ виден участнику.
        val createResult = mockMvc.perform(
            post("/api/clubs")
                .header("Authorization", "Bearer $testToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        CreateClubRequest(
                            name = "Closed Invite Club",
                            description = "Принимаем по заявке",
                            category = "sport",
                            accessType = "closed",
                            cityId = moscowId,
                            memberLimit = 30,
                            subscriptionPrice = 0
                        )
                    )
                )
        )
            .andExpect(status().isCreated)
            .andReturn()
        val clubId = objectMapper.readTree(createResult.response.contentAsString).get("id").asText()

        // Коды сеем напрямую: сам invite-share дергал бы Telegram, а нас интересует поведение входа.
        dsl.execute(
            "UPDATE clubs SET invite_link = 'direct-code-1', apply_invite_code = 'apply-code-1' WHERE id = '$clubId'"
        )

        val inviteeId = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$inviteeId', 555001, 'Invitee')")
        val inviteeToken = jwtService.generateToken(inviteeId, 555001)

        // Заявочная ссылка честно говорит посадочной: нужна заявка. Прямой код в ответе не светится.
        mockMvc.perform(
            get("/api/invite/apply-code-1").header("Authorization", "Bearer $inviteeToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.inviteRequiresApplication").value(true))
            .andExpect(jsonPath("$.inviteLink").doesNotExist())

        // И прямое вступление по ней невозможно — иначе правило обходилось бы вызовом эндпоинта.
        mockMvc.perform(
            post("/api/invite/apply-code-1/join").header("Authorization", "Bearer $inviteeToken")
        )
            .andExpect(status().isBadRequest)

        // Штатный путь по этой ссылке — заявка.
        mockMvc.perform(
            post("/api/clubs/$clubId/apply")
                .header("Authorization", "Bearer $inviteeToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"answerText":""}""")
        )
            .andExpect(status().is2xxSuccessful)

        // Прямая ссылка (её копирует только менеджер) пускает сразу — осознанный обход.
        val directJoinerId = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$directJoinerId', 555002, 'Direct')")
        val directToken = jwtService.generateToken(directJoinerId, 555002)

        mockMvc.perform(
            get("/api/invite/direct-code-1").header("Authorization", "Bearer $directToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.inviteRequiresApplication").value(false))

        mockMvc.perform(
            post("/api/invite/direct-code-1/join").header("Authorization", "Bearer $directToken")
        )
            .andExpect(status().isCreated)
    }

    @Test
    fun `PUT api clubs id edits cover and avatar independently (V70)`() {
        // Регрессия на причину появления cover_url: до V70 обложка страницы клуба рисовалась из
        // avatar_url, поэтому смена аватара молча меняла и обложку. Теперь поля независимы.
        val createResult = mockMvc.perform(
            post("/api/clubs")
                .header("Authorization", "Bearer $testToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        CreateClubRequest(
                            name = "Cover Test Club",
                            description = "Testing cover and avatar separation",
                            category = "sport",
                            accessType = "open",
                            cityId = moscowId,
                            memberLimit = 30,
                            subscriptionPrice = 0,
                            avatarUrl = "https://cdn.example/avatar-1.png"
                        )
                    )
                )
        )
            .andExpect(status().isCreated)
            .andReturn()

        val clubId = objectMapper.readTree(createResult.response.contentAsString).get("id").asText()

        // Свежесозданный клуб обложки не имеет — фронтенд рисует градиент по категории.
        mockMvc.perform(get("/api/clubs/$clubId").header("Authorization", "Bearer $testToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.avatarUrl").value("https://cdn.example/avatar-1.png"))
            .andExpect(jsonPath("$.coverUrl").doesNotExist())

        // Ставим обложку — аватар обязан остаться прежним.
        mockMvc.perform(
            put("/api/clubs/$clubId")
                .header("Authorization", "Bearer $testToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(UpdateClubRequest(coverUrl = "https://cdn.example/cover.png")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.coverUrl").value("https://cdn.example/cover.png"))
            .andExpect(jsonPath("$.avatarUrl").value("https://cdn.example/avatar-1.png"))

        // Меняем аватар — обложка обязана остаться прежней.
        mockMvc.perform(
            put("/api/clubs/$clubId")
                .header("Authorization", "Bearer $testToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(UpdateClubRequest(avatarUrl = "https://cdn.example/avatar-2.png")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.avatarUrl").value("https://cdn.example/avatar-2.png"))
            .andExpect(jsonPath("$.coverUrl").value("https://cdn.example/cover.png"))

        // Пустая строка очищает обложку в NULL (конвенция nullable-полей), аватар снова не задет.
        mockMvc.perform(
            put("/api/clubs/$clubId")
                .header("Authorization", "Bearer $testToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(UpdateClubRequest(coverUrl = "")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.coverUrl").doesNotExist())
            .andExpect(jsonPath("$.avatarUrl").value("https://cdn.example/avatar-2.png"))
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
            "cityId" to moscowId.toString(),
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

    private var memberSeq = 200_000L

    private fun insertClub(id: UUID, name: String, memberCount: Int) {
        dsl.execute(
            "INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, " +
                "member_limit) VALUES ('$id', '$testUserId', '$name', 'desc', " +
                "'sport', 'open', 'Moscow', 30)"
        )
        // Seed `memberCount` real member rows: the discovery sort + «Популярный» tag read the live
        // count from `memberships` (active/frozen/expired); the old `clubs.member_count` column was dropped (V33).
        repeat(memberCount) { insertMembership(newMember(), id, "active", "member") }
    }

    /** Creates a fresh user and returns its id (for seeding distinct memberships). */
    private fun newMember(): UUID {
        val uid = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$uid', ${memberSeq++}, 'M')")
        return uid
    }

    private fun insertMembership(userId: UUID, clubId: UUID, status: String, role: String) {
        dsl.execute(
            "INSERT INTO memberships (id, user_id, club_id, status, role) VALUES " +
                "('${UUID.randomUUID()}', '$userId', '$clubId', '$status'::membership_status, '$role'::membership_role)"
        )
    }

    @Test
    fun `GET api clubs id returns live member count (active plus frozen plus expired incl owner, excl cancelled)`() {
        val clubId = UUID.randomUUID()
        // Column deliberately left at 0 (the drift the bug produced); the response must ignore it.
        insertClub(clubId, "Live Count", memberCount = 0)
        insertMembership(testUserId, clubId, "active", "organizer")   // owner — counted
        insertMembership(newMember(), clubId, "active", "member")     // active member — counted
        insertMembership(newMember(), clubId, "frozen", "member")     // ждёт первого взноса, слот занят — counted
        insertMembership(newMember(), clubId, "expired", "member")    // должник по продлению, слот занят — counted
        insertMembership(newMember(), clubId, "cancelled", "member")  // left — NOT counted

        mockMvc.perform(
            get("/api/clubs/$clubId").header("Authorization", "Bearer $testToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.memberCount").value(4)) // owner + active + frozen + expired; cancelled excluded
    }

    /** A recent (yesterday), non-cancelled event — counted by the discovery activity signal. */
    private fun insertRecentEvent(clubId: UUID) {
        dsl.execute(
            "INSERT INTO events (club_id, created_by, title, location_text, event_datetime, " +
                "participant_limit, status) VALUES ('$clubId', '$testUserId', 'Event', 'Somewhere', " +
                "NOW() - INTERVAL '1 day', 10, 'completed')"
        )
    }

    @Test
    fun `city filter finds a club regardless of how its city text was written`() {
        // Клуб с «правильным» городом и клуб-легаси, чей текст города написан иначе. Раньше
        // фильтр сравнивал строки (equalIgnoreCase), и второй клуб не находился НИКОГДА.
        val proper = UUID.randomUUID()
        val legacyText = UUID.randomUUID()
        insertClub(proper, "Proper Club", 0)
        insertClub(legacyText, "Legacy Text Club", 0)
        dsl.execute("UPDATE clubs SET city_id = '$moscowId' WHERE id = '$proper'")
        dsl.execute("UPDATE clubs SET city_id = '$moscowId', city = 'мск' WHERE id = '$legacyText'")

        val result = mockMvc.perform(
            get("/api/clubs").param("cityId", moscowId.toString())
                .header("Authorization", "Bearer $testToken")
        )
            .andExpect(status().isOk)
            .andReturn()

        val ids = objectMapper.readTree(result.response.contentAsString)
            .get("content").map { it.get("id").asText() }
        assertTrue(ids.contains(proper.toString()), "клуб с городом из справочника должен находиться")
        assertTrue(
            ids.contains(legacyText.toString()),
            "клуб находится по FK, а не по написанию города — иначе он невидим в каталоге",
        )
    }

    @Test
    fun `city filter excludes clubs from other cities`() {
        val spbId = dsl.fetchValue(
            "SELECT id FROM cities WHERE country_code = 'RU' AND normalized_name = 'санкт-петербург'"
        ) as UUID
        val moscowClub = UUID.randomUUID()
        insertClub(moscowClub, "Moscow Club", 0)
        dsl.execute("UPDATE clubs SET city_id = '$moscowId' WHERE id = '$moscowClub'")

        val result = mockMvc.perform(
            get("/api/clubs").param("cityId", spbId.toString())
                .header("Authorization", "Bearer $testToken")
        )
            .andExpect(status().isOk)
            .andReturn()

        val ids = objectMapper.readTree(result.response.contentAsString)
            .get("content").map { it.get("id").asText() }
        assertTrue(ids.isEmpty(), "в Петербурге клубов нет — выдача должна быть пустой")
    }

    @Test
    fun `GET api cities returns the seeded dictionary`() {
        mockMvc.perform(get("/api/cities").header("Authorization", "Bearer $testToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").exists())
            .andExpect(jsonPath("$[0].name").exists())
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
