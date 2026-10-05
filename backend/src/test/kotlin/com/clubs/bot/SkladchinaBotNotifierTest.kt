package com.clubs.bot

import com.clubs.chatlink.SkladchinaChatStatusService
import com.clubs.generated.jooq.enums.SkladchinaKind
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.skladchina.SkladchinaCreatedEvent
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/** DM при создании сбора: фото сбора (обычно чек) уходит картинкой с подписью, любой сбой — прежним текстом. */
class SkladchinaBotNotifierTest {

    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val chatStatusService = mockk<SkladchinaChatStatusService>(relaxed = true)
    private val gateway = mockk<ChatTelegramGateway>(relaxed = true)
    private val notifier = SkladchinaBotNotifier(userRepository, notificationService, chatStatusService, gateway)

    private val creatorId = UUID.randomUUID()
    private val debtorId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        every { userRepository.findByIds(listOf(debtorId)) } returns listOf(UsersRecord(id = debtorId, telegramId = 42L, firstName = "Саша"))
        every { userRepository.findById(creatorId) } returns UsersRecord(id = creatorId, telegramId = 1L, firstName = "Иван")
        every { chatStatusService.onSkladchinaCreated(any(), any()) } returns null
    }

    private fun created(
        photoUrl: String?,
        kind: SkladchinaKind = SkladchinaKind.shared,
        paymentLink: String = "https://bank.example/pay",
        enrollmentUntil: OffsetDateTime? = null,
    ) = SkladchinaCreatedEvent(
        skladchinaId = UUID.randomUUID(), clubId = UUID.randomUUID(), clubName = "Партия", creatorId = creatorId,
        kind = kind, title = "Ужин после игры", description = null, photoUrl = photoUrl,
        paymentLink = paymentLink, paymentMethodNote = null, amountKopecks = 300_000L,
        deadline = OffsetDateTime.now().plusDays(3), enrollmentUntil = enrollmentUntil, eventId = null, hiddenFromUserId = null,
        recipientUserIds = listOf(debtorId), debtorShares = mapOf(debtorId to 100_000L)
    )

    @Test
    fun `DM text longer than the caption limit goes as text without trying the photo`() {
        // Реквизиты — свободный текст до 1000 символов: вместе с описанием подпись легко выходит за 1024.
        val event = created(photoUrl = "/uploads/check.jpg", paymentLink = "Реквизиты: ".repeat(100))

        notifier.onSkladchinaCreated(event)

        verify(exactly = 0) { gateway.sendDmPhotoWithButtons(any(), any(), any(), any()) }
        verify { notificationService.sendDirectMessageWithDeepLink(42L, any(), "/skladchina/${event.skladchinaId}", SkladchinaBotNotifier.OPEN_BUTTON) }
    }

    @Test
    fun `photo DM that fails keeps the quick button keyboard in the text fallback`() {
        val event = created(photoUrl = "/uploads/check.jpg", enrollmentUntil = OffsetDateTime.now().plusDays(1))
        every { gateway.sendDmPhotoWithButtons(any(), any(), any(), any()) } returns false

        notifier.onSkladchinaCreated(event)

        verify { gateway.sendDmWithButtons(42L, any(), match { rows -> rows.size == 2 && rows[0][0].callbackData == SkladchinaCallbackService.ENROLL_PREFIX + event.skladchinaId }) }
        verify(exactly = 0) { notificationService.sendDirectMessageWithDeepLink(any(), any(), any(), any()) }
    }

    @Test
    fun `skladchina with a photo sends the DM as a picture with the same text and buttons`() {
        val event = created(photoUrl = "/uploads/check.jpg")
        every { gateway.sendDmPhotoWithButtons(any(), any(), any(), any()) } returns true

        notifier.onSkladchinaCreated(event)

        verify {
            gateway.sendDmPhotoWithButtons(
                42L, "/uploads/check.jpg", match { it.contains("Ужин после игры") },
                match { rows -> rows.flatten().any { it.text == SkladchinaBotNotifier.OPEN_BUTTON && it.webAppPath == "/skladchina/${event.skladchinaId}" } }
            )
        }
        verify(exactly = 0) { notificationService.sendDirectMessageWithDeepLink(any(), any(), any(), any()) }
        verify(exactly = 0) { gateway.sendDmWithButtons(any(), any(), any()) }
    }

    @Test
    fun `photo DM that fails falls back to the usual text DM`() {
        val event = created(photoUrl = "/uploads/check.jpg")
        every { gateway.sendDmPhotoWithButtons(any(), any(), any(), any()) } returns false

        notifier.onSkladchinaCreated(event)

        verify { notificationService.sendDirectMessageWithDeepLink(42L, match { it.contains("Ужин после игры") }, "/skladchina/${event.skladchinaId}", SkladchinaBotNotifier.OPEN_BUTTON) }
    }

    @Test
    fun `skladchina without a photo never touches the photo API, quick button keeps its keyboard`() {
        val event = created(photoUrl = null, kind = SkladchinaKind.per_head)

        notifier.onSkladchinaCreated(event)

        verify(exactly = 0) { gateway.sendDmPhotoWithButtons(any(), any(), any(), any()) }
        verify { gateway.sendDmWithButtons(42L, any(), match { rows -> rows.size == 2 && rows[0][0].callbackData == SkladchinaCallbackService.TAKE_PREFIX + event.skladchinaId }) }
    }
}
