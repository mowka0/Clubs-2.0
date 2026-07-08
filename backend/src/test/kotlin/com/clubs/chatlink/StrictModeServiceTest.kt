package com.clubs.chatlink

import com.clubs.bot.ChatTelegramGateway
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.membership.MembershipRepository
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Строгий режим (слайс 5): мьют должников, бан покинувших клуб, backfill/снятие при тумблере.
 * Проверяются гейты (тумблер, бот в чате) и то, что действия не выходят за пределы Telegram
 * (best-effort — бизнес-состояние сервис не трогает).
 */
class StrictModeServiceTest {

    private lateinit var chatLinkRepository: ChatLinkRepository
    private lateinit var membershipRepository: MembershipRepository
    private lateinit var userRepository: UserRepository
    private lateinit var gateway: ChatTelegramGateway
    private lateinit var service: StrictModeService

    private val clubId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val telegramId = 555L
    private val chatId = -100123L

    @BeforeEach
    fun setUp() {
        chatLinkRepository = mockk(relaxed = true)
        membershipRepository = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        gateway = mockk(relaxed = true)
        service = StrictModeService(chatLinkRepository, membershipRepository, userRepository, gateway)
        val user = mockk<UsersRecord> {
            every { telegramId } returns this@StrictModeServiceTest.telegramId
        }
        every { userRepository.findById(userId) } returns user
    }

    @Test
    fun `доступ закрылся при включённом режиме — должник мьютится`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, chatId = chatId, strictModeEnabled = true)

        service.onAccessClosed(clubId, userId)

        verify { gateway.muteChatMember(chatId, telegramId) }
    }

    @Test
    fun `доступ закрылся при ВЫКЛЮЧЕННОМ режиме — Telegram не трогаем`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, chatId = chatId, strictModeEnabled = false)

        service.onAccessClosed(clubId, userId)

        verify(exactly = 0) { gateway.muteChatMember(any(), any()) }
    }

    @Test
    fun `бот кикнут из чата — режим не действует`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, chatId = chatId, strictModeEnabled = true, botStatus = BotChatStatus.KICKED)

        service.onAccessClosed(clubId, userId)
        service.onMembershipRevoked(clubId, userId)

        verify(exactly = 0) { gateway.muteChatMember(any(), any()) }
        verify(exactly = 0) { gateway.banChatMember(any(), any()) }
    }

    @Test
    fun `чат не привязан — no-op`() {
        every { chatLinkRepository.findByClubId(clubId) } returns null

        service.onAccessClosed(clubId, userId)
        service.onAccessOpened(clubId, userId)
        service.onMembershipRevoked(clubId, userId)

        verify(exactly = 0) { gateway.muteChatMember(any(), any()) }
        verify(exactly = 0) { gateway.unmuteChatMember(any(), any()) }
        verify(exactly = 0) { gateway.banChatMember(any(), any()) }
    }

    @Test
    fun `доступ открылся — голос возвращается`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, chatId = chatId, strictModeEnabled = true)

        service.onAccessOpened(clubId, userId)

        verify { gateway.unmuteChatMember(chatId, telegramId) }
    }

    @Test
    fun `человек покинул клуб — бан в чате`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, chatId = chatId, strictModeEnabled = true)

        service.onMembershipRevoked(clubId, userId)

        verify { gateway.banChatMember(chatId, telegramId) }
    }

    @Test
    fun `backfill мьютит всех текущих должников клуба`() {
        val link = chatLinkFixture(clubId = clubId, chatId = chatId)
        every { membershipRepository.findDebtorTelegramIds(clubId) } returns listOf(1L, 2L, 3L)

        service.backfillForClub(link)

        verify { gateway.muteChatMember(chatId, 1L) }
        verify { gateway.muteChatMember(chatId, 2L) }
        verify { gateway.muteChatMember(chatId, 3L) }
        verify(exactly = 0) { gateway.banChatMember(any(), any()) } // бан-backfill сознательно не делается
    }

    @Test
    fun `выключение возвращает голос должникам, баны не снимает`() {
        val link = chatLinkFixture(clubId = clubId, chatId = chatId, strictModeEnabled = true)
        every { membershipRepository.findDebtorTelegramIds(clubId) } returns listOf(1L, 2L)

        service.disableForClub(link)

        verify { gateway.unmuteChatMember(chatId, 1L) }
        verify { gateway.unmuteChatMember(chatId, 2L) }
        verify(exactly = 0) { gateway.unbanChatMember(any(), any()) }
    }

}
