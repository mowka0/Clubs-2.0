package com.clubs.payment

import com.clubs.bot.NotificationService
import com.clubs.membership.ExpiringSubscriptionNotification
import com.clubs.membership.ExpiryReminderCandidate
import com.clubs.membership.MembershipAccessRef
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
import org.springframework.context.ApplicationEventPublisher
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Tests the cron entry point and the transactional lifecycle service separately.
 * Covers AC-9..AC-11 from docs/modules/payment.md and the two-threshold reminder rules
 * (AC-R6/AC-R7 from docs/modules/membership-lifecycle.md §7).
 */
class SubscriptionSchedulerTest {

    private lateinit var membershipRepository: MembershipRepository
    private lateinit var notificationService: NotificationService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var lifecycleService: SubscriptionLifecycleService
    private lateinit var scheduler: SubscriptionScheduler

    // 12:00 МСК — крон тикает днём, как в проде (9:00 UTC).
    private val fixedNow = OffsetDateTime.parse("2026-04-24T09:00:00Z")

    @BeforeEach
    fun setUp() {
        membershipRepository = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        lifecycleService = SubscriptionLifecycleService(membershipRepository, eventPublisher)
        scheduler = SubscriptionScheduler(lifecycleService, notificationService)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(OffsetDateTime::class)
    }

    private fun freezeNow() {
        mockkStatic(OffsetDateTime::class)
        every { OffsetDateTime.now() } returns fixedNow
    }

    /** Кандидат, у которого до конца окна [daysLeft] календарных дней по МСК. */
    private fun candidate(
        daysLeft: Long,
        lastReminderDaysLeft: Int?,
        membershipId: UUID = UUID.randomUUID(),
        telegramId: Long = 123L,
        clubName: String = "Chess Club"
    ) = ExpiryReminderCandidate(
        membershipId = membershipId,
        telegramId = telegramId,
        clubName = clubName,
        // +2ч к моменту тика: окно почти всегда заканчивается не ровно в час крона.
        expiresAt = fixedNow.plusDays(daysLeft).plusHours(2),
        lastReminderDaysLeft = lastReminderDaysLeft
    )

    // AC-9 + AC-R1 (раннее продление §7): напоминание за 3 дня ведёт кнопкой «Продлить подписку» на «Мои клубы».
    @Test
    fun `scheduler sends the 3-day reminder with a renew deep-link`() {
        val membershipId = UUID.randomUUID()
        freezeNow()
        every { membershipRepository.findExpiryReminderCandidates(any(), any()) } returns listOf(
            candidate(daysLeft = 3, lastReminderDaysLeft = null, membershipId = membershipId)
        )
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verify(exactly = 1) {
            notificationService.sendDirectMessageWithDeepLink(
                123L,
                match { it.contains("Chess Club") && it.contains("через 3 дня") },
                "/my-clubs",
                "Продлить подписку"
            )
        }
        verify(exactly = 1) { membershipRepository.markExpiryReminderSent(listOf(membershipId), 3) }
    }

    // AC-R6: порог, который уже отправлен, молчит на следующих тиках — иначе при дневном кроне
    // участник получал три одинаковых DM подряд, а при частом кроне staging спам шёл на каждом тике.
    @Test
    fun `scheduler stays silent when the 3-day reminder was already sent`() {
        freezeNow()
        every { membershipRepository.findExpiryReminderCandidates(any(), any()) } returns listOf(
            candidate(daysLeft = 2, lastReminderDaysLeft = 3),
            candidate(daysLeft = 3, lastReminderDaysLeft = 3)
        )
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verify(exactly = 0) { notificationService.sendDirectMessageWithDeepLink(any(), any(), any(), any()) }
        verify(exactly = 0) { membershipRepository.markExpiryReminderSent(any(), any()) }
    }

    // AC-R6: второй (и последний) порог — за 1 день; текст говорит «завтра», а не «через 3 дня».
    @Test
    fun `scheduler sends the 1-day reminder after the 3-day one`() {
        val membershipId = UUID.randomUUID()
        freezeNow()
        every { membershipRepository.findExpiryReminderCandidates(any(), any()) } returns listOf(
            candidate(daysLeft = 1, lastReminderDaysLeft = 3, membershipId = membershipId)
        )
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verify(exactly = 1) {
            notificationService.sendDirectMessageWithDeepLink(
                123L,
                match { it.contains("Chess Club") && it.contains("завтра") },
                "/my-clubs",
                "Продлить подписку"
            )
        }
        verify(exactly = 1) { membershipRepository.markExpiryReminderSent(listOf(membershipId), 1) }
    }

    // AC-R6: после порога «за 1 день» напоминаний больше нет — последний день молчит.
    @Test
    fun `scheduler stays silent on the last day when both reminders were sent`() {
        freezeNow()
        every { membershipRepository.findExpiryReminderCandidates(any(), any()) } returns listOf(
            candidate(daysLeft = 0, lastReminderDaysLeft = 1)
        )
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verify(exactly = 0) { notificationService.sendDirectMessageWithDeepLink(any(), any(), any(), any()) }
    }

    // Кандидаты дальше самого дальнего порога попадают в выборку (горизонт шире на сутки), но DM не получают.
    @Test
    fun `scheduler ignores candidates beyond the farthest threshold`() {
        freezeNow()
        every { membershipRepository.findExpiryReminderCandidates(any(), any()) } returns listOf(
            candidate(daysLeft = 4, lastReminderDaysLeft = null)
        )
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verify(exactly = 0) { notificationService.sendDirectMessageWithDeepLink(any(), any(), any(), any()) }
        verify(exactly = 0) { membershipRepository.markExpiryReminderSent(any(), any()) }
    }

    // Пропущенный тик (простой/ре-деплой): порог «за 3 дня» не отправлялся, остаток уже 1 день —
    // уходит одно честное «завтра», а не задним числом «через 3 дня».
    @Test
    fun `scheduler catches up with an honest phrase after a missed tick`() {
        val membershipId = UUID.randomUUID()
        freezeNow()
        every { membershipRepository.findExpiryReminderCandidates(any(), any()) } returns listOf(
            candidate(daysLeft = 1, lastReminderDaysLeft = null, membershipId = membershipId)
        )
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verify(exactly = 1) {
            notificationService.sendDirectMessageWithDeepLink(
                123L,
                match { it.contains("завтра") && !it.contains("через 3 дня") },
                "/my-clubs",
                "Продлить подписку"
            )
        }
        verify(exactly = 1) { membershipRepository.markExpiryReminderSent(listOf(membershipId), 1) }
    }

    // Отметка порогов идёт пачкой: один UPDATE на порог со всеми id сразу, а не по строке на участника.
    @Test
    fun `scheduler marks each threshold once with all its recipients`() {
        val firstOfEarly = UUID.randomUUID()
        val secondOfEarly = UUID.randomUUID()
        val final = UUID.randomUUID()
        freezeNow()
        every { membershipRepository.findExpiryReminderCandidates(any(), any()) } returns listOf(
            candidate(daysLeft = 3, lastReminderDaysLeft = null, membershipId = firstOfEarly, telegramId = 1L),
            candidate(daysLeft = 1, lastReminderDaysLeft = 3, membershipId = final, telegramId = 2L),
            candidate(daysLeft = 3, lastReminderDaysLeft = null, membershipId = secondOfEarly, telegramId = 3L),
            // Порог уже закрыт — в пачку не попадает.
            candidate(daysLeft = 2, lastReminderDaysLeft = 3, telegramId = 4L)
        )
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verify(exactly = 3) { notificationService.sendDirectMessageWithDeepLink(any(), any(), any(), any()) }
        verify(exactly = 1) { membershipRepository.markExpiryReminderSent(listOf(firstOfEarly, secondOfEarly), 3) }
        verify(exactly = 1) { membershipRepository.markExpiryReminderSent(listOf(final), 1) }
    }

    @Test
    fun `scheduler sends no notifications when nothing is expiring`() {
        every { membershipRepository.findExpiryReminderCandidates(any(), any()) } returns emptyList()
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
        every { membershipRepository.findExpiryReminderCandidates(any(), any()) } returns emptyList()
        every { membershipRepository.findActiveExpired(any()) } returns listOf(
            ExpiringSubscriptionNotification(telegramId = tgId, clubName = "Poker Club", clubId = club)
        )

        scheduler.checkSubscriptions()

        verify(exactly = 1) {
            notificationService.sendDirectMessageWithDeepLink(
                tgId,
                match { it.contains("Poker Club") && it.contains("истёк") },
                "/clubs/$club?pay=1",
                "Оплатить взнос"
            )
        }
    }

    // findActiveExpired (the DM snapshot) must run BEFORE processExpiry — otherwise the rows we want to
    // notify about have already flipped to expired.
    @Test
    fun `scheduler reads active-expired before expiring access`() {
        every { membershipRepository.findExpiryReminderCandidates(any(), any()) } returns emptyList()
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
        freezeNow()
        every { membershipRepository.findExpiryReminderCandidates(any(), any()) } returns listOf(
            candidate(daysLeft = 3, lastReminderDaysLeft = null, telegramId = tgId, clubName = "A")
        )
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verifyOrder {
            notificationService.sendDirectMessageWithDeepLink(tgId, any(), any(), any())
            membershipRepository.expireOverdueAccess(any())
        }
    }

    // Статусная модель 2026-07-06: processExpiry переводит просроченные active в expired (должник остаётся в клубе).
    @Test
    fun `processExpiry expires overdue access to expired`() {
        every { membershipRepository.expireOverdueAccess(any()) } returns listOf(
            MembershipAccessRef(UUID.randomUUID(), UUID.randomUUID()),
            MembershipAccessRef(UUID.randomUUID(), UUID.randomUUID()),
            MembershipAccessRef(UUID.randomUUID(), UUID.randomUUID())
        )

        lifecycleService.processExpiry(OffsetDateTime.now())

        verify(exactly = 1) { membershipRepository.expireOverdueAccess(any()) }
    }

    @Test
    fun `processExpiry passes now to expireOverdueAccess`() {
        val now = OffsetDateTime.parse("2026-04-24T10:00:00Z")
        every { membershipRepository.expireOverdueAccess(any()) } returns emptyList()

        lifecycleService.processExpiry(now)

        verify(exactly = 1) { membershipRepository.expireOverdueAccess(now) }
    }

    // Горизонт выборки шире самого дальнего порога на сутки: подписка, до конца которой ровно 3
    // календарных дня, но по времени суток чуть дальше, иначе не попала бы в кандидаты.
    @Test
    fun `scheduler scans candidates with a 4-day forward window from now`() {
        freezeNow()
        every { membershipRepository.findExpiryReminderCandidates(any(), any()) } returns emptyList()
        every { membershipRepository.findActiveExpired(any()) } returns emptyList()

        scheduler.checkSubscriptions()

        verify(exactly = 1) {
            membershipRepository.findExpiryReminderCandidates(fixedNow, fixedNow.plusDays(4))
        }
    }
}
