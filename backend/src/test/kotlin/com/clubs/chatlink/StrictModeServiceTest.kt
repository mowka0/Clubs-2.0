package com.clubs.chatlink

import com.clubs.bot.ChatTelegramGateway
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.membership.MembershipRepository
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
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
    private lateinit var strictBanRepository: StrictBanRepository
    private lateinit var memberTagService: MemberTagService
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
        strictBanRepository = mockk(relaxed = true)
        memberTagService = mockk(relaxed = true)
        gateway = mockk(relaxed = true)
        service = StrictModeService(chatLinkRepository, membershipRepository, userRepository, strictBanRepository, memberTagService, gateway)
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
    fun `человек покинул клуб — бан в чате, бан попадает в учёт`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, chatId = chatId, strictModeEnabled = true)
        every { gateway.banChatMember(chatId, telegramId) } returns true

        service.onMembershipRevoked(clubId, userId)

        verify { gateway.banChatMember(chatId, telegramId) }
        verify { strictBanRepository.record(clubId, telegramId) }
    }

    @Test
    fun `бан не сработал (Telegram отказал) — в учёт не пишем`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, chatId = chatId, strictModeEnabled = true)
        every { gateway.banChatMember(chatId, telegramId) } returns false

        service.onMembershipRevoked(clubId, userId)

        verify(exactly = 0) { strictBanRepository.record(any(), any()) }
    }

    @Test
    fun `доступ открылся — учёт бана чистится даже при выключенном тумблере`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, chatId = chatId, strictModeEnabled = false)

        service.onAccessOpened(clubId, userId)

        verify { strictBanRepository.delete(clubId, telegramId) }
        verify(exactly = 0) { gateway.unmuteChatMember(any(), any()) }
    }

    @Test
    fun `отвязка чата снимает все баны из учёта и чистит его`() {
        val link = chatLinkFixture(clubId = clubId, chatId = chatId, strictModeEnabled = false)
        every { strictBanRepository.findTelegramIds(clubId) } returns listOf(10L, 20L)

        service.liftBansForClub(link)

        verify { gateway.unbanChatMember(chatId, 10L) }
        verify { gateway.unbanChatMember(chatId, 20L) }
        verify { strictBanRepository.deleteAllForClub(clubId) }
    }

    @Test
    fun `отвязка без банов в учёте — Telegram не трогаем`() {
        val link = chatLinkFixture(clubId = clubId, chatId = chatId)
        every { strictBanRepository.findTelegramIds(clubId) } returns emptyList()

        service.liftBansForClub(link)

        verify(exactly = 0) { gateway.unbanChatMember(any(), any()) }
    }

    @Test
    fun `уход из клуба при ВЫКЛЮЧЕННОМ строгом режиме — тег снимается, бана нет`() {
        val link = chatLinkFixture(clubId = clubId, chatId = chatId, strictModeEnabled = false)
        every { chatLinkRepository.findByClubId(clubId) } returns link

        service.onMembershipRevoked(clubId, userId)

        verify { memberTagService.removeTag(link, telegramId) }
        verify(exactly = 0) { gateway.banChatMember(any(), any()) }
    }

    @Test
    fun `уход при включённом строгом режиме — тег снят и бан наложен`() {
        val link = chatLinkFixture(clubId = clubId, chatId = chatId, strictModeEnabled = true)
        every { chatLinkRepository.findByClubId(clubId) } returns link
        every { gateway.banChatMember(chatId, telegramId) } returns true

        service.onMembershipRevoked(clubId, userId)

        verify { memberTagService.removeTag(link, telegramId) }
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
