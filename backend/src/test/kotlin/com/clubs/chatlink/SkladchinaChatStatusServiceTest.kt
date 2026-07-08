package com.clubs.chatlink

import com.clubs.bot.ChatTelegramGateway
import com.clubs.bot.PARSE_MODE_HTML
import com.clubs.bot.UserChatState
import com.clubs.generated.jooq.enums.SkladchinaMode
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.generated.jooq.enums.SkladchinaTemplate
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.skladchina.Skladchina
import com.clubs.skladchina.SkladchinaClosedEvent
import com.clubs.skladchina.SkladchinaParticipant
import com.clubs.skladchina.SkladchinaRepository
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

// Складчина для живого статуса: минимально заполненная, активная по умолчанию.
private fun chatStatusSkladchina(
    id: UUID = UUID.randomUUID(),
    clubId: UUID = UUID.randomUUID(),
    status: SkladchinaStatus = SkladchinaStatus.active,
    deadline: OffsetDateTime = OffsetDateTime.now().plusDays(2)
): Skladchina = Skladchina(
    id = id,
    clubId = clubId,
    creatorId = UUID.randomUUID(),
    title = "Бронь корта",
    description = null,
    rules = null,
    photoUrl = null,
    template = SkladchinaTemplate.custom,
    paymentMode = SkladchinaMode.fixed_equal,
    totalGoalKopecks = 100_000L,
    paymentLink = "https://bank.example/pay",
    paymentMethodNote = null,
    eventId = null,
    deadline = deadline,
    affectsReputation = true,
    status = status,
    closedAt = null,
    closedBy = null,
    createdAt = OffsetDateTime.now(),
    updatedAt = OffsetDateTime.now()
)

private fun participant(
    skladchinaId: UUID,
    userId: UUID = UUID.randomUUID(),
    status: SkladchinaParticipantStatus = SkladchinaParticipantStatus.pending
): SkladchinaParticipant = SkladchinaParticipant(
    skladchinaId = skladchinaId,
    userId = userId,
    expectedAmountKopecks = 50_000L,
    declaredAmountKopecks = null,
    status = status,
    paidAt = null,
    declinedAt = null,
    reputationApplied = false,
    declineNote = null,
    declineRequestedAt = null,
    declineRejected = false,
    createdAt = OffsetDateTime.now()
)

private fun userRecord(userId: UUID, telegramId: Long, firstName: String): UsersRecord =
    UsersRecord(id = userId, telegramId = telegramId, firstName = firstName)

class SkladchinaChatStatusServiceTest {

    private lateinit var chatLinkRepository: ChatLinkRepository
    private lateinit var postRepository: SkladchinaChatPostRepository
    private lateinit var skladchinaRepository: SkladchinaRepository
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
        userRepository = mockk(relaxed = true)
        gateway = mockk(relaxed = true)
        service = SkladchinaChatStatusService(
            chatLinkRepository, postRepository, skladchinaRepository, userRepository,
            SkladchinaChatStatusRenderer(botUsername = "clubs_test_bot"), gateway
        )
        every { chatLinkRepository.findByClubId(clubId) } returns link
    }

    private fun stubCounts(skladchina: Skladchina, paid: Int, total: Int, pending: List<SkladchinaParticipant>) {
        every { skladchinaRepository.findById(skladchina.id) } returns skladchina
        every { skladchinaRepository.countParticipantsByStatus(skladchina.id, SkladchinaParticipantStatus.paid) } returns paid
        every { skladchinaRepository.countParticipants(skladchina.id) } returns total
        every { skladchinaRepository.findParticipants(skladchina.id) } returns pending
        every { userRepository.findByIds(pending.map { it.userId }) } returns
            pending.mapIndexed { i, p -> userRecord(p.userId, 1000L + i, "Гость$i") }
    }

    @Test
    fun `onSkladchinaCreated постит HTML-статус с упоминаниями, пином и строкой`() {
        val skladchina = chatStatusSkladchina(clubId = clubId)
        stubCounts(skladchina, paid = 0, total = 2, pending = listOf(participant(skladchina.id), participant(skladchina.id)))
        every { postRepository.findBySkladchinaId(skladchina.id) } returns null
        every { postRepository.insertIfAbsent(any()) } returns true
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), any(), any(), PARSE_MODE_HTML) } returns 777L

        service.onSkladchinaCreated(clubId, skladchina.id)

        verify {
            gateway.sendGroupMessageWithUrlButton(
                chatId,
                match { it.contains("Бронь корта") && it.contains("Скинулись — 0 из 2") && it.contains("tg://user?id=") },
                "Открыть сбор",
                "https://t.me/clubs_test_bot?startapp=skladchina_${skladchina.id}",
                PARSE_MODE_HTML
            )
        }
        verify { postRepository.insertIfAbsent(match { it.skladchinaId == skladchina.id && it.messageId == 777L }) }
        verify { gateway.pinChatMessage(chatId, 777L) }
    }

    @Test
    fun `createPost — конкурент уже вставил строку (гонка backfill × создание) — pin не зовётся`() {
        val skladchina = chatStatusSkladchina(clubId = clubId)
        stubCounts(skladchina, paid = 0, total = 1, pending = emptyList())
        every { postRepository.findBySkladchinaId(skladchina.id) } returns null
        every { postRepository.insertIfAbsent(any()) } returns false
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), any(), any(), PARSE_MODE_HTML) } returns 777L

        service.onSkladchinaCreated(clubId, skladchina.id)

        verify(exactly = 0) { gateway.pinChatMessage(any(), any()) }
    }

    @Test
    fun `onSkladchinaCreated молчит при выключенном тумблере`() {
        every { chatLinkRepository.findByClubId(clubId) } returns link.copy(skladchinaStatusEnabled = false)

        service.onSkladchinaCreated(clubId, UUID.randomUUID())

        verify(exactly = 0) { gateway.sendGroupMessageWithUrlButton(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `onSkladchinaCreated молчит если бот выпал из чата`() {
        every { chatLinkRepository.findByClubId(clubId) } returns link.copy(botStatus = BotChatStatus.KICKED)

        service.onSkladchinaCreated(clubId, UUID.randomUUID())

        verify(exactly = 0) { gateway.sendGroupMessageWithUrlButton(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `createPost не дублирует пост при существующей строке`() {
        val skladchina = chatStatusSkladchina(clubId = clubId)
        stubCounts(skladchina, 0, 2, emptyList())
        every { postRepository.findBySkladchinaId(skladchina.id) } returns
            SkladchinaChatPost(skladchina.id, chatId, 777L, closedAt = null)

        service.onSkladchinaCreated(clubId, skladchina.id)

        verify(exactly = 0) { gateway.sendGroupMessageWithUrlButton(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `createPost без права закрепа — пост уходит, pin не зовётся`() {
        every { chatLinkRepository.findByClubId(clubId) } returns link.copy(canPinMessages = false)
        val skladchina = chatStatusSkladchina(clubId = clubId)
        stubCounts(skladchina, 0, 1, emptyList())
        every { postRepository.findBySkladchinaId(skladchina.id) } returns null
        every { postRepository.insertIfAbsent(any()) } returns true
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), any(), any(), PARSE_MODE_HTML) } returns 777L

        service.onSkladchinaCreated(clubId, skladchina.id)

        verify { postRepository.insertIfAbsent(any()) }
        verify(exactly = 0) { gateway.pinChatMessage(any(), any()) }
    }

    @Test
    fun `onSkladchinaClosed — финальный edit, unpin и закрытие строки`() {
        val skladchinaId = UUID.randomUUID()
        every { postRepository.findBySkladchinaId(skladchinaId) } returns
            SkladchinaChatPost(skladchinaId, chatId, 777L, closedAt = null)

        service.onSkladchinaClosed(
            SkladchinaClosedEvent(
                skladchinaId = skladchinaId,
                creatorId = UUID.randomUUID(),
                clubName = "Партия",
                title = "Бронь корта",
                finalStatus = SkladchinaStatus.closed_success,
                collectedKopecks = 100_000L,
                totalGoalKopecks = 100_000L,
                paidCount = 2,
                participantCount = 2,
                affectsReputation = true
            )
        )

        verify {
            gateway.editGroupMessage(
                chatId, 777L,
                match { it.contains("Сбор закрыт · скинулись 2 из 2") },
                null, null, PARSE_MODE_HTML
            )
        }
        verify { gateway.unpinChatMessage(chatId, 777L) }
        verify { postRepository.markClosed(skladchinaId) }
    }

    @Test
    fun `flush перерисовывает dirty-складчину с актуальными счётчиками`() {
        val skladchina = chatStatusSkladchina(clubId = clubId)
        stubCounts(skladchina, paid = 5, total = 8, pending = listOf(participant(skladchina.id)))
        every { postRepository.findBySkladchinaId(skladchina.id) } returns
            SkladchinaChatPost(skladchina.id, chatId, 777L, closedAt = null)
        every { postRepository.findOpenPostsOfInactiveSkladchinas() } returns emptyList()

        service.markDirty(skladchina.id)
        service.flush()

        verify {
            gateway.editGroupMessage(
                chatId, 777L,
                match { it.contains("Скинулись — 5 из 8") },
                "Открыть сбор",
                any(),
                PARSE_MODE_HTML
            )
        }
    }

    @Test
    fun `flush close-проход закрывает пост НЕактивной складчины (каскад без доменного события)`() {
        val skladchina = chatStatusSkladchina(clubId = clubId, status = SkladchinaStatus.cancelled)
        every { skladchinaRepository.findById(skladchina.id) } returns skladchina
        every { skladchinaRepository.countParticipantsByStatus(skladchina.id, SkladchinaParticipantStatus.paid) } returns 1
        every { skladchinaRepository.countParticipants(skladchina.id) } returns 3
        every { postRepository.findOpenPostsOfInactiveSkladchinas() } returns
            listOf(SkladchinaChatPost(skladchina.id, chatId, 777L, closedAt = null))

        service.flush()

        verify { gateway.editGroupMessage(chatId, 777L, match { it.contains("Сбор отменён") }, null, null, PARSE_MODE_HTML) }
        verify { gateway.unpinChatMessage(chatId, 777L) }
        verify { postRepository.markClosed(skladchina.id) }
    }

    @Test
    fun `disableForClub открепляет живые статусы и удаляет строки`() {
        val id1 = UUID.randomUUID()
        every { postRepository.findOpenByChatId(chatId) } returns
            listOf(SkladchinaChatPost(id1, chatId, 777L, closedAt = null))

        service.disableForClub(link)

        verify { gateway.unpinChatMessage(chatId, 777L) }
        verify { postRepository.delete(id1) }
    }

    @Test
    fun `postDeadlineReminder — пинг в чат тем кто в чате, остальные для DM-фоллбека`() {
        val skladchina = chatStatusSkladchina(clubId = clubId)
        val inChatUser = UUID.randomUUID()
        val outOfChatUser = UUID.randomUUID()
        every { skladchinaRepository.findById(skladchina.id) } returns skladchina
        every { postRepository.findBySkladchinaId(skladchina.id) } returns
            SkladchinaChatPost(skladchina.id, chatId, 777L, closedAt = null)
        every { userRepository.findByIds(listOf(inChatUser, outOfChatUser)) } returns listOf(
            userRecord(inChatUser, 111L, "Наташа"),
            userRecord(outOfChatUser, 222L, "Марк")
        )
        every { gateway.getUserChatState(chatId, 111L) } returns UserChatState.IN_CHAT
        every { gateway.getUserChatState(chatId, 222L) } returns UserChatState.NOT_IN_CHAT
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), any(), any(), PARSE_MODE_HTML) } returns 888L

        val covered = service.postDeadlineReminder(skladchina, listOf(inChatUser, outOfChatUser))

        assertEquals(setOf(inChatUser), covered)
        verify {
            gateway.sendGroupMessageWithUrlButton(
                chatId,
                match { it.contains("Напоминание") && it.contains("tg://user?id=111") && !it.contains("tg://user?id=222") },
                "Открыть сбор", any(), PARSE_MODE_HTML
            )
        }
    }

    @Test
    fun `postDeadlineReminder — чат недоступен (нет поста) — пустой сет, DM всем`() {
        val skladchina = chatStatusSkladchina(clubId = clubId)
        every { postRepository.findBySkladchinaId(skladchina.id) } returns null

        val covered = service.postDeadlineReminder(skladchina, listOf(UUID.randomUUID()))

        assertTrue(covered.isEmpty())
        verify(exactly = 0) { gateway.sendGroupMessageWithUrlButton(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `postDeadlineReminder — отправка в чат не удалась — пустой сет (фоллбек на DM всем)`() {
        val skladchina = chatStatusSkladchina(clubId = clubId)
        val userId = UUID.randomUUID()
        every { postRepository.findBySkladchinaId(skladchina.id) } returns
            SkladchinaChatPost(skladchina.id, chatId, 777L, closedAt = null)
        every { userRepository.findByIds(listOf(userId)) } returns listOf(userRecord(userId, 111L, "Наташа"))
        every { gateway.getUserChatState(chatId, 111L) } returns UserChatState.IN_CHAT
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), any(), any(), PARSE_MODE_HTML) } returns null

        val covered = service.postDeadlineReminder(skladchina, listOf(userId))

        assertTrue(covered.isEmpty())
    }

    @Test
    fun `flush close-проход закрывает пост даже если складчина исчезла из БД`() {
        val orphanId = UUID.randomUUID()
        every { skladchinaRepository.findById(orphanId) } returns null
        every { postRepository.findOpenPostsOfInactiveSkladchinas() } returns
            listOf(SkladchinaChatPost(orphanId, chatId, 777L, closedAt = null))

        service.flush()

        // Мёртвый пост не должен ретраиться вечно: строка закрывается без edit'а.
        verify { postRepository.markClosed(orphanId) }
        verify(exactly = 0) { gateway.editGroupMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `postDeadlineReminder — 16-й участник в чате не упомянут и уходит в DM-фоллбек`() {
        val skladchina = chatStatusSkladchina(clubId = clubId)
        val userIds = (1..16).map { UUID.randomUUID() }
        every { postRepository.findBySkladchinaId(skladchina.id) } returns
            SkladchinaChatPost(skladchina.id, chatId, 777L, closedAt = null)
        // Имена Гость01..Гость16 — сортировка по имени детерминирована, Гость16 последний.
        every { userRepository.findByIds(userIds) } returns
            userIds.mapIndexed { i, id -> userRecord(id, 1000L + i, "Гость%02d".format(i + 1)) }
        every { gateway.getUserChatState(chatId, any()) } returns UserChatState.IN_CHAT
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), any(), any(), PARSE_MODE_HTML) } returns 888L

        val covered = service.postDeadlineReminder(skladchina, userIds)

        // «Упомянутый = пингнутый»: covered ровно MAX_MENTIONS, 16-й получит прежний DM.
        assertEquals(SkladchinaChatStatusRenderer.MAX_MENTIONS, covered.size)
        verify {
            gateway.sendGroupMessageWithUrlButton(
                chatId,
                match { it.contains("Гость15") && !it.contains("Гость16") },
                any(), any(), PARSE_MODE_HTML
            )
        }
    }

    @Test
    fun `postDeadlineReminder — тумблер выключен — пустой сет`() {
        every { chatLinkRepository.findByClubId(clubId) } returns link.copy(skladchinaStatusEnabled = false)
        val skladchina = chatStatusSkladchina(clubId = clubId)

        val covered = service.postDeadlineReminder(skladchina, listOf(UUID.randomUUID()))

        assertTrue(covered.isEmpty())
        verify(exactly = 0) { gateway.sendGroupMessageWithUrlButton(any(), any(), any(), any(), any()) }
    }
}
