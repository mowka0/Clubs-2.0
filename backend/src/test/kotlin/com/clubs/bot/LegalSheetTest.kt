package com.clubs.bot

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegalSheetTest {

    // Синтетические реквизиты: репозиторий публичный, настоящие живут в env и на сайте.
    private fun texts(inn: String = "000000000000", email: String = "support@example.com") = LegalSheet(
        recipientName = "Тестов Тест Тестович", recipientInn = inn, billingProvider = "stub",
        supportUsername = "clubs_tech_support", supportEmail = email, webAppBaseUrl = "https://app.test", trialDays = 15,
        subscriptionRepository = mockk { every { currentPriceKopecks(any()) } returns 19900 },
    )

    @Test
    fun `стартовое сообщение несёт всё обязательное для продажи в Telegram`() {
        val block = texts().infoBlock()
        // Robokassa для магазина в Telegram: услуга и цена, реквизиты, контакты; условия — в оферте по кнопке.
        listOf("199 ₽", "15 дней", "Тестов Тест Тестович", "ИНН 000000000000",
            "@clubs_tech_support", "support@example.com", "настоящий клуб").forEach {
            assertTrue(it in block, "нет «$it»")
        }
        assertTrue(block.length <= LegalSheet.TELEGRAM_TEXT_LIMIT)
    }

    @Test
    fun `оферта постранично под лимит — продавец, цена, бесплатный период и все 11 разделов шаблона`() {
        val sheet = texts()
        val pages = sheet.offerPages()
        assertTrue(pages.size >= 3, "шаблон Robokassa не влезает в одно сообщение — ожидались страницы")
        pages.forEach { assertTrue(it.length <= LegalSheet.TELEGRAM_TEXT_LIMIT, "страница длиннее лимита: ${it.length}") }
        val all = pages.joinToString("\n")
        assertTrue("Исполнитель — самозанятый Тестов Тест Тестович, ИНН 000000000000" in all)
        assertTrue(pages.first().startsWith("📄 Публичная оферта") && "Редакция от " in pages.first())
        assertTrue("@clubs_tech_support" in all && "support@example.com" in all)
        assertTrue("199 ₽ за 30 дней" in all)
        assertTrue("первых 15 дней" in all)
        (1..11).forEach { n -> assertTrue("\n$n. " in all, "потерян раздел $n") }
        // Старые кнопки шлют «offer» без номера — это первая страница с навигацией вперёд.
        assertEquals(listOf(listOf("legal:offer:1"), listOf("legal:info")), callbacks(sheet.render("offer")))
        assertEquals(pages.last(), sheet.render("offer:99").text)
    }

    @Test
    fun `политика влезает в лимит Telegram целиком и с подписью страниц`() {
        val pages = texts().privacyPages()
        pages.forEach { assertTrue(it.length <= LegalSheet.TELEGRAM_TEXT_LIMIT, "страница длиннее лимита: ${it.length}") }
        val all = pages.joinToString("\n")
        (1..10).forEach { n -> assertTrue("\n$n. " in all, "потерян раздел $n") }
        pages.forEachIndexed { i, page -> assertTrue(page.endsWith("Страница ${i + 1} из ${pages.size}")) }
        assertTrue(LegalSheet.PRIVACY_UPDATED in pages.first())
    }

    @Test
    fun `при маленьком лимите политика режется по разделам, не теряя ни одного`() {
        val pages = texts().privacyPages(limit = 1500)
        assertTrue(pages.size >= 3, "ожидалось несколько страниц, получено ${pages.size}")
        pages.forEach { assertTrue(it.length <= 1500, "страница длиннее лимита: ${it.length}") }
        val all = pages.joinToString("\n")
        (1..10).forEach { n -> assertTrue("\n$n. " in all, "потерян раздел $n") }
        assertTrue("(продолжение)" in pages[1])
        assertTrue(pages.last().endsWith("Страница ${pages.size} из ${pages.size}"))
    }

    @Test
    fun `без ИНН и e-mail реквизиты вырождаются, а не ломаются`() {
        val block = texts(inn = "", email = "").infoBlock()
        assertTrue("самозанятый Тестов Тест Тестович." in block)
        assertTrue("ИНН" !in block)
        assertEquals(1, Regex("@clubs_tech_support").findAll(block).count())
    }

    private fun callbacks(screen: LegalSheet.Screen) = screen.markup.keyboard.map { row -> row.map { it.callbackData } }

    @Test
    fun `стартовый экран — Mini App по базовому URL, оферта, политика и поддержка`() {
        val screen = texts().startScreen()
        val buttons = screen.markup.keyboard.flatten()
        assertEquals("https://app.test", buttons.first { it.webApp != null }.webApp.url)
        // Большая кнопка приложения сверху, три документные — одним рядом снизу.
        assertEquals(listOf(listOf(null), listOf("legal:offer", "legal:privacy:0", null)), callbacks(screen))
        assertEquals("https://t.me/clubs_tech_support", buttons.first { it.url != null }.url)
    }

    @Test
    fun `навигация по страницам политики — первая только вперёд, средняя в обе стороны, последняя только назад`() {
        val sheet = texts()
        val pages = sheet.privacyPages(limit = 1500)
        val first = sheet.render("privacy:0", limit = 1500)
        val middle = sheet.render("privacy:1", limit = 1500)
        val last = sheet.render("privacy:${pages.size - 1}", limit = 1500)
        assertEquals(listOf(listOf("legal:privacy:1"), listOf("legal:info")), callbacks(first))
        assertEquals(listOf(listOf("legal:privacy:0", "legal:privacy:2"), listOf("legal:info")), callbacks(middle))
        assertEquals(listOf(listOf("legal:privacy:${pages.size - 2}"), listOf("legal:info")), callbacks(last))
        assertEquals(pages.last(), last.text)
    }

    @Test
    fun `одна страница политики — без ряда навигации, мусорный номер прижимается к нулю`() {
        val screen = texts().render("privacy:abc")
        assertEquals(listOf(listOf("legal:info")), callbacks(screen))
        assertEquals(texts().privacyPages().single(), screen.text)
        assertEquals(texts().infoBlock(), texts().render("что-угодно").text)
    }

    @Test
    fun `бесплатный период склоняется — 1 день, 2 дня, 15 дней, 21 день`() {
        fun block(days: Int) = LegalSheet(
            recipientName = "Т", recipientInn = "", billingProvider = "stub", supportUsername = "s", supportEmail = "",
            webAppBaseUrl = "https://app.test", trialDays = days,
            subscriptionRepository = mockk { every { currentPriceKopecks(any()) } returns 19900 },
        ).infoBlock()
        assertTrue("Первые 1 день " in block(1))
        assertTrue("Первые 2 дня " in block(2))
        assertTrue("Первые 15 дней " in block(15))
        assertTrue("Первые 21 день " in block(21))
    }
}
