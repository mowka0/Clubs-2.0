package com.clubs.subscription

import com.clubs.chatlink.BotChatStatus
import com.clubs.chatlink.ChatLink
import com.clubs.chatlink.ChatLinkRepository
import com.clubs.club.Club
import com.clubs.common.exception.PaymentRequiredException
import com.clubs.common.exception.PaywallReason
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.SubscriptionPayerRole
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Гейт биллинга за чат (platform-billing.md § 6.4): клуб без чата бесплатен, оплаченный период
 * и грейс пропускают, бесплатный период чата идёт от первой встречи, стена — с причиной для шита.
 */
class BillingGateTest {

    private val chatLinkRepository = mockk<ChatLinkRepository>()
    private val subscriptionRepository = mockk<SubscriptionRepository>()
    private val chatTrialRepository = mockk<ChatTrialRepository>()
    private val funnelEventRepository = mockk<FunnelEventRepository>(relaxed = true)
    private val gate = BillingGate(
        chatLinkRepository, subscriptionRepository, chatTrialRepository, funnelEventRepository,
        graceDays = 7, trialDays = 15,
    )

    private val clubId: UUID = UUID.randomUUID()
    private val ownerId: UUID = UUID.randomUUID()
    private val eventId: UUID = UUID.randomUUID()
    private val chatId = -1001234567890L
    private val club = club()

    private fun club(): Club {
        val now = OffsetDateTime.now()
        return Club(
            id = clubId, ownerId = ownerId, name = "Клуб", description = "", category = ClubCategory.sport,
            accessType = AccessType.`open`, city = "Moscow", district = null, memberLimit = 30,
            subscriptionPrice = 0, avatarUrl = null, rules = null, applicationQuestion = null, inviteLink = null,
            memberCount = 1, isActive = true, paymentLink = null, paymentMethodNote = null,
            createdAt = now, updatedAt = now,
        )
    }

    private fun link() = ChatLink(
        clubId = clubId, chatId = chatId, chatTitle = "Чат", linkedByUserId = ownerId,
        linkedAt = OffsetDateTime.now(), botStatus = BotChatStatus.ADMINISTRATOR,
        canPinMessages = true, canInviteUsers = true, canRestrictMembers = true, canManageTags = true,
        doorEnabled = false, doorInviteLink = null, livePinEnabled = true, skladchinaStatusEnabled = true,
        strictModeEnabled = false, awardTagsEnabled = false,
    )

    private fun subscription(status: SubscriptionStatus, periodEnd: OffsetDateTime) = ServiceSubscription(
        id = UUID.randomUUID(), payerUserId = ownerId, payerRole = SubscriptionPayerRole.ORGANIZER,
        plan = SubscriptionPlan.CHAT, subjectClubId = clubId, status = status, currentPeriodEnd = periodEnd,
        providerToken = "100001", createdAt = OffsetDateTime.now(), updatedAt = OffsetDateTime.now(),
    )

    /** Бесплатный период чата, начатый [daysAgo] дней назад; justStarted — первая ли это встреча. */
    private fun trial(daysAgo: Long, justStarted: Boolean = false) {
        every { chatTrialRepository.startOrGet(chatId, clubId, eventId) } returns
            TrialStart(OffsetDateTime.now().minusDays(daysAgo), justStarted)
    }

    private fun linked() {
        every { chatLinkRepository.findByClubId(clubId) } returns link()
        every { subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT) } returns 19900
    }

    @Test
    fun `club without a chat is never billed (R2)`() {
        every { chatLinkRepository.findByClubId(clubId) } returns null

        gate.requireBillable(club, eventId, ownerId)

        verify(exactly = 0) { subscriptionRepository.findLatestByClub(any()) }
        verify(exactly = 0) { chatTrialRepository.startOrGet(any(), any(), any()) }
    }

    @Test
    fun `first meeting of a chat starts the trial and passes`() {
        linked()
        every { subscriptionRepository.findLatestByClub(clubId) } returns null
        trial(daysAgo = 0, justStarted = true)

        gate.requireBillable(club, eventId, ownerId)

        verify(exactly = 1) { funnelEventRepository.record(FunnelStep.TRIAL_STARTED, ownerId, clubId) }
    }

    @Test
    fun `meeting inside the trial window passes without a second funnel step`() {
        linked()
        every { subscriptionRepository.findLatestByClub(clubId) } returns null
        trial(daysAgo = 14)

        gate.requireBillable(club, eventId, ownerId)

        verify(exactly = 0) { funnelEventRepository.record(FunnelStep.TRIAL_STARTED, any(), any()) }
    }

    @Test
    fun `meeting after the trial without a subscription hits the paywall with TRIAL_ENDED`() {
        linked()
        every { subscriptionRepository.findLatestByClub(clubId) } returns null
        trial(daysAgo = 16)

        val ex = assertThrows<PaymentRequiredException> { gate.requireBillable(club, eventId, ownerId) }

        assertEquals(PaywallReason.TRIAL_ENDED, ex.reason)
        assertEquals(clubId, ex.clubId)
        assertEquals(19900, ex.priceKopecks)
        // Пейволл откатывает транзакцию создания — шаг воронки пишется отдельной.
        verify(exactly = 1) { funnelEventRepository.recordDetached(FunnelStep.PAYWALL_SEEN, ownerId, clubId) }
    }

    @Test
    fun `active subscription passes without touching the trial`() {
        linked()
        every { subscriptionRepository.findLatestByClub(clubId) } returns
            subscription(SubscriptionStatus.ACTIVE, OffsetDateTime.now().plusDays(20))

        gate.requireBillable(club, eventId, ownerId)

        verify(exactly = 0) { chatTrialRepository.startOrGet(any(), any(), any()) }
    }

    @Test
    fun `PAST_DUE inside the grace window passes (R10)`() {
        linked()
        every { subscriptionRepository.findLatestByClub(clubId) } returns
            subscription(SubscriptionStatus.PAST_DUE, OffsetDateTime.now().minusDays(6))

        gate.requireBillable(club, eventId, ownerId)

        verify(exactly = 0) { chatTrialRepository.startOrGet(any(), any(), any()) }
    }

    @Test
    fun `PAST_DUE past the grace window hits the paywall with SUBSCRIPTION_EXPIRED`() {
        linked()
        every { subscriptionRepository.findLatestByClub(clubId) } returns
            subscription(SubscriptionStatus.PAST_DUE, OffsetDateTime.now().minusDays(8))
        trial(daysAgo = 40)

        val ex = assertThrows<PaymentRequiredException> { gate.requireBillable(club, eventId, ownerId) }

        assertEquals(PaywallReason.SUBSCRIPTION_EXPIRED, ex.reason)
    }

    @Test
    fun `ENDED subscription with a trial still running lets the meeting through`() {
        // Крайний случай: подписку успели закончить, а бесплатный период чата ещё идёт —
        // человеку это видно как «бесплатно до …», и стены быть не должно.
        linked()
        every { subscriptionRepository.findLatestByClub(clubId) } returns
            subscription(SubscriptionStatus.ENDED, OffsetDateTime.now().minusDays(30))
        trial(daysAgo = 3)

        gate.requireBillable(club, eventId, ownerId)

        verify(exactly = 0) { funnelEventRepository.recordDetached(FunnelStep.PAYWALL_SEEN, any(), any()) }
    }
}
