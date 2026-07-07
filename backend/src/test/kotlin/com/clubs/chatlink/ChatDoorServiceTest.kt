package com.clubs.chatlink

import com.clubs.bot.ChatTelegramGateway
import com.clubs.club.ClubRepository
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.membership.Membership
import com.clubs.membership.MembershipRepository
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class ChatDoorServiceTest {

    private lateinit var chatLinkRepository: ChatLinkRepository
    private lateinit var clubRepository: ClubRepository
    private lateinit var membershipRepository: MembershipRepository
    private lateinit var userRepository: UserRepository
    private lateinit var gateway: ChatTelegramGateway
    private lateinit var service: ChatDoorService

    private val clubId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val telegramId = 555L
    private val chatId = -100500L
    private val doorLink = "https://t.me/+door"
    private val club = chatLinkTestClub(clubId = clubId)

    @BeforeEach
    fun setUp() {
        chatLinkRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        membershipRepository = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        gateway = mockk(relaxed = true)
        service = ChatDoorService(chatLinkRepository, clubRepository, membershipRepository, userRepository, gateway)

        every { clubRepository.findById(clubId) } returns club
        every { chatLinkRepository.findByChatId(chatId) } returns
            chatLinkFixture(clubId = clubId, chatId = chatId, doorEnabled = true, doorInviteLink = doorLink)
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, chatId = chatId, doorEnabled = true, doorInviteLink = doorLink)
        val user = mockk<UsersRecord>(relaxed = true) {
            every { id } returns userId
            every { telegramId } returns this@ChatDoorServiceTest.telegramId
        }
        every { userRepository.findByTelegramId(telegramId) } returns user
        every { userRepository.findById(userId) } returns user
    }

    private fun membership(status: MembershipStatus, expiresAt: OffsetDateTime? = null) = Membership(
        id = UUID.randomUUID(),
        userId = userId,
        clubId = clubId,
        status = status,
        role = MembershipRole.member,
        joinedAt = OffsetDateTime.now(),
        subscriptionExpiresAt = expiresAt,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )

    // ---- chat_join_request ----

    @Test
    fun `стук при выключенной двери игнорируется`() {
        every { chatLinkRepository.findByChatId(chatId) } returns
            chatLinkFixture(clubId = clubId, chatId = chatId, doorEnabled = false)

        service.onChatJoinRequest(chatId, telegramId)

        verify(exactly = 0) { gateway.approveJoinRequest(any(), any()) }
        verify(exactly = 0) { gateway.sendDmWithWebApp(any(), any(), any(), any()) }
    }

    @Test
    fun `стук активного участника - мгновенный впуск без DM`() {
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns membership(MembershipStatus.active)

        service.onChatJoinRequest(chatId, telegramId)

        verify { gateway.approveJoinRequest(chatId, telegramId) }
        verify(exactly = 0) { gateway.sendDmWithWebApp(any(), any(), any(), any()) }
    }

    @Test
    fun `стук отменённого в оплаченном периоде - впуск (он ещё в клубе)`() {
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns
            membership(MembershipStatus.cancelled, expiresAt = OffsetDateTime.now().plusDays(10))

        service.onChatJoinRequest(chatId, telegramId)

        verify { gateway.approveJoinRequest(chatId, telegramId) }
    }

    @Test
    fun `стук frozen-должника - DM про взнос, заявка остаётся висеть`() {
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns membership(MembershipStatus.frozen)

        service.onChatJoinRequest(chatId, telegramId)

        verify(exactly = 0) { gateway.approveJoinRequest(any(), any()) }
        verify(exactly = 0) { gateway.declineJoinRequest(any(), any()) }
        verify { gateway.sendDmWithWebApp(telegramId, match { it.contains("взнос") }, any(), "/clubs/$clubId") }
    }

    @Test
    fun `стук чужого - DM с правилами игры и кнопкой на клуб, без впуска`() {
        every { userRepository.findByTelegramId(telegramId) } returns null

        service.onChatJoinRequest(chatId, telegramId)

        verify(exactly = 0) { gateway.approveJoinRequest(any(), any()) }
        verify(exactly = 0) { gateway.declineJoinRequest(any(), any()) }
        verify { gateway.sendDmWithWebApp(telegramId, match { it.contains("Подай заявку") }, any(), "/clubs/$clubId") }
    }

    @Test
    fun `стук в непривязанный чат - no-op`() {
        every { chatLinkRepository.findByChatId(-1L) } returns null

        service.onChatJoinRequest(-1L, telegramId)

        verify(exactly = 0) { gateway.approveJoinRequest(any(), any()) }
    }

    // ---- доступ открылся ----

    @Test
    fun `доступ открылся - висящая заявка одобрена, DM 'ты уже в чате'`() {
        every { gateway.approveJoinRequest(chatId, telegramId) } returns true

        service.onAccessOpened(clubId, userId)

        verify { gateway.approveJoinRequest(chatId, telegramId) }
        verify { gateway.sendDmWithUrlButton(telegramId, match { it.contains("уже в чате") }, any(), doorLink) }
    }

    @Test
    fun `доступ открылся без заявки - не в чате - DM с приглашением`() {
        every { gateway.approveJoinRequest(chatId, telegramId) } returns false
        every { gateway.isChatParticipant(chatId, telegramId) } returns false

        service.onAccessOpened(clubId, userId)

        verify { gateway.sendDmWithUrlButton(telegramId, match { it.contains("Вступай в чат") || it.contains("чат клуба") }, any(), doorLink) }
    }

    @Test
    fun `доступ открылся - уже в чате (продление взноса) - без DM, не спамим`() {
        every { gateway.approveJoinRequest(chatId, telegramId) } returns false
        every { gateway.isChatParticipant(chatId, telegramId) } returns true

        service.onAccessOpened(clubId, userId)

        verify(exactly = 0) { gateway.sendDmWithUrlButton(any(), any(), any(), any()) }
    }

    @Test
    fun `доступ открылся - статус в чате неизвестен (Telegram молчит) - приглашение всё равно шлём`() {
        // Потерять вход для новичка хуже, чем изредка прислать лишний DM участнику чата.
        every { gateway.approveJoinRequest(chatId, telegramId) } returns false
        every { gateway.isChatParticipant(chatId, telegramId) } returns null

        service.onAccessOpened(clubId, userId)

        verify { gateway.sendDmWithUrlButton(telegramId, any(), any(), doorLink) }
    }

    @Test
    fun `доступ открылся при выключенной двери - no-op`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, chatId = chatId, doorEnabled = false)

        service.onAccessOpened(clubId, userId)

        verify(exactly = 0) { gateway.approveJoinRequest(any(), any()) }
        verify(exactly = 0) { gateway.sendDmWithUrlButton(any(), any(), any(), any()) }
    }

    @Test
    fun `доступ открылся при кикнутом боте - no-op`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, chatId = chatId, doorEnabled = true, doorInviteLink = doorLink, botStatus = BotChatStatus.KICKED)

        service.onAccessOpened(clubId, userId)

        verify(exactly = 0) { gateway.approveJoinRequest(any(), any()) }
    }

    // ---- доступ закрылся ----

    @Test
    fun `отказ по заявке - висящая chat-заявка отклоняется`() {
        service.onAccessRevoked(clubId, userId)

        verify { gateway.declineJoinRequest(chatId, telegramId) }
    }

    @Test
    fun `отказ при выключенной двери - заявки не наши, не трогаем`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, chatId = chatId, doorEnabled = false)

        service.onAccessRevoked(clubId, userId)

        verify(exactly = 0) { gateway.declineJoinRequest(any(), any()) }
    }
}
