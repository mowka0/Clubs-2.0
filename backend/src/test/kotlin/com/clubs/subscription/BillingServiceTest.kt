package com.clubs.subscription

import com.clubs.chatlink.ChatLinkRepository
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.membership.MembershipRepository
import com.clubs.payment.CheckoutRequest
import com.clubs.payment.CheckoutUrl
import com.clubs.payment.PaymentProvider
import com.clubs.payment.ResultNotification
import com.clubs.reputation.ReputationService
import com.clubs.subscription.BillingTestFixtures.PRICE
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    private val membershipRepository = mockk<MembershipRepository>()
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val reputationService = mockk<ReputationService>(relaxed = true)
    private val chatTrialRepository = mockk<ChatTrialRepository>(relaxed = true)
    private val funnelEventRepository = mockk<FunnelEventRepository>(relaxed = true)
    private val consentRepository = mockk<AutopayConsentRepository>(relaxed = true)
    private val paymentProvider = mockk<PaymentProvider>()
    private val notifier = mockk<BillingNotifier>(relaxed = true)

    private val service = BillingService(
        subscriptionRepository, paymentRepository, chatLinkRepository, clubRepository, membershipRepository, userRepository, reputationService,
        chatTrialRepository, funnelEventRepository, consentRepository, paymentProvider, notifier,
        graceDays = 7, trialDays = 15, periodDays = 30, checkoutReuseMinutes = 30,
        successUrl = "https://app.example/pay/return", failUrl = "https://app.example/pay/fail",
        recipientName = "Варламов Иван Иванович",
    )

    private val club = BillingTestFixtures.club()
    private val link = BillingTestFixtures.link(club)

    @BeforeEach
    fun setUp() {
        every { clubRepository.findById(club.id) } returns club
        every { chatLinkRepository.findByClubId(club.id) } returns link
        every { subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT) } returns PRICE
        every { subscriptionRepository.findLatestByClub(club.id) } returns null
        every { paymentRepository.findPendingMother(club.id, any(), any()) } returns null
        // Владелец — тоже участник (строка членства organizer); не-участника тесты задают явно.
        every { membershipRepository.isActiveMemberInActiveClub(any(), club.id) } returns true
        every { paymentProvider.id } returns "robokassa"
        every { paymentProvider.recurringAvailable } returns true
        every { paymentProvider.createCheckout(any()) } answers { CheckoutUrl("https://rk.example/pay?inv=${firstArg<CheckoutRequest>().invId}") }
    }

    @Test
    fun `provider without recurring - checkout asks no Recurring and a card payment leaves autopay impossible`() {
        every { paymentProvider.recurringAvailable } returns false
        val payment = BillingTestFixtures.payment(club, autopayRequested = true)
        every { paymentRepository.create(club.id, club.ownerId, null, PaymentKind.MOTHER, PRICE, null, true) } returns payment
        every { paymentRepository.findByInvId(payment.invId) } returns payment
        every { paymentRepository.markSucceeded(payment.id, "BankCard", null, any()) } returns 1
        val created = BillingTestFixtures.subscription(club, periodEnd = OffsetDateTime.now().plusDays(30), autopay = true)
        every { subscriptionRepository.createChatSubscription(club.ownerId, club.id, any(), "100001", true, false) } returns created

        service.checkout(club.id, club.ownerId, autopayRequested = true)
        service.onResult(ResultNotification(payment.invId, PRICE, "BankCard", null))

        val request = slot<CheckoutRequest>()
        verify { paymentProvider.createCheckout(capture(request)) }
        assertFalse(request.captured.recurring, "без услуги рекуррента Recurring не просим — иначе ошибка 34 на любую оплату")
        verify { subscriptionRepository.createChatSubscription(club.ownerId, club.id, any(), "100001", autopay = true, autopayPossible = false) }
        verify { notifier.paid(club, created.currentPeriodEnd, autopayOn = false, priceKopecks = PRICE) }
    }

    @Test
    fun `provider without recurring - renewal by a new mother payment drops the saved card flag`() {
        every { paymentProvider.recurringAvailable } returns false
        val live = BillingTestFixtures.subscription(club, periodEnd = OffsetDateTime.now().plusDays(3))
        every { subscriptionRepository.findLatestByClub(club.id) } returns live
        every { subscriptionRepository.findById(live.id) } returns live
        val payment = BillingTestFixtures.payment(club, subscriptionId = live.id, invId = 100778, autopayRequested = true)
        every { paymentRepository.findByInvId(payment.invId) } returns payment
        every { paymentRepository.markSucceeded(payment.id, "BankCard", null, any()) } returns 1

        service.onResult(ResultNotification(payment.invId, PRICE, "BankCard", null))

        // Карта с прошлой подписки больше не считается сохранённой: токен указывает на платёж без Recurring.
        verify { subscriptionRepository.markMotherPaid(live.id, "100778", true, false) }
    }

    private fun assertClose(expected: OffsetDateTime, actual: OffsetDateTime) {
        assertTrue(Duration.between(expected, actual).abs() < Duration.ofMinutes(1), "expected ≈ $expected, got $actual")
    }

    // ---------- checkout ----------

    @Test
    fun `checkout creates a mother invoice with the requested autopay and records the funnel step`() {
        val created = BillingTestFixtures.payment(club, autopayRequested = false)
        every { paymentRepository.create(club.id, club.ownerId, null, PaymentKind.MOTHER, PRICE, null, false) } returns created

        val result = service.checkout(club.id, club.ownerId, autopayRequested = false)

        assertEquals(created.invId, result.invId)
        assertEquals("https://rk.example/pay?inv=${created.invId}", result.paymentUrl)
        verify(exactly = 1) { funnelEventRepository.record(FunnelStep.CHECKOUT_STARTED, club.ownerId, club.id) }
        // История согласий: снятая отметка тоже записывается — видно, что выбрали на этой оплате.
        verify(exactly = 1) {
            consentRepository.record(AutopayConsent(club.id, club.ownerId, ConsentSource.CHECKOUT, granted = false, paymentId = created.id, subscriptionId = null))
        }
        val request = slot<CheckoutRequest>()
        verify { paymentProvider.createCheckout(capture(request)) }
        assertTrue(request.captured.recurring, "карта сохраняется всегда — ползунок решает, списывать ли")
        // Имя бота в адрес возврата не подставляем: страница берёт его из бандла (иначе
        // `?bot=<чужой>` давал бы нашу страницу «Оплата принята» с кнопкой в чужого бота).
        assertEquals("https://app.example/pay/return?club=${club.id}", request.captured.successUrl)
        assertEquals(PRICE, request.captured.amountKopecks)
    }

    @Test
    fun `checkout reuses a fresh pending invoice instead of creating a second one`() {
        val pending = BillingTestFixtures.payment(club)
        every { paymentRepository.findPendingMother(club.id, club.ownerId, any()) } returns pending

        val result = service.checkout(club.id, club.ownerId, autopayRequested = true)

        assertEquals(pending.invId, result.invId)
        verify(exactly = 0) { paymentRepository.create(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { funnelEventRepository.record(any(), any(), any(), any()) }
        // Согласие по отметке пишется на тот же счёт — каждый чекаут оставляет след.
        verify(exactly = 1) {
            consentRepository.record(AutopayConsent(club.id, club.ownerId, ConsentSource.CHECKOUT, granted = true, paymentId = pending.id, subscriptionId = null))
        }
    }

    @Test
    fun `reusing an invoice carries the autopay choice made on the second attempt`() {
        // Ползунок выключили при повторном заходе — счёт тот же, решение владельца новое.
        val pending = BillingTestFixtures.payment(club, autopayRequested = true)
        every { paymentRepository.findPendingMother(club.id, club.ownerId, any()) } returns pending

        service.checkout(club.id, club.ownerId, autopayRequested = false)

        verify(exactly = 1) { paymentRepository.updateAutopayRequested(pending.id, false) }
    }

    @Test
    fun `checkout is for club members only and needs a linked chat`() {
        val stranger = UUID.randomUUID()
        every { membershipRepository.isActiveMemberInActiveClub(stranger, club.id) } returns false
        assertThrows<ForbiddenException> { service.checkout(club.id, stranger, autopayRequested = true) }

        every { chatLinkRepository.findByClubId(club.id) } returns null
        assertThrows<ConflictException> { service.checkout(club.id, club.ownerId, autopayRequested = true) }
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
    fun `payment for a deleted club is recorded but never silently creates a subscription`() {
        val payment = BillingTestFixtures.payment(club)
        every { paymentRepository.findByInvId(payment.invId) } returns payment
        every { clubRepository.findById(club.id) } returns null

        assertEquals(ResultOutcome.UNKNOWN_INVOICE, service.onResult(ResultNotification(payment.invId, PRICE, "BankCard", null)))

        // Деньги пришли — счёт фиксируем (аудит), но подписки у удалённого клуба быть не может.
        verify(exactly = 1) { paymentRepository.markSucceeded(payment.id, "BankCard", null, any()) }
        verify(exactly = 0) { subscriptionRepository.createChatSubscription(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { notifier.paid(any(), any(), any(), any()) }
    }

    @Test
    fun `a recurring payment confirmed after the grace ended revives the subscription`() {
        // Списание подтвердилось позже, чем шедулер закрыл подписку: без перехода ENDED → ACTIVE
        // владелец заплатил бы и всё равно упирался в 402.
        val ended = BillingTestFixtures.subscription(
            club, status = SubscriptionStatus.ENDED, periodEnd = OffsetDateTime.now().minusDays(8),
        )
        every { subscriptionRepository.findById(ended.id) } returns ended
        val payment = BillingTestFixtures.payment(club, kind = PaymentKind.RECURRING, subscriptionId = ended.id, invId = 100901)
        every { paymentRepository.findByInvId(payment.invId) } returns payment
        every { paymentRepository.markSucceeded(payment.id, any(), any(), any()) } returns 1

        service.onResult(ResultNotification(payment.invId, PRICE, "BankCard", null))

        verify {
            subscriptionRepository.transitionStatus(
                ended.id,
                match { it.contains(SubscriptionStatus.ENDED) && it.contains(SubscriptionStatus.PAST_DUE) },
                SubscriptionStatus.ACTIVE,
            )
        }
        val newEnd = slot<OffsetDateTime>()
        verify { subscriptionRepository.extendPeriod(ended.id, capture(newEnd)) }
        assertClose(OffsetDateTime.now().plusDays(30), newEnd.captured)
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
    fun `renewal without the consent mark switches autopay off even if it was on`() {
        // Отметка снята по умолчанию и на продлении: согласие даётся на каждой оплате заново.
        val live = BillingTestFixtures.subscription(club, autopay = true)
        every { subscriptionRepository.findLatestByClub(club.id) } returns live
        every { subscriptionRepository.findById(live.id) } returns live
        val payment = BillingTestFixtures.payment(club, subscriptionId = live.id, invId = 100779, autopayRequested = false)
        every { paymentRepository.findByInvId(payment.invId) } returns payment
        every { paymentRepository.markSucceeded(payment.id, "BankCard", null, any()) } returns 1

        service.onResult(ResultNotification(payment.invId, PRICE, "BankCard", null))

        verify { subscriptionRepository.markMotherPaid(live.id, "100779", false, true) }
        verify { notifier.paid(club, any(), autopayOn = false, priceKopecks = PRICE) }
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
        verify(exactly = 0) { consentRepository.record(any()) }
    }

    @Test
    fun `autopay toggle updates the live subscription and records the consent change`() {
        val live = BillingTestFixtures.subscription(club)
        every { subscriptionRepository.findLatestByClub(club.id) } returns live

        service.setAutopay(club.id, club.ownerId, autopay = false)
        service.setAutopay(club.id, club.ownerId, autopay = true)

        verify { subscriptionRepository.updateAutopay(live.id, false) }
        // Выключил = отзыв согласия, включил = согласие: обе записи в истории.
        verify { consentRepository.record(AutopayConsent(club.id, club.ownerId, ConsentSource.TOGGLE, granted = false, subscriptionId = live.id)) }
        verify { consentRepository.record(AutopayConsent(club.id, club.ownerId, ConsentSource.TOGGLE, granted = true, subscriptionId = live.id)) }
    }

    @Test
    fun `autopay cannot be enabled while the provider has no recurring, even with a saved card`() {
        every { paymentProvider.recurringAvailable } returns false
        every { subscriptionRepository.findLatestByClub(club.id) } returns BillingTestFixtures.subscription(club, autopayPossible = true)

        assertThrows<ConflictException> { service.setAutopay(club.id, club.ownerId, autopay = true) }
        verify(exactly = 0) { subscriptionRepository.updateAutopay(any(), any()) }
    }

    // ---------- status ----------

    @Test
    fun `status reflects chat, free meeting, period and grace`() {

        every { chatLinkRepository.findByClubId(club.id) } returns null
        val noChat = service.status(club.id, club.ownerId)
        assertEquals(BillingState.NO_CHAT, noChat.state)
        assertEquals("Варламов Иван Иванович", noChat.recipientName)

        every { chatLinkRepository.findByClubId(club.id) } returns link
        every { chatTrialRepository.findStartedAt(link.chatId) } returns null
        assertEquals(BillingState.TRIAL_NOT_STARTED, service.status(club.id, club.ownerId).state)

        every { chatTrialRepository.findStartedAt(link.chatId) } returns OffsetDateTime.now().minusDays(2)
        val trial = service.status(club.id, club.ownerId)
        assertEquals(BillingState.TRIAL, trial.state)
        assertEquals(15, trial.trialDays)
        assertNotNull(trial.trialUntil, "полоска показывает, до какого числа бесплатно")

        every { chatTrialRepository.findStartedAt(link.chatId) } returns OffsetDateTime.now().minusDays(16)
        val ended = service.status(club.id, club.ownerId)
        assertEquals(BillingState.TRIAL_ENDED, ended.state)
        assertNull(ended.trialUntil, "период кончился — дату больше не показываем")

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

        every { paymentRepository.hasPendingMother(club.id, club.ownerId) } returns true
        assertTrue(service.status(club.id, club.ownerId).pendingCheckout)
    }

    @Test
    fun `after the period end the strip keeps ACTIVE while the auto-renewal is ahead or in flight`() {
        // Иначе «Подписка закончилась… Продлить» толкала бы владельца с сохранённой картой платить
        // вручную второй раз (bugfix 2026-10-07).
        every { chatLinkRepository.findByClubId(club.id) } returns link
        every { paymentProvider.recurringAvailable } returns true
        val renewing = BillingTestFixtures.subscription(club, periodEnd = OffsetDateTime.now().minusHours(2))
        fun stateOf(subscription: ServiceSubscription): BillingState {
            every { subscriptionRepository.findLatestByClub(club.id) } returns subscription
            return service.status(club.id, club.ownerId).state
        }

        every { subscriptionRepository.findLatestByClub(club.id) } returns renewing
        val awaiting = service.status(club.id, club.ownerId)
        assertEquals(BillingState.ACTIVE, awaiting.state)
        assertNull(awaiting.graceUntil)

        assertEquals(BillingState.GRACE, stateOf(renewing.copy(autopay = false)), "без автопродления платить вручную")
        assertEquals(BillingState.GRACE, stateOf(renewing.copy(autopayPossible = false)), "карта не сохранена")
        assertEquals(BillingState.GRACE, stateOf(renewing.copy(status = SubscriptionStatus.PAST_DUE)), "списание не прошло")
        every { paymentProvider.recurringAvailable } returns false
        assertEquals(BillingState.GRACE, stateOf(renewing), "рекуррент магазину не разрешён — шедулер не спишет")

        // Ползунок выключили, когда дочернее списание уже ушло провайдеру: деньги в пути — не «Продлить».
        every { paymentRepository.hasPendingRecurring(renewing.id) } returns true
        assertEquals(BillingState.ACTIVE, stateOf(renewing.copy(autopay = false)))
    }

    @Test
    fun `a charge failed on the morning of the last day shows Renew before the period is over`() {
        // Слот 0 — утро дня окончания: владельцу уже пришло «не удалось списать», полоска не должна
        // обещать «спишем с карты» до вечера.
        every { chatLinkRepository.findByClubId(club.id) } returns link
        every { paymentProvider.recurringAvailable } returns true
        every { subscriptionRepository.findLatestByClub(club.id) } returns
            BillingTestFixtures.subscription(club, status = SubscriptionStatus.PAST_DUE, periodEnd = OffsetDateTime.now().plusHours(10))

        val status = service.status(club.id, club.ownerId)

        assertEquals(BillingState.GRACE, status.state)
        assertNotNull(status.graceUntil)
    }

    @Test
    fun `status shows the pause when the bot was kicked, keeping the paid period visible`() {
        every { chatLinkRepository.findByClubId(club.id) } returns
            BillingTestFixtures.link(club, botStatus = com.clubs.chatlink.BotChatStatus.KICKED)
        val sub = BillingTestFixtures.subscription(club)
        every { subscriptionRepository.findLatestByClub(club.id) } returns sub

        val status = service.status(club.id, club.ownerId)

        assertEquals(BillingState.BOT_REMOVED, status.state)
        assertEquals(sub.currentPeriodEnd, status.currentPeriodEnd, "оплаченный период не прячем")
    }

    @Test
    fun `only the owner is offered the autopay consent, members pay once`() {
        every { chatTrialRepository.findStartedAt(link.chatId) } returns OffsetDateTime.now().minusDays(16)

        assertTrue(service.status(club.id, club.ownerId).canEnableAutopay)
        assertFalse(service.status(club.id, UUID.randomUUID()).canEnableAutopay, "участник платит разово, карту не сохраняем")
    }

    @Test
    fun `status is for club members only`() {
        val stranger = UUID.randomUUID()
        every { membershipRepository.isActiveMemberInActiveClub(stranger, club.id) } returns false
        assertThrows<ForbiddenException> { service.status(club.id, stranger) }
    }

    @Test
    fun `payment is due a week before the end of the free or paid period, and whenever the club is unpaid`() {
        fun dueWith(trialStartedDaysAgo: Long?, subscription: ServiceSubscription?): Boolean {
            every { chatTrialRepository.findStartedAt(link.chatId) } returns trialStartedDaysAgo?.let { OffsetDateTime.now().minusDays(it) }
            every { subscriptionRepository.findLatestByClub(club.id) } returns subscription
            return service.status(club.id, club.ownerId).paymentDue
        }

        assertFalse(dueWith(null, null), "период не начат — плашка только в «Управлении»")
        assertFalse(dueWith(2, null), "до конца бесплатного периода 13 дней")
        assertFalse(dueWith(7, null), "граница: 8 дней — ещё рано")
        assertTrue(dueWith(8, null), "граница: 7 дней — пора")
        assertTrue(dueWith(9, null), "до конца бесплатного периода 6 дней")
        assertTrue(dueWith(16, null), "бесплатный период кончился, не оплачено")
        assertFalse(dueWith(16, BillingTestFixtures.subscription(club, periodEnd = OffsetDateTime.now().plusDays(20))), "оплачено надолго")
        assertTrue(dueWith(16, BillingTestFixtures.subscription(club, periodEnd = OffsetDateTime.now().plusDays(5))), "до конца оплаченного 5 дней")
        assertTrue(
            dueWith(16, BillingTestFixtures.subscription(club, status = SubscriptionStatus.PAST_DUE, periodEnd = OffsetDateTime.now().minusDays(2))),
            "грейс",
        )
    }

    @Test
    fun `payment is not due while the owner's auto charge is in flight — a member would pay twice`() {
        val live = BillingTestFixtures.subscription(club, periodEnd = OffsetDateTime.now().plusHours(5))
        every { chatTrialRepository.findStartedAt(link.chatId) } returns OffsetDateTime.now().minusDays(16)
        every { subscriptionRepository.findLatestByClub(club.id) } returns live
        assertTrue(service.status(club.id, club.ownerId).paymentDue)

        every { paymentRepository.hasPendingRecurring(live.id) } returns true
        assertFalse(service.status(club.id, club.ownerId).paymentDue)
    }

    @Test
    fun `the last payer is named while the subscription is alive`() {
        val memberId = UUID.randomUUID()
        every { chatTrialRepository.findStartedAt(link.chatId) } returns OffsetDateTime.now().minusDays(16)
        every { paymentRepository.findLastSucceeded(club.id) } returns
            BillingTestFixtures.payment(club, status = PlatformPaymentStatus.SUCCEEDED, payerUserId = memberId)
        every { userRepository.findById(memberId) } returns UsersRecord(id = memberId, telegramId = 7L, firstName = "Маша", lastName = "Петрова")

        assertNull(service.status(club.id, club.ownerId).lastPayer, "подписки нет — и «крайнего» нет")

        every { subscriptionRepository.findLatestByClub(club.id) } returns BillingTestFixtures.subscription(club)
        val payer = service.status(club.id, club.ownerId).lastPayer
        assertEquals(memberId, payer?.userId)
        assertEquals("Маша Петрова", payer?.name)
    }

    // ---------- оплата участником (billing-member-pays.md) ----------

    @Test
    fun `a member checkout never asks for autopay or a saved card, whatever the request says`() {
        val memberId = UUID.randomUUID()
        val created = BillingTestFixtures.payment(club, autopayRequested = false, payerUserId = memberId)
        every { paymentRepository.create(club.id, memberId, null, PaymentKind.MOTHER, PRICE, null, false) } returns created

        service.checkout(club.id, memberId, autopayRequested = true)

        val request = slot<CheckoutRequest>()
        verify { paymentProvider.createCheckout(capture(request)) }
        assertFalse(request.captured.recurring, "карту участника не сохраняем")
        verify { paymentRepository.create(club.id, memberId, null, PaymentKind.MOTHER, PRICE, null, false) }
        verify { paymentRepository.findPendingMother(club.id, memberId, any()) }
    }

    @Test
    fun `a member payment shifts the period and keeps the owner's card, autopay and token`() {
        val memberId = UUID.randomUUID()
        val live = BillingTestFixtures.subscription(club, status = SubscriptionStatus.PAST_DUE, periodEnd = OffsetDateTime.now().minusDays(1), chargeAttempts = 1)
        every { subscriptionRepository.findLatestByClub(club.id) } returns live
        every { subscriptionRepository.findById(live.id) } returns live
        val payment = BillingTestFixtures.payment(club, subscriptionId = live.id, invId = 100900, autopayRequested = false, payerUserId = memberId)
        every { paymentRepository.findByInvId(payment.invId) } returns payment
        every { paymentRepository.markSucceeded(payment.id, "SBP", null, any()) } returns 1

        service.onResult(ResultNotification(payment.invId, PRICE, "SBP", null))

        val newEnd = slot<OffsetDateTime>()
        verify { subscriptionRepository.extendPeriod(live.id, capture(newEnd)) }
        assertClose(OffsetDateTime.now().plusDays(30), newEnd.captured)
        verify { subscriptionRepository.transitionStatus(live.id, listOf(SubscriptionStatus.PAST_DUE), SubscriptionStatus.ACTIVE) }
        verify { subscriptionRepository.resetChargeAttempts(live.id) }
        verify(exactly = 0) { subscriptionRepository.markMotherPaid(any(), any(), any(), any()) }
        verify { reputationService.rewardClubBillingPayment(memberId, club.id, payment.id, any(), any()) }
        verify { notifier.paidByMember(club, memberId, any(), ownerAutopayOn = true) }
        verify(exactly = 0) { notifier.paid(any(), any(), any(), any()) }
    }

    @Test
    fun `a member paying ahead adds the month to the paid period, records no consent, and a former member earns nothing`() {
        val memberId = UUID.randomUUID()
        every { membershipRepository.isActiveMemberInActiveClub(memberId, club.id) } returns false
        val end = OffsetDateTime.now().plusDays(5)
        val live = BillingTestFixtures.subscription(club, periodEnd = end)
        every { subscriptionRepository.findLatestByClub(club.id) } returns live
        every { subscriptionRepository.findById(live.id) } returns live
        val payment = BillingTestFixtures.payment(club, subscriptionId = live.id, invId = 100901, autopayRequested = false, payerUserId = memberId)
        every { paymentRepository.findByInvId(payment.invId) } returns payment
        every { paymentRepository.markSucceeded(payment.id, "BankCard", null, any()) } returns 1

        service.onResult(ResultNotification(payment.invId, PRICE, "BankCard", null))

        verify { subscriptionRepository.extendPeriod(live.id, end.plusDays(30)) }
        verify(exactly = 0) { reputationService.rewardClubBillingPayment(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a member checkout leaves no autopay consent record — the member never saw the mark`() {
        val memberId = UUID.randomUUID()
        every { paymentRepository.create(club.id, memberId, null, PaymentKind.MOTHER, PRICE, null, false) } returns
            BillingTestFixtures.payment(club, autopayRequested = false, payerUserId = memberId)

        service.checkout(club.id, memberId, autopayRequested = false)

        verify(exactly = 0) { consentRepository.record(any()) }
    }

    @Test
    fun `the first payment made by a member creates the owner's subscription without a card`() {
        val memberId = UUID.randomUUID()
        val payment = BillingTestFixtures.payment(club, autopayRequested = false, payerUserId = memberId)
        every { paymentRepository.findByInvId(payment.invId) } returns payment
        every { paymentRepository.markSucceeded(payment.id, "BankCard", null, any()) } returns 1
        val created = BillingTestFixtures.subscription(club, autopay = false, autopayPossible = false, providerToken = null)
        every { subscriptionRepository.createChatSubscription(club.ownerId, club.id, any(), null, false, false) } returns created

        service.onResult(ResultNotification(payment.invId, PRICE, "BankCard", null))

        verify { subscriptionRepository.createChatSubscription(club.ownerId, club.id, any(), null, autopay = false, autopayPossible = false) }
        verify { notifier.paidByMember(club, memberId, created.currentPeriodEnd, ownerAutopayOn = false) }
    }

    @Test
    fun `the owner's own payment earns no reliability`() {
        val payment = BillingTestFixtures.payment(club, autopayRequested = false)
        every { paymentRepository.findByInvId(payment.invId) } returns payment
        every { paymentRepository.markSucceeded(payment.id, "BankCard", null, any()) } returns 1
        every { subscriptionRepository.createChatSubscription(any(), any(), any(), any(), any(), any()) } returns BillingTestFixtures.subscription(club)

        service.onResult(ResultNotification(payment.invId, PRICE, "BankCard", null))

        verify(exactly = 0) { reputationService.rewardClubBillingPayment(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { notifier.paidByMember(any(), any(), any(), any()) }
    }

    @Test
    fun `status tells the sheet whether autopay is available at all`() {
        every { chatLinkRepository.findByClubId(club.id) } returns link
        every { chatTrialRepository.findStartedAt(link.chatId) } returns OffsetDateTime.now().minusDays(16)

        assertTrue(service.status(club.id, club.ownerId).autopayAvailable)

        every { paymentProvider.recurringAvailable } returns false
        assertFalse(service.status(club.id, club.ownerId).autopayAvailable, "рекуррент магазину не разрешён — шит не обещает списания")
        every { chatLinkRepository.findByClubId(club.id) } returns null
        assertFalse(service.status(club.id, club.ownerId).autopayAvailable, "флаг отдаётся и клубу без чата")
    }
}
