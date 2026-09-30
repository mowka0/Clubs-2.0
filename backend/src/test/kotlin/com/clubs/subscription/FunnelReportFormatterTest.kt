package com.clubs.subscription

import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Текст отчёта слово в слово (AC-5) и выбор недели (AC-10). */
class FunnelReportFormatterTest {

    private val msk = ZoneOffset.ofHours(3)
    private val week39 = ReportWeek(
        start = OffsetDateTime.of(2026, 9, 21, 0, 0, 0, 0, msk),
        end = OffsetDateTime.of(2026, 9, 28, 0, 0, 0, 0, msk),
    )

    private val sample = FunnelReport(
        clubs = 120, clubsWithChat = 47,
        newClubs = 12, newClubsWithChat = 9,
        connections = 11, disconnections = 2,
        paying = 31, mrrRubles = 6169,
        meetingsCompleted = 62,
        idleChats = listOf("Бег по утрам", "Настолки на Таганке", "Киноклуб"),
        starts = 94, firstMeetings = 7, checkouts = 5, payments = 4,
        campaigns = listOf(
            CampaignRow("vk1", starts = 60, connections = 8, payments = 2),
            CampaignRow("tg2", starts = 20, connections = 2, payments = 0),
            CampaignRow(null, starts = 14, connections = 1, payments = 2),
        ),
    )

    @Test
    fun `отчёт на фикстуре из спеки - слово в слово`() {
        val expected = """
            📊 Clubs, неделя 39 (21–27 сентября)
            Клубов: 120 · с чатом: 47 (39%)
            За неделю: +12 клубов, из них с чатом 9 (75%) · подключений 11, отключений 2
            Платят: 31 · MRR 6 169 ₽
            Встреч состоялось: 62
            Чатов без встреч 30 дней: 3 ← смотреть сюда
              Бег по утрам · Настолки на Таганке · Киноклуб
            Воронка за неделю: 94 старта → 11 подключений → 7 первых встреч → 5 чекаутов → 4 оплаты
            Кампании: ad_vk1 — 60 стартов / 8 подключений / 2 оплаты · ad_tg2 — 20 / 2 / 0 · органика — 14 / 1 / 2
        """.trimIndent()

        assertEquals(expected, FunnelReportFormatter.format(sample, week39))
    }

    @Test
    fun `пустая неделя - нули, проценты без деления на ноль, без строки названий`() {
        val empty = FunnelReport(0, 0, 0, 0, 0, 0, 0, 0, 0, emptyList(), 0, 0, 0, 0, emptyList())

        val text = FunnelReportFormatter.format(empty, week39)

        assertTrue(text.contains("Клубов: 0 · с чатом: 0 (0%)"))
        assertTrue(text.contains("За неделю: +0 клубов, из них с чатом 0 (0%) · подключений 0, отключений 0"))
        assertTrue(text.contains("Чатов без встреч 30 дней: 0 ← смотреть сюда\nВоронка"))
        assertTrue(text.contains("0 стартов → 0 подключений → 0 первых встреч → 0 чекаутов → 0 оплат"))
        assertTrue(text.endsWith("Кампании: стартов не было"))
    }

    @Test
    fun `больше десяти простаивающих чатов - показаны десять и «ещё N»`() {
        val names = (1..12).map { "Клуб $it" }

        val text = FunnelReportFormatter.format(sample.copy(idleChats = names), week39)

        assertTrue(text.contains("Чатов без встреч 30 дней: 12 ← смотреть сюда\n  Клуб 1 · Клуб 2"))
        assertTrue(text.contains("Клуб 10 · ещё 2\n"))
        assertFalse(text.contains("Клуб 11"))
    }

    @Test
    fun `больше десяти кампаний - показаны десять и «ещё N кампаний» (метку придумывает кто угодно)`() {
        val rows = (1..12).map { CampaignRow("c$it", starts = 13 - it, connections = 0, payments = 0) }

        val text = FunnelReportFormatter.format(sample.copy(campaigns = rows), week39)

        assertTrue(text.endsWith("ad_c10 — 3 / 0 / 0 · ещё 2 кампании"))
        assertFalse(text.contains("ad_c11"))
    }

    @Test
    fun `неделя через границу месяцев подписывается обоими месяцами`() {
        val week40 = ReportWeek(
            start = OffsetDateTime.of(2026, 9, 28, 0, 0, 0, 0, msk),
            end = OffsetDateTime.of(2026, 10, 5, 0, 0, 0, 0, msk),
        )

        assertTrue(FunnelReportFormatter.format(sample, week40).startsWith("📊 Clubs, неделя 40 (28 сентября – 4 октября)"))
    }

    @Test
    fun `в понедельник отчёт за прошедшую неделю, в другие дни - за текущую`() {
        val mondayMorning = OffsetDateTime.of(2026, 9, 28, 9, 0, 0, 0, msk)
        assertEquals(week39, ReportWeek.containingYesterday(mondayMorning))

        val wednesday = OffsetDateTime.of(2026, 9, 30, 12, 0, 0, 0, msk)
        assertEquals(OffsetDateTime.of(2026, 9, 28, 0, 0, 0, 0, msk), ReportWeek.containingYesterday(wednesday).start)

        val sundayNight = OffsetDateTime.of(2026, 9, 27, 23, 30, 0, 0, msk)
        assertEquals(week39, ReportWeek.containingYesterday(sundayNight))
    }

    @Test
    fun `граница недели считается по Москве, а не по UTC`() {
        // 21:30 UTC воскресенья = 00:30 понедельника по Москве: «вчера» — воскресенье, неделя прошедшая.
        val lateSundayUtc = OffsetDateTime.of(2026, 9, 27, 21, 30, 0, 0, ZoneOffset.UTC)

        val week = ReportWeek.containingYesterday(lateSundayUtc)

        assertTrue(week.start.isEqual(week39.start))
        assertTrue(week.end.isEqual(week39.end))
        assertEquals(39, week.isoWeek)
    }
}
