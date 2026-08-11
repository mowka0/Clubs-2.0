package com.clubs.chatlink

import com.clubs.event.Event
import com.clubs.event.EventEditedEvent
import com.clubs.generated.jooq.enums.EventStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class LivePinRendererTest {

    private val renderer = LivePinRenderer(botUsername = "clubs_test_bot")

    // 12.07.2026 16:00 UTC = 19:00 МСК — проверяем и сдвиг часового пояса.
    private val event = Event(
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        clubId = UUID.randomUUID(),
        createdBy = UUID.randomUUID(),
        title = "Поход в баню",
        description = null,
        locationText = "Сандуны",
        eventDatetime = OffsetDateTime.of(2026, 7, 12, 16, 0, 0, 0, ZoneOffset.UTC),
        participantLimit = 15,
        votingOpensDaysBefore = 14,
        status = EventStatus.upcoming,
        stage2Triggered = false,
        attendanceMarked = false,
        attendanceFinalized = false,
        photoUrl = null,
        createdAt = null,
        updatedAt = null
    )

    @Test
    fun `stage1 — голоса, места, время в МСК`() {
        val text = renderer.stage1Text(event, going = 9, maybe = 3)
        // Общий шаблон (PO 2026-07-26): формат встречи жирным заголовком, затем что/когда/где.
        assertTrue(text.contains("<b>Обычная встреча</b>"))
        assertTrue(text.contains("Поход в баню"))
        assertTrue(text.contains("когда: 12.07.2026 19:00 МСК"))
        // Призыв к действию стоит прямо над счётчиками (PO 2026-08-10)
        assertTrue(text.contains("Проголосуй в клубе!\n✅ Идут — 9"))
        assertTrue(text.contains("✅ Идут — 9"))
        assertTrue(text.contains("🤔 Возможно — 3"))
        assertTrue(text.contains("👥 Мест — 15"))
    }

    @Test
    fun `место в закрепе — уточнение в скобках после адреса, у события без места строки где нет`() {
        assertTrue(renderer.stage1Text(event, going = 1, maybe = 0).contains("где: Сандуны"))
        val withHint = renderer.stage1Text(event.copy(locationHint = "3-й этаж"), going = 1, maybe = 0)
        assertTrue(withHint.contains("где: Сандуны (3-й этаж)"))
        val hintOnly = renderer.stage1Text(event.copy(locationText = null, locationHint = "В зуме"), going = 1, maybe = 0)
        assertTrue(hintOnly.contains("где: В зуме"))
        val noLocation = renderer.stage1Text(event.copy(locationText = null), going = 1, maybe = 0)
        assertFalse(noLocation.contains("где:"))
    }

    // HTML parse_mode: пользовательский ввод обязан экранироваться, иначе `<` ломает
    // разметку всего сообщения и Telegram молча его не доставляет.
    @Test
    fun `спецсимволы в названии и месте экранируются`() {
        val nasty = event.copy(title = "Баня <b>&", locationText = "Дом & сад", locationHint = null)
        val text = renderer.stage1Text(nasty, going = 1, maybe = 0)
        assertTrue(text.contains("Баня &lt;b&gt;&amp;"))
        assertTrue(text.contains("где: Дом &amp; сад"))
        // Заголовок формата — единственный настоящий тег в сообщении.
        assertEquals(1, Regex("<b>").findAll(text).count())
    }

    @Test
    fun `stage2 — подтверждённые, очередь и дедлайн = старт события`() {
        val text = renderer.stage2Text(event, confirmed = 12, waitlisted = 2)
        assertTrue(text.contains("<b>Обычная встреча</b>"))
        assertTrue(text.contains("✅ Подтвердили — 12 из 15"))
        assertTrue(text.contains("📋 В очереди — 2"))
        assertTrue(text.contains("⏳ Подтвердить до — 12.07.2026 19:00 МСК"))
    }

    // Открытая встреча (V62): лимита нет — строка мест без числа, счёт без знаменателя, очереди нет.
    @Test
    fun `открытая встреча — без лимита мест, счёт без знаменателя и без строки очереди`() {
        val open = event.copy(participantLimit = null)

        val stage1 = renderer.stage1Text(open, going = 9, maybe = 3)
        assertTrue(stage1.contains("<b>Открытая встреча</b>"))
        assertTrue(stage1.contains("👥 Без лимита мест — приходят все желающие"))
        assertFalse(stage1.contains("Мест —"))

        val stage2 = renderer.stage2Text(open, confirmed = 12, waitlisted = 0)
        assertTrue(stage2.contains("<b>Открытая встреча</b>"))
        assertTrue(stage2.contains("✅ Подтвердили — 12\n"))
        assertFalse(stage2.contains("Подтвердили — 12 из"))
        assertFalse(stage2.contains("В очереди"))

        val closed = renderer.closedText(open, confirmed = 12)
        assertTrue(closed.contains("✅ Подтвердили — 12"))
    }

    @Test
    fun `кнопка зависит от этапа`() {
        assertEquals("Проголосовать", renderer.buttonText(event))
        assertEquals("Подтвердить участие", renderer.buttonText(event.copy(stage2Triggered = true)))
    }

    @Test
    fun `url кнопки — Main Mini App диплинк с event-payload (не WebApp, не short-name app)`() {
        assertEquals(
            "https://t.me/clubs_test_bot?startapp=event_00000000-0000-0000-0000-000000000001",
            renderer.eventUrl(event.id)
        )
    }

    @Test
    fun `итог — один впервые пришедший`() {
        val text = renderer.summaryText(14, attended = 11, confirmedTotal = 13, firstTimerNames = listOf("Наташа"), nextEvent = null)
        assertTrue(text.contains("Встреча №14 прошла ✅"))
        assertTrue(text.contains("👥 Пришли — 11 из 13"))
        assertTrue(text.contains("🎉 Наташа — впервые на встрече клуба"))
        assertFalse(text.contains("Следующая"))
    }

    @Test
    fun `итог — двое склеиваются через «и», больше трёх — «и ещё k»`() {
        val two = renderer.summaryText(2, 5, 6, listOf("Марк", "Наташа"), null)
        assertTrue(two.contains("🎉 Марк и Наташа — впервые"))

        val five = renderer.summaryText(2, 5, 6, listOf("Аня", "Марк", "Наташа", "Оля", "Пётр"), null)
        assertTrue(five.contains("🎉 Аня, Марк, Наташа и ещё 2 — впервые"))
    }

    @Test
    fun `итог — без Этапа 2 знаменатель не рендерится, следующее событие с датой`() {
        val next = event.copy(title = "Кино", eventDatetime = OffsetDateTime.of(2026, 7, 24, 16, 0, 0, 0, ZoneOffset.UTC))
        val text = renderer.summaryText(3, attended = 7, confirmedTotal = 0, firstTimerNames = emptyList(), nextEvent = next)
        assertTrue(text.contains("👥 Пришли — 7\n") || text.endsWith("Пришли — 7") || text.contains("Пришли — 7\n\n"))
        assertFalse(text.contains("из 0"))
        assertFalse(text.contains("🎉"))
        assertTrue(text.contains("Следующая — 24.07.2026 19:00 МСК: Кино"))
    }

    @Test
    fun `отмена — причина опциональна`() {
        val withReason = renderer.cancelledText(event, "все заболели")
        assertTrue(withReason.contains("<b>Обычная встреча отменена</b>"))
        assertTrue(withReason.contains("причина: все заболели"))
        assertFalse(renderer.cancelledText(event, null).contains("причина"))
    }

    @Test
    fun `переезд — между «было» и «стало» пустая строка, иначе адреса слипаются`() {
        val moved = event.copy(locationText = "парк имени Революции 1905 года")
        val text = renderer.editedText(EventEditedEvent(event = moved, oldEvent = event))

        assertTrue(text.contains("<b>Обычная встреча меняет место</b>"))
        // Именно \n\n: адреса длинные, переносятся на две-три строки и без отступа читаются
        // как один абзац — разница «было/стало» теряется (правка PO 2026-08-10).
        assertTrue(text.contains("где было: Сандуны\n\nгде стало: парк имени Революции 1905 года"))
        // Правка — повод переголосовать, и об этом просим текстом, а не только кнопкой.
        assertTrue(text.trimEnd().endsWith("Проголосуй в клубе!"))
    }

    @Test
    fun `финал при старте — «Событие началось», не «Сбор закрыт» (сбор = складчина, путало PO)`() {
        val text = renderer.closedText(event, confirmed = 12)
        assertTrue(text.contains("<b>Обычная встреча началась</b>"))
        assertTrue(text.contains("✅ Подтвердили — 12 из 15"))
        assertTrue(text.contains("Итог появится после отметки явки"))
    }
}
