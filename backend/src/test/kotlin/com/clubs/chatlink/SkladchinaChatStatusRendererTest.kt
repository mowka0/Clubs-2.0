package com.clubs.chatlink

import com.clubs.generated.jooq.enums.SkladchinaStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SkladchinaChatStatusRendererTest {

    private val renderer = SkladchinaChatStatusRenderer(botUsername = "clubs_test_bot")

    // 2026-07-10 15:00 UTC = 18:00 МСК
    private val deadline = OffsetDateTime.of(2026, 7, 10, 15, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `statusText — прогресс в людях, дедлайн МСК и упоминания в «Ждём»`() {
        val text = renderer.statusText(
            title = "Бронь корта",
            paidCount = 3,
            participantCount = 10,
            deadline = deadline,
            pending = listOf(ChatMention(111L, "Наташа"), ChatMention(222L, "Марк"))
        )

        assertTrue(text.contains("💰 Бронь корта"))
        assertTrue(text.contains("👥 Скинулись — 3 из 10"))
        assertTrue(text.contains("⏳ До 10.07.2026 18:00 МСК"))
        assertTrue(text.contains("Ждём: <a href=\"tg://user?id=111\">Наташа</a>, <a href=\"tg://user?id=222\">Марк</a>"))
    }

    @Test
    fun `statusText без pending — строки «Ждём» нет`() {
        val text = renderer.statusText("Бронь корта", 10, 10, deadline, pending = emptyList())
        assertFalse(text.contains("Ждём"))
    }

    @Test
    fun `statusText — HTML в заголовке и именах экранируется (инъекция разметки невозможна)`() {
        val text = renderer.statusText(
            title = "Сбор <b>&\"жирный\"</b>",
            paidCount = 0,
            participantCount = 1,
            deadline = deadline,
            pending = listOf(ChatMention(1L, "<script>Вася & Ко"))
        )

        assertTrue(text.contains("💰 Сбор &lt;b&gt;&amp;\"жирный\"&lt;/b&gt;"))
        assertTrue(text.contains(">&lt;script&gt;Вася &amp; Ко</a>"))
        assertFalse(text.contains("<b>"))
        assertFalse(text.contains("<script>"))
    }

    @Test
    fun `statusText — упоминания режутся по MAX_MENTIONS с хвостом «и ещё k»`() {
        val pending = (1..17).map { ChatMention(it.toLong(), "Гость$it") }

        val text = renderer.statusText("Сбор", 0, 17, deadline, pending)

        assertTrue(text.contains("Гость${SkladchinaChatStatusRenderer.MAX_MENTIONS}"))
        assertFalse(text.contains("Гость${SkladchinaChatStatusRenderer.MAX_MENTIONS + 1}<"))
        assertTrue(text.contains("и ещё 2"))
    }

    @Test
    fun `closedText — нейтральный финал без списка неоплативших`() {
        assertEquals(
            "💰 Бронь корта\nСбор закрыт · скинулись 8 из 10 ✅",
            renderer.closedText("Бронь корта", SkladchinaStatus.closed_success, 8, 10)
        )
        assertEquals(
            "💰 Бронь корта\nСбор закрыт · скинулись 3 из 10",
            renderer.closedText("Бронь корта", SkladchinaStatus.closed_failed, 3, 10)
        )
        assertEquals(
            "💰 Бронь корта\nСбор отменён",
            renderer.closedText("Бронь корта", SkladchinaStatus.cancelled, 0, 10)
        )
    }

    @Test
    fun `reminderText — упоминания не ответивших, БЕЗ цены молчания (не коллектор)`() {
        val text = renderer.reminderText(
            title = "Бронь корта",
            deadline = deadline,
            pending = listOf(ChatMention(111L, "Наташа"))
        )

        assertTrue(text.contains("⏰ Напоминание: сбор «Бронь корта» закрывается 10.07.2026 18:00 МСК."))
        assertTrue(text.contains("Ещё не ответили: <a href=\"tg://user?id=111\">Наташа</a>"))
        // Публичный текст не позорит и не грозит штрафом — полная цена молчания живёт в DM.
        assertFalse(text.contains("40"))
        assertFalse(text.lowercase().contains("репутац"))
    }

    @Test
    fun `skladchinaUrl — deep link Main Mini App с префиксом skladchina_`() {
        val id = UUID.randomUUID()
        assertEquals("https://t.me/clubs_test_bot?startapp=skladchina_$id", renderer.skladchinaUrl(id))
    }
}
