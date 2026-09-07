package com.clubs.subscription

import com.clubs.chatlink.ChatLinkRepository
import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import com.clubs.payment.CheckoutRequest
import com.clubs.payment.CheckoutUrl
import com.clubs.payment.PaymentProvider
import com.clubs.payment.ResultNotification
import com.clubs.subscription.BillingTestFixtures.PRICE
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** Чекаут, подтверждение оплаты, ползунок и статус (platform-billing.md § 6.3). */
class BillingServiceTest {

    private val subscriptionRepository = mockk<SubscriptionRepository>(relaxed = true)
    private val paymentRepository = mockk<PlatformPaymentRepository>(relaxed = true)
    private val chatLinkRepository = mockk<ChatLinkRepository>()
    private val clubRepository = mockk<ClubRepository>()
    private val clubRoleGuard = mockk<ClubRoleGuard>()
    private val freeMeetingRepository = mockk<FreeMeetingRepository>(relaxed = true)
    private val funnelEventRepository = mockk<FunnelEventRepository>(relaxed = true)
    private val paymentProvider = mockk<PaymentProvider>()
    private val notifier = mockk<BillingNotifier>(relaxed = true)

    private val service = BillingService(
        subscriptionRepository, paymentRepository, chatLinkRepository, clubRepository, clubRoleGuard,
        freeMeetingRepository, funnelEventRepository, paymentProvider, notifier,
        graceDays = 7, periodDays = 30, checkoutReuseMinutes = 30,
        successUrl = "https://app.example/pay/return", failUrl = "https://app.example/pay/fail",
    )

    private val club = BillingTestFixtures.club()
    private val link = BillingTestFixtures.link(club)

    @BeforeEach
    fun setUp() {
        every { clubRepository.findById(club.id) } returns club
        every { chatLinkRepository.findByClubId(club.id) } returns link
        every { subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT) } returns PRICE
        every { subscriptionRepository.findLatestByClub(club.id) } returns null
        every { paymentRepository.findPendingMother(club.id, any()) } returns null
        every { paymentProvider.id } returns "robokassa"
        every { paymentProvider.createCheckout(any()) } answers { CheckoutUrl("https://rk.example/pay?inv=${firstArg<CheckoutRequest>().invId}") }
    }

    private fun assertClose(expected: OffsetDateTime, actual: OffsetDateTime) {
        assertTrue(Duration.between(expected, actual).abs() < Duration.ofMinutes(1), "expected ≈ $expected, got $actual")
    }

    // ---------- checkout ----------

    @Test
    fun `checkout creates a mother invoice with the requested autopay and records the funnel step`() {
        val created = BillingTestFixtures.payment(club, autopayRequested = false)
        every { paymentRepository.create(club.id, null, PaymentKind.MOTHER, PRICE, null, false) } returns created

        val result = service.checkout(club.id, club.ownerId, autopay = false)

        assertEquals(created.invId, result.invId)
        assertEquals("https://rk.example/pay?inv=${created.invId}", result.paymentUrl)
        verify(exactly = 1) { funnelEventRepository.record(FunnelStep.CHECKOUT_STARTED, club.ownerId, club.id) }
        val request = slot<CheckoutRequest>()
        verify { paymentProvider.createCheckout(capture(request)) }
        assertTrue(request.captured.recurring, "карта сохраняется всегда — ползунок решает, списывать ли")
        assertEquals("https://app.example/pay/return?club=${club.id}", request.captured.successUrl)
        assertEquals(PRICE, request.captured.amountKopecks)
    }

    @Test
    fun `checkout reuses a fresh pending invoice instead of creating a second one`() {
        val pending = BillingTestFixtures.payment(club)
        every { paymentRepository.findPendingMother(club.id, any()) } returns pending

        val result = service.checkout(club.id, club.ownerId, autopay = true)

        assertEquals(pending.invId, result.invId)
        verify(exactly = 0) { paymentRepository.create(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { funnelEventRepository.record(any(), any(), any(), any()) }
    }

    @Test
    fun `checkout is owner-only and needs a linked chat`() {
        assertThrows<ForbiddenException> { service.checkout(club.id, UUID.randomUUID(), autopay = true) }

        every { chatLinkRepository.findByClubId(club.id) } returns null
        assertThrows<ConflictException> { service.checkout(club.id, club.ownerId, autopay = true) }
    }

    // ---------- onResult ----------

    @Test
    fun `first payment creates an ACTIVE subscription for 30 days with the autopay chosen in the sheet`() {
        val payment = BillingTestFixtures.payment(club, autopayRequested = false)
        every { paymentRepository.findByInvId(payment.invId) } returns payment
        every { paymentRepository.markSucceeded(payment.id, "BankCard", null, any()) } returns 1
        val created = BillingTestFixtures.subscription(club, periodEnd = OffsetDateTime.now().plusDays(30), autopay = false)
        every { subscriptionRepository.createChatSubscription(club.ownerId, club.id, any(), "100001", false, true) } returns created

        val outcome = service.onResult(ResultNotification(payment.invId, PRICE, "BankCard", null))

        assertEquals(ResultOutcome.APPLIED, outcome)
        val periodEnd = slot<OffsetDateTime>()
        verify { subscriptionRepository.createChatSubscription(club.ownerId, club.id, capture(periodEnd), "100001", false, true) }
        assertClose(OffsetDateTime.now().plusDays(30), periodEnd.captured)
        verify { paymentRepository.attachSubscription(payment.id, created.id) }
        verify { subscriptionRepository.recordEventIfNew(created.id, "robokassa:paid:100001", "PAID_MOTHER") }
        verify { funnelEventRepository.record(FunnelStep.PAYMENT_SUCCEEDED, club.ownerId, club.id) }
        verify { notifier.paid(club, created.currentPeriodEnd, autopayOn = false, priceKopecks = PRICE) }
    }

    @Test
    fun `SBP mother payment leaves autopay impossible even if the toggle was on`() {
        val payment = BillingTestFixtures.payment(club, autopayRequested = true)
        every { paymentRepository.findByInvId(payment.invId) } returns payment
        every { paymentRepository.markSucceeded(payment.id, "SBP", null, any()) } returns 1
        every { subscriptionRepository.createChatSubscription(any(), any(), any(), any(), any(), any()) } returns
            BillingTestFixtures.subscription(club, autopayPossible = false)

        service.onResult(ResultNotification(payment.invId, PRICE, "SBP", null))

        verify { subscriptionRepository.createChatSubscription(club.ownerId, club.id, any(), "100001", true, false) }
        verify { notifier.paid(club, any(), autopayOn = false, priceKopecks = PRICE) }
    }

    @Test
    fun `a repeated notification for a settled invoice changes nothing`() {
        val payment = BillingTestFixtures.payment(club)
        every { paymentRepository.findByInvId(payment.invId) } returns payment
        every { paymentRepository.markSucceeded(payment.id, any(), any(), any()) } returns 0

        assertEquals(ResultOutcome.ALREADY_SETTLED, service.onResult(ResultNotification(payment.invId, PRICE, "BankCard", null)))

        verify(exactly = 0) { subscriptionRepository.createChatSubscription(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { subscriptionRepository.extendPeriod(any(), any()) }
        verify(exactly = 0) { notifier.paid(any(), any(), any(), any()) }
    }

    @Test
    fun `amount mismatch and unknown invoice do not touch state`() {
        val payment = BillingTestFixtures.payment(club)
        every { paymentRepository.findByInvId(payment.invId) } returns payment
        every { paymentRepository.findByInvId(777) } returns null

        assertEquals(ResultOutcome.AMOUNT_MISMATCH, service.onResult(ResultNotification(payment.invId, PRICE + 1, "BankCard", null)))
        assertEquals(ResultOutcome.UNKNOWN_INVOICE, service.onResult(ResultNotification(777, PRICE, "BankCard", null)))

        verify(exactly = 0) { paymentRepository.markSucceeded(any(), any(), any(), any()) }
    }

    @Test
    fun `renewal by a new mother payment extends from the later of now and period end and reactivates PAST_DUE`() {
        val live = BillingTestFixtures.subscription(club, status = SubscriptionStatus.PAST_DUE, periodEnd = OffsetDateTime.now().minusDays(2))
        every { subscriptionRepository.findLatestByClub(club.id) } returns live
        val payment = BillingTestFixtures.payment(club, subscriptionId = live.id, invId = 100777, autopayRequested = true)
        every { paymentRepository.findByInvId(payment.invId) } returns payment
        every { paymentRepository.markSucceeded(payment.id, "BankCard", null, any()) } returns 1
        every { subscriptionRepository.findById(live.id) } returns live

        service.onResult(ResultNotification(payment.invId, PRICE, "BankCard", null))

        val newEnd = slot<OffsetDateTime>()
        verify { subscriptionRepository.extendPeriod(live.id, capture(newEnd)) }
        assertClose(OffsetDateTime.now().plusDays(30), newEnd.captured)
        verify { subscriptionRepository.transitionStatus(live.id, listOf(SubscriptionStatus.PAST_DUE), SubscriptionStatus.ACTIVE) }
        verify { subscriptionRepository.markMotherPaid(live.id, "100777", true, true) }
        verify(exactly = 0) { subscriptionRepository.createChatSubscription(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `recurring payment extends from the current period end and resets retries`() {
        val live = BillingTestFixtures.subscription(club, periodEnd = OffsetDateTime.now().plusDays(1), chargeAttempts = 1)
        every { subscriptionRepository.findById(live.id) } returns live
        val payment = BillingTestFixtures.payment(club, kind = PaymentKind.RECURRING, subscriptionId = live.id, invId = 100900)
        every { paymentRepository.findByInvId(payment.invId) } returns payment
        every { paymentRepository.markSucceeded(payment.id, any(), any(), any()) } returns 1

        service.onResult(ResultNotification(payment.invId, PRICE, null, null))

        val newEnd = slot<OffsetDateTime>()
        verify { subscriptionRepository.extendPeriod(live.id, capture(newEnd)) }
        assertClose(live.currentPeriodEnd.plusDays(30), newEnd.captured)
        verify { subscriptionRepository.resetChargeAttempts(live.id) }
        verify { notifier.renewed(club, newEnd.captured) }
        verify(exactly = 0) { subscriptionRepository.markMotherPaid(any(), any(), any(), any()) }
    }

    // ---------- autopay ----------

    @Test
    fun `autopay cannot be enabled after a non-card payment`() {
        every { subscriptionRepository.findLatestByClub(club.id) } returns BillingTestFixtures.subscription(club, autopayPossible = false)

        assertThrows<ConflictException> { service.setAutopay(club.id, club.ownerId, autopay = true) }
        verify(exactly = 0) { subscriptionRepository.updateAutopay(any(), any()) }
    }

    @Test
    fun `autopay toggle updates the live subscription`() {
        val live = BillingTestFixtures.subscription(club)
        every { subscriptionRepository.findLatestByClub(club.id) } returns live

        service.setAutopay(club.id, club.ownerId, autopay = false)

        verify { subscriptionRepository.updateAutopay(live.id, false) }
    }

    // ---------- status ----------

    @Test
    fun `status reflects chat, free meeting, period and grace`() {
        every { clubRoleGuard.requireCapability(club.id, club.ownerId, any()) } returns club

        every { chatLinkRepository.findByClubId(club.id) } returns null
        assertEquals(BillingState.NO_CHAT, service.status(club.id, club.ownerId).state)

        every { chatLinkRepository.findByClubId(club.id) } returns link
        every { freeMeetingRepository.isUsed(link.chatId) } returns false
        assertEquals(BillingState.FREE_MEETING_AVAILABLE, service.status(club.id, club.ownerId).state)
        every { freeMeetingRepository.isUsed(link.chatId) } returns true
        assertEquals(BillingState.FREE_MEETING_USED, service.status(club.id, club.ownerId).state)

        every { subscriptionRepository.findLatestByClub(club.id) } returns BillingTestFixtures.subscription(club, autopay = false)
        val active = service.status(club.id, club.ownerId)
        assertEquals(BillingState.ACTIVE, active.state)
        assertFalse(active.autopay)
        assertEquals(null, active.graceUntil)

        val pastDue = BillingTestFixtures.subscription(club, status = SubscriptionStatus.PAST_DUE, periodEnd = OffsetDateTime.now().minusDays(2))
        every { subscriptionRepository.findLatestByClub(club.id) } returns pastDue
        val grace = service.status(club.id, club.ownerId)
        assertEquals(BillingState.GRACE, grace.state)
        assertEquals(pastDue.currentPeriodEnd.plusDays(7), grace.graceUntil)

        every { subscriptionRepository.findLatestByClub(club.id) } returns
            BillingTestFixtures.subscription(club, status = SubscriptionStatus.ENDED, periodEnd = OffsetDateTime.now().minusDays(20))
        assertEquals(BillingState.ENDED, service.status(club.id, club.ownerId).state)

        every { paymentRepository.findPendingMother(club.id, any()) } returns BillingTestFixtures.payment(club)
        assertTrue(service.status(club.id, club.ownerId).pendingCheckout)
    }
}
