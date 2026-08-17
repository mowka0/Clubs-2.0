package com.clubs.chatlink

import com.clubs.bot.BotChatState
import com.clubs.bot.ChatTelegramGateway
import com.clubs.club.ClubRepository
import com.clubs.club.ClubService
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
    private lateinit var clubService: ClubService
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
        clubService = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        chatLinkService = mockk(relaxed = true)
        gateway = mockk(relaxed = true)
        service = ChatLinkBotService(chatLinkRepository, clubRepository, clubService, userRepository, chatLinkService, gateway, botUsername = "clubs_test_bot")

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
    fun `у клуба уже другой чат - отказ и выход (чат свободен, сидеть незачем)`() {
        every { chatLinkRepository.findByClubId(clubId) } returns chatLinkFixture(clubId = clubId, chatId = -777L)
        every { chatLinkRepository.findByChatId(chatId) } returns null

        service.handleGroupStart(chatId, "Второй чат", ownerTelegramId, clubId)

        verify(exactly = 0) { chatLinkRepository.insert(any()) }
        verify { gateway.leaveChat(chatId) }
    }

    @Test
    fun `чат обслуживает живой чужой клуб - отказ БЕЗ выхода бота`() {
        // Уход бота снёс бы работающую интеграцию клуба-хозяина чата руками постороннего.
        val otherClubId = UUID.randomUUID()
        every { chatLinkRepository.findByChatId(chatId) } returns chatLinkFixture(clubId = otherClubId, chatId = chatId)
        every { clubRepository.findById(otherClubId) } returns chatLinkTestClub(clubId = otherClubId)

        service.handleGroupStart(chatId, "Чат", ownerTelegramId, clubId)

        verify(exactly = 0) { chatLinkRepository.insert(any()) }
        verify(exactly = 0) { chatLinkRepository.delete(any()) }
        verify(exactly = 0) { chatLinkService.releaseKeepingBotInChat(any()) }
        verify { gateway.sendGroupMessage(chatId, match { it.contains("уже привязан к другому клубу") }) }
        verify(exactly = 0) { gateway.leaveChat(any()) }
    }

    @Test
    fun `чат с осиротевшей строкой удалённого клуба - привязка проходит`() {
        // Клуб удалён (soft delete) — findById его не видит, значит чат свободен: освобождаем
        // строку, оставив бота в чате, и забираем чат под новый клуб.
        val deadClubId = UUID.randomUUID()
        val orphan = chatLinkFixture(clubId = deadClubId, chatId = chatId, doorInviteLink = "https://t.me/+orphan")
        every { chatLinkRepository.findByChatId(chatId) } returns orphan
        every { clubRepository.findById(deadClubId) } returns null
        every { gateway.isChatAdmin(chatId, ownerTelegramId) } returns true
        every { gateway.getBotChatState(chatId) } returns
            BotChatState("administrator", canPinMessages = true, canInviteUsers = true, canRestrictMembers = true, canManageTags = true)
        val inserted = slot<ChatLink>()
        every { chatLinkRepository.insert(capture(inserted)) } answers { inserted.captured }

        service.handleGroupStart(chatId, "Возрождённый чат", ownerTelegramId, clubId)

        verify(exactly = 1) { chatLinkService.releaseKeepingBotInChat(orphan) }
        assertEquals(clubId, inserted.captured.clubId)
        assertEquals(chatId, inserted.captured.chatId)
        verify {
            gateway.sendDmWithWebAppAndCallbackButton(
                telegramId = ownerTelegramId,
                text = match { it.contains("привязан к клубу") },
                webAppButtonText = any(),
                webAppPath = "/clubs/$clubId",
                callbackButtonText = any(),
                callbackData = "chatlink:unlink:$clubId"
            )
        }
        verify(exactly = 0) { gateway.leaveChat(any()) }
    }

    @Test
    fun `перехват сироты не-админом чата - отказ, сирота цела`() {
        // Сироты живут там, где бот сидит админом: без гейта рядовой участник легаси-группы
        // завёл бы свой клуб и увёл группу со всеми правами под себя.
        val deadClubId = UUID.randomUUID()
        val orphan = chatLinkFixture(clubId = deadClubId, chatId = chatId)
        every { chatLinkRepository.findByChatId(chatId) } returns orphan
        every { clubRepository.findById(deadClubId) } returns null
        every { gateway.isChatAdmin(chatId, ownerTelegramId) } returns false

        service.handleGroupStart(chatId, "Легаси-группа", ownerTelegramId, clubId)

        verify(exactly = 0) { chatLinkRepository.insert(any()) }
        verify(exactly = 0) { chatLinkService.releaseKeepingBotInChat(any()) }
        verify { gateway.sendGroupMessage(chatId, match { it.contains("администратор чата") }) }
        // Чат свободен (за строкой мёртвый клуб) — сидеть в нём боту незачем.
        verify { gateway.leaveChat(chatId) }
    }

    @Test
    fun `перехват сироты при молчании Telegram - fail-closed, привязки нет`() {
        // isChatAdmin не смог узнать статус (Telegram недоступен) → отказ, а не «пропустим».
        val deadClubId = UUID.randomUUID()
        every { chatLinkRepository.findByChatId(chatId) } returns chatLinkFixture(clubId = deadClubId, chatId = chatId)
        every { clubRepository.findById(deadClubId) } returns null
        every { gateway.isChatAdmin(any(), any()) } returns false

        service.handleGroupStart(chatId, "Легаси-группа", ownerTelegramId, clubId)

        verify(exactly = 0) { chatLinkRepository.insert(any()) }
        verify(exactly = 0) { chatLinkService.releaseKeepingBotInChat(any()) }
    }

    @Test
    fun `не владелец в чате с живой привязкой - бот остаётся И молчит (анти-спам)`() {
        // Уйти бот не может (сломал бы клуб А), значит и отвечать не должен: иначе любой участник
        // группы гонял бы /start с произвольным UUID и превращал бота в спамера чужого чата.
        val otherClubId = UUID.randomUUID()
        val strangerTelegramId = 999L
        every { userRepository.findByTelegramId(strangerTelegramId) } returns mockk(relaxed = true) {
            every { id } returns UUID.randomUUID()
        }
        every { chatLinkRepository.findByChatId(chatId) } returns chatLinkFixture(clubId = otherClubId, chatId = chatId)
        every { clubRepository.findById(otherClubId) } returns chatLinkTestClub(clubId = otherClubId)

        service.handleGroupStart(chatId, "Чат клуба А", strangerTelegramId, clubId)

        verify(exactly = 0) { chatLinkRepository.insert(any()) }
        verify(exactly = 0) { gateway.sendGroupMessage(any(), any()) }
        verify(exactly = 0) { gateway.leaveChat(any()) }
    }

    @Test
    fun `неизвестный клуб в чате с живой привязкой - бот остаётся И молчит (анти-спам)`() {
        val otherClubId = UUID.randomUUID()
        val missing = UUID.randomUUID()
        every { clubRepository.findById(missing) } returns null
        every { chatLinkRepository.findByChatId(chatId) } returns chatLinkFixture(clubId = otherClubId, chatId = chatId)
        every { clubRepository.findById(otherClubId) } returns chatLinkTestClub(clubId = otherClubId)

        service.handleGroupStart(chatId, "Чат клуба А", ownerTelegramId, missing)

        verify(exactly = 0) { chatLinkRepository.insert(any()) }
        verify(exactly = 0) { gateway.sendGroupMessage(any(), any()) }
        verify(exactly = 0) { gateway.leaveChat(any()) }
    }

    @Test
    fun `отказ владельцу в чате с осиротевшей строкой - выход, но сирота НЕ тронута`() {
        // У клуба уже есть другой чат: привязка не состоится, освобождать чужую строку не за чем.
        val deadClubId = UUID.randomUUID()
        val orphan = chatLinkFixture(clubId = deadClubId, chatId = chatId)
        every { chatLinkRepository.findByClubId(clubId) } returns chatLinkFixture(clubId = clubId, chatId = -777L)
        every { chatLinkRepository.findByChatId(chatId) } returns orphan
        every { clubRepository.findById(deadClubId) } returns null

        service.handleGroupStart(chatId, "Брошенный чат", ownerTelegramId, clubId)

        verify(exactly = 0) { chatLinkRepository.insert(any()) }
        verify(exactly = 0) { chatLinkService.releaseKeepingBotInChat(any()) }
        verify { gateway.sendGroupMessage(chatId, match { it.contains("уже привязан другой чат") }) }
        verify { gateway.leaveChat(chatId) }
    }

    @Test
    fun `у клуба другой чат, а этот обслуживает живую привязку - бот остаётся`() {
        val otherClubId = UUID.randomUUID()
        every { chatLinkRepository.findByClubId(clubId) } returns chatLinkFixture(clubId = clubId, chatId = -777L)
        every { chatLinkRepository.findByChatId(chatId) } returns chatLinkFixture(clubId = otherClubId, chatId = chatId)
        every { clubRepository.findById(otherClubId) } returns chatLinkTestClub(clubId = otherClubId)

        service.handleGroupStart(chatId, "Чат клуба А", ownerTelegramId, clubId)

        verify(exactly = 0) { chatLinkRepository.insert(any()) }
        verify { gateway.sendGroupMessage(chatId, match { it.contains("уже привязан другой чат") }) }
        verify(exactly = 0) { gateway.leaveChat(any()) }
    }

    @Test
    fun `успешная привязка - insert, invite-ссылка сразу, ОДИН пост в чат и подтверждение в личку`() {
        every { gateway.getBotChatState(chatId) } returns
            BotChatState("administrator", canPinMessages = true, canInviteUsers = true, canRestrictMembers = true, canManageTags = true)
        every { gateway.createJoinRequestInviteLink(chatId, any()) } returns "https://t.me/+fresh"
        val inserted = slot<ChatLink>()
        every { chatLinkRepository.insert(capture(inserted)) } answers { inserted.captured }

        service.handleGroupStart(chatId, "Партия — чат", ownerTelegramId, clubId)

        assertEquals(clubId, inserted.captured.clubId)
        assertEquals(chatId, inserted.captured.chatId)
        assertEquals(BotChatStatus.ADMINISTRATOR, inserted.captured.botStatus)
        assertTrue(inserted.captured.canPinMessages)
        assertEquals(ownerId, inserted.captured.linkedByUserId)
        // Реестр багов №4: ссылка создаётся при привязке, не дожидаясь тумблера двери
        verify { chatLinkRepository.updateInviteLink(clubId, "https://t.me/+fresh") }
        // В чат — ровно ОДНО сообщение: закреплённая ссылка на клуб с зовом вступить
        // (решение PO 2026-08-15; раньше их было три подряд). Текст и закреп — в ChatLinkService,
        // здесь проверяем сам факт вызова и что других постов в чат не осталось.
        verify(exactly = 1) { chatLinkService.postAndPinClubLink(chatId, "Партия", clubId) }
        verify(exactly = 0) { gateway.sendGroupMessage(any(), any()) }
        verify(exactly = 0) { gateway.sendGroupMessageWithUrlButton(any(), any(), any(), any(), any(), any()) }
        // Подтверждение привязки уехало в личку владельцу и там же несёт петлю безопасности
        verify {
            gateway.sendDmWithWebAppAndCallbackButton(
                telegramId = ownerTelegramId,
                text = match { it.contains("привязан к клубу") && it.contains("Это были вы") },
                webAppButtonText = any(),
                webAppPath = "/clubs/$clubId",
                callbackButtonText = any(),
                callbackData = "chatlink:unlink:$clubId"
            )
        }
        verify(exactly = 0) { gateway.leaveChat(any()) }
    }

    @Test
    fun `привязка БЕЗ права приглашать - ссылка не создаётся (создастся при выдаче права)`() {
        every { gateway.getBotChatState(chatId) } returns
            BotChatState("member", canPinMessages = false, canInviteUsers = false, canRestrictMembers = false, canManageTags = false)
        val inserted = slot<ChatLink>()
        every { chatLinkRepository.insert(capture(inserted)) } answers { inserted.captured }

        service.handleGroupStart(chatId, "Партия — чат", ownerTelegramId, clubId)

        verify(exactly = 0) { gateway.createJoinRequestInviteLink(any(), any()) }
        verify(exactly = 0) { chatLinkRepository.updateInviteLink(any(), any()) }
    }

    @Test
    fun `повторный старт в том же чате - идемпотентно, подтверждение в личку, чат не трогаем`() {
        // Реестр багов №3: «уже привязан» сбивал с толку после кика бота.
        val own = chatLinkFixture(clubId = clubId, chatId = chatId, doorInviteLink = "https://t.me/+alive")
        every { chatLinkRepository.findByClubId(clubId) } returns own
        // В реальной БД по этому chat_id лежит собственная строка клуба — стаб это отражает.
        every { chatLinkRepository.findByChatId(chatId) } returns own
        every { gateway.getBotChatState(chatId) } returns
            BotChatState("administrator", canPinMessages = true, canInviteUsers = true, canRestrictMembers = true, canManageTags = true)

        service.handleGroupStart(chatId, "Партия — чат", ownerTelegramId, clubId)

        verify(exactly = 0) { chatLinkRepository.insert(any()) }
        verify(exactly = 0) { gateway.leaveChat(any()) }
        // Подтверждение — владельцу в личку: участникам группы оно не адресовано (PO 2026-08-15)
        verify {
            gateway.sendDmWithWebAppAndCallbackButton(
                telegramId = ownerTelegramId,
                text = match { it.contains("привязан к клубу") },
                webAppButtonText = any(),
                webAppPath = "/clubs/$clubId",
                callbackButtonText = any(),
                callbackData = "chatlink:unlink:$clubId"
            )
        }
        // В сам чат при повторном /start не летит ничего: закреп со ссылкой там уже висит
        verify(exactly = 0) { chatLinkService.postAndPinClubLink(any(), any(), any()) }
        verify(exactly = 0) { gateway.sendGroupMessage(any(), any()) }
        verify(exactly = 0) { gateway.sendGroupMessageWithUrlButton(any(), any(), any(), any(), any(), any()) }
        // Ссылка была живой (бот всё время мог приглашать) — не пересоздаём
        verify(exactly = 0) { gateway.createJoinRequestInviteLink(any(), any()) }
    }

    @Test
    fun `повторная привязка после кика - пересоздаёт мёртвую invite-ссылку`() {
        // Реестр багов №2: Telegram отзывает все ссылки удалённого админа.
        every { chatLinkRepository.findByClubId(clubId) } returns chatLinkFixture(
            clubId = clubId, chatId = chatId,
            botStatus = BotChatStatus.KICKED,
            doorInviteLink = "https://t.me/+dead"
        )
        every { gateway.getBotChatState(chatId) } returns
            BotChatState("administrator", canPinMessages = true, canInviteUsers = true, canRestrictMembers = true, canManageTags = true)
        every { gateway.createJoinRequestInviteLink(chatId, any()) } returns "https://t.me/+fresh"

        service.handleGroupStart(chatId, "Партия — чат", ownerTelegramId, clubId)

        verify { gateway.revokeInviteLink(chatId, "https://t.me/+dead") }
        verify { chatLinkRepository.updateInviteLink(clubId, "https://t.me/+fresh") }
    }

    @Test
    fun `my_chat_member по непривязанному чату - no-op`() {
        every { chatLinkRepository.findByChatId(-1L) } returns null

        service.handleMyChatMember(-1L, "kicked", canPinMessages = false, canInviteUsers = false, canRestrictMembers = false)

        verify(exactly = 0) { chatLinkRepository.updateBotState(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `my_chat_member kick - статус обновлён, привязка живёт`() {
        every { chatLinkRepository.findByChatId(chatId) } returns chatLinkFixture(clubId = clubId, chatId = chatId)

        service.handleMyChatMember(chatId, "kicked", canPinMessages = false, canInviteUsers = false, canRestrictMembers = false)

        verify { chatLinkRepository.updateBotState(clubId, BotChatStatus.KICKED, false, false, false, false) }
        verify(exactly = 0) { chatLinkRepository.delete(any()) }
    }

    @Test
    fun `my_chat_member - возвращение бота с правами пересоздаёт мёртвую invite-ссылку`() {
        every { chatLinkRepository.findByChatId(chatId) } returns chatLinkFixture(
            clubId = clubId, chatId = chatId,
            botStatus = BotChatStatus.KICKED,
            doorInviteLink = "https://t.me/+dead"
        )
        every { gateway.createJoinRequestInviteLink(chatId, any()) } returns "https://t.me/+fresh"

        service.handleMyChatMember(chatId, "administrator", canPinMessages = true, canInviteUsers = true, canRestrictMembers = true)

        verify { gateway.revokeInviteLink(chatId, "https://t.me/+dead") }
        verify { chatLinkRepository.updateInviteLink(clubId, "https://t.me/+fresh") }
    }

    @Test
    fun `my_chat_member - возвращение БЕЗ права приглашать ссылку не трогает`() {
        every { chatLinkRepository.findByChatId(chatId) } returns chatLinkFixture(
            clubId = clubId, chatId = chatId,
            botStatus = BotChatStatus.KICKED,
            doorInviteLink = "https://t.me/+dead"
        )

        service.handleMyChatMember(chatId, "member", canPinMessages = false, canInviteUsers = false, canRestrictMembers = false)

        verify(exactly = 0) { gateway.createJoinRequestInviteLink(any(), any()) }
        verify(exactly = 0) { chatLinkRepository.updateInviteLink(any(), any()) }
    }

    @Test
    fun `my_chat_member - права не менялись (бот и так мог приглашать) - живую ссылку не пересоздаём`() {
        every { chatLinkRepository.findByChatId(chatId) } returns chatLinkFixture(
            clubId = clubId, chatId = chatId,
            botStatus = BotChatStatus.ADMINISTRATOR, canInviteUsers = true,
            doorInviteLink = "https://t.me/+alive"
        )

        service.handleMyChatMember(chatId, "administrator", canPinMessages = true, canInviteUsers = true, canRestrictMembers = true)

        verify(exactly = 0) { gateway.createJoinRequestInviteLink(any(), any()) }
        verify(exactly = 0) { gateway.revokeInviteLink(any(), any()) }
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
    fun `миграция группы в супергруппу переносит chat_id и пересоздаёт invite-ссылку`() {
        // Все ссылки старой группы при миграции умирают (реестр багов №2).
        every { chatLinkRepository.findByChatId(chatId) } returns chatLinkFixture(
            clubId = clubId, chatId = chatId, doorInviteLink = "https://t.me/+old-group"
        )
        every { gateway.createJoinRequestInviteLink(-1009999L, any()) } returns "https://t.me/+supergroup"

        service.handleChatMigration(chatId, -1009999L)

        verify { chatLinkRepository.updateChatId(chatId, -1009999L) }
        verify { chatLinkRepository.updateInviteLink(clubId, "https://t.me/+supergroup") }
    }

    // --- ?startgroup=new: клуб рождается из чата (спринт 1.0, чат-модель) ---

    @Test
    fun `startgroup new - клуб создаётся из чата и сразу привязывается`() {
        val newClubId = UUID.randomUUID()
        val newClub = chatLinkTestClub(clubId = newClubId, ownerId = ownerId, name = "Бегуны Сокольники")
        every { clubService.createClubFromChat(any(), ownerId, any()) } returns newClub
        every { clubRepository.findById(newClubId) } returns newClub

        service.handleGroupStartNewClub(chatId, "Бегуны Сокольники", ownerTelegramId)

        // Название клуба берётся у чата, владельцем становится тот, кто добавил бота.
        verify { clubService.createClubFromChat("Бегуны Сокольники", ownerId, any()) }
        verify { chatLinkRepository.insert(match { it.clubId == newClubId && it.chatId == chatId }) }
    }

    @Test
    fun `startgroup new - размер клуба берётся из числа участников чата`() {
        val newClubId = UUID.randomUUID()
        val newClub = chatLinkTestClub(clubId = newClubId, ownerId = ownerId, name = "Бегуны Сокольники")
        every { clubService.createClubFromChat(any(), ownerId, any()) } returns newClub
        every { clubRepository.findById(newClubId) } returns newClub
        every { gateway.getChatMemberCount(chatId) } returns 184

        service.handleGroupStartNewClub(chatId, "Бегуны Сокольники", ownerTelegramId)

        // Клуб обязан вместить тех, кто уже сидит в группе, — иначе часть чата упрётся в лимит.
        verify { clubService.createClubFromChat(any(), ownerId, 184) }
    }

    @Test
    fun `startgroup new - чат уже за живым клубом - клуб НЕ создаётся и бот остаётся`() {
        // Уход бота снёс бы работающую интеграцию клуба-хозяина руками постороннего.
        val otherClubId = UUID.randomUUID()
        every { chatLinkRepository.findByChatId(chatId) } returns chatLinkFixture(clubId = otherClubId, chatId = chatId)
        every { clubRepository.findById(otherClubId) } returns chatLinkTestClub(clubId = otherClubId)

        service.handleGroupStartNewClub(chatId, "Чат", ownerTelegramId)

        verify(exactly = 0) { clubService.createClubFromChat(any(), any(), any()) }
        verify(exactly = 0) { chatLinkRepository.insert(any()) }
        verify(exactly = 0) { gateway.leaveChat(chatId) }
    }

    @Test
    fun `startgroup new - добавивший ни разу не открывал приложение - отказ и выход`() {
        // Пользователь заводится только при входе в Mini App — владельца назначить не из чего.
        val unknownTelegramId = 777L
        every { userRepository.findByTelegramId(unknownTelegramId) } returns null

        service.handleGroupStartNewClub(chatId, "Чат", unknownTelegramId)

        verify(exactly = 0) { clubService.createClubFromChat(any(), any(), any()) }
        verify { gateway.leaveChat(chatId) }
    }

    @Test
    fun `startgroup new - осиротевший чат перехватывает только администратор чата`() {
        // Иначе рядовой участник легаси-группы увёл бы её под свой клуб вместе с правами бота.
        val staleClubId = UUID.randomUUID()
        every { chatLinkRepository.findByChatId(chatId) } returns chatLinkFixture(clubId = staleClubId, chatId = chatId)
        every { clubRepository.findById(staleClubId) } returns null
        every { gateway.isChatAdmin(chatId, ownerTelegramId) } returns false

        service.handleGroupStartNewClub(chatId, "Чат", ownerTelegramId)

        verify(exactly = 0) { clubService.createClubFromChat(any(), any(), any()) }
        verify(exactly = 0) { chatLinkRepository.insert(any()) }
    }
}
