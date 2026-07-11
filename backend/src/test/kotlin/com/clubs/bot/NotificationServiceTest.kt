package com.clubs.bot

import com.clubs.event.Event
import com.clubs.event.EventResponseRepository
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.membership.MembershipRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Аудитория DM об ОТМЕНЕ события: все участники клуба с доступом (UPDATED 2026-07-05),
 * а не только going/maybe-голосовавшие.
 */
class NotificationServiceTest {

    private val membershipRepository = mockk<MembershipRepository>(relaxed = true)
    private val eventResponseRepository = mockk<EventResponseRepository>(relaxed = true)
    private val telegramClient = mockk<TelegramClient>(relaxed = true)
    private val gateway = mockk<ChatTelegramGateway>(relaxed = true)
    private val service = NotificationService(
        membershipRepository, eventResponseRepository, telegramClient, ChatAwareBroadcast(gateway), "bot", "https://app"
    )

    @Test
    fun `sendEventCancelled notifies ALL club members with access, not just voters`() {
        val event = sampleEvent()
        every { membershipRepository.findMemberTelegramIds(event.clubId) } returns listOf(101L, 102L, 103L)

        service.sendEventCancelled(event, reason = "погода")

        // аудитория = все участники клуба, НЕ подмножество going/maybe-голосовавших
        verify(exactly = 1) { membershipRepository.findMemberTelegramIds(event.clubId) }
        verify(exactly = 0) { eventResponseRepository.findStage2TargetTelegramIds(any()) }
        verify(exactly = 3) { telegramClient.execute(any<SendMessage>()) }
    }

    @Test
    fun `sendEventCancelled with no members sends nothing`() {
        val event = sampleEvent()
        every { membershipRepository.findMemberTelegramIds(event.clubId) } returns emptyList()

        service.sendEventCancelled(event, reason = null)

        verify(exactly = 0) { telegramClient.execute(any<SendMessage>()) }
    }

    @Test
    fun `маршрутизатор — участники чата не получают DM об отмене, остальные получают`() {
        val event = sampleEvent()
        val chatId = -100123L
        every { membershipRepository.findMemberTelegramIds(event.clubId) } returns listOf(101L, 102L, 103L)
        every { gateway.getUserChatState(chatId, 101L) } returns UserChatState.IN_CHAT
        every { gateway.getUserChatState(chatId, 102L) } returns UserChatState.NOT_IN_CHAT
        // Telegram молчит про 103 → UNKNOWN → DM (лишний DM лучше потерянного уведомления)
        every { gateway.getUserChatState(chatId, 103L) } returns UserChatState.UNKNOWN

        service.sendEventCancelled(event, reason = "погода", chatPostChatId = chatId)

        verify(exactly = 2) { telegramClient.execute(any<SendMessage>()) }
    }

    @Test
    fun `маршрутизатор — событие создано, пост вышел, DM только не-в-чате`() {
        val event = sampleEvent()
        val chatId = -100123L
        every { membershipRepository.findMemberTelegramIds(event.clubId) } returns listOf(101L, 102L)
        every { gateway.getUserChatState(chatId, 101L) } returns UserChatState.IN_CHAT
        every { gateway.getUserChatState(chatId, 102L) } returns UserChatState.NOT_IN_CHAT

        service.sendEventCreated(event, chatPostChatId = chatId)

        verify(exactly = 1) { telegramClient.execute(any<SendMessage>()) }
    }

    @Test
    fun `маршрутизатор — поста нет (null) — DM всем, как до чат-интеграции`() {
        val event = sampleEvent()
        every { membershipRepository.findMemberTelegramIds(event.clubId) } returns listOf(101L, 102L)

        service.sendEventCreated(event, chatPostChatId = null)

        verify(exactly = 2) { telegramClient.execute(any<SendMessage>()) }
        verify(exactly = 0) { gateway.getUserChatState(any(), any()) }
    }

    // ---- event-geo: кнопка «Открыть в Яндекс.Картах» в DM о новом событии ----

    @Test
    fun `событие с гео-точкой — DM с кнопкой Яндекс_Карт (lon,lat в pt) второй строкой`() {
        val event = sampleEvent(lat = 55.761216, lon = 37.646488)
        every { membershipRepository.findMemberTelegramIds(event.clubId) } returns listOf(101L)
        val msgSlot = slot<SendMessage>()
        every { telegramClient.execute(capture(msgSlot)) } returns mockk(relaxed = true)

        service.sendEventCreated(event)

        verify(exactly = 1) { telegramClient.execute(any<SendMessage>()) }
        val urls = markupUrls(msgSlot.captured.replyMarkup as InlineKeyboardMarkup)
        // порядок Яндекса в pt — lon,lat
        assertTrue(urls.any { it.contains("yandex.ru/maps") && it.contains("pt=37.646488,55.761216") })
    }

    @Test
    fun `событие без координат — обычный текстовый DM без кнопки карт`() {
        val event = sampleEvent()
        every { membershipRepository.findMemberTelegramIds(event.clubId) } returns listOf(101L)
        val msgSlot = slot<SendMessage>()
        every { telegramClient.execute(capture(msgSlot)) } returns mockk(relaxed = true)

        service.sendEventCreated(event)

        verify(exactly = 1) { telegramClient.execute(any<SendMessage>()) }
        assertTrue(markupUrls(msgSlot.captured.replyMarkup as InlineKeyboardMarkup).none { it.contains("yandex.ru/maps") })
    }

    @Test
    fun `событие без места — строка 📍 в тексте DM не рендерится`() {
        val event = sampleEvent(locationText = null)
        every { membershipRepository.findMemberTelegramIds(event.clubId) } returns listOf(101L)
        val msgSlot = slot<SendMessage>()
        every { telegramClient.execute(capture(msgSlot)) } returns mockk(relaxed = true)

        service.sendEventCreated(event)

        assertTrue(!msgSlot.captured.text.contains("📍"))
    }

    @Test
    fun `уточнение к месту попадает в текст DM в скобках после адреса`() {
        val event = sampleEvent(locationHint = "Вход со двора")
        every { membershipRepository.findMemberTelegramIds(event.clubId) } returns listOf(101L)
        val msgSlot = slot<SendMessage>()
        every { telegramClient.execute(capture(msgSlot)) } returns mockk(relaxed = true)

        service.sendEventCreated(event)

        assertTrue(msgSlot.captured.text.contains("📍 Бар (Вход со двора)"))
    }

    @Test
    fun `hint-only событие — уточнение как место в тексте DM, кнопки карт нет`() {
        val event = sampleEvent(locationText = null, locationHint = "Встречаемся в зуме")
        every { membershipRepository.findMemberTelegramIds(event.clubId) } returns listOf(101L)
        val msgSlot = slot<SendMessage>()
        every { telegramClient.execute(capture(msgSlot)) } returns mockk(relaxed = true)

        service.sendEventCreated(event)

        assertTrue(msgSlot.captured.text.contains("📍 Встречаемся в зуме"))
        assertTrue(markupUrls(msgSlot.captured.replyMarkup as InlineKeyboardMarkup).none { it.contains("yandex.ru/maps") })
    }

    // ---- фото события в DM (PO 2026-07-11) ----

    @Test
    fun `событие с фото — DM уходит фото-сообщением с абсолютным URL и тем же текстом в caption`() {
        val event = sampleEvent(photoUrl = "/uploads/cover.jpg")
        every { membershipRepository.findMemberTelegramIds(event.clubId) } returns listOf(101L)
        val photoSlot = slot<SendPhoto>()
        every { telegramClient.execute(capture(photoSlot)) } returns mockk(relaxed = true)

        service.sendEventCreated(event)

        verify(exactly = 1) { telegramClient.execute(any<SendPhoto>()) }
        verify(exactly = 0) { telegramClient.execute(any<SendMessage>()) }
        // относительный /uploads/… превращается в абсолютный URL фронта — иначе Telegram не скачает
        assertTrue(photoSlot.captured.photo.attachName == "https://app/uploads/cover.jpg")
        assertTrue(photoSlot.captured.caption!!.contains("Событие"))
    }

    @Test
    fun `фото + гео-точка — в фото-DM есть кнопка Яндекс_Карт`() {
        val event = sampleEvent(lat = 55.761216, lon = 37.646488, photoUrl = "https://cdn.example.com/c.jpg")
        every { membershipRepository.findMemberTelegramIds(event.clubId) } returns listOf(101L)
        val photoSlot = slot<SendPhoto>()
        every { telegramClient.execute(capture(photoSlot)) } returns mockk(relaxed = true)

        service.sendEventCreated(event)

        assertTrue(photoSlot.captured.photo.attachName == "https://cdn.example.com/c.jpg")
        assertTrue(markupUrls(photoSlot.captured.replyMarkup as InlineKeyboardMarkup).any { it.contains("yandex.ru/maps") })
    }

    @Test
    fun `сбой отправки фото деградирует до текстового DM`() {
        val event = sampleEvent(photoUrl = "/uploads/cover.jpg")
        every { membershipRepository.findMemberTelegramIds(event.clubId) } returns listOf(101L)
        every { telegramClient.execute(any<SendPhoto>()) } throws RuntimeException("wrong file identifier")
        every { telegramClient.execute(any<SendMessage>()) } returns mockk(relaxed = true)

        service.sendEventCreated(event)

        verify(exactly = 1) { telegramClient.execute(any<SendPhoto>()) }
        verify(exactly = 1) { telegramClient.execute(any<SendMessage>()) }
    }

    /** Все url-значения кнопок клавиатуры (webApp-кнопки дают null и отфильтровываются). */
    private fun markupUrls(markup: InlineKeyboardMarkup): List<String> =
        markup.keyboard.flatten().mapNotNull { it.url }

    private fun sampleEvent(
        lat: Double? = null,
        lon: Double? = null,
        locationText: String? = "Бар",
        locationHint: String? = null,
        photoUrl: String? = null
    ) = Event(
        id = UUID.randomUUID(),
        clubId = UUID.randomUUID(),
        createdBy = UUID.randomUUID(),
        title = "Событие",
        description = null,
        locationText = locationText,
        locationLat = lat,
        locationLon = lon,
        locationHint = locationHint,
        eventDatetime = OffsetDateTime.now().plusDays(3),
        participantLimit = 20,
        votingOpensDaysBefore = 14,
        status = EventStatus.stage_2,
        stage2Triggered = true,
        attendanceMarked = false,
        attendanceFinalized = false,
        photoUrl = photoUrl,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )
}
