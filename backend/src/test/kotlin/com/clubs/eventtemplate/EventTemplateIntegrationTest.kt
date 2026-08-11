package com.clubs.eventtemplate

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Шаблоны встреч (docs/modules/event-templates.md § 8): права, изоляция клубов, лимит,
 * уникальность имени, каскад при удалении клуба. Номера AC — из спеки.
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
class EventTemplateIntegrationTest {

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
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
    private lateinit var coOrganizerId: UUID
    private lateinit var memberId: UUID
    private lateinit var clubId: UUID
    private lateinit var otherClubId: UUID
    private lateinit var ownerToken: String
    private lateinit var coOrganizerToken: String
    private lateinit var memberToken: String
    private lateinit var outsiderToken: String

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM event_templates")
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")

        ownerId = UUID.randomUUID()
        coOrganizerId = UUID.randomUUID()
        memberId = UUID.randomUUID()
        val outsiderId = UUID.randomUUID()
        clubId = UUID.randomUUID()
        otherClubId = UUID.randomUUID()

        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$ownerId', 7001, 'Owner')")
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$coOrganizerId', 7002, 'CoOrg')")
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$memberId', 7003, 'Member')")
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$outsiderId', 7004, 'Outsider')")

        ownerToken = jwtService.generateToken(ownerId, 7001L)
        coOrganizerToken = jwtService.generateToken(coOrganizerId, 7002L)
        memberToken = jwtService.generateToken(memberId, 7003L)
        outsiderToken = jwtService.generateToken(outsiderId, 7004L)

        insertClub(clubId, ownerId, "Клуб шаблонов")
        // Второй клуб чужого владельца — для проверки изоляции и фильтра /me/event-templates.
        insertClub(otherClubId, outsiderId, "Чужой клуб")

        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$ownerId', '$clubId', 'active', 'organizer')")
        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$coOrganizerId', '$clubId', 'active', 'co_organizer')")
        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$memberId', '$clubId', 'active', 'member')")
        dsl.execute("INSERT INTO memberships (user_id, club_id, status, role) VALUES ('$outsiderId', '$otherClubId', 'active', 'organizer')")
    }

    private fun insertClub(id: UUID, owner: UUID, name: String) {
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price)
            VALUES ('$id', '$owner', '$name', 'desc', 'sport', 'closed', 'Moscow', 20, 0)
            """.trimIndent()
        )
    }

    private fun body(
        name: String = "Разговорный клуб",
        title: String = "Разговорный клуб",
        participantLimit: Int? = 12,
        isOpenEvent: Boolean = false,
        isUrgentEvent: Boolean = false,
        stage2LeadMinutes: Int? = null,
        defaultWeekday: Short? = 2,
        defaultTime: LocalTime? = LocalTime.of(19, 0)
    ) = SaveEventTemplateRequest(
        name = name,
        title = title,
        description = "Говорим по-английски",
        locationText = "ул. Покровка, 47, Москва",
        locationLat = 55.761216,
        locationLon = 37.646488,
        locationHint = "Вход со двора",
        participantLimit = participantLimit,
        isOpenEvent = isOpenEvent,
        isUrgentEvent = isUrgentEvent,
        stage2LeadMinutes = stage2LeadMinutes,
        defaultWeekday = defaultWeekday,
        defaultTime = defaultTime
    )

    private fun create(token: String, club: UUID = clubId, request: SaveEventTemplateRequest = body()) =
        mockMvc.perform(
            post("/api/clubs/$club/event-templates")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )

    private fun createdId(request: SaveEventTemplateRequest = body(), club: UUID = clubId): UUID {
        val response = create(ownerToken, club, request).andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return UUID.fromString(objectMapper.readTree(response).get("id").asText())
    }

    @Test
    fun `AC-2 владелец сохраняет шаблон и видит его в списке клуба`() {
        createdId()

        mockMvc.perform(get("/api/clubs/$clubId/event-templates").header("Authorization", "Bearer $ownerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Разговорный клуб"))
            .andExpect(jsonPath("$[0].clubName").value("Клуб шаблонов"))
            .andExpect(jsonPath("$[0].participantLimit").value(12))
            .andExpect(jsonPath("$[0].defaultWeekday").value(2))
            .andExpect(jsonPath("$[0].defaultTime").value("19:00:00"))
    }

    @Test
    fun `AC-11 со-организатор работает с шаблонами наравне с владельцем`() {
        create(coOrganizerToken).andExpect(status().isCreated)

        mockMvc.perform(get("/api/clubs/$clubId/event-templates").header("Authorization", "Bearer $coOrganizerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `AC-11 участник без MANAGE_EVENTS получает 403 на всех точках`() {
        val templateId = createdId()

        mockMvc.perform(get("/api/clubs/$clubId/event-templates").header("Authorization", "Bearer $memberToken"))
            .andExpect(status().isForbidden)
        create(memberToken).andExpect(status().isForbidden)
        mockMvc.perform(
            put("/api/clubs/$clubId/event-templates/$templateId")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body(name = "Переименовано")))
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            delete("/api/clubs/$clubId/event-templates/$templateId").header("Authorization", "Bearer $memberToken")
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `AC-12 шаблон чужого клуба не находится по club-scoped пути`() {
        val templateId = createdId()

        // Владелец «Чужого клуба» подставляет свой clubId и чужой templateId — капабилити-гейт
        // он проходит (это его клуб), а шаблон должен выглядеть несуществующим.
        mockMvc.perform(
            put("/api/clubs/$otherClubId/event-templates/$templateId")
                .header("Authorization", "Bearer $outsiderToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body(name = "Угон")))
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            delete("/api/clubs/$otherClubId/event-templates/$templateId")
                .header("Authorization", "Bearer $outsiderToken")
        ).andExpect(status().isNotFound)

        assertEquals(1, dsl.fetchCount(dsl.selectFrom("event_templates")), "Шаблон должен остаться нетронутым")
    }

    @Test
    fun `AC-8 обновление меняет содержимое, не плодя новые шаблоны`() {
        val templateId = createdId()

        mockMvc.perform(
            put("/api/clubs/$clubId/event-templates/$templateId")
                .header("Authorization", "Bearer $ownerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        body(name = "Разговорный клуб", title = "Разговорный клуб (новички)", participantLimit = 8)
                    )
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(templateId.toString()))
            .andExpect(jsonPath("$.title").value("Разговорный клуб (новички)"))
            .andExpect(jsonPath("$.participantLimit").value(8))

        assertEquals(1, dsl.fetchCount(dsl.selectFrom("event_templates")), "Новых строк появиться не должно")
    }

    @Test
    fun `AC-10 второй шаблон с тем же именем в другом регистре отклоняется`() {
        createdId()

        create(ownerToken, clubId, body(name = "разговорный КЛУБ"))
            .andExpect(status().isConflict)

        assertEquals(1, dsl.fetchCount(dsl.selectFrom("event_templates")))
    }

    @Test
    fun `AC-10 переименование в занятое имя отклоняется, а сохранение под своим именем проходит`() {
        val firstId = createdId()
        createdId(body(name = "Киновечер", title = "Киновечер"))

        mockMvc.perform(
            put("/api/clubs/$clubId/event-templates/$firstId")
                .header("Authorization", "Bearer $ownerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body(name = "Киновечер")))
        ).andExpect(status().isConflict)

        // Своё же имя занятым не считается — иначе «Обновить шаблон» падал бы всегда.
        mockMvc.perform(
            put("/api/clubs/$clubId/event-templates/$firstId")
                .header("Authorization", "Bearer $ownerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body(name = "Разговорный клуб", participantLimit = 30)))
        ).andExpect(status().isOk)
    }

    @Test
    fun `AC-10 одноимённые шаблоны в РАЗНЫХ клубах уживаются`() {
        createdId()
        create(outsiderToken, otherClubId, body()).andExpect(status().isCreated)

        assertEquals(2, dsl.fetchCount(dsl.selectFrom("event_templates")))
    }

    @Test
    fun `AC-9 одиннадцатый шаблон клуба отклоняется`() {
        repeat(EventTemplateService.MAX_TEMPLATES_PER_CLUB) { i ->
            createdId(body(name = "Шаблон $i", title = "Встреча $i"))
        }

        create(ownerToken, clubId, body(name = "Лишний", title = "Лишний"))
            .andExpect(status().isConflict)

        assertEquals(
            EventTemplateService.MAX_TEMPLATES_PER_CLUB,
            dsl.fetchCount(dsl.selectFrom("event_templates"))
        )
    }

    @Test
    fun `AC-13 me-эндпоинт отдаёт только шаблоны управляемых клубов`() {
        createdId()
        create(outsiderToken, otherClubId, body(name = "Чужой шаблон", title = "Чужая встреча"))
            .andExpect(status().isCreated)

        // Владелец видит только свой клуб.
        mockMvc.perform(get("/api/me/event-templates").header("Authorization", "Bearer $ownerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].clubId").value(clubId.toString()))

        // Со-организатор — тоже (MANAGE_EVENTS делегируется).
        mockMvc.perform(get("/api/me/event-templates").header("Authorization", "Bearer $coOrganizerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))

        // Обычный участник не управляет ничем — список пуст, а не 403.
        mockMvc.perform(get("/api/me/event-templates").header("Authorization", "Bearer $memberToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `AC-13 замороженное членство со-организатора прав не даёт`() {
        createdId()
        dsl.execute("UPDATE memberships SET status = 'frozen' WHERE user_id = '$coOrganizerId'")

        mockMvc.perform(get("/api/me/event-templates").header("Authorization", "Bearer $coOrganizerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))

        mockMvc.perform(get("/api/clubs/$clubId/event-templates").header("Authorization", "Bearer $coOrganizerToken"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `удаление шаблона убирает его из списка`() {
        val templateId = createdId()

        mockMvc.perform(
            delete("/api/clubs/$clubId/event-templates/$templateId").header("Authorization", "Bearer $ownerToken")
        ).andExpect(status().isNoContent)

        mockMvc.perform(get("/api/clubs/$clubId/event-templates").header("Authorization", "Bearer $ownerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))

        // Повторное удаление — 404, а не молчаливый успех.
        mockMvc.perform(
            delete("/api/clubs/$clubId/event-templates/$templateId").header("Authorization", "Bearer $ownerToken")
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `AC-7 шаблон открытой встречи хранится без лимита и без интервала Этапа 2`() {
        createdId(
            body(name = "Открытая пробежка", title = "Пробежка", participantLimit = null, isOpenEvent = true)
        )

        mockMvc.perform(get("/api/clubs/$clubId/event-templates").header("Authorization", "Bearer $ownerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].isOpenEvent").value(true))
            .andExpect(jsonPath("$[0].participantLimit").doesNotExist())
            .andExpect(jsonPath("$[0].stage2LeadMinutes").doesNotExist())
    }

    @Test
    fun `AC-14 удаление клуба уносит его шаблоны каскадом`() {
        createdId()

        dsl.execute("DELETE FROM memberships WHERE club_id = '$clubId'")
        dsl.execute("DELETE FROM clubs WHERE id = '$clubId'")

        assertEquals(0, dsl.fetchCount(dsl.selectFrom("event_templates")), "Сирот остаться не должно")
    }

    @Test
    fun `AC-6 шаблон без дня недели сохраняется с пустой датой повтора`() {
        createdId(body(name = "Поход", title = "Поход", defaultWeekday = null, defaultTime = null))

        mockMvc.perform(get("/api/clubs/$clubId/event-templates").header("Authorization", "Bearer $ownerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].defaultWeekday").doesNotExist())
            .andExpect(jsonPath("$[0].defaultTime").doesNotExist())
    }
}
