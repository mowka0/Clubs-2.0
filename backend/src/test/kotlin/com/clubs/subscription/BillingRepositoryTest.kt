package com.clubs.subscription

import com.clubs.generated.jooq.enums.SubscriptionStatus
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
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Счета платформы и подписка за чат на реальном Postgres (V97): InvId из последовательности,
 * атомарное подтверждение счёта, идемпотентный чекаут, обход живых подписок, одна живая
 * подписка на клуб.
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
class BillingRepositoryTest {

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

        private val telegramSeq = AtomicLong(9_100_000L)
    }

    @Autowired lateinit var payments: PlatformPaymentRepository
    @Autowired lateinit var subscriptions: SubscriptionRepository
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
    private lateinit var clubId: UUID

    @BeforeEach
    fun setUp() {
        ownerId = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$ownerId', ${telegramSeq.incrementAndGet()}, 'U')")
        clubId = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price, is_active)
            VALUES ('$clubId', '$ownerId', 'Club', 'desc', 'sport', 'open', 'Moscow', 20, 0, true)
            """.trimIndent()
        )
    }

    @Test
    fun `invoice numbers come from the sequence and settle exactly once`() {
        val first = payments.create(clubId, null, PaymentKind.MOTHER, 19900, null, autopayRequested = false)
        val second = payments.create(clubId, null, PaymentKind.MOTHER, 19900, null, autopayRequested = true)

        assertTrue(first.invId >= 100_000, "InvId стартует со 100000")
        assertTrue(second.invId > first.invId)
        assertEquals(PlatformPaymentStatus.PENDING, first.status)
        assertFalse(first.autopayRequested)
        assertEquals(first.id, payments.findByInvId(first.invId)?.id)

        val paidAt = OffsetDateTime.now()
        assertEquals(1, payments.markSucceeded(first.id, "BankCard", BigDecimal("6.77"), paidAt))
        assertEquals(0, payments.markSucceeded(first.id, "BankCard", null, paidAt), "повтор ResultURL — no-op")
        assertEquals(0, payments.markFailed(first.id), "подтверждённый счёт не проваливается")

        // Счёт, закрытый по таймауту, обязан принять позднюю оплату: ссылка у провайдера не истекает.
        assertEquals(1, payments.markFailed(second.id))
        assertEquals(1, payments.markSucceeded(second.id, "SBP", null, paidAt), "поздняя оплата оживляет закрытый счёт")
        assertEquals(PlatformPaymentStatus.SUCCEEDED, payments.findByInvId(second.invId)!!.status)
        val settled = payments.findByInvId(first.invId)!!
        assertEquals(PlatformPaymentStatus.SUCCEEDED, settled.status)
        assertEquals("BankCard", settled.paymentMethod)
        assertEquals(0, BigDecimal("6.77").compareTo(settled.providerFee))
        assertNotNull(settled.paidAt)
    }

    @Test
    fun `pending mother lookup respects the reuse window and pending state`() {
        val now = OffsetDateTime.now()
        assertNull(payments.findPendingMother(clubId, now.minusMinutes(30)))

        val pending = payments.create(clubId, null, PaymentKind.MOTHER, 19900, null, autopayRequested = true)
        assertEquals(pending.id, payments.findPendingMother(clubId, now.minusMinutes(30))?.id)
        assertNull(payments.findPendingMother(clubId, now.plusMinutes(1)), "счёт старше окна не переиспользуется")
        // Статус для шита смотрит на счета ЛЮБОГО возраста, иначе «проверяем оплату» превращается
        // в ложное «оплачено» при долгой оплате.
        assertTrue(payments.hasPendingMother(clubId))

        assertEquals(1, payments.updateAutopayRequested(pending.id, false))
        assertEquals(false, payments.findByInvId(pending.invId)!!.autopayRequested)

        payments.markFailed(pending.id)
        assertNull(payments.findPendingMother(clubId, now.minusMinutes(30)))
        assertFalse(payments.hasPendingMother(clubId))

        assertTrue(payments.findPendingCreatedBefore(now.plusMinutes(1)).none { it.id == pending.id })
    }

    @Test
    fun `chat subscription is created ACTIVE, walked by the scheduler and limited to one live row per club`() {
        val periodEnd = OffsetDateTime.now().plusDays(30)
        val created = subscriptions.createChatSubscription(ownerId, clubId, periodEnd, "100001", autopay = true, autopayPossible = true)

        assertEquals(SubscriptionStatus.ACTIVE, created.status)
        assertEquals(clubId, created.subjectClubId)
        assertEquals(created.id, subscriptions.findLatestByClub(clubId)?.id)
        assertTrue(subscriptions.findLive().any { it.id == created.id })

        // Вторая живая на тот же клуб — нарушает uq_service_subscription_live_club.
        val duplicate = runCatching {
            subscriptions.createChatSubscription(ownerId, clubId, periodEnd, "100002", autopay = true, autopayPossible = true)
        }
        assertTrue(duplicate.isFailure, "одна живая подписка на клуб")

        assertEquals(1, subscriptions.recordChargeAttempt(created.id, OffsetDateTime.now()))
        assertEquals(1, subscriptions.findById(created.id)!!.chargeAttempts)
        assertEquals(1, subscriptions.updateAutopay(created.id, false))
        assertEquals(1, subscriptions.markMotherPaid(created.id, "100003", autopay = true, autopayPossible = false))
        val reloaded = subscriptions.findById(created.id)!!
        assertEquals("100003", reloaded.providerToken)
        assertTrue(reloaded.autopay)
        assertFalse(reloaded.autopayPossible)
        assertEquals(0, reloaded.chargeAttempts)
        assertNull(reloaded.lastChargeAt)

        // Дочернее списание привязывается к подписке; после ENDED клуб может подписаться заново.
        val recurring = payments.create(clubId, created.id, PaymentKind.RECURRING, 19900, 100003, autopayRequested = true)
        assertTrue(payments.hasPendingRecurring(created.id))
        payments.markFailed(recurring.id)
        assertFalse(payments.hasPendingRecurring(created.id))

        assertEquals(1, subscriptions.transitionStatus(created.id, listOf(SubscriptionStatus.ACTIVE), SubscriptionStatus.ENDED))
        assertTrue(subscriptions.findLive().none { it.id == created.id })
        val renewed = subscriptions.createChatSubscription(ownerId, clubId, periodEnd.plusDays(40), "100004", autopay = true, autopayPossible = true)
        assertEquals(renewed.id, subscriptions.findLatestByClub(clubId)?.id, "живая с самым поздним периодом идёт первой")
    }
}
