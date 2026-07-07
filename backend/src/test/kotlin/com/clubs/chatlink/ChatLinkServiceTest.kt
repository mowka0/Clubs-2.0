package com.clubs.chatlink

import com.clubs.bot.ChatTelegramGateway
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class ChatLinkServiceTest {

    private lateinit var chatLinkRepository: ChatLinkRepository
    private lateinit var clubRepository: ClubRepository
    private lateinit var gateway: ChatTelegramGateway
    private lateinit var service: ChatLinkService

    private val clubId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()
    private val strangerId = UUID.randomUUID()
    private val club = chatLinkTestClub(clubId = clubId, ownerId = ownerId)

    @BeforeEach
    fun setUp() {
        chatLinkRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        gateway = mockk(relaxed = true)
        service = ChatLinkService(chatLinkRepository, clubRepository, ChatLinkMapper(), gateway, botUsername = "clubs_test_bot")
        every { clubRepository.findById(clubId) } returns club
    }

    @Test
    fun `getStatus отдаёт startGroupUrl с club_id и admin-правами даже без привязки`() {
        every { chatLinkRepository.findByClubId(clubId) } returns null

        val status = service.getStatus(clubId, ownerId)

        assertFalse(status.linked)
        assertEquals("https://t.me/clubs_test_bot?startgroup=$clubId&admin=pin_messages+invite_users", status.startGroupUrl)
    }

    @Test
    fun `getStatus не-владельцу — 403`() {
        assertThrows(ForbiddenException::class.java) { service.getStatus(clubId, strangerId) }
    }

    @Test
    fun `setDoor включение без права приглашать — 409 и дверь не включается`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, canInviteUsers = false)

        assertThrows(ConflictException::class.java) { service.setDoor(clubId, ownerId, enabled = true) }
        verify(exactly = 0) { chatLinkRepository.updateDoor(any(), any(), any()) }
    }

    @Test
    fun `setDoor включение — создаёт join-request ссылку и сохраняет`() {
        val link = chatLinkFixture(clubId = clubId, canInviteUsers = true)
        every { chatLinkRepository.findByClubId(clubId) } returns link andThen
            link.copy(doorEnabled = true, doorInviteLink = "https://t.me/+abc")
        every { gateway.createJoinRequestInviteLink(link.chatId, any()) } returns "https://t.me/+abc"

        val status = service.setDoor(clubId, ownerId, enabled = true)

        assertTrue(status.doorEnabled)
        assertEquals("https://t.me/+abc", status.doorInviteLink)
        verify { chatLinkRepository.updateDoor(clubId, true, "https://t.me/+abc") }
    }

    @Test
    fun `setDoor включение при недоступном Telegram — 409, ничего не сохраняем`() {
        every { chatLinkRepository.findByClubId(clubId) } returns chatLinkFixture(clubId = clubId)
        every { gateway.createJoinRequestInviteLink(any(), any()) } returns null

        assertThrows(ConflictException::class.java) { service.setDoor(clubId, ownerId, enabled = true) }
        verify(exactly = 0) { chatLinkRepository.updateDoor(any(), any(), any()) }
    }

    @Test
    fun `setDoor выключение — отзывает ссылку и чистит её`() {
        val link = chatLinkFixture(clubId = clubId, doorEnabled = true, doorInviteLink = "https://t.me/+abc")
        every { chatLinkRepository.findByClubId(clubId) } returns link andThen link.copy(doorEnabled = false, doorInviteLink = null)

        val status = service.setDoor(clubId, ownerId, enabled = false)

        assertFalse(status.doorEnabled)
        assertNull(status.doorInviteLink)
        verify { gateway.revokeInviteLink(link.chatId, "https://t.me/+abc") }
        verify { chatLinkRepository.updateDoor(clubId, false, null) }
    }

    @Test
    fun `setDoor идемпотентен — то же значение не трогает Telegram и БД`() {
        every { chatLinkRepository.findByClubId(clubId) } returns chatLinkFixture(clubId = clubId, doorEnabled = false)

        val status = service.setDoor(clubId, ownerId, enabled = false)

        assertFalse(status.doorEnabled)
        verify(exactly = 0) { chatLinkRepository.updateDoor(any(), any(), any()) }
        verify(exactly = 0) { gateway.revokeInviteLink(any(), any()) }
    }

    @Test
    fun `unlink — отзыв ссылки, выход из чата, удаление строки`() {
        val link = chatLinkFixture(clubId = clubId, doorEnabled = true, doorInviteLink = "https://t.me/+abc")
        every { chatLinkRepository.findByClubId(clubId) } returns link

        service.unlink(clubId, ownerId)

        verify { gateway.revokeInviteLink(link.chatId, "https://t.me/+abc") }
        verify { gateway.leaveChat(link.chatId) }
        verify { chatLinkRepository.delete(clubId) }
    }

    @Test
    fun `unlink без привязки — 404`() {
        every { chatLinkRepository.findByClubId(clubId) } returns null
        assertThrows(NotFoundException::class.java) { service.unlink(clubId, ownerId) }
    }

    @Test
    fun `refresh при недоступном Telegram — 409, состояние не трогаем`() {
        every { chatLinkRepository.findByClubId(clubId) } returns chatLinkFixture(clubId = clubId)
        every { gateway.getBotChatState(any()) } returns null

        assertThrows(ConflictException::class.java) { service.refresh(clubId, ownerId) }
        verify(exactly = 0) { chatLinkRepository.updateBotState(any(), any(), any(), any()) }
    }
}
