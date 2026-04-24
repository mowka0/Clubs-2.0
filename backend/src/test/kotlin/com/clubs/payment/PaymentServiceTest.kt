package com.clubs.payment

import com.clubs.club.ClubRepository
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.TransactionStatus
import com.clubs.generated.jooq.enums.TransactionType
import com.clubs.generated.jooq.tables.records.ClubsRecord
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.membership.MembershipExpiryRef
import com.clubs.membership.MembershipRepository
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DuplicateKeyException
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for PaymentService. Covers acceptance criteria AC-1..AC-8 from
 * docs/modules/payment.md.
 */
class PaymentServiceTest {

    private lateinit var clubRepository: ClubRepository
    private lateinit var userRepository: UserRepository
    private lateinit var membershipRepository: MembershipRepository
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var telegramClient: TelegramClient
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var service: PaymentService

    private val userId = UUID.randomUUID()
    private val clubId = UUID.randomUUID()
    private val membershipId = UUID.randomUUID()
    private val telegramId = 42_000_000L
    private val chargeId = "CHARGE-XYZ"

    @BeforeEach
    fun setUp() {
        clubRepository = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        membershipRepository = mockk(relaxed = true)
        transactionRepository = mockk(relaxed = true)
        telegramClient = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        service = PaymentService(
            clubRepository,
            userRepository,
            membershipRepository,
            transactionRepository,
            telegramClient,
            eventPublisher
        )
    }

    private fun clubRecord(price: Int?, name: String = "Test Club"): ClubsRecord =
        ClubsRecord(
            id = clubId,
            ownerId = UUID.randomUUID(),
            name = name,
            description = "desc",
            category = ClubCategory.sport,
            accessType = AccessType.`open`,
            city = "Moscow",
            memberLimit = 50,
            subscriptionPrice = price,
            memberCount = 0,
            activityRating = 0,
            isActive = true
        )

    private fun userRecord(): UsersRecord =
        UsersRecord(
            id = userId,
            telegramId = telegramId,
            firstName = "Test"
        )

    // AC-1
    @Test
    fun `createInvoice sends SendInvoice for paid club`() {
        every { clubRepository.findById(clubId) } returns clubRecord(price = 500)
        every { userRepository.findById(userId) } returns userRecord()
        val captured = slot<SendInvoice>()
        every { telegramClient.execute(capture(captured)) } returns mockk(relaxed = true)

        service.createInvoice(userId, clubId)

        verify(exactly = 1) { telegramClient.execute(any<SendInvoice>()) }
        val sent = captured.captured
        assertEquals(telegramId.toString(), sent.chatId)
        assertEquals("XTR", sent.currency)
        assertEquals(500, sent.prices.single().amount)
        assertTrue(sent.payload.startsWith("club_subscription:"))
        assertTrue(sent.payload.endsWith("$clubId:$userId"))
    }

    // AC-2
    @Test
    fun `createInvoice is a no-op for free club`() {
        every { clubRepository.findById(clubId) } returns clubRecord(price = 0)

        service.createInvoice(userId, clubId)

        verify(exactly = 0) { telegramClient.execute(any<SendInvoice>()) }
        verify(exactly = 0) { userRepository.findById(any()) }
    }

    @Test
    fun `createInvoice is a no-op for club with null price`() {
        every { clubRepository.findById(clubId) } returns clubRecord(price = null)

        service.createInvoice(userId, clubId)

        verify(exactly = 0) { telegramClient.execute(any<SendInvoice>()) }
    }

    // AC-3
    @Test
    fun `handleSuccessfulPayment creates membership and transaction on first payment`() {
        every { transactionRepository.existsByTelegramChargeId(chargeId) } returns false
        every { clubRepository.findById(clubId) } returns clubRecord(price = 500)
        every { membershipRepository.findExpiryRefByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.activateSubscription(userId, clubId, any()) } returns membershipId

        val savedTx = slot<Transaction>()
        every { transactionRepository.save(capture(savedTx)) } answers { firstArg() }

        service.handleSuccessfulPayment(
            telegramId = telegramId,
            telegramChargeId = chargeId,
            payload = "club_subscription:$clubId:$userId",
            amount = 500
        )

        verify(exactly = 1) { membershipRepository.activateSubscription(userId, clubId, any()) }
        verify(exactly = 1) { clubRepository.incrementMemberCount(clubId) }
        verify(exactly = 0) { membershipRepository.renewSubscription(any(), any()) }

        val tx = savedTx.captured
        assertEquals(TransactionType.subscription, tx.type)
        assertEquals(TransactionStatus.completed, tx.status)
        assertEquals(500, tx.amount)
        assertEquals(100, tx.platformFee)
        assertEquals(400, tx.organizerRevenue)
        assertEquals(chargeId, tx.telegramPaymentChargeId)
        assertEquals(membershipId, tx.membershipId)
    }

    // AC-4
    @Test
    fun `handleSuccessfulPayment renews an active subscription by adding 30 days to current expiry`() {
        val currentExpiry = OffsetDateTime.now().plusDays(10)
        every { transactionRepository.existsByTelegramChargeId(chargeId) } returns false
        every { clubRepository.findById(clubId) } returns clubRecord(price = 500)
        every { membershipRepository.findExpiryRefByUserAndClub(userId, clubId) } returns
            MembershipExpiryRef(id = membershipId, subscriptionExpiresAt = currentExpiry)

        val newExpiry = slot<OffsetDateTime>()
        every { membershipRepository.renewSubscription(membershipId, capture(newExpiry)) } returns Unit

        val savedTx = slot<Transaction>()
        every { transactionRepository.save(capture(savedTx)) } answers { firstArg() }

        service.handleSuccessfulPayment(telegramId, chargeId, "club_subscription:$clubId:$userId", 500)

        verify(exactly = 0) { clubRepository.incrementMemberCount(any()) }
        verify(exactly = 0) { membershipRepository.activateSubscription(any(), any(), any()) }
        assertEquals(currentExpiry.plusDays(30), newExpiry.captured)
        assertEquals(TransactionType.renewal, savedTx.captured.type)
    }

    // AC-5
    @Test
    fun `handleSuccessfulPayment on expired subscription restarts expiry from now`() {
        val expiredAt = OffsetDateTime.now().minusDays(2)
        every { transactionRepository.existsByTelegramChargeId(chargeId) } returns false
        every { clubRepository.findById(clubId) } returns clubRecord(price = 500)
        every { membershipRepository.findExpiryRefByUserAndClub(userId, clubId) } returns
            MembershipExpiryRef(id = membershipId, subscriptionExpiresAt = expiredAt)

        val newExpiry = slot<OffsetDateTime>()
        every { membershipRepository.renewSubscription(membershipId, capture(newExpiry)) } returns Unit
        every { transactionRepository.save(any()) } answers { firstArg() }

        val before = OffsetDateTime.now()
        service.handleSuccessfulPayment(telegramId, chargeId, "club_subscription:$clubId:$userId", 500)
        val after = OffsetDateTime.now()

        verify(exactly = 0) { clubRepository.incrementMemberCount(any()) }
        val expected = newExpiry.captured
        assertTrue(expected.isAfter(before.plusDays(30).minusSeconds(1)))
        assertTrue(expected.isBefore(after.plusDays(30).plusSeconds(1)))
    }

    // AC-6
    @Test
    fun `handleSuccessfulPayment is idempotent when charge_id already exists`() {
        every { transactionRepository.existsByTelegramChargeId(chargeId) } returns true

        service.handleSuccessfulPayment(telegramId, chargeId, "club_subscription:$clubId:$userId", 500)

        verify(exactly = 0) { membershipRepository.activateSubscription(any(), any(), any()) }
        verify(exactly = 0) { membershipRepository.renewSubscription(any(), any()) }
        verify(exactly = 0) { clubRepository.incrementMemberCount(any()) }
        verify(exactly = 0) { transactionRepository.save(any()) }
    }

    // AC-7
    @Test
    fun `handleSuccessfulPayment ignores malformed payload`() {
        service.handleSuccessfulPayment(telegramId, chargeId, "wrong:format", 500)

        verify(exactly = 0) { transactionRepository.existsByTelegramChargeId(any()) }
        verify(exactly = 0) { transactionRepository.save(any()) }
        verify(exactly = 0) { membershipRepository.activateSubscription(any(), any(), any()) }
    }

    @Test
    fun `handleSuccessfulPayment ignores payload with non-UUID ids`() {
        service.handleSuccessfulPayment(
            telegramId, chargeId, "club_subscription:not-a-uuid:$userId", 500
        )
        verify(exactly = 0) { transactionRepository.save(any()) }
    }

    @Test
    fun `handleSuccessfulPayment ignores payment for unknown club`() {
        every { transactionRepository.existsByTelegramChargeId(chargeId) } returns false
        every { clubRepository.findById(clubId) } returns null

        service.handleSuccessfulPayment(telegramId, chargeId, "club_subscription:$clubId:$userId", 500)

        verify(exactly = 0) { transactionRepository.save(any()) }
        verify(exactly = 0) { membershipRepository.activateSubscription(any(), any(), any()) }
    }

    @Test
    fun `handleSuccessfulPayment ignores non-positive amount`() {
        service.handleSuccessfulPayment(telegramId, chargeId, "club_subscription:$clubId:$userId", 0)
        service.handleSuccessfulPayment(telegramId, chargeId, "club_subscription:$clubId:$userId", -50)

        verify(exactly = 0) { transactionRepository.existsByTelegramChargeId(any()) }
    }

    // AC-8 (partial): DuplicateKey на save пробрасывается наверх,
    // чтобы внешний @Transactional откатил membership-работу.
    @Test
    fun `handleSuccessfulPayment rethrows DuplicateKeyException so outer transaction rolls back`() {
        every { transactionRepository.existsByTelegramChargeId(chargeId) } returns false
        every { clubRepository.findById(clubId) } returns clubRecord(price = 500)
        every { membershipRepository.findExpiryRefByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.activateSubscription(userId, clubId, any()) } returns membershipId
        every { transactionRepository.save(any()) } throws DuplicateKeyException("unique")

        assertThrows<DuplicateKeyException> {
            service.handleSuccessfulPayment(telegramId, chargeId, "club_subscription:$clubId:$userId", 500)
        }
    }

    @Test
    fun `handleSuccessfulPayment publishes PaymentConfirmedEvent on success`() {
        every { transactionRepository.existsByTelegramChargeId(chargeId) } returns false
        every { clubRepository.findById(clubId) } returns clubRecord(price = 500, name = "Chess Club")
        every { membershipRepository.findExpiryRefByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.activateSubscription(userId, clubId, any()) } returns membershipId
        every { transactionRepository.save(any()) } answers { firstArg() }

        val captured = slot<PaymentConfirmedEvent>()
        every { eventPublisher.publishEvent(capture(captured)) } returns Unit

        service.handleSuccessfulPayment(telegramId, chargeId, "club_subscription:$clubId:$userId", 500)

        verify(exactly = 1) { eventPublisher.publishEvent(any<PaymentConfirmedEvent>()) }
        assertEquals(telegramId, captured.captured.telegramId)
        assertEquals("Chess Club", captured.captured.clubName)
    }

    @Test
    fun `handleSuccessfulPayment does NOT publish event on idempotent duplicate`() {
        every { transactionRepository.existsByTelegramChargeId(chargeId) } returns true

        service.handleSuccessfulPayment(telegramId, chargeId, "club_subscription:$clubId:$userId", 500)

        verify(exactly = 0) { eventPublisher.publishEvent(any<PaymentConfirmedEvent>()) }
    }

    @Test
    fun `handleSuccessfulPayment does NOT publish event on validation failures`() {
        // unknown payload
        service.handleSuccessfulPayment(telegramId, chargeId, "wrong:format", 500)
        // non-positive amount
        service.handleSuccessfulPayment(telegramId, chargeId, "club_subscription:$clubId:$userId", 0)

        // unknown club
        every { transactionRepository.existsByTelegramChargeId(any()) } returns false
        every { clubRepository.findById(clubId) } returns null
        service.handleSuccessfulPayment(telegramId, chargeId, "club_subscription:$clubId:$userId", 500)

        verify(exactly = 0) { eventPublisher.publishEvent(any<PaymentConfirmedEvent>()) }
    }

    @Test
    fun `platform fee is 20 percent and organizer revenue is the rest`() {
        every { transactionRepository.existsByTelegramChargeId(any()) } returns false
        every { clubRepository.findById(clubId) } returns clubRecord(price = 7)
        every { membershipRepository.findExpiryRefByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.activateSubscription(userId, clubId, any()) } returns membershipId
        val saved = slot<Transaction>()
        every { transactionRepository.save(capture(saved)) } answers { firstArg() }

        service.handleSuccessfulPayment(telegramId, chargeId, "club_subscription:$clubId:$userId", 7)

        // 7 * 20 / 100 = 1 (integer floor)
        assertEquals(1, saved.captured.platformFee)
        assertEquals(6, saved.captured.organizerRevenue)
        assertEquals(7, saved.captured.amount)
    }
}
