package com.clubs.club

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Темы клуба (club-interests.md): разметка, поиск по темам, чипы полки, счётчики.
 * Критерии приёмки из спеки — в именах тестов.
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
class ClubInterestsIntegrationTest {

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
    private lateinit var ownerToken: String
    private lateinit var strangerToken: String
    private lateinit var moscowId: UUID

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM club_interests")
        dsl.execute("DELETE FROM user_interests")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")
        // Счётчики затравки обнуляем: тесты проверяют изменения относительно нуля.
        dsl.execute("UPDATE interests SET club_usage_count = 0, usage_count = 0")

        ownerId = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$ownerId', 111222333, 'Owner')")
        ownerToken = jwtService.generateToken(ownerId, 111222333L)

        val strangerId = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$strangerId', 444555666, 'Stranger')")
        strangerToken = jwtService.generateToken(strangerId, 444555666L)

        moscowId = dsl.fetchValue(
            "SELECT id FROM cities WHERE country_code = 'RU' AND normalized_name = 'москва'"
        ) as UUID
    }

    private fun createClub(
        name: String,
        description: String = "Описание клуба",
        category: String = "board_games",
        interests: List<String>? = null
    ): UUID {
        val request = CreateClubRequest(
            name = name,
            description = description,
            category = category,
            accessType = "open",
            cityId = moscowId,
            memberLimit = 30,
            subscriptionPrice = 0,
            interests = interests
        )
        val body = mockMvc.perform(
            post("/api/clubs")
                .header("Authorization", "Bearer $ownerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return UUID.fromString(objectMapper.readTree(body).get("id").asText())
    }

    @Test
    fun `AC-1 клуб создаётся с темами и отдаёт их в ответе`() {
        val clubId = createClub("Пятничные партии", interests = listOf("настолки", "мафия"))

        mockMvc.perform(get("/api/clubs/$clubId").header("Authorization", "Bearer $ownerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.interests.length()").value(2))
            .andExpect(jsonPath("$.interests[0]").value("настолки"))
            .andExpect(jsonPath("$.interests[1]").value("мафия"))

        assertEquals(
            2,
            dsl.fetchCount(dsl.selectFrom("club_interests").where("club_id = '$clubId'")),
            "Связи клуба с темами должны сохраниться"
        )
    }

    @Test
    fun `AC-1 восьмая тема отбрасывается молча, а не роняет запрос`() {
        val clubId = createClub(
            "Слишком увлечённый клуб",
            category = "sport",
            interests = listOf("бег", "йога", "футбол", "бокс", "лыжи", "теннис", "хоккей", "марафон")
        )

        assertEquals(
            7,
            dsl.fetchCount(dsl.selectFrom("club_interests").where("club_id = '$clubId'")),
            "Лимит тем на клуб — 7, лишние отбрасываются без ошибки"
        )
    }

    @Test
    fun `нормализация приводит темы к канонической форме`() {
        val clubId = createClub("Клуб с грязным вводом", interests = listOf("  НАСТОЛКИ  ", "«Мафия»"))

        mockMvc.perform(get("/api/clubs/$clubId").header("Authorization", "Bearer $ownerToken"))
            .andExpect(jsonPath("$.interests[0]").value("настолки"))
            .andExpect(jsonPath("$.interests[1]").value("мафия"))
    }

    @Test
    fun `AC-4 клуб находится в каталоге по теме, которой нет в названии и описании`() {
        createClub("Пятничные партии", description = "Собираемся по пятницам", interests = listOf("настолки"))

        mockMvc.perform(
            get("/api/clubs").param("search", "настолки").header("Authorization", "Bearer $ownerToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].name").value("Пятничные партии"))
            .andExpect(jsonPath("$.content[0].interests[0]").value("настолки"))
    }

    @Test
    fun `AC-5 поиск по названию и описанию продолжает работать`() {
        createClub("Книжный клуб", description = "Читаем нонфикшн", category = "education")

        mockMvc.perform(
            get("/api/clubs").param("search", "книжный").header("Authorization", "Bearer $ownerToken")
        )
            .andExpect(jsonPath("$.content.length()").value(1))

        mockMvc.perform(
            get("/api/clubs").param("search", "нонфикшн").header("Authorization", "Bearer $ownerToken")
        )
            .andExpect(jsonPath("$.content.length()").value(1))
    }

    @Test
    fun `AC-6 чипы полки приходят из словаря и соответствуют категории`() {
        val body = mockMvc.perform(
            get("/api/interests").param("category", "sport").header("Authorization", "Bearer $ownerToken")
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        val names = objectMapper.readValue(body, Array<String>::class.java).toList()
        assertTrue(names.contains("бег"), "В полке «Спорт» ожидается «бег», пришло: $names")
        assertTrue(names.none { it == "мафия" }, "Тема чужой полки не должна попадать в выдачу")
    }

    @Test
    fun `неизвестная категория в чипах отклоняется валидацией`() {
        mockMvc.perform(
            get("/api/interests").param("category", "wizardry").header("Authorization", "Bearer $ownerToken")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `AC-2 темы редактируются владельцем, набор заменяется целиком`() {
        val clubId = createClub("Клуб", interests = listOf("настолки", "мафия"))

        mockMvc.perform(
            put("/api/clubs/$clubId")
                .header("Authorization", "Bearer $ownerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"interests": ["шахматы"]}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.interests.length()").value(1))
            .andExpect(jsonPath("$.interests[0]").value("шахматы"))
    }

    @Test
    fun `AC-2 посторонний не может править темы чужого клуба`() {
        val clubId = createClub("Клуб", interests = listOf("настолки"))

        mockMvc.perform(
            put("/api/clubs/$clubId")
                .header("Authorization", "Bearer $strangerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"interests": ["покер"]}""")
        )
            .andExpect(status().isForbidden)

        assertEquals(
            1,
            dsl.fetchCount(dsl.selectFrom("club_interests").where("club_id = '$clubId'")),
            "Отказ авторизации не должен менять разметку"
        )
    }

    @Test
    fun `пустой список снимает все темы, отсутствие ключа их не трогает`() {
        val clubId = createClub("Клуб", interests = listOf("настолки", "мафия"))

        // Ключа нет — темы остаются на месте.
        mockMvc.perform(
            put("/api/clubs/$clubId")
                .header("Authorization", "Bearer $ownerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name": "Клуб переименован"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.interests.length()").value(2))

        // Пустой массив — снять все.
        mockMvc.perform(
            put("/api/clubs/$clubId")
                .header("Authorization", "Bearer $ownerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"interests": []}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.interests.length()").value(0))
    }

    @Test
    fun `AC-9 разметка клуба двигает club_usage_count и не трогает usage_count профилей`() {
        val clubId = createClub("Клуб", interests = listOf("настолки"))

        val afterCreate = dsl.fetchOne("SELECT club_usage_count, usage_count FROM interests WHERE name = 'настолки'")!!
        assertEquals(1, afterCreate.get("club_usage_count"), "Счётчик клубов должен вырасти")
        assertEquals(0, afterCreate.get("usage_count"), "Счётчик пользователей трогать нельзя")

        mockMvc.perform(
            put("/api/clubs/$clubId")
                .header("Authorization", "Bearer $ownerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"interests": []}""")
        )
            .andExpect(status().isOk)

        val afterClear = dsl.fetchOne("SELECT club_usage_count FROM interests WHERE name = 'настолки'")!!
        assertEquals(0, afterClear.get("club_usage_count"), "Снятие темы должно вернуть счётчик")
    }

    @Test
    fun `AC-11 клуб без тем отдаёт пустой список, а не null`() {
        val clubId = createClub("Клуб без разметки")

        mockMvc.perform(get("/api/clubs/$clubId").header("Authorization", "Bearer $ownerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.interests").isArray)
            .andExpect(jsonPath("$.interests.length()").value(0))
    }

    @Test
    fun `AC-10 тема, введённая человеком до затравки, не задваивается при разметке клуба`() {
        // Имитируем интерес профиля, который совпадает по имени с затравленной темой.
        val userInterestId = dsl.fetchValue("SELECT id FROM interests WHERE name = 'шахматы'") as UUID
        dsl.execute("UPDATE interests SET usage_count = 3 WHERE id = '$userInterestId'")

        createClub("Шахматный клуб", interests = listOf("шахматы"))

        assertEquals(
            1,
            dsl.fetchCount(dsl.selectFrom("interests").where("name = 'шахматы'")),
            "Строка словаря должна остаться одна"
        )
        val row = dsl.fetchOne("SELECT usage_count, club_usage_count FROM interests WHERE name = 'шахматы'")!!
        assertEquals(3, row.get("usage_count"), "Накопленный профилями счётчик не должен пострадать")
        assertEquals(1, row.get("club_usage_count"))
    }
}
