package com.clubs.payment

import com.clubs.bot.NotificationService
import com.clubs.club.ClubRepository
import com.clubs.membership.ClubMembershipExpiredCount
import com.clubs.membership.ExpiringSubscriptionNotification
import com.clubs.membership.MembershipRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Tests the cron entry point and the transactional lifecycle service separately.
 * Covers AC-9..AC-11 from docs/modules/payment.md.
 */
class SubscriptionSchedulerTest {

    private lateinit var membershipRepository: MembershipRepository
    private lateinit var clubRepository: ClubRepository
    private lateinit var notificationService: NotificationService
    private lateinit var lifecycleService: SubscriptionLifecycleService
    private lateinit var scheduler: SubscriptionScheduler

    @BeforeEach
    fun setUp() {
        membershipRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        lifecycleService = SubscriptionLifecycleService(membershipRepository, clubRepository)
        scheduler = SubscriptionScheduler(lifecycleService, notificationService)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(OffsetDateTime::class)
    }

    // AC-9
    @Test
    fun `scheduler sends expiry notifications for subscriptions expiring within 3 days`() {
        val tgId = 123L
        every { membershipRepository.findExpiringWithin(any(), any()) } returns listOf(
            ExpiringSubscriptionNotification(telegramId = tgId, clubName = "Chess Club")
        )
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()
        every { membershipRepository.findGracePeriodExpiredGroupedByClub(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verify(exactly = 1) {
            notificationService.sendDirectMessage(
                tgId,
                match { it.contains("Chess Club") && it.contains("3 дня") }
            )
        }
    }

    @Test
    fun `scheduler sends no notifications when nothing is expiring`() {
        every { membershipRepository.findExpiringWithin(any(), any()) } returns emptyList()
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()
        every { membershipRepository.findGracePeriodExpiredGroupedByClub(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verify(exactly = 0) { notificationService.sendDirectMessage(any(), any()) }
    }

    // PRD §4.7.3.3: notification when subscription moves to grace_period.
    @Test
    fun `scheduler notifies users when their subscription enters grace period`() {
        val tgId = 321L
        every { membershipRepository.findExpiringWithin(any(), any()) } returns emptyList()
        every { membershipRepository.findActiveExpired(any()) } returns listOf(
            ExpiringSubscriptionNotification(telegramId = tgId, clubName = "Poker Club")
        )
        every { membershipRepository.findGracePeriodExpiredGroupedByClub(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verify(exactly = 1) {
            notificationService.sendDirectMessage(
                tgId,
                match { it.contains("Poker Club") && it.contains("3 дня") && it.contains("истекла") }
            )
        }
    }

    // findActiveExpired must run BEFORE processExpiry — otherwise the rows
    // we want to notify about are already in grace_period status.
    @Test
    fun `scheduler reads active-expired before moving them to grace_period`() {
        every { membershipRepository.findExpiringWithin(any(), any()) } returns emptyList()
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()
        every { membershipRepository.findGracePeriodExpiredGroupedByClub(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verifyOrder {
            membershipRepository.findActiveExpired(any())
            membershipRepository.moveActiveToGracePeriod(any())
        }
    }

    // AC-9 (continued) — notifications happen BEFORE DB mutations so they stay
    // outside the transaction boundary of processExpiry.
    @Test
    fun `scheduler sends notifications before moving anything to grace period`() {
        val tgId = 123L
        every { membershipRepository.findExpiringWithin(any(), any()) } returns listOf(
            ExpiringSubscriptionNotification(telegramId = tgId, clubName = "A")
        )
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()
        every { membershipRepository.findGracePeriodExpiredGroupedByClub(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verifyOrder {
            notificationService.sendDirectMessage(tgId, any())
            membershipRepository.moveActiveToGracePeriod(any())
        }
    }

    // AC-10
    @Test
    fun `processExpiry moves active subscriptions to grace_period without decrementing member_count`() {
        every { membershipRepository.moveActiveToGracePeriod(any()) } returns 3
        every { membershipRepository.findGracePeriodExpiredGroupedByClub(any()) } returns emptyList()

        lifecycleService.processExpiry(OffsetDateTime.now())

        verify(exactly = 1) { membershipRepository.moveActiveToGracePeriod(any()) }
        verify(exactly = 0) { clubRepository.decrementMemberCountSafely(any(), any()) }
        verify(exactly = 0) { membershipRepository.moveGracePeriodToExpired(any()) }
    }

    // AC-11
    @Test
    fun `processExpiry decrements member_count by exact per-club count when moving grace_period to expired`() {
        val clubA = UUID.randomUUID()
        val clubB = UUID.randomUUID()
        every { membershipRepository.moveActiveToGracePeriod(any()) } returns 0
        every { membershipRepository.findGracePeriodExpiredGroupedByClub(any()) } returns listOf(
            ClubMembershipExpiredCount(clubA, 2),
            ClubMembershipExpiredCount(clubB, 5)
        )
        every { membershipRepository.moveGracePeriodToExpired(any()) } returns 7

        lifecycleService.processExpiry(OffsetDateTime.now())

        verify(exactly = 1) { clubRepository.decrementMemberCountSafely(clubA, 2) }
        verify(exactly = 1) { clubRepository.decrementMemberCountSafely(clubB, 5) }
        verify(exactly = 1) { membershipRepository.moveGracePeriodToExpired(any()) }
    }

    @Test
    fun `processExpiry skips move and decrement when nothing is in grace_period past the window`() {
        every { membershipRepository.moveActiveToGracePeriod(any()) } returns 0
        every { membershipRepository.findGracePeriodExpiredGroupedByClub(any()) } returns emptyList()

        lifecycleService.processExpiry(OffsetDateTime.now())

        verify(exactly = 0) { membershipRepository.moveGracePeriodToExpired(any()) }
        verify(exactly = 0) { clubRepository.decrementMemberCountSafely(any(), any()) }
    }

    // AC-9 (threshold): findExpiringWithin gets the right (now, now+3d] window
    @Test
    fun `scheduler queries expiring with a 3-day forward window from now`() {
        val fixedNow = OffsetDateTime.parse("2026-04-24T10:00:00Z")
        mockkStatic(OffsetDateTime::class)
        every { OffsetDateTime.now() } returns fixedNow
        every { membershipRepository.findExpiringWithin(any(), any()) } returns emptyList()
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()
        every { membershipRepository.findGracePeriodExpiredGroupedByClub(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verify(exactly = 1) {
            membershipRepository.findExpiringWithin(fixedNow, fixedNow.plusDays(3))
        }
    }
}
