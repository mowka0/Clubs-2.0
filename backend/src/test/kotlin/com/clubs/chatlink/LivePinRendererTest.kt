package com.clubs.chatlink

import com.clubs.event.Event
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
        assertTrue(text.contains("📅 Поход в баню"))
        assertTrue(text.contains("12.07.2026 19:00 МСК"))
        assertTrue(text.contains("✅ Идут — 9"))
        assertTrue(text.contains("🤔 Возможно — 3"))
        assertTrue(text.contains("👥 Мест — 15"))
    }

    @Test
    fun `место в закрепе — уточнение в скобках после адреса, у события без места строки 📍 нет`() {
        assertTrue(renderer.stage1Text(event, going = 1, maybe = 0).contains("📍 Сандуны"))
        val withHint = renderer.stage1Text(event.copy(locationHint = "3-й этаж"), going = 1, maybe = 0)
        assertTrue(withHint.contains("📍 Сандуны (3-й этаж)"))
        val hintOnly = renderer.stage1Text(event.copy(locationText = null, locationHint = "В зуме"), going = 1, maybe = 0)
        assertTrue(hintOnly.contains("📍 В зуме"))
        val noLocation = renderer.stage1Text(event.copy(locationText = null), going = 1, maybe = 0)
        assertTrue(!noLocation.contains("📍"))
    }

    @Test
    fun `stage2 — подтверждённые, очередь и дедлайн = старт события`() {
        val text = renderer.stage2Text(event, confirmed = 12, waitlisted = 2)
        assertTrue(text.contains("подтверждение мест"))
        assertTrue(text.contains("✅ Подтвердили — 12 из 15"))
        assertTrue(text.contains("📋 В очереди — 2"))
        assertTrue(text.contains("⏳ Подтвердить до — 12.07.2026 19:00 МСК"))
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
        assertTrue(renderer.cancelledText(event, "все заболели").contains("Причина: все заболели"))
        assertFalse(renderer.cancelledText(event, null).contains("Причина"))
    }

    @Test
    fun `финал при старте — «Событие началось», не «Сбор закрыт» (сбор = складчина, путало PO)`() {
        val text = renderer.closedText(event, confirmed = 12)
        assertTrue(text.contains("Событие началось — подтвердили 12 из 15"))
        assertTrue(text.contains("Итог появится после отметки явки"))
    }
}
