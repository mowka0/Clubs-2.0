package com.clubs.subscription

import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.tables.references.CHAT_FREE_MEETING
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Признак бесплатной встречи на реальном Postgres (V87): атомарное взятие, возврат отменой (R5),
 * переезд chat_id, плюс цена плана CHAT из V88. Гоняется на полной Flyway-цепочке.
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
class FreeMeetingRepositoryTest {

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
    }

    @Autowired lateinit var repository: FreeMeetingRepository
    @Autowired lateinit var subscriptionRepository: SubscriptionRepository
    @Autowired lateinit var dsl: DSLContext

    private fun freshChatId(): Long = chatSeq.decrementAndGet()

    @Test
    fun `chat gets exactly one free meeting`() {
        val chatId = freshChatId()
        val clubId = UUID.randomUUID()

        assertTrue(repository.claim(chatId, clubId, UUID.randomUUID()))
        assertFalse(repository.claim(chatId, clubId, UUID.randomUUID()))
        // Другой клуб на том же чате (переподключение, R6) — тоже стена.
        assertFalse(repository.claim(chatId, UUID.randomUUID(), UUID.randomUUID()))
    }

    @Test
    fun `cancelling the free meeting returns it to the chat (R5)`() {
        val chatId = freshChatId()
        val clubId = UUID.randomUUID()
        val firstEvent = UUID.randomUUID()
        val secondEvent = UUID.randomUUID()
        assertTrue(repository.claim(chatId, clubId, firstEvent))

        assertEquals(1, repository.release(firstEvent))
        assertEquals(0, repository.release(firstEvent), "повторный возврат — no-op")
        assertEquals(0, repository.release(UUID.randomUUID()), "чужая/платная встреча — no-op")

        assertTrue(repository.claim(chatId, clubId, secondEvent))
        val row = dsl.selectFrom(CHAT_FREE_MEETING).where(CHAT_FREE_MEETING.CHAT_ID.eq(chatId)).fetchOne()
        assertNotNull(row)
        assertEquals(secondEvent, row.eventId)
        assertNull(row.releasedAt)
        assertFalse(repository.claim(chatId, clubId, UUID.randomUUID()))
    }

    @Test
    fun `chat id migration moves the flag and drops the old one when the new id is taken`() {
        val oldChatId = freshChatId()
        val newChatId = freshChatId()
        val clubId = UUID.randomUUID()
        assertTrue(repository.claim(oldChatId, clubId, UUID.randomUUID()))

        assertEquals(1, repository.migrateChatId(oldChatId, newChatId))
        assertFalse(repository.claim(newChatId, clubId, UUID.randomUUID()), "бесплатная уехала за новым id")
        assertTrue(repository.claim(oldChatId, clubId, UUID.randomUUID()), "старый id свободен")

        // Новый id уже занят (двойник успел взять бесплатную): старая строка отбрасывается.
        assertEquals(0, repository.migrateChatId(oldChatId, newChatId))
        assertNull(dsl.selectFrom(CHAT_FREE_MEETING).where(CHAT_FREE_MEETING.CHAT_ID.eq(oldChatId)).fetchOne())
    }

    @Test
    fun `CHAT plan is priced at 199 rubles (V88)`() {
        assertEquals(19900, subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT))
    }
}
