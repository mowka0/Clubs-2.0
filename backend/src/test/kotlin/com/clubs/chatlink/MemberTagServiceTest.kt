package com.clubs.chatlink

import com.clubs.award.Award
import com.clubs.award.AwardRepository
import com.clubs.award.AwardService
import com.clubs.award.TagSyncRow
import com.clubs.bot.ChatTelegramGateway
import com.clubs.bot.MemberTagLookup
import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Теги наград (слайс 4, Bot API 9.5): тег = последняя награда, лимит 16, гейты тумблера/бота,
 * правила полной синхронизации PO (пустая сторона заполняется, конфликты не трогаются).
 */
class MemberTagServiceTest {

    private lateinit var chatLinkRepository: ChatLinkRepository
    private lateinit var tagRepository: ChatAwardTagRepository
    private lateinit var awardRepository: AwardRepository
    private lateinit var awardService: AwardService
    private lateinit var clubRepository: ClubRepository
    private lateinit var userRepository: UserRepository
    private lateinit var gateway: ChatTelegramGateway
    private lateinit var service: MemberTagService

    private val clubId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val telegramId = 777L
    private val chatId = -100555L

    @BeforeEach
    fun setUp() {
        chatLinkRepository = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        awardRepository = mockk(relaxed = true)
        awardService = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        gateway = mockk(relaxed = true)
        service = MemberTagService(chatLinkRepository, tagRepository, awardRepository, awardService, clubRepository, userRepository, gateway)
        val user = mockk<UsersRecord> {
            every { telegramId } returns this@MemberTagServiceTest.telegramId
        }
        every { userRepository.findById(userId) } returns user
        every { tagRepository.find(clubId, any()) } returns null
        every { gateway.setMemberTag(any(), any(), any()) } returns true
    }

    private fun taggedLink() =
        chatLinkFixture(clubId = clubId, chatId = chatId, awardTagsEnabled = true)

    private fun award(label: String) = Award(
        id = UUID.randomUUID(),
        clubId = clubId,
        userId = userId,
        emoji = "🏆",
        label = label,
        awardedBy = ownerId,
        awardedAt = OffsetDateTime.now()
    )

    @Test
    fun `выдана награда - тег выставлен и учтён, без повышений в админы`() {
        every { chatLinkRepository.findByClubId(clubId) } returns taggedLink()
        every { awardRepository.findByMember(clubId, userId) } returns listOf(award("Летописец"))

        service.onAwardChanged(clubId, userId)

        verify { gateway.setMemberTag(chatId, telegramId, "Летописец") }
        verify { tagRepository.upsert(clubId, telegramId, "Летописец") }
    }

    @Test
    fun `тумблер выключен - Telegram не трогаем`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, chatId = chatId, awardTagsEnabled = false)

        service.onAwardChanged(clubId, userId)

        verify(exactly = 0) { gateway.setMemberTag(any(), any(), any()) }
    }

    @Test
    fun `легаси-награда длиннее 16 - тег обрезается`() {
        every { chatLinkRepository.findByClubId(clubId) } returns taggedLink()
        every { awardRepository.findByMember(clubId, userId) } returns listOf(award("Хранитель традиций клуба"))

        service.onAwardChanged(clubId, userId)

        verify { gateway.setMemberTag(chatId, telegramId, "Хранитель традиц") }
    }

    @Test
    fun `тот же тег уже стоит - no-op без Telegram-вызова`() {
        every { chatLinkRepository.findByClubId(clubId) } returns taggedLink()
        every { awardRepository.findByMember(clubId, userId) } returns listOf(award("Летописец"))
        every { tagRepository.find(clubId, telegramId) } returns ChatAwardTag(telegramId, "Летописец")

        service.onAwardChanged(clubId, userId)

        verify(exactly = 0) { gateway.setMemberTag(any(), any(), any()) }
    }

    @Test
    fun `наград не осталось - тег снимается пустой строкой, учёт чистится`() {
        every { chatLinkRepository.findByClubId(clubId) } returns taggedLink()
        every { awardRepository.findByMember(clubId, userId) } returns emptyList()
        every { tagRepository.find(clubId, telegramId) } returns ChatAwardTag(telegramId, "Летописец")

        service.onAwardChanged(clubId, userId)

        verify { gateway.setMemberTag(chatId, telegramId, "") }
        verify { tagRepository.delete(clubId, telegramId) }
    }

    @Test
    fun `backfill тегирует всех живых участников с наградами`() {
        val link = taggedLink()
        every { awardRepository.findTagSyncRows(clubId) } returns listOf(
            TagSyncRow(UUID.randomUUID(), 1L, "Летописец"),
            TagSyncRow(UUID.randomUUID(), 2L, null), // без наград — не тегируется
            TagSyncRow(UUID.randomUUID(), 3L, "Казначей")
        )

        service.backfillForClub(link)

        verify { gateway.setMemberTag(chatId, 1L, "Летописец") }
        verify { gateway.setMemberTag(chatId, 3L, "Казначей") }
        verify(exactly = 0) { gateway.setMemberTag(chatId, 2L, any()) }
    }

    @Test
    fun `sync - награда есть, тега нет - тег выставляется`() {
        val link = taggedLink()
        every { awardRepository.findTagSyncRows(clubId) } returns
            listOf(TagSyncRow(userId, telegramId, "Летописец"))
        every { gateway.getMemberTag(chatId, telegramId) } returns MemberTagLookup(tag = null, inChat = true)

        service.syncClub(link)

        verify { gateway.setMemberTag(chatId, telegramId, "Летописец") }
    }

    @Test
    fun `sync - наград нет, тег есть - награда импортируется в приложение`() {
        val link = taggedLink()
        every { awardRepository.findTagSyncRows(clubId) } returns
            listOf(TagSyncRow(userId, telegramId, null))
        every { gateway.getMemberTag(chatId, telegramId) } returns MemberTagLookup(tag = "Ветеран", inChat = true)
        every { clubRepository.findById(clubId) } returns testClub()

        service.syncClub(link)

        verify { awardService.grant(clubId, userId, MemberTagService.IMPORTED_AWARD_EMOJI, "Ветеран", ownerId) }
        verify(exactly = 0) { gateway.setMemberTag(any(), any(), any()) }
    }

    @Test
    fun `sync - обе стороны заполнены - конфликт не трогаем (ручной тег уважается)`() {
        val link = taggedLink()
        every { awardRepository.findTagSyncRows(clubId) } returns
            listOf(TagSyncRow(userId, telegramId, "Летописец"))
        every { gateway.getMemberTag(chatId, telegramId) } returns MemberTagLookup(tag = "Свой тег", inChat = true)

        service.syncClub(link)

        verify(exactly = 0) { gateway.setMemberTag(any(), any(), any()) }
        verify(exactly = 0) { awardService.grant(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sync - участник не в чате - пропускается`() {
        val link = taggedLink()
        every { awardRepository.findTagSyncRows(clubId) } returns
            listOf(TagSyncRow(userId, telegramId, "Летописец"))
        every { gateway.getMemberTag(chatId, telegramId) } returns MemberTagLookup(tag = null, inChat = false)

        service.syncClub(link)

        verify(exactly = 0) { gateway.setMemberTag(any(), any(), any()) }
    }

    @Test
    fun `sync - Telegram не ответил - участник пропускается без импорта`() {
        val link = taggedLink()
        every { awardRepository.findTagSyncRows(clubId) } returns
            listOf(TagSyncRow(userId, telegramId, null))
        every { gateway.getMemberTag(chatId, telegramId) } returns null

        service.syncClub(link)

        verify(exactly = 0) { awardService.grant(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `выключение снимает все теги из учёта`() {
        val link = taggedLink()
        every { tagRepository.findAllForClub(clubId) } returns listOf(
            ChatAwardTag(1L, "Летописец"), ChatAwardTag(2L, "Казначей")
        )

        service.disableForClub(link)

        verify { gateway.setMemberTag(chatId, 1L, "") }
        verify { gateway.setMemberTag(chatId, 2L, "") }
        verify { tagRepository.deleteAllForClub(clubId) }
    }

    private fun testClub(): Club = Club(
        id = clubId,
        ownerId = ownerId,
        name = "Партия",
        description = "desc",
        category = ClubCategory.board_games,
        accessType = AccessType.closed,
        city = "Москва",
        district = null,
        memberLimit = 20,
        subscriptionPrice = 0,
        avatarUrl = null,
        rules = null,
        applicationQuestion = null,
        inviteLink = null,
        memberCount = 5,
        isActive = true,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )
}
