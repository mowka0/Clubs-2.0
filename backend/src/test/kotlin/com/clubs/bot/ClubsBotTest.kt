package com.clubs.bot

import com.clubs.chatlink.ChatLinkBotService
import com.clubs.subscription.FunnelTracker
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
import java.util.UUID
import kotlin.test.assertEquals

class ClubsBotTest {

    private lateinit var telegramClient: TelegramClient
    private lateinit var funnelTracker: FunnelTracker
    private lateinit var chatLinkBotService: ChatLinkBotService
    private lateinit var legalSheet: LegalSheet
    private lateinit var bot: ClubsBot

    @BeforeEach
    fun setUp() {
        telegramClient = mockk(relaxed = true)
        chatLinkBotService = mockk(relaxed = true)
        funnelTracker = mockk(relaxed = true)
        legalSheet = LegalSheet(
            recipientName = "Тестов Тест Тестович", recipientInn = "000000000000", billingProvider = "stub",
            supportUsername = "clubs_tech_support", supportEmail = "support@example.com",
            webAppBaseUrl = "https://app.test", trialDays = 15, subscriptionRepository = mockk { every { currentPriceKopecks(any()) } returns 19900 },
        )
        bot = ClubsBot(
            botToken = "dummy-token",
            telegramClient = telegramClient,
            chatLinkBotService = chatLinkBotService,
            chatDoorService = mockk(relaxed = true),
            rosterCallbackService = mockk(relaxed = true),
            skladchinaCallbackService = mockk(relaxed = true),
            legalSheet = legalSheet,
            funnelTracker = funnelTracker
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

    private fun textUpdate(text: String, chatType: String, chatId: Long = 42L, fromId: Long = chatId): Update {
        val message: Message = mockk(relaxed = true) {
            every { hasText() } returns true
            every { this@mockk.text } returns text
            every { this@mockk.chatId } returns chatId
            every { from } returns mockk(relaxed = true) { every { id } returns fromId }
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
    fun `start в личке пишет шаг воронки - telegram id отправителя и текст команды с меткой`() {
        every { telegramClient.execute(any<SendMessage>()) } returns mockk(relaxed = true)

        bot.consume(textUpdate("/start ad_vk1", "private", fromId = 777L))

        verify { funnelTracker.botStarted(777L, "/start ad_vk1") }
    }

    @Test
    fun `terms в личке и start в группе шаг воронки не пишут`() {
        every { telegramClient.execute(any<SendMessage>()) } returns mockk(relaxed = true)

        bot.consume(textUpdate("/terms", "private"))
        bot.consume(textUpdate("/start new", "supergroup"))

        verify(exactly = 0) { funnelTracker.botStarted(any(), any()) }
    }

    @Test
    fun `сбой учёта воронки не лишает человека приветствия`() {
        val sent = slot<SendMessage>()
        every { telegramClient.execute(capture(sent)) } returns mockk(relaxed = true)
        every { funnelTracker.botStarted(any(), any()) } throws IllegalStateException("db down")

        bot.consume(textUpdate("/start", "private"))

        assertEquals(legalSheet.infoBlock(), sent.captured.text)
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
}
