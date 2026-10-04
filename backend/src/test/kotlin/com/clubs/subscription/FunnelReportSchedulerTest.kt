package com.clubs.subscription

import com.clubs.bot.NotificationService
import com.clubs.common.config.PlatformAdmins
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Получатели и устойчивость рассылки отчёта (AC-6, AC-7). */
class FunnelReportSchedulerTest {

    private val repository = mockk<FunnelReportRepository>()
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val mondayMorning = OffsetDateTime.of(2026, 9, 28, 9, 0, 0, 0, ZoneOffset.ofHours(3))
    private val emptyReport = FunnelReport(0, 0, 0, 0, 0, 0, 0, 0, 0, emptyList(), 0, 0, 0, 0, emptyList())

    @Test
    fun `пустой список администраторов - отчёт не строится и никуда не уходит`() {
        FunnelReportScheduler(repository, PlatformAdmins(""), notificationService).sendWeeklyReport(mondayMorning)

        verify(exactly = 0) { repository.collect(any(), any()) }
        verify(exactly = 0) { notificationService.trySendDirectMessage(any(), any()) }
    }

    @Test
    fun `сбой отправки одному администратору не мешает остальным`() {
        every { repository.collect(any(), mondayMorning) } returns emptyReport
        every { notificationService.trySendDirectMessage(111L, any()) } returns false
        every { notificationService.trySendDirectMessage(222L, any()) } returns true

        FunnelReportScheduler(repository, PlatformAdmins("111, 222"), notificationService).sendWeeklyReport(mondayMorning)

        verify { notificationService.trySendDirectMessage(222L, match { it.startsWith("📊 Clubs, неделя 39 (21–27 сентября)") }) }
    }
}
