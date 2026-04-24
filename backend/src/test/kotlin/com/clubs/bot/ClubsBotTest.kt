package com.clubs.bot

import com.clubs.payment.PaymentService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClubsBotTest {

    private lateinit var telegramClient: TelegramClient
    private lateinit var dsl: DSLContext
    private lateinit var paymentService: PaymentService
    private lateinit var bot: ClubsBot

    @BeforeEach
    fun setUp() {
        telegramClient = mockk(relaxed = true)
        dsl = mockk(relaxed = true)
        paymentService = mockk(relaxed = true)
        bot = ClubsBot("dummy-token", telegramClient, dsl, paymentService)
    }

    private fun buildQuery(id: String, payload: String): PreCheckoutQuery =
        mockk {
            every { this@mockk.id } returns id
            every { invoicePayload } returns payload
        }

    @Test
    fun `handlePreCheckoutQuery answers ok=true for valid club_subscription payload`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val query = buildQuery("Q-1", "club_subscription:$clubId:$userId")

        val sent = slot<AnswerPreCheckoutQuery>()
        every { telegramClient.execute(capture(sent)) } returns mockk(relaxed = true)

        bot.handlePreCheckoutQuery(query)

        verify(exactly = 1) { telegramClient.execute(any<AnswerPreCheckoutQuery>()) }
        assertEquals("Q-1", sent.captured.preCheckoutQueryId)
        assertEquals(true, sent.captured.ok)
        assertNull(sent.captured.errorMessage)
    }

    @Test
    fun `handlePreCheckoutQuery answers ok=false with error message for malformed payload`() {
        val query = buildQuery("Q-2", "not_a_valid_format")

        val sent = slot<AnswerPreCheckoutQuery>()
        every { telegramClient.execute(capture(sent)) } returns mockk(relaxed = true)

        bot.handlePreCheckoutQuery(query)

        assertEquals(false, sent.captured.ok)
        assertEquals("Некорректный формат заказа. Попробуйте вступить снова из приложения.", sent.captured.errorMessage)
    }

    @Test
    fun `handlePreCheckoutQuery answers ok=false for wrong prefix`() {
        val query = buildQuery("Q-3", "event_ticket:foo:bar")

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
}
