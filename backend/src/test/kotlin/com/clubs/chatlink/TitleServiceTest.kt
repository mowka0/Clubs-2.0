package com.clubs.chatlink

import com.clubs.award.Award
import com.clubs.award.AwardRepository
import com.clubs.award.AwardTitleCandidate
import com.clubs.bot.ChatTelegramGateway
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.membership.Membership
import com.clubs.membership.MembershipRepository
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Титулы наград (слайс 4): титул = последняя награда, лимит 16, гейты тумблера/бота,
 * пересечение со строгим режимом (должник при включённом строгом режиме не титулуется).
 */
class TitleServiceTest {

    private lateinit var chatLinkRepository: ChatLinkRepository
    private lateinit var titleRepository: ChatAwardTitleRepository
    private lateinit var awardRepository: AwardRepository
    private lateinit var membershipRepository: MembershipRepository
    private lateinit var userRepository: UserRepository
    private lateinit var gateway: ChatTelegramGateway
    private lateinit var service: TitleService

    private val clubId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val telegramId = 777L
    private val chatId = -100555L

    @BeforeEach
    fun setUp() {
        chatLinkRepository = mockk(relaxed = true)
        titleRepository = mockk(relaxed = true)
        awardRepository = mockk(relaxed = true)
        membershipRepository = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        gateway = mockk(relaxed = true)
        service = TitleService(chatLinkRepository, titleRepository, awardRepository, membershipRepository, userRepository, gateway)
        val user = mockk<UsersRecord> {
            every { telegramId } returns this@TitleServiceTest.telegramId
        }
        every { userRepository.findById(userId) } returns user
        every { titleRepository.find(clubId, telegramId) } returns null
        every { gateway.setAdminCustomTitle(any(), any(), any()) } returns true
        every { gateway.demoteTitledAdmin(any(), any()) } returns true
    }

    private fun titledLink(strict: Boolean = false) =
        chatLinkFixture(clubId = clubId, chatId = chatId, awardTitlesEnabled = true, strictModeEnabled = strict)

    private fun membership(status: MembershipStatus) = Membership(
        id = UUID.randomUUID(),
        userId = userId,
        clubId = clubId,
        status = status,
        role = MembershipRole.member,
        joinedAt = OffsetDateTime.now(),
        subscriptionExpiresAt = null,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )

    private fun award(label: String) = Award(
        id = UUID.randomUUID(),
        clubId = clubId,
        userId = userId,
        emoji = "🏆",
        label = label,
        awardedBy = UUID.randomUUID(),
        awardedAt = OffsetDateTime.now()
    )

    @Test
    fun `выдана награда - промоут, титул, учёт`() {
        every { chatLinkRepository.findByClubId(clubId) } returns titledLink()
        every { awardRepository.findByMember(clubId, userId) } returns listOf(award("Летописец"))
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns membership(MembershipStatus.active)

        service.onAwardChanged(clubId, userId)

        verifyOrder {
            gateway.promoteToTitledAdmin(chatId, telegramId)
            gateway.setAdminCustomTitle(chatId, telegramId, "Летописец")
        }
        verify { titleRepository.upsert(clubId, telegramId, "Летописец") }
    }

    @Test
    fun `тумблер выключен - Telegram не трогаем`() {
        every { chatLinkRepository.findByClubId(clubId) } returns
            chatLinkFixture(clubId = clubId, chatId = chatId, awardTitlesEnabled = false)

        service.onAwardChanged(clubId, userId)

        verify(exactly = 0) { gateway.promoteToTitledAdmin(any(), any()) }
        verify(exactly = 0) { gateway.setAdminCustomTitle(any(), any(), any()) }
    }

    @Test
    fun `легаси-награда длиннее 16 - титул обрезается`() {
        every { chatLinkRepository.findByClubId(clubId) } returns titledLink()
        every { awardRepository.findByMember(clubId, userId) } returns listOf(award("Хранитель традиций клуба"))
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns membership(MembershipStatus.active)

        service.onAwardChanged(clubId, userId)

        verify { gateway.setAdminCustomTitle(chatId, telegramId, "Хранитель традиц") }
    }

    @Test
    fun `тот же титул уже стоит - no-op без Telegram-вызовов`() {
        every { chatLinkRepository.findByClubId(clubId) } returns titledLink()
        every { awardRepository.findByMember(clubId, userId) } returns listOf(award("Летописец"))
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns membership(MembershipStatus.active)
        every { titleRepository.find(clubId, telegramId) } returns ChatAwardTitle(telegramId, "Летописец")

        service.onAwardChanged(clubId, userId)

        verify(exactly = 0) { gateway.promoteToTitledAdmin(any(), any()) }
        verify(exactly = 0) { gateway.setAdminCustomTitle(any(), any(), any()) }
    }

    @Test
    fun `отзыв последней награды - откат титула к предыдущей без повторного промоута`() {
        every { chatLinkRepository.findByClubId(clubId) } returns titledLink()
        every { awardRepository.findByMember(clubId, userId) } returns listOf(award("Душа компании"))
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns membership(MembershipStatus.active)
        every { titleRepository.find(clubId, telegramId) } returns ChatAwardTitle(telegramId, "Летописец")

        service.onAwardChanged(clubId, userId)

        // Уже наш админ (есть в учёте) — только смена титула
        verify(exactly = 0) { gateway.promoteToTitledAdmin(any(), any()) }
        verify { gateway.setAdminCustomTitle(chatId, telegramId, "Душа компании") }
        verify { titleRepository.upsert(clubId, telegramId, "Душа компании") }
    }

    @Test
    fun `наград не осталось - демоут и чистка учёта`() {
        every { chatLinkRepository.findByClubId(clubId) } returns titledLink()
        every { awardRepository.findByMember(clubId, userId) } returns emptyList()
        every { titleRepository.find(clubId, telegramId) } returns ChatAwardTitle(telegramId, "Летописец")

        service.onAwardChanged(clubId, userId)

        verify { gateway.demoteTitledAdmin(chatId, telegramId) }
        verify { titleRepository.delete(clubId, telegramId) }
    }

    @Test
    fun `должник при ВКЛЮЧЁННОМ строгом режиме не титулуется`() {
        every { chatLinkRepository.findByClubId(clubId) } returns titledLink(strict = true)
        every { awardRepository.findByMember(clubId, userId) } returns listOf(award("Летописец"))
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns membership(MembershipStatus.expired)

        service.onAwardChanged(clubId, userId)

        verify(exactly = 0) { gateway.promoteToTitledAdmin(any(), any()) }
        verify(exactly = 0) { gateway.setAdminCustomTitle(any(), any(), any()) }
    }

    @Test
    fun `должник при выключенном строгом режиме титулуется`() {
        every { chatLinkRepository.findByClubId(clubId) } returns titledLink(strict = false)
        every { awardRepository.findByMember(clubId, userId) } returns listOf(award("Летописец"))
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns membership(MembershipStatus.expired)

        service.onAwardChanged(clubId, userId)

        verify { gateway.setAdminCustomTitle(chatId, telegramId, "Летописец") }
    }

    @Test
    fun `backfill титулует кандидатов, пропуская должников при строгом режиме`() {
        val link = titledLink(strict = true)
        every { awardRepository.findTitleCandidates(clubId) } returns listOf(
            AwardTitleCandidate(UUID.randomUUID(), 1L, "Летописец", "active"),
            AwardTitleCandidate(UUID.randomUUID(), 2L, "Казначей", "expired")
        )
        every { titleRepository.find(clubId, any()) } returns null

        service.backfillForClub(link)

        verify { gateway.setAdminCustomTitle(chatId, 1L, "Летописец") }
        verify(exactly = 0) { gateway.setAdminCustomTitle(chatId, 2L, any()) }
    }

    @Test
    fun `выключение снимает все титулы из учёта`() {
        val link = titledLink()
        every { titleRepository.findAllForClub(clubId) } returns listOf(
            ChatAwardTitle(1L, "Летописец"), ChatAwardTitle(2L, "Казначей")
        )

        service.disableForClub(link)

        verify { gateway.demoteTitledAdmin(chatId, 1L) }
        verify { gateway.demoteTitledAdmin(chatId, 2L) }
        verify { titleRepository.deleteAllForClub(clubId) }
    }
}
