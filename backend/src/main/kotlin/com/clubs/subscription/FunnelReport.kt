package com.clubs.subscription

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/** Срез недельного отчёта (funnel.md § 3.3). Чистые данные — текст собирает [FunnelReportFormatter]. */
data class FunnelReport(
    val clubs: Int,
    val clubsWithChat: Int,
    val newClubs: Int,
    val newClubsWithChat: Int,
    val connections: Int,
    val disconnections: Int,
    val paying: Int,
    val mrrRubles: Int,
    val meetingsCompleted: Int,
    /** Названия клубов, чей чат 30 дней без встреч — все, до десяти показывает форматтер. */
    val idleChats: List<String>,
    val starts: Int,
    val firstMeetings: Int,
    val checkouts: Int,
    val payments: Int,
    val campaigns: List<CampaignRow>,
)

/** Строка кампаний за неделю; `campaign = null` — органика. */
data class CampaignRow(
    val campaign: String?,
    val starts: Int,
    val connections: Int,
    val payments: Int,
)

/** Окно отчёта — ISO-неделя по Москве, полуинтервал `[start, end)`. */
data class ReportWeek(val start: OffsetDateTime, val end: OffsetDateTime) {

    val firstDay: LocalDate get() = start.atZoneSameInstant(ZONE).toLocalDate()
    val lastDay: LocalDate get() = firstDay.plusDays(6)
    val isoWeek: Int get() = firstDay.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    companion object {
        /** Часовой пояс отчёта: границы недели и даты в заголовке — по Москве. */
        val ZONE: ZoneId = ZoneId.of("Europe/Moscow")

        /**
         * Неделя, в которую входит «вчера»: в понедельник (штатный запуск) — прошедшая полная,
         * в любой другой день (staging, ручной прогон) — текущая, начатая.
         */
        fun containingYesterday(now: OffsetDateTime): ReportWeek {
            val monday = now.atZoneSameInstant(ZONE).toLocalDate().minusDays(1)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return ReportWeek(
                start = monday.atStartOfDay(ZONE).toOffsetDateTime(),
                end = monday.plusWeeks(1).atStartOfDay(ZONE).toOffsetDateTime(),
            )
        }
    }
}
