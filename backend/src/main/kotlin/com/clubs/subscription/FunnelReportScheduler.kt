package com.clubs.subscription

import com.clubs.bot.NotificationService
import com.clubs.common.config.PlatformAdmins
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Недельный отчёт себе в DM (funnel.md § 3.3) — вместо дашборда, пока чатов меньше ~50.
 * Крон `funnel.report-cron` по Москве; получатели — администраторы платформы из env.
 */
@Component
class FunnelReportScheduler(
    private val reportRepository: FunnelReportRepository,
    private val admins: PlatformAdmins,
    private val notificationService: NotificationService,
) {
    private val log = LoggerFactory.getLogger(FunnelReportScheduler::class.java)

    @Scheduled(cron = "\${funnel.report-cron:0 0 9 * * MON}", zone = "Europe/Moscow")
    fun sendWeeklyReport() = sendWeeklyReport(OffsetDateTime.now())

    fun sendWeeklyReport(now: OffsetDateTime) {
        if (admins.telegramIds.isEmpty()) {
            log.info("Funnel report skipped: PLATFORM_ADMIN_TELEGRAM_IDS is empty")
            return
        }
        val week = ReportWeek.containingYesterday(now)
        val text = FunnelReportFormatter.format(reportRepository.collect(week, now), week)
        // Каждому получателю независимо, с результатом: best-effort отправка глотает ошибку
        // Telegram, и лог «отправлено» врал бы при недоставке (например, слишком длинный текст).
        val delivered = admins.telegramIds.count { notificationService.trySendDirectMessage(it, text) }
        if (delivered < admins.telegramIds.size) {
            log.warn("Funnel report delivered to {} of {} admins: isoWeek={} length={}", delivered, admins.telegramIds.size, week.isoWeek, text.length)
        } else {
            log.info("Funnel report sent: isoWeek={} recipients={}", week.isoWeek, delivered)
        }
    }
}
