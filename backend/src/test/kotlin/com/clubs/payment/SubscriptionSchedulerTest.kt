package com.clubs.payment

import com.clubs.bot.NotificationService
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
    private lateinit var notificationService: NotificationService
    private lateinit var lifecycleService: SubscriptionLifecycleService
    private lateinit var scheduler: SubscriptionScheduler

    @BeforeEach
    fun setUp() {
        membershipRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        lifecycleService = SubscriptionLifecycleService(membershipRepository)
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
            ExpiringSubscriptionNotification(telegramId = tgId, clubName = "Chess Club", clubId = UUID.randomUUID())
        )
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()

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

        scheduler.checkSubscriptions()

        verify(exactly = 0) { notificationService.sendDirectMessage(any(), any()) }
        verify(exactly = 0) { notificationService.sendDirectMessageWithDeepLink(any(), any(), any(), any()) }
    }

    // Статусная модель 2026-07-06: DM «подписка истекла» уходит с кнопкой-диплинком «Оплатить взнос»
    // на страницу клуба (expired-участник заявляет там оплату) — AC-6 membership-lifecycle.md.
    @Test
    fun `scheduler notifies newly expired users with a deep-link payment button`() {
        val tgId = 321L
        val club = UUID.randomUUID()
        every { membershipRepository.findExpiringWithin(any(), any()) } returns emptyList()
        every { membershipRepository.findActiveExpired(any()) } returns listOf(
            ExpiringSubscriptionNotification(telegramId = tgId, clubName = "Poker Club", clubId = club)
        )

        scheduler.checkSubscriptions()

        verify(exactly = 1) {
            notificationService.sendDirectMessageWithDeepLink(
                tgId,
                match { it.contains("Poker Club") && it.contains("истёк") },
                "/clubs/$club",
                "Оплатить взнос"
            )
        }
    }

    // findActiveExpired (the DM snapshot) must run BEFORE processExpiry — otherwise the rows we want to
    // notify about have already flipped to expired.
    @Test
    fun `scheduler reads active-expired before expiring access`() {
        every { membershipRepository.findExpiringWithin(any(), any()) } returns emptyList()
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verifyOrder {
            membershipRepository.findActiveExpired(any())
            membershipRepository.expireOverdueAccess(any())
        }
    }

    // Notifications happen BEFORE the DB mutation so they stay outside processExpiry's transaction.
    @Test
    fun `scheduler sends notifications before expiring access`() {
        val tgId = 123L
        every { membershipRepository.findExpiringWithin(any(), any()) } returns listOf(
            ExpiringSubscriptionNotification(telegramId = tgId, clubName = "A", clubId = UUID.randomUUID())
        )
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verifyOrder {
            notificationService.sendDirectMessage(tgId, any())
            membershipRepository.expireOverdueAccess(any())
        }
    }

    // Статусная модель 2026-07-06: processExpiry переводит просроченные active в expired (должник остаётся в клубе).
    @Test
    fun `processExpiry expires overdue access to expired`() {
        every { membershipRepository.expireOverdueAccess(any()) } returns 3

        lifecycleService.processExpiry(OffsetDateTime.now())

        verify(exactly = 1) { membershipRepository.expireOverdueAccess(any()) }
    }

    @Test
    fun `processExpiry passes now to expireOverdueAccess`() {
        val fixedNow = OffsetDateTime.parse("2026-04-24T10:00:00Z")
        every { membershipRepository.expireOverdueAccess(any()) } returns 0

        lifecycleService.processExpiry(fixedNow)

        verify(exactly = 1) { membershipRepository.expireOverdueAccess(fixedNow) }
    }

    // AC-9 (threshold): findExpiringWithin gets the right (now, now+3d] window
    @Test
    fun `scheduler queries expiring with a 3-day forward window from now`() {
        val fixedNow = OffsetDateTime.parse("2026-04-24T10:00:00Z")
        mockkStatic(OffsetDateTime::class)
        every { OffsetDateTime.now() } returns fixedNow
        every { membershipRepository.findExpiringWithin(any(), any()) } returns emptyList()
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verify(exactly = 1) {
            membershipRepository.findExpiringWithin(fixedNow, fixedNow.plusDays(3))
        }
    }
}
