package com.clubs.bot

import com.clubs.chatlink.ChatLinkBotService
import com.clubs.event.Event
import com.clubs.event.EventRepository
import com.clubs.event.EventResponseRepository
import com.clubs.generated.jooq.enums.EventStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClubsBotTest {

    private lateinit var telegramClient: TelegramClient
    private lateinit var eventRepository: EventRepository
    private lateinit var eventResponseRepository: EventResponseRepository
    private lateinit var chatLinkBotService: ChatLinkBotService
    private lateinit var bot: ClubsBot

    @BeforeEach
    fun setUp() {
        telegramClient = mockk(relaxed = true)
        eventRepository = mockk(relaxed = true)
        eventResponseRepository = mockk(relaxed = true)
        chatLinkBotService = mockk(relaxed = true)
        bot = ClubsBot(
            botToken = "dummy-token",
            telegramClient = telegramClient,
            eventRepository = eventRepository,
            eventResponseRepository = eventResponseRepository,
            chatLinkBotService = chatLinkBotService,
            chatDoorService = mockk(relaxed = true),
            rosterCallbackService = mockk(relaxed = true),
            skladchinaCallbackService = mockk(relaxed = true)
        )
    }

    private fun buildQuery(id: String, payload: String): PreCheckoutQuery =
        mockk {
            every { this@mockk.id } returns id
            every { invoicePayload } returns payload
        }

    @Test
    fun `handlePreCheckoutQuery always rejects (ok=false) — Stars pay-to-join retired`() {
        // De-Stars: every pre_checkout is rejected so no member is ever charged through the bot —
        // even a once-valid club_subscription payload.
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val query = buildQuery("Q-1", "club_subscription:$clubId:$userId")

        val sent = slot<AnswerPreCheckoutQuery>()
        every { telegramClient.execute(capture(sent)) } returns mockk(relaxed = true)

        bot.handlePreCheckoutQuery(query)

        verify(exactly = 1) { telegramClient.execute(any<AnswerPreCheckoutQuery>()) }
        assertEquals("Q-1", sent.captured.preCheckoutQueryId)
        assertEquals(false, sent.captured.ok)
        assertEquals("Оплата через бота больше не используется. Доступ к клубу открывает организатор.", sent.captured.errorMessage)
    }

    @Test
    fun `handlePreCheckoutQuery rejects any payload shape`() {
        val query = buildQuery("Q-2", "not_a_valid_format")

        val sent = slot<AnswerPreCheckoutQuery>()
        every { telegramClient.execute(capture(sent)) } returns mockk(relaxed = true)

        bot.handlePreCheckoutQuery(query)

        assertEquals(false, sent.captured.ok)
    }

    @Test
    fun `handlePreCheckoutQuery swallows Telegram API exception without rethrowing`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val query = buildQuery("Q-4", "club_subscription:$clubId:$userId")

        every { telegramClient.execute(any<AnswerPreCheckoutQuery>()) } throws RuntimeException("telegram api down")

        // Should NOT throw — a bot failure here would propagate up the long-polling loop
        // and kill update handling for every subsequent update.
        bot.handlePreCheckoutQuery(query)
    }

    // ---- «/кто_идет»: скоуп по привязанному чату (bugfix 2026-09-15) ----

    private fun whoIsGoingUpdate(chatId: Long, chatType: String): Update {
        val message: Message = mockk(relaxed = true) {
            every { hasText() } returns true
            every { text } returns "/кто_идет"
            every { this@mockk.chatId } returns chatId
            every { migrateToChatId } returns null
            every { hasSuccessfulPayment() } returns false
            every { chat } returns mockk(relaxed = true) { every { type } returns chatType }
        }
        return mockk(relaxed = true) {
            every { hasMessage() } returns true
            every { this@mockk.message } returns message
        }
    }

    private fun sampleEvent(clubId: UUID) = Event(
        id = UUID.randomUUID(),
        clubId = clubId,
        createdBy = UUID.randomUUID(),
        title = "Забег в парке",
        description = null,
        locationText = "Парк Горького, вход у фонтана",
        eventDatetime = OffsetDateTime.now().plusDays(2),
        participantLimit = 10,
        votingOpensDaysBefore = 14,
        status = EventStatus.upcoming,
        stage2Triggered = false,
        attendanceMarked = false,
        attendanceFinalized = false,
        photoUrl = null,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )

    private fun captureSentText(): String {
        val sent = slot<SendMessage>()
        verify { telegramClient.execute(capture(sent)) }
        return sent.captured.text
    }

    @Test
    fun `кто_идет в личке не читает встречи и отвечает подсказкой`() {
        // Дыра до 2026-09-15: команда отдавала в личку ближайшую встречу ЛЮБОГО клуба с адресом.
        bot.consume(whoIsGoingUpdate(chatId = 777L, chatType = "private"))

        verify(exactly = 0) { eventRepository.findFutureEventsByClub(any(), any()) }
        val text = captureSentText()
        assertTrue(text.contains("чате клуба"), text)
        assertFalse(text.contains("Парк"), text)
    }

    @Test
    fun `кто_идет в непривязанной группе не читает встречи`() {
        every { chatLinkBotService.findLinkedClubId(-100L) } returns null

        bot.consume(whoIsGoingUpdate(chatId = -100L, chatType = "supergroup"))

        verify(exactly = 0) { eventRepository.findFutureEventsByClub(any(), any()) }
        assertTrue(captureSentText().contains("не привязан"), captureSentText())
    }

    @Test
    fun `кто_идет в привязанной группе отдаёт ближайшую встречу ЭТОГО клуба`() {
        val clubId = UUID.randomUUID()
        val event = sampleEvent(clubId)
        every { chatLinkBotService.findLinkedClubId(-100L) } returns clubId
        every { eventRepository.findFutureEventsByClub(clubId, any()) } returns listOf(event)
        every { eventResponseRepository.countByVote(event.id) } returns mapOf("going" to 3, "maybe" to 1)

        bot.consume(whoIsGoingUpdate(chatId = -100L, chatType = "supergroup"))

        // Клуб берётся из привязки чата, а не «ближайший по платформе».
        verify(exactly = 1) { eventRepository.findFutureEventsByClub(clubId, any()) }
        val text = captureSentText()
        assertTrue(text.contains("Забег в парке"), text)
        assertTrue(text.contains("Парк Горького"), text)
        assertTrue(text.contains("Пойдут: 3"), text)
    }

    @Test
    fun `кто_идет в привязанной группе без будущих встреч отвечает пустым списком`() {
        val clubId = UUID.randomUUID()
        every { chatLinkBotService.findLinkedClubId(-100L) } returns clubId
        every { eventRepository.findFutureEventsByClub(clubId, any()) } returns emptyList()

        bot.consume(whoIsGoingUpdate(chatId = -100L, chatType = "supergroup"))

        assertTrue(captureSentText().contains("Ближайших встреч нет"), captureSentText())
    }
}
