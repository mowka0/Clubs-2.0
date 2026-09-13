package com.clubs.chatlink

import com.clubs.bot.ChatTelegramGateway
import com.clubs.bot.PARSE_MODE_HTML
import com.clubs.debt.DebtRepository
import com.clubs.debt.DebtTotals
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.skladchina.Skladchina
import com.clubs.skladchina.SkladchinaRepository
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class SkladchinaChatStatusServiceTest {

    private lateinit var chatLinkRepository: ChatLinkRepository
    private lateinit var postRepository: SkladchinaChatPostRepository
    private lateinit var skladchinaRepository: SkladchinaRepository
    private lateinit var debtRepository: DebtRepository
    private lateinit var userRepository: UserRepository
    private lateinit var gateway: ChatTelegramGateway
    private lateinit var service: SkladchinaChatStatusService

    private val clubId = UUID.randomUUID()
    private val chatId = -100123L
    private val link = chatLinkFixture(clubId = clubId, chatId = chatId, skladchinaStatusEnabled = true)

    @BeforeEach
    fun setUp() {
        chatLinkRepository = mockk(relaxed = true)
        postRepository = mockk(relaxed = true)
        skladchinaRepository = mockk(relaxed = true)
        debtRepository = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        gateway = mockk(relaxed = true)
        service = SkladchinaChatStatusService(
            chatLinkRepository, postRepository, skladchinaRepository, debtRepository, userRepository,
            SkladchinaChatStatusRenderer("clubs_admin_bot"), gateway
        )
        every { chatLinkRepository.findByClubId(clubId) } returns link
        every { debtRepository.totals(any()) } returns DebtTotals.EMPTY
        every { debtRepository.findBySkladchina(any()) } returns emptyList()
        every { userRepository.findByIds(any()) } returns emptyList()
        every { userRepository.findById(any()) } returns UsersRecord(id = UUID.randomUUID(), telegramId = 1L, firstName = "Иван")
    }

    private fun skladchina(status: SkladchinaStatus = SkladchinaStatus.active, hiddenFrom: UUID? = null): Skladchina =
        rendererSkladchina(status = status, hiddenFromUserId = hiddenFrom).copy(clubId = clubId)

    @Test
    fun `created posts a pinned status and returns the chat id`() {
        val s = skladchina()
        every { skladchinaRepository.findById(s.id) } returns s
        every { postRepository.findBySkladchinaId(s.id) } returns null
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), any(), any(), PARSE_MODE_HTML) } returns 777L
        every { postRepository.insertIfAbsent(any()) } returns true

        assertEquals(chatId, service.onSkladchinaCreated(clubId, s.id))
        verify { gateway.pinChatMessage(chatId, 777L, notify = true) }
        verify { postRepository.insertIfAbsent(match { it.skladchinaId == s.id && it.messageId == 777L }) }
    }

    @Test
    fun `hidden voluntary never gets a chat post`() {
        val s = skladchina(hiddenFrom = UUID.randomUUID())
        every { skladchinaRepository.findById(s.id) } returns s

        assertNull(service.onSkladchinaCreated(clubId, s.id))
        verify(exactly = 0) { gateway.sendGroupMessageWithUrlButton(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `toggle off or bot outside the chat means no post and no chat id`() {
        val s = skladchina()
        every { skladchinaRepository.findById(s.id) } returns s
        every { chatLinkRepository.findByClubId(clubId) } returns chatLinkFixture(clubId = clubId, skladchinaStatusEnabled = false)

        assertNull(service.onSkladchinaCreated(clubId, s.id))
        verify(exactly = 0) { gateway.sendGroupMessageWithUrlButton(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `flush redraws dirty posts of active skladchinas and closes posts of inactive ones`() {
        val active = skladchina()
        val closed = skladchina(status = SkladchinaStatus.collected)
        every { skladchinaRepository.findById(active.id) } returns active
        every { skladchinaRepository.findById(closed.id) } returns closed
        every { postRepository.findBySkladchinaId(active.id) } returns SkladchinaChatPost(active.id, chatId, 1L, null)
        val closedPost = SkladchinaChatPost(closed.id, chatId, 2L, null)
        every { postRepository.findOpenPostsOfInactiveSkladchinas() } returns listOf(closedPost)

        service.markDirty(active.id)
        service.flush()

        verify(exactly = 1) { gateway.editGroupMessage(chatId, 1L, any(), any(), any(), PARSE_MODE_HTML) }
        verify(exactly = 1) { gateway.editGroupMessage(chatId, 2L, match { it.contains("Собрано") }, null, null, PARSE_MODE_HTML) }
        verify { gateway.unpinChatMessage(chatId, 2L) }
        verify { postRepository.markClosed(closed.id) }
        // Флаг снят: повторный flush ничего не перерисовывает.
        service.flush()
        verify(exactly = 1) { gateway.editGroupMessage(chatId, 1L, any(), any(), any(), PARSE_MODE_HTML) }
    }

    @Test
    fun `closeNow edits the final text once and unpins`() {
        val s = skladchina(status = SkladchinaStatus.cancelled)
        every { skladchinaRepository.findById(s.id) } returns s
        every { postRepository.findBySkladchinaId(s.id) } returns SkladchinaChatPost(s.id, chatId, 5L, null)

        service.closeNow(s.id)

        verify { gateway.editGroupMessage(chatId, 5L, match { it.contains("Сбор отменён") }, null, null, PARSE_MODE_HTML) }
        verify { gateway.unpinChatMessage(chatId, 5L) }
        verify { postRepository.markClosed(s.id) }
    }

    @Test
    fun `deadline reminder mentions only members present in the chat and returns their ids`() {
        val s = skladchina()
        val inChat = UUID.randomUUID(); val outside = UUID.randomUUID()
        every { postRepository.findBySkladchinaId(s.id) } returns SkladchinaChatPost(s.id, chatId, 1L, null)
        every { userRepository.findByIds(listOf(inChat, outside)) } returns listOf(
            UsersRecord(id = inChat, telegramId = 10L, firstName = "Саша"),
            UsersRecord(id = outside, telegramId = 20L, firstName = "Оля")
        )
        every { gateway.getUserChatState(chatId, 10L) } returns com.clubs.bot.UserChatState.IN_CHAT
        every { gateway.getUserChatState(chatId, 20L) } returns com.clubs.bot.UserChatState.NOT_IN_CHAT
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), any(), any(), PARSE_MODE_HTML) } returns 99L

        val covered = service.postDeadlineReminder(s, listOf(inChat, outside))

        assertEquals(setOf(inChat), covered)
        verify { gateway.sendGroupMessageWithUrlButton(chatId, match { it.contains("Саша") && !it.contains("Оля") }, any(), any(), PARSE_MODE_HTML) }
    }
}
