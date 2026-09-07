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
 * и грейс пропускают, бесплатная встреча берётся атомарно, стена — с причиной для шита.
 */
class BillingGateTest {

    private val chatLinkRepository = mockk<ChatLinkRepository>()
    private val subscriptionRepository = mockk<SubscriptionRepository>()
    private val freeMeetingRepository = mockk<FreeMeetingRepository>()
    private val funnelEventRepository = mockk<FunnelEventRepository>(relaxed = true)
    private val gate = BillingGate(
        chatLinkRepository, subscriptionRepository, freeMeetingRepository, funnelEventRepository, graceDays = 7,
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

    private fun linked() {
        every { chatLinkRepository.findByClubId(clubId) } returns link()
        every { subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT) } returns 19900
    }

    @Test
    fun `club without a chat is never billed (R2)`() {
        every { chatLinkRepository.findByClubId(clubId) } returns null

        gate.requireBillable(club, eventId, ownerId)

        verify(exactly = 0) { subscriptionRepository.findLatestByClub(any()) }
        verify(exactly = 0) { freeMeetingRepository.claim(any(), any(), any()) }
    }

    @Test
    fun `first meeting of a chat is free and recorded by event id`() {
        linked()
        every { subscriptionRepository.findLatestByClub(clubId) } returns null
        every { freeMeetingRepository.claim(chatId, clubId, eventId) } returns true

        gate.requireBillable(club, eventId, ownerId)

        verify(exactly = 1) { funnelEventRepository.record(FunnelStep.FREE_MEETING_USED, ownerId, clubId) }
    }

    @Test
    fun `second meeting without a subscription hits the paywall with FREE_MEETING_USED`() {
        linked()
        every { subscriptionRepository.findLatestByClub(clubId) } returns null
        every { freeMeetingRepository.claim(chatId, clubId, eventId) } returns false

        val ex = assertThrows<PaymentRequiredException> { gate.requireBillable(club, eventId, ownerId) }

        assertEquals(PaywallReason.FREE_MEETING_USED, ex.reason)
        assertEquals(clubId, ex.clubId)
        assertEquals(19900, ex.priceKopecks)
        // Пейволл откатывает транзакцию создания — шаг воронки пишется отдельной.
        verify(exactly = 1) { funnelEventRepository.recordDetached(FunnelStep.PAYWALL_SEEN, ownerId, clubId) }
    }

    @Test
    fun `active subscription passes without touching the free meeting`() {
        linked()
        every { subscriptionRepository.findLatestByClub(clubId) } returns
            subscription(SubscriptionStatus.ACTIVE, OffsetDateTime.now().plusDays(20))

        gate.requireBillable(club, eventId, ownerId)

        verify(exactly = 0) { freeMeetingRepository.claim(any(), any(), any()) }
    }

    @Test
    fun `PAST_DUE inside the grace window passes (R10)`() {
        linked()
        every { subscriptionRepository.findLatestByClub(clubId) } returns
            subscription(SubscriptionStatus.PAST_DUE, OffsetDateTime.now().minusDays(6))

        gate.requireBillable(club, eventId, ownerId)

        verify(exactly = 0) { freeMeetingRepository.claim(any(), any(), any()) }
    }

    @Test
    fun `PAST_DUE past the grace window hits the paywall with SUBSCRIPTION_EXPIRED`() {
        linked()
        every { subscriptionRepository.findLatestByClub(clubId) } returns
            subscription(SubscriptionStatus.PAST_DUE, OffsetDateTime.now().minusDays(8))
        every { freeMeetingRepository.claim(chatId, clubId, eventId) } returns false

        val ex = assertThrows<PaymentRequiredException> { gate.requireBillable(club, eventId, ownerId) }

        assertEquals(PaywallReason.SUBSCRIPTION_EXPIRED, ex.reason)
    }

    @Test
    fun `ENDED subscription with a released free meeting lets the meeting through`() {
        // Бесплатная вернулась отменой (R5) — она снова доступна, даже если подписка кончилась.
        linked()
        every { subscriptionRepository.findLatestByClub(clubId) } returns
            subscription(SubscriptionStatus.ENDED, OffsetDateTime.now().minusDays(30))
        every { freeMeetingRepository.claim(chatId, clubId, eventId) } returns true

        gate.requireBillable(club, eventId, ownerId)

        verify(exactly = 1) { funnelEventRepository.record(FunnelStep.FREE_MEETING_USED, ownerId, clubId) }
    }

    @Test
    fun `releaseFreeMeeting delegates to the repository`() {
        every { freeMeetingRepository.release(eventId) } returns 1

        gate.releaseFreeMeeting(eventId)

        verify(exactly = 1) { freeMeetingRepository.release(eventId) }
    }
}
