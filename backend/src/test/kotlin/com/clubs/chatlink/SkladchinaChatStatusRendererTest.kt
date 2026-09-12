package com.clubs.chatlink

import com.clubs.debt.DebtTotals
import com.clubs.generated.jooq.enums.SkladchinaKind
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.skladchina.Skladchina
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

// Сбор для текстов поста: минимально заполненный, активный по умолчанию.
internal fun rendererSkladchina(
    kind: SkladchinaKind = SkladchinaKind.shared,
    status: SkladchinaStatus = SkladchinaStatus.active,
    amountKopecks: Long? = 600_000L,
    deadline: OffsetDateTime? = OffsetDateTime.now().plusDays(2),
    enrollmentUntil: OffsetDateTime? = null,
    minParticipants: Int? = null,
    lockedAt: OffsetDateTime? = null,
    orderedAt: OffsetDateTime? = null,
    hiddenFromUserId: UUID? = null,
    title: String = "Ужин после игры"
): Skladchina = Skladchina(
    id = UUID.randomUUID(), clubId = UUID.randomUUID(), creatorId = UUID.randomUUID(),
    title = title, description = null, rules = null, photoUrl = null,
    kind = kind, amountKopecks = amountKopecks, paymentLink = "https://bank.example/pay", paymentMethodNote = null,
    eventId = null, deadline = deadline, enrollmentUntil = enrollmentUntil, minParticipants = minParticipants,
    lockedAt = lockedAt, orderedAt = orderedAt, hiddenFromUserId = hiddenFromUserId,
    status = status, closedAt = null, reminderSentAt = null, orderRemindedAt = null,
    createdAt = OffsetDateTime.now(), updatedAt = OffsetDateTime.now()
)

class SkladchinaChatStatusRendererTest {

    private val renderer = SkladchinaChatStatusRenderer(botUsername = "clubs_admin_bot")
    private val now = OffsetDateTime.now()

    private fun view(
        s: Skladchina,
        totals: DebtTotals = DebtTotals(receivedKopecks = 100_000, claimedKopecks = 0, targetKopecks = 600_000, debtCount = 6, receivedCount = 1, openCount = 5, claimedCount = 0),
        waiting: List<ChatMention> = emptyList(),
        enrolled: List<ChatMention> = emptyList(),
        enrolledCount: Int = enrolled.size,
        at: OffsetDateTime = now
    ) = ChatStatusView(s, totals, enrolledCount, creatorName = "Иван", waiting = waiting, enrolled = enrolled, now = at)

    @Test
    fun `shared status shows money progress, share per person and mentions before the deadline`() {
        val text = renderer.statusText(view(rendererSkladchina(), waiting = listOf(ChatMention(11L, "Саша"), ChatMention(12L, "Оля"))))
        assertTrue(text.contains("по 1 000 ₽ с человека"), text)
        assertTrue(text.contains("Получено 1 000 ₽ из 6 000 ₽"), text)
        assertTrue(text.contains("оплатили 1 из 6"), text)
        assertTrue(text.contains("Ждём: "), text)
        assertTrue(text.contains("<a href=\"tg://user?id=11\">Саша</a>"), text)
        assertTrue(text.contains("🟩"), text)
    }

    @Test
    fun `after the deadline only the number of unpaid is shown, no names (PO 2026-09-12)`() {
        val past = rendererSkladchina(deadline = now.minusHours(1))
        val text = renderer.statusText(view(past, waiting = listOf(ChatMention(11L, "Саша"))))
        assertTrue(text.contains("Срок вышел · не оплатили 5"), text)
        assertFalse(text.contains("Ждём"), text)
        assertFalse(text.contains("Саша"), text)
    }

    @Test
    fun `enrolling stage lists who is in and the minimum, button says В деле`() {
        val s = rendererSkladchina(enrollmentUntil = now.plusDays(1), minParticipants = 6)
        val text = renderer.statusText(view(s, totals = DebtTotals.EMPTY, enrolled = listOf(ChatMention(1L, "Иван"), ChatMention(2L, "Оля"))))
        assertTrue(text.contains("6 000 ₽ на группу"), text)
        assertTrue(text.contains("В деле 2 · нужно 6"), text)
        assertTrue(text.contains("Отметиться до"), text)
        assertEquals("В деле", renderer.buttonText(s))
    }

    @Test
    fun `per_head shows takers and Беру button, after order shows bought count`() {
        val s = rendererSkladchina(kind = SkladchinaKind.per_head, amountKopecks = 150_000)
        val text = renderer.statusText(view(s, totals = DebtTotals(300_000, 0, 450_000, 3, 2, 1, 0)))
        assertTrue(text.contains("1 500 ₽ за штуку"), text)
        assertTrue(text.contains("Берут 3 · оплатили 2"), text)
        assertEquals("Беру", renderer.buttonText(s))

        val ordered = rendererSkladchina(kind = SkladchinaKind.per_head, amountKopecks = 150_000, orderedAt = now)
        val orderedText = renderer.statusText(view(ordered, totals = DebtTotals(300_000, 0, 300_000, 2, 2, 0, 0)))
        assertTrue(orderedText.contains("Куплено 2 · 3 000 ₽ · приём закрыт"), orderedText)
        assertEquals("Открыть сбор", renderer.buttonText(ordered))
    }

    @Test
    fun `closed texts distinguish collected, cancelled and shortfall`() {
        val collected = rendererSkladchina(status = SkladchinaStatus.collected)
        assertTrue(renderer.closedText(view(collected, totals = DebtTotals(600_000, 0, 600_000, 6, 6, 0, 0))).contains("Собрано 6 000 ₽ · оплатили 6 из 6"))

        val cancelled = rendererSkladchina(status = SkladchinaStatus.cancelled)
        assertTrue(renderer.closedText(view(cancelled)).contains("Сбор отменён"))

        val shortfall = rendererSkladchina(status = SkladchinaStatus.cancelled, enrollmentUntil = now, minParticipants = 6, lockedAt = now)
        val text = renderer.closedText(view(shortfall, totals = DebtTotals.EMPTY, enrolledCount = 4))
        assertTrue(text.contains("Не набрали: в деле 4 из 6"), text)
        assertTrue(text.contains("денег никто не переводил"), text)
    }

    @Test
    fun `user input is html-escaped`() {
        val s = rendererSkladchina(title = "<b>Ужин</b> & Co")
        val text = renderer.statusText(view(s, waiting = listOf(ChatMention(5L, "<script>"))))
        assertTrue(text.contains("&lt;b&gt;Ужин&lt;/b&gt; &amp; Co"), text)
        assertTrue(text.contains("&lt;script&gt;"), text)
        assertFalse(text.contains("<b>"), text)
    }
}
