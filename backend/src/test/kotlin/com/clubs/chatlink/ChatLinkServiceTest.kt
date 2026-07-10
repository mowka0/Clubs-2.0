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
    private lateinit var livePinService: LivePinService
    private lateinit var skladchinaChatStatusService: SkladchinaChatStatusService
    private lateinit var strictModeService: StrictModeService
    private lateinit var titleService: TitleService
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
        livePinService = mockk(relaxed = true)
        skladchinaChatStatusService = mockk(relaxed = true)
        strictModeService = mockk(relaxed = true)
        titleService = mockk(relaxed = true)
        service = ChatLinkService(chatLinkRepository, clubRepository, ChatLinkMapper(), gateway, livePinService, skladchinaChatStatusService, strictModeService, titleService, botUsername = "clubs_test_bot")
        every { clubRepository.findById(clubId) } returns club
    }

    @Test
    fun `getStatus отдаёт startGroupUrl с club_id и admin-правами даже без привязки`() {
        every { chatLinkRepository.findByClubId(clubId) } returns null

        val status = service.getStatus(clubId, ownerId)

        assertFalse(status.linked)
        // restrict_members — право снимать баны (реестр багов №1: «удалить из группы» = бан)
        assertEquals("https://t.me/clubs_test_bot?startgroup=$clubId&admin=pin_messages+invite_users+restrict_members+promote_members", status.startGroupUrl)
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
    fun `setDoor включение без ссылки — создаёт join-request ссылку и сохраняет`() {
        val link = chatLinkFixture(clubId = clubId, canInviteUsers = true, doorInviteLink = null)
        every { chatLinkRepository.findByClubId(clubId) } returns link andThen
            link.copy(doorEnabled = true, doorInviteLink = "https://t.me/+abc")
        every { gateway.createJoinRequestInviteLink(link.chatId, any()) } returns "https://t.me/+abc"

        val status = service.setDoor(clubId, ownerId, enabled = true)

        assertTrue(status.doorEnabled)
        assertEquals("https://t.me/+abc", status.doorInviteLink)
        verify { chatLinkRepository.updateDoor(clubId, true, "https://t.me/+abc") }
    }

    @Test
    fun `setDoor включение переиспользует ссылку, созданную при привязке`() {
        // Реестр багов №4: ссылка живёт независимо от тумблера.
        val link = chatLinkFixture(clubId = clubId, canInviteUsers = true, doorInviteLink = "https://t.me/+linked")
        every { chatLinkRepository.findByClubId(clubId) } returns link andThen link.copy(doorEnabled = true)

        service.setDoor(clubId, ownerId, enabled = true)

        verify(exactly = 0) { gateway.createJoinRequestInviteLink(any(), any()) }
        verify { chatLinkRepository.updateDoor(clubId, true, "https://t.me/+linked") }
    }

    @Test
    fun `setDoor включение при недоступном Telegram — 409, ничего не сохраняем`() {
        every { chatLinkRepository.findByClubId(clubId) } returns chatLinkFixture(clubId = clubId, doorInviteLink = null)
        every { gateway.createJoinRequestInviteLink(any(), any()) } returns null

        assertThrows(ConflictException::class.java) { service.setDoor(clubId, ownerId, enabled = true) }
        verify(exactly = 0) { chatLinkRepository.updateDoor(any(), any(), any()) }
    }

    @Test
    fun `setDoor выключение — ссылка ЖИВЁТ (по ней работает кнопка «Чат клуба»)`() {
        val link = chatLinkFixture(clubId = clubId, doorEnabled = true, doorInviteLink = "https://t.me/+abc")
        every { chatLinkRepository.findByClubId(clubId) } returns link andThen link.copy(doorEnabled = false)

        val status = service.setDoor(clubId, ownerId, enabled = false)

        assertFalse(status.doorEnabled)
        assertEquals("https://t.me/+abc", status.doorInviteLink)
        verify(exactly = 0) { gateway.revokeInviteLink(any(), any()) }
        verify { chatLinkRepository.updateDoor(clubId, false, "https://t.me/+abc") }
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
    fun `setLivePin включение без права закрепа — 409, ничего не сохраняем`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, canPinMessages = false)

        assertThrows(ConflictException::class.java) { service.setLivePin(clubId, ownerId, enabled = true) }
        verify(exactly = 0) { chatLinkRepository.updateLivePin(any(), any()) }
        verify(exactly = 0) { livePinService.backfillForClub(any()) }
    }

    @Test
    fun `setLivePin включение — сохраняет тумблер и запускает backfill будущих событий`() {
        val link = chatLinkFixture(clubId = clubId, canPinMessages = true)
        every { chatLinkRepository.findByClubId(clubId) } returns link andThen link.copy(livePinEnabled = true)

        val status = service.setLivePin(clubId, ownerId, enabled = true)

        assertTrue(status.livePinEnabled)
        verify { chatLinkRepository.updateLivePin(clubId, true) }
        verify { livePinService.backfillForClub(clubId) }
    }

    @Test
    fun `setLivePin выключение — открепляет живые пины через LivePinService`() {
        val link = chatLinkFixture(clubId = clubId, livePinEnabled = true)
        every { chatLinkRepository.findByClubId(clubId) } returns link andThen link.copy(livePinEnabled = false)

        val status = service.setLivePin(clubId, ownerId, enabled = false)

        assertFalse(status.livePinEnabled)
        verify { chatLinkRepository.updateLivePin(clubId, false) }
        verify { livePinService.disableForClub(link) }
    }

    @Test
    fun `setLivePin идемпотентен — то же значение не трогает БД и LivePinService`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, livePinEnabled = false)

        val status = service.setLivePin(clubId, ownerId, enabled = false)

        assertFalse(status.livePinEnabled)
        verify(exactly = 0) { chatLinkRepository.updateLivePin(any(), any()) }
        verify(exactly = 0) { livePinService.disableForClub(any()) }
    }

    @Test
    fun `setLivePin не-владельцу — 403`() {
        assertThrows(ForbiddenException::class.java) { service.setLivePin(clubId, strangerId, enabled = true) }
    }

    @Test
    fun `setSkladchinaStatus включение при кикнутом боте — 409, ничего не сохраняем`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, botStatus = BotChatStatus.KICKED)

        assertThrows(ConflictException::class.java) { service.setSkladchinaStatus(clubId, ownerId, enabled = true) }
        verify(exactly = 0) { chatLinkRepository.updateSkladchinaStatus(any(), any()) }
        verify(exactly = 0) { skladchinaChatStatusService.backfillForClub(any()) }
    }

    @Test
    fun `setSkladchinaStatus включение БЕЗ права закрепа — работает (право не требуется)`() {
        val link = chatLinkFixture(clubId = clubId, canPinMessages = false)
        every { chatLinkRepository.findByClubId(clubId) } returns link andThen link.copy(skladchinaStatusEnabled = true)

        val status = service.setSkladchinaStatus(clubId, ownerId, enabled = true)

        assertTrue(status.skladchinaStatusEnabled)
        verify { chatLinkRepository.updateSkladchinaStatus(clubId, true) }
        verify { skladchinaChatStatusService.backfillForClub(clubId) }
    }

    @Test
    fun `setSkladchinaStatus выключение — снимает живые статусы через сервис`() {
        val link = chatLinkFixture(clubId = clubId, skladchinaStatusEnabled = true)
        every { chatLinkRepository.findByClubId(clubId) } returns link andThen link.copy(skladchinaStatusEnabled = false)

        val status = service.setSkladchinaStatus(clubId, ownerId, enabled = false)

        assertFalse(status.skladchinaStatusEnabled)
        verify { chatLinkRepository.updateSkladchinaStatus(clubId, false) }
        verify { skladchinaChatStatusService.disableForClub(link) }
    }

    @Test
    fun `setSkladchinaStatus идемпотентен — то же значение не трогает БД и сервис`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, skladchinaStatusEnabled = false)

        val status = service.setSkladchinaStatus(clubId, ownerId, enabled = false)

        assertFalse(status.skladchinaStatusEnabled)
        verify(exactly = 0) { chatLinkRepository.updateSkladchinaStatus(any(), any()) }
        verify(exactly = 0) { skladchinaChatStatusService.disableForClub(any()) }
    }

    @Test
    fun `setSkladchinaStatus не-владельцу — 403`() {
        assertThrows(ForbiddenException::class.java) { service.setSkladchinaStatus(clubId, strangerId, enabled = true) }
    }

    @Test
    fun `update применяет только присланные поля — skladchinaStatusEnabled без остальных`() {
        val link = chatLinkFixture(clubId = clubId)
        every { chatLinkRepository.findByClubId(clubId) } returns link andThen link.copy(skladchinaStatusEnabled = true)

        service.update(clubId, ownerId, UpdateChatLinkRequest(skladchinaStatusEnabled = true))

        verify { chatLinkRepository.updateSkladchinaStatus(clubId, true) }
        verify(exactly = 0) { chatLinkRepository.updateDoor(any(), any(), any()) }
        verify(exactly = 0) { chatLinkRepository.updateLivePin(any(), any()) }
    }

    @Test
    fun `update применяет только присланные поля — livePinEnabled без doorEnabled`() {
        val link = chatLinkFixture(clubId = clubId, canPinMessages = true)
        every { chatLinkRepository.findByClubId(clubId) } returns link andThen link.copy(livePinEnabled = true)

        service.update(clubId, ownerId, UpdateChatLinkRequest(livePinEnabled = true))

        verify { chatLinkRepository.updateLivePin(clubId, true) }
        verify(exactly = 0) { chatLinkRepository.updateDoor(any(), any(), any()) }
    }

    @Test
    fun `unlink — отзыв ссылки, выход из чата, удаление строки`() {
        val link = chatLinkFixture(clubId = clubId, doorEnabled = true, doorInviteLink = "https://t.me/+abc")
        every { chatLinkRepository.findByClubId(clubId) } returns link

        service.unlink(clubId, ownerId)

        verify { gateway.revokeInviteLink(link.chatId, "https://t.me/+abc") }
        verify { gateway.leaveChat(link.chatId) }
        verify { chatLinkRepository.delete(clubId) }
        // Живые закрепы снимаются ДО выхода из чата — иначе flush редактировал бы сообщения
        // в чате, где бота больше нет
        verify { livePinService.disableForClub(link) }
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
        verify(exactly = 0) { chatLinkRepository.updateBotState(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `setStrictMode включение без права блокировки — 409, ничего не сохраняем`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, canRestrictMembers = false)

        assertThrows(ConflictException::class.java) { service.setStrictMode(clubId, ownerId, enabled = true) }
        verify(exactly = 0) { chatLinkRepository.updateStrictMode(any(), any()) }
        verify(exactly = 0) { strictModeService.backfillForClub(any()) }
    }

    @Test
    fun `setStrictMode включение — сохраняет тумблер и мьютит текущих должников (backfill)`() {
        val link = chatLinkFixture(clubId = clubId, canRestrictMembers = true)
        every { chatLinkRepository.findByClubId(clubId) } returns link andThen link.copy(strictModeEnabled = true)

        val status = service.setStrictMode(clubId, ownerId, enabled = true)

        assertTrue(status.strictModeEnabled)
        verify { chatLinkRepository.updateStrictMode(clubId, true) }
        verify { strictModeService.backfillForClub(link) }
    }

    @Test
    fun `setStrictMode выключение — возвращает голос должникам`() {
        val link = chatLinkFixture(clubId = clubId, strictModeEnabled = true)
        every { chatLinkRepository.findByClubId(clubId) } returns link andThen link.copy(strictModeEnabled = false)

        val status = service.setStrictMode(clubId, ownerId, enabled = false)

        assertFalse(status.strictModeEnabled)
        verify { chatLinkRepository.updateStrictMode(clubId, false) }
        verify { strictModeService.disableForClub(link) }
    }

    @Test
    fun `setStrictMode идемпотентен — то же значение не трогает БД и должников`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, strictModeEnabled = false)

        val status = service.setStrictMode(clubId, ownerId, enabled = false)

        assertFalse(status.strictModeEnabled)
        verify(exactly = 0) { chatLinkRepository.updateStrictMode(any(), any()) }
        verify(exactly = 0) { strictModeService.disableForClub(any()) }
    }

    @Test
    fun `unlink при включённом строгом режиме — возвращает голос должникам до выхода бота`() {
        val link = chatLinkFixture(clubId = clubId, strictModeEnabled = true, doorInviteLink = "https://t.me/+abc")
        every { chatLinkRepository.findByClubId(clubId) } returns link

        service.unlink(clubId, ownerId)

        verify { strictModeService.disableForClub(link) }
        verify { strictModeService.liftBansForClub(link) }
        verify { gateway.leaveChat(link.chatId) }
        verify { chatLinkRepository.delete(clubId) }
    }

    @Test
    fun `setAwardTitles включение без права назначения админов — 409, ничего не сохраняем`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, canPromoteMembers = false)

        assertThrows(ConflictException::class.java) { service.setAwardTitles(clubId, ownerId, enabled = true) }
        verify(exactly = 0) { chatLinkRepository.updateAwardTitles(any(), any()) }
        verify(exactly = 0) { titleService.backfillForClub(any()) }
    }

    @Test
    fun `setAwardTitles включение — сохраняет тумблер и титулует участников с наградами (backfill)`() {
        val link = chatLinkFixture(clubId = clubId, canPromoteMembers = true)
        every { chatLinkRepository.findByClubId(clubId) } returns link andThen link.copy(awardTitlesEnabled = true)

        val status = service.setAwardTitles(clubId, ownerId, enabled = true)

        assertTrue(status.awardTitlesEnabled)
        verify { chatLinkRepository.updateAwardTitles(clubId, true) }
        verify { titleService.backfillForClub(link) }
    }

    @Test
    fun `setAwardTitles выключение — снимает титулы`() {
        val link = chatLinkFixture(clubId = clubId, awardTitlesEnabled = true)
        every { chatLinkRepository.findByClubId(clubId) } returns link andThen link.copy(awardTitlesEnabled = false)

        val status = service.setAwardTitles(clubId, ownerId, enabled = false)

        assertFalse(status.awardTitlesEnabled)
        verify { chatLinkRepository.updateAwardTitles(clubId, false) }
        verify { titleService.disableForClub(link) }
    }

    @Test
    fun `unlink снимает титулы наград до выхода бота`() {
        val link = chatLinkFixture(clubId = clubId, awardTitlesEnabled = true)
        every { chatLinkRepository.findByClubId(clubId) } returns link

        service.unlink(clubId, ownerId)

        verify { titleService.disableForClub(link) }
        verify { gateway.leaveChat(link.chatId) }
    }

    @Test
    fun `unlink снимает баны строгого режима даже при ВЫКЛЮЧЕННОМ тумблере`() {
        // Баны переживают выключение тумблера по дизайну, но отвязка терминальна:
        // после ухода бота впустить забаненного больше некому (фидбек PO со staging).
        val link = chatLinkFixture(clubId = clubId, strictModeEnabled = false)
        every { chatLinkRepository.findByClubId(clubId) } returns link

        service.unlink(clubId, ownerId)

        verify { strictModeService.liftBansForClub(link) }
        verify(exactly = 0) { strictModeService.disableForClub(any()) }
    }
}
