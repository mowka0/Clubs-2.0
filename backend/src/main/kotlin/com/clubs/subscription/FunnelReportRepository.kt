package com.clubs.subscription

import java.time.OffsetDateTime

/** Агрегаты недельного отчёта (funnel.md § 3.3). Только чтение. */
interface FunnelReportRepository {

    /** Срез за [week]; [now] — точка отсчёта для «чатов без встреч 30 дней». */
    fun collect(week: ReportWeek, now: OffsetDateTime): FunnelReport
}
