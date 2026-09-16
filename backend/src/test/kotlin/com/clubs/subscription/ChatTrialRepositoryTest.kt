package com.clubs.subscription

import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.tables.references.CHAT_TRIAL
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Бесплатный период чата на реальном Postgres (V99): отсчёт заводится первой встречей и не
 * сдвигается следующими, переезд chat_id, выборка кандидатов на напоминание и его дедуп, плюс
 * цена плана CHAT из V98. Гоняется на полной Flyway-цепочке.
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
class ChatTrialRepositoryTest {

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

        // Общий на класс: JUnit создаёт экземпляр на каждый тест, а контейнер один — id чатов
        // не должны повторяться между тестами.
        private val chatSeq = AtomicLong(-1_001_000_000_000L)
        private val telegramSeq = AtomicLong(9_300_000L)
    }

    @Autowired lateinit var repository: ChatTrialRepository
    @Autowired lateinit var subscriptionRepository: SubscriptionRepository
    @Autowired lateinit var dsl: DSLContext

    private fun freshChatId(): Long = chatSeq.decrementAndGet()

    /** Живая привязка чата к клубу: выборка кандидатов на напоминание идёт через club_chat_links. */
    private fun linkChatToClub(chatId: Long, clubId: UUID, ownerId: UUID, botStatus: String = "administrator") {
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$ownerId', ${telegramSeq.incrementAndGet()}, 'U')")
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price, is_active)
            VALUES ('$clubId', '$ownerId', 'Club', 'desc', 'sport', 'open', 'Moscow', 20, 0, true)
            """.trimIndent()
        )
        dsl.execute(
            """
            INSERT INTO club_chat_links (club_id, chat_id, chat_title, linked_by_user_id, bot_status)
            VALUES ('$clubId', $chatId, 'Чат', '$ownerId', '$botStatus')
            """.trimIndent()
        )
    }

    @Test
    fun `the first meeting starts the trial and later ones keep the same start`() {
        val chatId = freshChatId()
        val clubId = UUID.randomUUID()

        val first = repository.startOrGet(chatId, clubId, UUID.randomUUID())
        assertTrue(first.justStarted)

        val second = repository.startOrGet(chatId, clubId, UUID.randomUUID())
        assertFalse(second.justStarted, "вторая встреча отсчёт не перезапускает")
        assertEquals(first.startedAt, second.startedAt)

        // Другой клуб на том же чате (переподключение, R6) получает тот же срок, а не новый.
        val other = repository.startOrGet(chatId, UUID.randomUUID(), UUID.randomUUID())
        assertFalse(other.justStarted)
        assertEquals(first.startedAt, other.startedAt)
        assertEquals(first.startedAt, repository.findStartedAt(chatId))
    }

    @Test
    fun `trials ending soon are listed once per reminder threshold`() {
        val chatId = freshChatId()
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        linkChatToClub(chatId, clubId, ownerId)
        repository.startOrGet(chatId, clubId, UUID.randomUUID())
        // Период 15 дней: через 9 дней после старта до конца остаётся 6 — попадает в окно «неделя».
        val inNineDays = OffsetDateTime.now().plusDays(9)

        val ending = repository.findTrialsEndingBefore(inNineDays.plusDays(7), trialDays = 15)
        val row = ending.singleOrNull { it.chatId == chatId }
        assertNotNull(row)
        assertEquals(clubId, row.clubId)
        assertNull(row.reminderDaysLeft, "напоминаний ещё не было")

        assertEquals(1, repository.markReminded(chatId, 7))
        val afterMark = repository.findTrialsEndingBefore(inNineDays.plusDays(7), trialDays = 15)
            .single { it.chatId == chatId }
        assertEquals(7, afterMark.reminderDaysLeft)
    }

    @Test
    fun `a chat the bot was kicked from is not reminded about its trial`() {
        val chatId = freshChatId()
        val clubId = UUID.randomUUID()
        linkChatToClub(chatId, clubId, UUID.randomUUID(), botStatus = "kicked")
        repository.startOrGet(chatId, clubId, UUID.randomUUID())

        val ending = repository.findTrialsEndingBefore(OffsetDateTime.now().plusDays(30), trialDays = 15)

        assertTrue(ending.none { it.chatId == chatId }, "без бота чат бесплатен — напоминать не о чем")
    }

    @Test
    fun `a chat that already pays is not reminded about its trial`() {
        val chatId = freshChatId()
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        linkChatToClub(chatId, clubId, ownerId)
        repository.startOrGet(chatId, clubId, UUID.randomUUID())
        subscriptionRepository.createChatSubscription(
            payerUserId = ownerId, clubId = clubId, currentPeriodEnd = OffsetDateTime.now().plusDays(30),
            providerToken = "100001", autopay = true, autopayPossible = true,
        )

        val ending = repository.findTrialsEndingBefore(OffsetDateTime.now().plusDays(30), trialDays = 15)

        assertTrue(ending.none { it.chatId == chatId }, "подписка есть — напоминать не о чем")
    }

    @Test
    fun `chat id migration moves the trial and drops the old one when the new id is taken`() {
        val oldChatId = freshChatId()
        val newChatId = freshChatId()
        val clubId = UUID.randomUUID()
        val started = repository.startOrGet(oldChatId, clubId, UUID.randomUUID()).startedAt

        assertEquals(1, repository.migrateChatId(oldChatId, newChatId))
        assertEquals(started, repository.findStartedAt(newChatId), "срок уехал за новым id")
        assertNull(repository.findStartedAt(oldChatId), "старый id свободен")

        // Новый id уже занят (двойник успел создать встречу): старая строка отбрасывается.
        repository.startOrGet(oldChatId, clubId, UUID.randomUUID())
        assertEquals(0, repository.migrateChatId(oldChatId, newChatId))
        assertNull(dsl.selectFrom(CHAT_TRIAL).where(CHAT_TRIAL.CHAT_ID.eq(oldChatId)).fetchOne())
    }

    @Test
    fun `CHAT plan is priced at 199 rubles (V98)`() {
        assertEquals(19900, subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT))
    }
}
