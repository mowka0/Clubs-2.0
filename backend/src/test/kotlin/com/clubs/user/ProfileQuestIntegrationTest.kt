package com.clubs.user

import com.clubs.reputation.XpService
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Интеграционный тест профиль-квеста на реальном Postgres (profile-quest.md AC-2/4/7):
 * SQL-механика markQuestMilestones (COALESCE + CASE + коррелированный EXISTS) — самое
 * рискованное место фичи, unit-тестами не покрываемое. Проверяет одноразовость вех
 * (очистка поля не отзывает XP) и невозможность фарма повторными сохранениями.
 */
@SpringBootTest(
    properties = [
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=0",
        "telegram.bot-token=test-bot-token"
    ]
)
@Testcontainers
@ActiveProfiles("test")
class ProfileQuestIntegrationTest {

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

    @Autowired lateinit var userService: UserService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var xpService: XpService
    @Autowired lateinit var dsl: DSLContext

    private lateinit var userId: UUID
    private var nextTelegramId = 7000L

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM user_interests")
        dsl.execute("DELETE FROM interests")
        dsl.execute("DELETE FROM reputation_ledger")
        dsl.execute("DELETE FROM users")
        userId = insertUser("Quest")
    }

    @Test
    fun `city milestone sets flag and 10 XP, others stay null`() {
        userService.updateProfile(userId, UpdateMeRequest(country = "RU", city = "Москва", bio = null))

        val flags = userRepository.findQuestFlags(userId)!!
        assertNotNull(flags.cityAt)
        assertNull(flags.interestsAt)
        assertNull(flags.bioAt)

        val gam = xpService.getGamification(userId)
        assertEquals(10, gam.xp)
        assertTrue(gam.quest.cityDone)
        assertFalse(gam.quest.completed)
    }

    @Test
    fun `empty interests array does not grant the interests milestone`() {
        userService.updateProfile(userId, UpdateMeRequest(city = "Москва", interests = emptyList()))
        assertNull(userRepository.findQuestFlags(userId)!!.interestsAt)
    }

    @Test
    fun `full profile reaches exactly level 2 with Визитка badge`() {
        userService.updateProfile(
            userId,
            UpdateMeRequest(city = "Москва", bio = "Привет!", interests = listOf("настолки"))
        )

        val gam = xpService.getGamification(userId)
        assertEquals(50, gam.xp)
        assertEquals(2, gam.level)
        assertEquals("Свой", gam.levelName)
        assertTrue(gam.quest.completed)
        assertEquals(listOf("profile_card"), gam.badges.map { it.id })
    }

    @Test
    fun `milestones are one-time - clearing fields keeps XP, re-filling adds nothing`() {
        userService.updateProfile(
            userId,
            UpdateMeRequest(city = "Москва", bio = "Привет!", interests = listOf("настолки"))
        )
        val flagsAfterFill = userRepository.findQuestFlags(userId)!!

        // Очистка всех полей: вехи и XP остаются (AC-4, инвариант «XP не убывает»)
        userService.updateProfile(userId, UpdateMeRequest(city = null, bio = null, interests = emptyList()))
        val flagsAfterClear = userRepository.findQuestFlags(userId)!!
        assertEquals(flagsAfterFill, flagsAfterClear, "метки вех не должны меняться при очистке полей")
        assertEquals(50, xpService.getGamification(userId).xp)

        // Повторное заполнение: фарм невозможен — те же метки, тот же XP (AC-7)
        userService.updateProfile(
            userId,
            UpdateMeRequest(city = "Питер", bio = "Снова тут", interests = listOf("походы"))
        )
        assertEquals(flagsAfterFill, userRepository.findQuestFlags(userId)!!, "повторное заполнение не создаёт новых меток")
        assertEquals(50, xpService.getGamification(userId).xp)
    }

    private fun insertUser(name: String): UUID {
        val id = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$id', ${nextTelegramId++}, '$name')")
        return id
    }
}
