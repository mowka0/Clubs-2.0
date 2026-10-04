package com.clubs.subscription

import com.clubs.generated.jooq.enums.SubscriptionStatus
import com.clubs.generated.jooq.tables.references.FUNNEL_EVENT
import com.clubs.generated.jooq.tables.references.SERVICE_SUBSCRIPTION
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

/**
 * Агрегаты недельного отчёта на реальном Postgres (AC-8): границы окна, уникальные старты,
 * атрибуция подключений и оплат к первому /start, «чат без встреч» и подписки.
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
class FunnelReportRepositoryTest {

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

        private val telegramSeq = AtomicLong(9_200_000L)
        private val chatSeq = AtomicLong(-1_000_000L)
    }

    @Autowired lateinit var repository: FunnelReportRepository
    @Autowired lateinit var subscriptions: SubscriptionRepository
    @Autowired lateinit var dsl: DSLContext

    private val msk = ZoneOffset.ofHours(3)
    private val week = ReportWeek(
        start = OffsetDateTime.of(2026, 9, 21, 0, 0, 0, 0, msk),
        end = OffsetDateTime.of(2026, 9, 28, 0, 0, 0, 0, msk),
    )
    private val now = OffsetDateTime.of(2026, 9, 28, 9, 0, 0, 0, msk)

    @BeforeEach
    fun clean() {
        // Отчёт считает по всей базе — каждый тест начинает с чистого листа (прайсинг из V98 без FK, остаётся).
        dsl.execute("TRUNCATE TABLE users CASCADE")
    }

    @Test
    fun `клубы, чаты, встречи и простаивающие чаты`() {
        val a = user(); val b = user(); val c = user(); val d = user(); val e = user()
        val monthsAgo = now.minusDays(60)
        val linkedLongAgo = now.minusDays(40)

        val oldWithChat = club(a, createdAt = monthsAgo)
        link(oldWithChat, a, linkedAt = linkedLongAgo, botStatus = "administrator")
        event(oldWithChat, a, at = week.start, status = "completed")              // граница: ровно начало недели — входит
        event(oldWithChat, a, at = week.end, status = "completed")                // граница: ровно конец — уже следующая неделя
        event(oldWithChat, a, at = week.start.plusDays(2), status = "cancelled")  // отменённая не состоялась

        val idle = club(b, createdAt = monthsAgo, name = "Настолки")
        link(idle, b, linkedAt = linkedLongAgo, botStatus = "member")
        event(idle, b, at = now.minusDays(5), status = "cancelled")               // отменённая не спасает от списка
        event(idle, b, at = now.minusDays(40), status = "completed")              // старше 30 дней — не считается

        val youngNoMeetings = club(c, createdAt = week.start.plusDays(4))
        link(youngNoMeetings, c, linkedAt = week.start.plusDays(4), botStatus = "member")

        val planned = club(d, createdAt = monthsAgo)
        link(planned, d, linkedAt = linkedLongAgo, botStatus = "administrator")
        event(planned, d, at = now.plusDays(10), status = "upcoming")             // запланированная спасает

        val kicked = club(e, createdAt = monthsAgo)
        link(kicked, e, linkedAt = linkedLongAgo, botStatus = "kicked")

        club(a, createdAt = week.start.plusDays(2))                               // новый без чата
        club(b, createdAt = week.start.plusDays(3), active = false)               // мягко удалённый
        val noChatOld = club(c, createdAt = monthsAgo)
        event(noChatOld, c, at = week.start.plusDays(1), status = "completed")    // без чата — не в «состоялось»

        val report = repository.collect(week, now)

        assertEquals(7, report.clubs)
        assertEquals(4, report.clubsWithChat, "kicked не считается чатом")
        assertEquals(2, report.newClubs)
        assertEquals(1, report.newClubsWithChat)
        assertEquals(1, report.meetingsCompleted)
        assertEquals(listOf("Настолки"), report.idleChats)
    }

    @Test
    fun `шаги воронки за неделю и атрибуция кампаний к первому старту`() {
        val a = user(); val b = user(); val c = user()
        val clubX = club(a, createdAt = week.start)
        val clubY = club(b, createdAt = week.start)

        // Старты: 1001 дважды (уникален), 1002 органика, 1003 — первый /start до недели с меткой tg2.
        funnel("bot_started", at = week.start, telegramId = a.telegramId, campaign = "vk1")
        funnel("bot_started", at = week.start.plusDays(1), telegramId = a.telegramId, campaign = "vk1")
        funnel("bot_started", at = week.start.plusDays(2), telegramId = b.telegramId)
        funnel("bot_started", at = week.start.minusDays(20), telegramId = c.telegramId, campaign = "tg2")
        funnel("bot_started", at = week.start.plusDays(3), telegramId = c.telegramId, campaign = "vk1")
        funnel("bot_started", at = week.end, telegramId = 9_999_999L, campaign = "vk1")  // уже следующая неделя

        // Подключения: c → tg2 (первый старт), b → органика, 9_999_998 никогда не жал /start → органика.
        funnel("chat_connected", at = week.start.plusDays(4), telegramId = c.telegramId, clubId = clubX, userId = c.id)
        funnel("chat_connected", at = week.start.plusDays(4), telegramId = b.telegramId, clubId = clubY, userId = b.id)
        funnel("chat_connected", at = week.start.plusDays(5), telegramId = 9_999_998L)
        funnel("chat_disconnected", at = week.start.plusDays(6), clubId = clubY, userId = b.id)

        funnel("trial_started", at = week.start.plusDays(4), clubId = clubX, userId = c.id)
        funnel("trial_started", at = week.start.plusDays(5), clubId = clubY, userId = b.id)
        funnel("checkout_started", at = week.start.plusDays(5), clubId = clubX, userId = c.id)
        funnel("checkout_started", at = week.start.plusDays(5), clubId = clubX, userId = c.id)
        funnel("checkout_started", at = week.start.plusDays(6), clubId = clubY, userId = b.id)

        // Оплаты: a → vk1, c → tg2; третья — за границей окна.
        funnel("payment_succeeded", at = week.start.plusDays(6), clubId = clubX, userId = a.id)
        funnel("payment_succeeded", at = week.start.plusDays(6), clubId = clubY, userId = c.id)
        funnel("payment_succeeded", at = week.end, clubId = clubY, userId = a.id)

        val report = repository.collect(week, now)

        assertEquals(3, report.starts, "уникальные telegram id в окне")
        assertEquals(3, report.connections)
        assertEquals(1, report.disconnections)
        assertEquals(2, report.firstMeetings)
        assertEquals(2, report.checkouts, "уникальные клубы")
        assertEquals(2, report.payments)
        assertEquals(
            listOf(
                CampaignRow("vk1", starts = 2, connections = 0, payments = 1),
                CampaignRow("tg2", starts = 0, connections = 1, payments = 1),
                CampaignRow(null, starts = 1, connections = 2, payments = 0),
            ),
            report.campaigns,
        )
    }

    @Test
    fun `платят - живые подписки, MRR - только ACTIVE по текущей цене`() {
        val owner = user()
        val periodEnd = now.plusDays(20)
        subscriptions.createChatSubscription(owner.id, club(owner, now.minusDays(30)), periodEnd, null, autopay = true, autopayPossible = false)
        val cancelled = subscriptions.createChatSubscription(owner.id, club(owner, now.minusDays(30)), periodEnd, null, autopay = false, autopayPossible = false)
        val pastDue = subscriptions.createChatSubscription(owner.id, club(owner, now.minusDays(30)), periodEnd, null, autopay = true, autopayPossible = true)
        val ended = subscriptions.createChatSubscription(owner.id, club(owner, now.minusDays(30)), periodEnd, null, autopay = true, autopayPossible = true)
        setStatus(cancelled.id, SubscriptionStatus.CANCELLED_PENDING_END)
        setStatus(pastDue.id, SubscriptionStatus.PAST_DUE)
        setStatus(ended.id, SubscriptionStatus.ENDED)

        val report = repository.collect(week, now)

        assertEquals(2, report.paying)
        assertEquals(199, report.mrrRubles)
    }

    private data class TestUser(val id: UUID, val telegramId: Long)

    private fun user(): TestUser {
        val id = UUID.randomUUID()
        val telegramId = telegramSeq.incrementAndGet()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$id', $telegramId, 'U')")
        return TestUser(id, telegramId)
    }

    private fun club(owner: TestUser, createdAt: OffsetDateTime, name: String = "Club", active: Boolean = true): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price, is_active, created_at)
            VALUES ('$id', '${owner.id}', '$name', 'desc', 'sport', 'open', 'Moscow', 20, 0, $active, '$createdAt')
            """.trimIndent()
        )
        return id
    }

    private fun link(clubId: UUID, owner: TestUser, linkedAt: OffsetDateTime, botStatus: String) {
        dsl.execute(
            """
            INSERT INTO club_chat_links (club_id, chat_id, linked_by_user_id, linked_at, bot_status)
            VALUES ('$clubId', ${chatSeq.decrementAndGet()}, '${owner.id}', '$linkedAt', '$botStatus')
            """.trimIndent()
        )
    }

    private fun event(clubId: UUID, creator: TestUser, at: OffsetDateTime, status: String) {
        dsl.execute(
            """
            INSERT INTO events (club_id, created_by, title, location_text, event_datetime, participant_limit, status)
            VALUES ('$clubId', '${creator.id}', 'Event', 'Somewhere', '$at', 10, '$status')
            """.trimIndent()
        )
    }

    private fun funnel(
        kind: String,
        at: OffsetDateTime,
        telegramId: Long? = null,
        clubId: UUID? = null,
        userId: UUID? = null,
        campaign: String? = null,
    ) {
        dsl.insertInto(FUNNEL_EVENT)
            .set(FUNNEL_EVENT.KIND, kind)
            .set(FUNNEL_EVENT.CREATED_AT, at)
            .set(FUNNEL_EVENT.TELEGRAM_ID, telegramId)
            .set(FUNNEL_EVENT.CLUB_ID, clubId)
            .set(FUNNEL_EVENT.USER_ID, userId)
            .set(FUNNEL_EVENT.CAMPAIGN, campaign)
            .execute()
    }

    private fun setStatus(subscriptionId: UUID, status: SubscriptionStatus) {
        dsl.update(SERVICE_SUBSCRIPTION).set(SERVICE_SUBSCRIPTION.STATUS, status)
            .where(SERVICE_SUBSCRIPTION.ID.eq(subscriptionId)).execute()
    }
}
