package com.clubs.chatlink

import com.clubs.bot.BotChatState
import com.clubs.bot.ChatTelegramGateway
import com.clubs.club.ClubRepository
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class ChatLinkBotServiceTest {

    private lateinit var chatLinkRepository: ChatLinkRepository
    private lateinit var clubRepository: ClubRepository
    private lateinit var userRepository: UserRepository
    private lateinit var chatLinkService: ChatLinkService
    private lateinit var gateway: ChatTelegramGateway
    private lateinit var service: ChatLinkBotService

    private val clubId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()
    private val ownerTelegramId = 111L
    private val chatId = -100500L
    private val club = chatLinkTestClub(clubId = clubId, ownerId = ownerId)

    @BeforeEach
    fun setUp() {
        chatLinkRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        chatLinkService = mockk(relaxed = true)
        gateway = mockk(relaxed = true)
        service = ChatLinkBotService(chatLinkRepository, clubRepository, userRepository, chatLinkService, gateway)

        every { clubRepository.findById(clubId) } returns club
        val owner = mockk<UsersRecord>(relaxed = true) {
            every { id } returns ownerId
            every { telegramId } returns ownerTelegramId
        }
        every { userRepository.findByTelegramId(ownerTelegramId) } returns owner
        every { chatLinkRepository.findByClubId(clubId) } returns null
        every { chatLinkRepository.findByChatId(chatId) } returns null
    }

    @Test
    fun `гейт безопасности - не владелец добавил бота - отказ и выход из чата`() {
        val strangerTelegramId = 999L
        every { userRepository.findByTelegramId(strangerTelegramId) } returns mockk(relaxed = true) {
            every { id } returns UUID.randomUUID()
        }

        service.handleGroupStart(chatId, "Чужой чат", strangerTelegramId, clubId)

        verify(exactly = 0) { chatLinkRepository.insert(any()) }
        verify { gateway.sendGroupMessage(chatId, match { it.contains("только владелец") }) }
        verify { gateway.leaveChat(chatId) }
    }

    @Test
    fun `неизвестный клуб - отказ и выход`() {
        val missing = UUID.randomUUID()
        every { clubRepository.findById(missing) } returns null

        service.handleGroupStart(chatId, "Чат", ownerTelegramId, missing)

        verify(exactly = 0) { chatLinkRepository.insert(any()) }
        verify { gateway.leaveChat(chatId) }
    }

    @Test
    fun `у клуба уже другой чат - отказ и выход`() {
        every { chatLinkRepository.findByClubId(clubId) } returns chatLinkFixture(clubId = clubId, chatId = -777L)

        service.handleGroupStart(chatId, "Второй чат", ownerTelegramId, clubId)

        verify(exactly = 0) { chatLinkRepository.insert(any()) }
        verify { gateway.leaveChat(chatId) }
    }

    @Test
    fun `чат уже привязан к другому клубу - отказ и выход`() {
        every { chatLinkRepository.findByChatId(chatId) } returns chatLinkFixture(clubId = UUID.randomUUID(), chatId = chatId)

        service.handleGroupStart(chatId, "Чат", ownerTelegramId, clubId)

        verify(exactly = 0) { chatLinkRepository.insert(any()) }
        verify { gateway.leaveChat(chatId) }
    }

    @Test
    fun `успешная привязка - insert, подтверждение в чат и DM-петля владельцу с кнопкой отвязки`() {
        every { gateway.getBotChatState(chatId) } returns
            BotChatState("administrator", canPinMessages = true, canInviteUsers = true)
        val inserted = slot<ChatLink>()
        every { chatLinkRepository.insert(capture(inserted)) } answers { inserted.captured }

        service.handleGroupStart(chatId, "Партия — чат", ownerTelegramId, clubId)

        assertEquals(clubId, inserted.captured.clubId)
        assertEquals(chatId, inserted.captured.chatId)
        assertEquals(BotChatStatus.ADMINISTRATOR, inserted.captured.botStatus)
        assertTrue(inserted.captured.canPinMessages)
        assertEquals(ownerId, inserted.captured.linkedByUserId)
        verify { gateway.sendGroupMessage(chatId, match { it.contains("привязан к клубу") }) }
        verify {
            gateway.sendDmWithCallbackButton(
                telegramId = ownerTelegramId,
                text = match { it.contains("Это были вы") },
                buttonText = any(),
                callbackData = "chatlink:unlink:$clubId"
            )
        }
        verify(exactly = 0) { gateway.leaveChat(any()) }
    }

    @Test
    fun `повторный старт в том же чате - идемпотентно, без второй петли подтверждения`() {
        every { chatLinkRepository.findByClubId(clubId) } returns chatLinkFixture(clubId = clubId, chatId = chatId)

        service.handleGroupStart(chatId, "Партия — чат", ownerTelegramId, clubId)

        verify(exactly = 0) { chatLinkRepository.insert(any()) }
        verify(exactly = 0) { gateway.sendDmWithCallbackButton(any(), any(), any(), any()) }
        verify(exactly = 0) { gateway.leaveChat(any()) }
    }

    @Test
    fun `my_chat_member по непривязанному чату - no-op`() {
        every { chatLinkRepository.findByChatId(-1L) } returns null

        service.handleMyChatMember(-1L, "kicked", canPinMessages = false, canInviteUsers = false)

        verify(exactly = 0) { chatLinkRepository.updateBotState(any(), any(), any(), any()) }
    }

    @Test
    fun `my_chat_member kick - статус обновлён, привязка живёт`() {
        every { chatLinkRepository.findByChatId(chatId) } returns chatLinkFixture(clubId = clubId, chatId = chatId)

        service.handleMyChatMember(chatId, "kicked", canPinMessages = false, canInviteUsers = false)

        verify { chatLinkRepository.updateBotState(clubId, BotChatStatus.KICKED, false, false) }
        verify(exactly = 0) { chatLinkRepository.delete(any()) }
    }

    @Test
    fun `callback отвязки от не-владельца - отказ без действий`() {
        val strangerTelegramId = 999L
        every { userRepository.findByTelegramId(strangerTelegramId) } returns mockk(relaxed = true) {
            every { id } returns UUID.randomUUID()
        }

        val answer = service.handleUnlinkCallback(strangerTelegramId, clubId)

        assertTrue(answer.contains("только владелец"))
        verify(exactly = 0) { chatLinkService.doUnlink(any()) }
    }

    @Test
    fun `callback отвязки от владельца - doUnlink`() {
        val link = chatLinkFixture(clubId = clubId, chatId = chatId)
        every { chatLinkRepository.findByClubId(clubId) } returns link

        val answer = service.handleUnlinkCallback(ownerTelegramId, clubId)

        assertTrue(answer.contains("отвязан"))
        verify { chatLinkService.doUnlink(link) }
    }

    @Test
    fun `миграция группы в супергруппу переносит chat_id`() {
        every { chatLinkRepository.findByChatId(chatId) } returns chatLinkFixture(clubId = clubId, chatId = chatId)

        service.handleChatMigration(chatId, -1009999L)

        verify { chatLinkRepository.updateChatId(chatId, -1009999L) }
    }
}
