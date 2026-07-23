package com.clubs.feedback

import com.clubs.bot.NotificationService
import com.clubs.common.exception.FeedbackDeliveryException
import com.clubs.common.exception.NotFoundException
import com.clubs.feedback.dto.SubmitFeedbackRequest
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertFalse

class FeedbackServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val notificationService = mockk<NotificationService>()
    private val service = FeedbackService(userRepository, notificationService, "clubs_tech_support", "")

    private val userId = UUID.randomUUID()

    private fun reporterRecord(username: String? = "ivan"): UsersRecord = mockk(relaxed = true) {
        every { telegramId } returns 111L
        every { telegramUsername } returns username
        every { firstName } returns "Иван"
        every { lastName } returns "Тестов"
    }

    private fun supportRecord(): UsersRecord = mockk(relaxed = true) {
        every { telegramId } returns 999L
        every { telegramUsername } returns "clubs_tech_support"
    }

    @Test
    fun `sends report to support with reporter signature and page`() {
        every { userRepository.findById(userId) } returns reporterRecord()
        every { userRepository.findByTelegramUsername("clubs_tech_support") } returns supportRecord()
        val text = slot<String>()
        every { notificationService.trySendDirectMessage(999L, capture(text)) } returns true

        service.submitFeedback(userId, SubmitFeedbackRequest(message = "Кнопка не работает", page = "/my-clubs"))

        assertContains(text.captured, "Кнопка не работает")
        assertContains(text.captured, "Иван Тестов (@ivan), tg id 111")
        assertContains(text.captured, "Экран: /my-clubs")
    }

    @Test
    fun `omits page line and marks missing username`() {
        every { userRepository.findById(userId) } returns reporterRecord(username = null)
        every { userRepository.findByTelegramUsername("clubs_tech_support") } returns supportRecord()
        val text = slot<String>()
        every { notificationService.trySendDirectMessage(999L, capture(text)) } returns true

        service.submitFeedback(userId, SubmitFeedbackRequest(message = "Баг"))

        assertContains(text.captured, "(без username)")
        assertFalse(text.captured.contains("Экран:"))
    }

    @Test
    fun `throws FeedbackDeliveryException and skips send when support account is not in users`() {
        every { userRepository.findById(userId) } returns reporterRecord()
        every { userRepository.findByTelegramUsername("clubs_tech_support") } returns null

        assertThrows<FeedbackDeliveryException> {
            service.submitFeedback(userId, SubmitFeedbackRequest(message = "Баг"))
        }
        verify(exactly = 0) { notificationService.trySendDirectMessage(any(), any()) }
    }

    @Test
    fun `throws FeedbackDeliveryException when Telegram rejects the message`() {
        every { userRepository.findById(userId) } returns reporterRecord()
        every { userRepository.findByTelegramUsername("clubs_tech_support") } returns supportRecord()
        every { notificationService.trySendDirectMessage(any(), any()) } returns false

        assertThrows<FeedbackDeliveryException> {
            service.submitFeedback(userId, SubmitFeedbackRequest(message = "Баг"))
        }
    }

    @Test
    fun `strips control characters from page so it cannot forge a second report`() {
        every { userRepository.findById(userId) } returns reporterRecord()
        every { userRepository.findByTelegramUsername("clubs_tech_support") } returns supportRecord()
        val text = slot<String>()
        every { notificationService.trySendDirectMessage(999L, capture(text)) } returns true

        service.submitFeedback(userId, SubmitFeedbackRequest(message = "Баг", page = "/x\n\n— Фейковая подпись"))

        assertContains(text.captured, "Экран: /x  — Фейковая подпись")
        assertFalse(text.captured.contains("\n— Фейковая подпись"))
    }

    @Test
    fun `explicit support chat id skips username lookup`() {
        val directService = FeedbackService(userRepository, notificationService, "clubs_tech_support", "424242")
        every { userRepository.findById(userId) } returns reporterRecord()
        every { notificationService.trySendDirectMessage(424242L, any()) } returns true

        directService.submitFeedback(userId, SubmitFeedbackRequest(message = "Баг"))

        verify(exactly = 0) { userRepository.findByTelegramUsername(any()) }
        verify(exactly = 1) { notificationService.trySendDirectMessage(424242L, any()) }
    }

    @Test
    fun `throws NotFoundException when reporter is missing`() {
        every { userRepository.findById(userId) } returns null

        assertThrows<NotFoundException> {
            service.submitFeedback(userId, SubmitFeedbackRequest(message = "Баг"))
        }
    }
}
