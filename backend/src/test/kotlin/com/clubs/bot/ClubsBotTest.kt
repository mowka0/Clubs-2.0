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
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
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
    private lateinit var legalSheet: LegalSheet
    private lateinit var bot: ClubsBot

    @BeforeEach
    fun setUp() {
        telegramClient = mockk(relaxed = true)
        eventRepository = mockk(relaxed = true)
        eventResponseRepository = mockk(relaxed = true)
        chatLinkBotService = mockk(relaxed = true)
        legalSheet = LegalSheet(
            recipientName = "Тестов Тест Тестович", recipientInn = "000000000000", billingProvider = "stub",
            supportUsername = "clubs_tech_support", supportEmail = "support@example.com",
            webAppBaseUrl = "https://app.test", trialDays = 15, subscriptionRepository = mockk { every { currentPriceKopecks(any()) } returns 19900 },
        )
        bot = ClubsBot(
            botToken = "dummy-token",
            telegramClient = telegramClient,
            eventRepository = eventRepository,
            eventResponseRepository = eventResponseRepository,
            chatLinkBotService = chatLinkBotService,
            chatDoorService = mockk(relaxed = true),
            rosterCallbackService = mockk(relaxed = true),
            skladchinaCallbackService = mockk(relaxed = true),
            legalSheet = legalSheet
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

    // ---- «/start», «/terms» и «шторка» оферты/политики (Robokassa: магазин = бот, 2026-09-20) ----

    private fun textUpdate(text: String, chatType: String, chatId: Long = 42L): Update {
        val message: Message = mockk(relaxed = true) {
            every { hasText() } returns true
            every { this@mockk.text } returns text
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

    private fun callbackUpdate(data: String, messageChatId: Long = 42L): Update {
        val query: CallbackQuery = mockk(relaxed = true) {
            every { this@mockk.data } returns data
            every { id } returns "cb-1"
            every { from } returns mockk(relaxed = true) { every { id } returns 42L }
            every { message } returns mockk<MaybeInaccessibleMessage>(relaxed = true) {
                every { chatId } returns messageChatId
                every { messageId } returns 7
            }
        }
        return mockk(relaxed = true) {
            every { hasCallbackQuery() } returns true
            every { callbackQuery } returns query
        }
    }

    private fun InlineKeyboardMarkup.buttons() = keyboard.flatten()

    @Test
    fun `start в личке отдаёт обязательную информацию и кнопки оферты, политики, поддержки, Mini App`() {
        val sent = slot<SendMessage>()
        every { telegramClient.execute(capture(sent)) } returns mockk(relaxed = true)

        bot.consume(textUpdate("/start", "private"))

        assertEquals(legalSheet.infoBlock(), sent.captured.text)
        val buttons = (sent.captured.replyMarkup as InlineKeyboardMarkup).buttons()
        // Mini App — по базовому URL окружения, как у всех WebApp-кнопок; t.me/<бот>/app на staging не работал.
        assertEquals("https://app.test", buttons.first { it.webApp != null }.webApp.url)
        assertEquals(setOf("legal:offer", "legal:privacy:0"), buttons.mapNotNull { it.callbackData }.toSet())
        assertEquals("https://t.me/clubs_tech_support", buttons.first { it.url != null }.url)
    }

    @Test
    fun `terms повторяет стартовое сообщение в личке и молчит в группе`() {
        val sent = mutableListOf<SendMessage>()
        every { telegramClient.execute(capture(sent)) } returns mockk(relaxed = true)

        bot.consume(textUpdate("/terms", "private"))
        bot.consume(textUpdate("/terms", "supergroup"))

        assertEquals(1, sent.size)
        assertEquals(legalSheet.infoBlock(), sent.single().text)
    }

    @Test
    fun `кнопка оферты правит то же сообщение и гасит спиннер без алерта`() {
        val edited = slot<EditMessageText>()
        val answered = slot<AnswerCallbackQuery>()
        every { telegramClient.execute(capture(edited)) } returns mockk(relaxed = true)
        every { telegramClient.execute(capture(answered)) } returns mockk(relaxed = true)

        bot.consume(callbackUpdate("legal:offer"))

        assertEquals("42", edited.captured.chatId)
        assertEquals(7, edited.captured.messageId)
        assertEquals(legalSheet.offerPages().first(), edited.captured.text)
        assertEquals(listOf("legal:offer:1", "legal:info"), (edited.captured.replyMarkup as InlineKeyboardMarkup).buttons().map { it.callbackData })
        assertEquals("cb-1", answered.captured.callbackQueryId)
        assertEquals(null, answered.captured.text)
    }

    @Test
    fun `номер страницы политики за пределами диапазона прижимается к последней, назад возвращает старт`() {
        val edited = mutableListOf<EditMessageText>()
        every { telegramClient.execute(capture(edited)) } returns mockk(relaxed = true)
        every { telegramClient.execute(ofType<AnswerCallbackQuery>()) } returns mockk(relaxed = true)

        bot.consume(callbackUpdate("legal:privacy:99"))
        bot.consume(callbackUpdate("legal:info"))

        assertEquals(legalSheet.privacyPages().last(), edited[0].text)
        assertEquals(legalSheet.infoBlock(), edited[1].text)
    }

    @Test
    fun `callback с сообщения бота в группе не правит его (закреп нельзя переписать офертой)`() {
        every { telegramClient.execute(ofType<AnswerCallbackQuery>()) } returns mockk(relaxed = true)

        bot.consume(callbackUpdate("legal:offer", messageChatId = -100123L))

        verify(exactly = 0) { telegramClient.execute(ofType<EditMessageText>()) }
        verify(exactly = 1) { telegramClient.execute(ofType<AnswerCallbackQuery>()) }
    }

    @Test
    fun `повторное нажатие той же кнопки — не сбой, спиннер гасится без алерта`() {
        val answered = slot<AnswerCallbackQuery>()
        every { telegramClient.execute(ofType<EditMessageText>()) } throws TelegramApiRequestException("Bad Request: message is not modified")
        every { telegramClient.execute(capture(answered)) } returns mockk(relaxed = true)

        bot.consume(callbackUpdate("legal:offer"))

        assertEquals(null, answered.captured.text)
    }

    @Test
    fun `сообщение нельзя поправить (переслано, flood-wait) — человеку алерт с подсказкой про terms`() {
        val answered = slot<AnswerCallbackQuery>()
        every { telegramClient.execute(ofType<EditMessageText>()) } throws TelegramApiRequestException("Bad Request: message can't be edited")
        every { telegramClient.execute(capture(answered)) } returns mockk(relaxed = true)

        bot.consume(callbackUpdate("legal:offer"))

        assertEquals(LegalSheet.EDIT_FAILED_ALERT, answered.captured.text)
        assertEquals(true, answered.captured.showAlert)
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
