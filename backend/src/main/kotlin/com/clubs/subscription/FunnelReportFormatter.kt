package com.clubs.subscription

import java.time.LocalDate
import java.util.Locale

/** Текст недельного отчёта из среза (funnel.md § 3.3). Чистая функция — тест сверяет слово в слово. */
object FunnelReportFormatter {

    // Сколько названий «чатов без встреч» показывать, дальше «ещё N» — иначе DM не читается.
    private const val IDLE_NAMES_SHOWN = 10

    // Сколько кампаний показывать. Метку придумывает любой, кто напишет боту «/start ad_…», поэтому
    // без потолка посторонний раздувает DM за лимит Telegram (4096 знаков) и отчёт не доходит.
    private const val CAMPAIGNS_SHOWN = 10

    private val MONTHS_GENITIVE = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    fun format(report: FunnelReport, week: ReportWeek): String = buildString {
        appendLine("📊 Clubs, неделя ${week.isoWeek} (${dateRange(week.firstDay, week.lastDay)})")
        appendLine("Клубов: ${report.clubs} · с чатом: ${report.clubsWithChat} (${percent(report.clubsWithChat, report.clubs)})")
        appendLine(
            "За неделю: +${plural(report.newClubs, "клуб", "клуба", "клубов")}, " +
                "из них с чатом ${report.newClubsWithChat} (${percent(report.newClubsWithChat, report.newClubs)}) · " +
                "подключений ${report.connections}, отключений ${report.disconnections}"
        )
        appendLine("Платят: ${report.paying} · MRR ${groupThousands(report.mrrRubles)} ₽")
        appendLine("Встреч состоялось: ${report.meetingsCompleted}")
        appendLine("Чатов без встреч 30 дней: ${report.idleChats.size} ← смотреть сюда")
        if (report.idleChats.isNotEmpty()) appendLine("  ${idleNames(report.idleChats)}")
        appendLine(
            "Воронка за неделю: ${plural(report.starts, "старт", "старта", "стартов")} → " +
                "${plural(report.connections, "подключение", "подключения", "подключений")} → " +
                "${plural(report.firstMeetings, "первая встреча", "первые встречи", "первых встреч")} → " +
                "${plural(report.checkouts, "чекаут", "чекаута", "чекаутов")} → " +
                plural(report.payments, "оплата", "оплаты", "оплат")
        )
        append("Кампании: ${campaigns(report.campaigns)}")
    }

    /** «21–27 сентября» внутри месяца, «28 сентября – 4 октября» через границу; год не нужен. */
    private fun dateRange(first: LocalDate, last: LocalDate): String =
        if (first.month == last.month) "${first.dayOfMonth}–${last.dayOfMonth} ${month(last)}"
        else "${first.dayOfMonth} ${month(first)} – ${last.dayOfMonth} ${month(last)}"

    private fun month(date: LocalDate): String = MONTHS_GENITIVE[date.monthValue - 1]

    private fun percent(part: Int, whole: Int): String =
        if (whole == 0) "0%" else "${Math.round(part * 100.0 / whole)}%"

    private fun groupThousands(value: Int): String = String.format(Locale.ROOT, "%,d", value).replace(',', ' ')

    private fun idleNames(names: List<String>): String {
        val shown = names.take(IDLE_NAMES_SHOWN).joinToString(" · ")
        val rest = names.size - IDLE_NAMES_SHOWN
        return if (rest > 0) "$shown · ещё $rest" else shown
    }

    /**
     * Первая строка — со словами, дальше только числа в том же порядке: старты / подключения /
     * оплаты. Показываются первые [CAMPAIGNS_SHOWN] (репозиторий уже отсортировал по стартам,
     * органика последней), хвост сворачивается в «ещё N кампаний».
     */
    private fun campaigns(rows: List<CampaignRow>): String {
        if (rows.isEmpty()) return "стартов не было"
        val rest = rows.size - CAMPAIGNS_SHOWN
        val shown = rows.take(CAMPAIGNS_SHOWN).mapIndexed { index, row ->
            val name = row.campaign?.let { "ad_$it" } ?: "органика"
            val numbers = if (index == 0) {
                "${plural(row.starts, "старт", "старта", "стартов")} / " +
                    "${plural(row.connections, "подключение", "подключения", "подключений")} / " +
                    plural(row.payments, "оплата", "оплаты", "оплат")
            } else {
                "${row.starts} / ${row.connections} / ${row.payments}"
            }
            "$name — $numbers"
        }.joinToString(" · ")
        return if (rest > 0) "$shown · ещё ${plural(rest, "кампания", "кампании", "кампаний")}" else shown
    }

    /** Русское число с существительным: 1 старт, 2 старта, 5 стартов, 11 стартов, 21 старт. */
    private fun plural(n: Int, one: String, few: String, many: String): String {
        val form = when {
            n % 100 in 11..19 -> many
            n % 10 == 1 -> one
            n % 10 in 2..4 -> few
            else -> many
        }
        return "$n $form"
    }
}
