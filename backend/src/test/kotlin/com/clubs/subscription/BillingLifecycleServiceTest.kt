package com.clubs.subscription

import com.clubs.chatlink.ChatLinkRepository
import com.clubs.club.ClubRepository
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import com.clubs.payment.ChargeAccepted
import com.clubs.payment.PaymentProvider
import com.clubs.payment.PaymentState
import com.clubs.payment.PaymentStateResult
import com.clubs.payment.RecurringChargeRequest
import com.clubs.payment.ResultNotification
import com.clubs.subscription.BillingTestFixtures.PRICE
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/** Календарь подписки (platform-billing.md § 6.5): напоминания, списания по слотам, PAST_DUE, ENDED, опрос. */
class BillingLifecycleServiceTest {

    private val subscriptionRepository = mockk<SubscriptionRepository>(relaxed = true)
    private val paymentRepository = mockk<PlatformPaymentRepository>(relaxed = true)
    private val chatLinkRepository = mockk<ChatLinkRepository>()
    private val clubRepository = mockk<ClubRepository>()
    private val funnelEventRepository = mockk<FunnelEventRepository>(relaxed = true)
    private val paymentProvider = mockk<PaymentProvider>(relaxed = true)
    private val billingService = mockk<BillingService>(relaxed = true)
    private val notifier = mockk<BillingNotifier>(relaxed = true)
    private val chatTrialRepository = mockk<ChatTrialRepository>(relaxed = true)

    private val service = BillingLifecycleService(
        subscriptionRepository, paymentRepository, chatLinkRepository, clubRepository, funnelEventRepository,
        chatTrialRepository, paymentProvider, billingService, notifier,
        graceDays = 7, trialDays = 15, periodDays = 30, retryDays = listOf(0, 1, 3),
        pendingTimeoutHours = 6, motherExpireHours = 24,
    )

    private val club = BillingTestFixtures.club()
    private val now: OffsetDateTime = OffsetDateTime.now()

    @BeforeEach
    fun setUp() {
        every { clubRepository.findById(club.id) } returns club
        every { chatLinkRepository.findByClubId(club.id) } returns BillingTestFixtures.link(club)
        every { subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT) } returns PRICE
        every { subscriptionRepository.recordEventIfNew(any(), any(), any()) } returns true
        every { paymentRepository.hasPendingRecurring(any()) } returns false
    }

    private fun live(vararg subscriptions: ServiceSubscription) {
        every { subscriptionRepository.findLive() } returns subscriptions.toList()
    }

    /** Бесплатный период чата, начатый [daysAgo] дней назад, с уже отправленным порогом [reminded]. */
    private fun trial(daysAgo: Long, reminded: Int? = null) {
        every { chatTrialRepository.findTrialsEndingBefore(any(), any()) } returns listOf(
            ChatTrial(chatId = -1001L, clubId = club.id, startedAt = now.minusDays(daysAgo), reminderDaysLeft = reminded),
        )
    }

    // ---------- напоминания ----------

    @Test
    fun `without autopay the owner is reminded 3 days and 1 day before the end, once each`() {
        val sub = BillingTestFixtures.subscription(club, periodEnd = now.plusDays(2).plusHours(1), autopay = false)
        live(sub)

        service.runDaily(now)
        verify(exactly = 1) { notifier.expiringSoon(club, sub.currentPeriodEnd, PRICE, 3) }

        every { subscriptionRepository.recordEventIfNew(any(), any(), any()) } returns false // тот же день, повторный тик
        service.runDaily(now)
        verify(exactly = 1) { notifier.expiringSoon(any(), any(), any(), any()) }

        every { subscriptionRepository.recordEventIfNew(any(), any(), any()) } returns true
        service.runDaily(now.plusDays(2))
        verify(exactly = 1) { notifier.expiringSoon(club, sub.currentPeriodEnd, PRICE, 1) }
    }

    @Test
    fun `with autopay there are no reminders before the charge`() {
        // Дата списания названа в DM об оплате; отдельное «завтра спишем» PO снял (2026-09-07).
        val sub = BillingTestFixtures.subscription(club, periodEnd = now.plusDays(2).plusHours(1))
        live(sub)

        service.runDaily(now)
        service.runDaily(now.plusDays(2))

        verify(exactly = 0) { notifier.expiringSoon(any(), any(), any(), any()) }
        verify(exactly = 0) { subscriptionRepository.recordEventIfNew(any(), any(), any()) }
    }

    // ---------- конец периода ----------

    @Test
    fun `period end with autopay sends a recurring charge from the mother invoice`() {
        val sub = BillingTestFixtures.subscription(club, periodEnd = now.minusHours(1), providerToken = "100001")
        live(sub)
        val payment = BillingTestFixtures.payment(club, kind = PaymentKind.RECURRING, subscriptionId = sub.id, invId = 100500)
        every { paymentRepository.create(club.id, sub.id, PaymentKind.RECURRING, PRICE, 100001, true) } returns payment
        every { paymentProvider.charge(any()) } returns ChargeAccepted(true)

        service.runDaily(now)

        val request = slot<RecurringChargeRequest>()
        verify { paymentProvider.charge(capture(request)) }
        assertEquals(100500L, request.captured.invId)
        assertEquals(100001L, request.captured.previousInvId)
        verify { subscriptionRepository.recordChargeAttempt(sub.id, now) }
        verify(exactly = 0) { paymentRepository.markFailed(any()) }
        verify(exactly = 0) { subscriptionRepository.transitionStatus(any(), any(), SubscriptionStatus.PAST_DUE) }
    }

    @Test
    fun `retry slots are honoured and a pending charge blocks a second one`() {
        val sub = BillingTestFixtures.subscription(club, periodEnd = now.minusHours(1), chargeAttempts = 1)
        live(sub)

        service.runDaily(now) // слот +1d ещё не наступил
        verify(exactly = 0) { paymentProvider.charge(any()) }

        every { paymentRepository.hasPendingRecurring(sub.id) } returns true
        service.runDaily(now.plusDays(1)) // слот наступил, но предыдущее списание без ответа
        verify(exactly = 0) { paymentProvider.charge(any()) }

        every { paymentRepository.hasPendingRecurring(sub.id) } returns false
        every { paymentRepository.create(any(), any(), any(), any(), any(), any()) } returns
            BillingTestFixtures.payment(club, kind = PaymentKind.RECURRING, subscriptionId = sub.id)
        every { paymentProvider.charge(any()) } returns ChargeAccepted(true)
        service.runDaily(now.plusDays(1))
        verify(exactly = 1) { paymentProvider.charge(any()) }

        live(sub.copy(chargeAttempts = 3))
        service.runDaily(now.plusDays(5)) // попыток больше нет — ждём грейса
        verify(exactly = 1) { paymentProvider.charge(any()) }
    }

    @Test
    fun `rejected charge moves ACTIVE to PAST_DUE and tells the owner once`() {
        val sub = BillingTestFixtures.subscription(club, periodEnd = now.minusHours(1))
        live(sub)
        val payment = BillingTestFixtures.payment(club, kind = PaymentKind.RECURRING, subscriptionId = sub.id)
        every { paymentRepository.create(any(), any(), any(), any(), any(), any()) } returns payment
        every { paymentProvider.charge(any()) } returns ChargeAccepted(false, "card expired")
        every { subscriptionRepository.findById(sub.id) } returns sub
        every { subscriptionRepository.transitionStatus(sub.id, listOf(SubscriptionStatus.ACTIVE), SubscriptionStatus.PAST_DUE) } returns 1

        service.runDaily(now)

        verify { paymentRepository.markFailed(payment.id) }
        verify { notifier.chargeFailed(club, PRICE, sub.currentPeriodEnd.plusDays(7)) }
    }

    @Test
    fun `period end without autopay moves to PAST_DUE with a grace notice`() {
        val sub = BillingTestFixtures.subscription(club, periodEnd = now.minusHours(1), autopay = false)
        live(sub)

        service.runDaily(now)

        verify { subscriptionRepository.transitionStatus(sub.id, listOf(SubscriptionStatus.ACTIVE), SubscriptionStatus.PAST_DUE) }
        verify { notifier.periodEnded(club, sub.currentPeriodEnd.plusDays(7)) }
        verify(exactly = 0) { paymentProvider.charge(any()) }
    }

    @Test
    fun `grace exhausted ends the subscription, records the funnel step and tells the owner`() {
        val sub = BillingTestFixtures.subscription(club, status = SubscriptionStatus.PAST_DUE, periodEnd = now.minusDays(7).minusHours(1))
        live(sub)
        every { subscriptionRepository.transitionStatus(sub.id, any(), SubscriptionStatus.ENDED) } returns 1

        service.runDaily(now)

        verify { funnelEventRepository.record(FunnelStep.SUBSCRIPTION_ENDED, club.ownerId, club.id) }
        verify { notifier.graceExhausted(club) }
        verify(exactly = 0) { paymentProvider.charge(any()) }
    }

    @Test
    fun `a club without a chat lives out the period quietly and ends silently`() {
        every { chatLinkRepository.findByClubId(club.id) } returns null
        live(BillingTestFixtures.subscription(club, periodEnd = now.minusDays(1)))
        service.runDaily(now)
        verify(exactly = 0) { paymentProvider.charge(any()) }
        verify(exactly = 0) { subscriptionRepository.transitionStatus(any(), any(), any()) }

        val ended = BillingTestFixtures.subscription(club, periodEnd = now.minusDays(8))
        live(ended)
        every { subscriptionRepository.transitionStatus(ended.id, any(), SubscriptionStatus.ENDED) } returns 1
        service.runDaily(now)
        verify(exactly = 0) { notifier.graceExhausted(any()) }
        verify(exactly = 0) { funnelEventRepository.record(any(), any(), any(), any()) }
    }

    // ---------- опрос счетов без ответа ----------

    @Test
    fun `reconcile applies succeeded charges, fails rejected ones and closes abandoned checkouts`() {
        val sub = BillingTestFixtures.subscription(club)
        val succeeded = BillingTestFixtures.payment(club, kind = PaymentKind.RECURRING, subscriptionId = sub.id, invId = 1, createdAt = now.minusHours(7))
        val failed = BillingTestFixtures.payment(club, kind = PaymentKind.RECURRING, subscriptionId = sub.id, invId = 2, createdAt = now.minusHours(7))
        val abandoned = BillingTestFixtures.payment(club, invId = 3, createdAt = now.minusHours(30))
        val young = BillingTestFixtures.payment(club, invId = 4, createdAt = now.minusHours(8))
        every { paymentRepository.findPendingCreatedBefore(now.minusHours(6)) } returns listOf(succeeded, failed, abandoned, young)
        every { paymentProvider.queryState(1) } returns PaymentStateResult(PaymentState.SUCCEEDED, "BankCard")
        every { paymentProvider.queryState(2) } returns PaymentStateResult(PaymentState.FAILED)
        every { paymentProvider.queryState(3) } returns PaymentStateResult(PaymentState.PENDING)
        every { paymentProvider.queryState(4) } returns PaymentStateResult(PaymentState.PENDING)
        every { subscriptionRepository.findById(sub.id) } returns sub
        every { subscriptionRepository.transitionStatus(sub.id, listOf(SubscriptionStatus.ACTIVE), SubscriptionStatus.PAST_DUE) } returns 1

        service.reconcilePending(now)

        verify { billingService.onResult(ResultNotification(1, PRICE, "BankCard", null)) }
        verify { paymentRepository.markFailed(failed.id) }
        verify { notifier.chargeFailed(club, PRICE, sub.currentPeriodEnd.plusDays(7)) }
        verify { paymentRepository.markFailed(abandoned.id) }
        verify(exactly = 0) { paymentRepository.markFailed(young.id) }
    }

    @Test
    fun `a missing club is ended after grace without notifications`() {
        val orphanClubId = UUID.randomUUID()
        val sub = BillingTestFixtures.subscription(club).copy(subjectClubId = orphanClubId, currentPeriodEnd = now.minusDays(9))
        every { clubRepository.findById(orphanClubId) } returns null
        every { chatLinkRepository.findByClubId(orphanClubId) } returns null
        live(sub)
        every { subscriptionRepository.transitionStatus(sub.id, any(), SubscriptionStatus.ENDED) } returns 1

        service.runDaily(now)

        verify(exactly = 0) { notifier.graceExhausted(any()) }
    }

    // ---------- конец бесплатного периода ----------

    @Test
    fun `trial end is announced a week before and a day before, once each`() {
        live()
        // 15-дневный период, начатый 9 дней назад: до конца 6 дней — порог «неделя».
        trial(daysAgo = 9)

        service.runDaily(now)

        verify(exactly = 1) { notifier.trialEndingSoon(club, now.minusDays(9).plusDays(15), PRICE, 7) }
        verify(exactly = 1) { chatTrialRepository.markReminded(-1001L, 7) }

        // Порог уже отмечен — повторный тик молчит.
        trial(daysAgo = 9, reminded = 7)
        service.runDaily(now)
        verify(exactly = 1) { notifier.trialEndingSoon(any(), any(), any(), 7) }

        // За день до конца уходит второе, последнее напоминание.
        trial(daysAgo = 14, reminded = 7)
        service.runDaily(now)
        verify(exactly = 1) { notifier.trialEndingSoon(club, now.minusDays(14).plusDays(15), PRICE, 1) }
        verify(exactly = 1) { chatTrialRepository.markReminded(-1001L, 1) }
    }

    @Test
    fun `a trial that already ended is not announced — the wall speaks for itself`() {
        live()
        trial(daysAgo = 16)

        service.runDaily(now)

        verify(exactly = 0) { notifier.trialEndingSoon(any(), any(), any(), any()) }
        verify(exactly = 0) { chatTrialRepository.markReminded(any(), any()) }
    }
}
