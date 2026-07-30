package com.clubs.club

import com.clubs.bot.NotificationService
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.membership.Membership
import com.clubs.membership.MembershipRepository
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Гейт личных приглашений. Ключевое поведение (решение PO 2026-07-30): звать может ЛЮБОЙ участник
 * клуба, а не только владелец / со-организатор. Роль при этом влияет на текст приглашения.
 */
class InviteShareServiceTest {

    private val clubService = mockk<ClubService>()
    private val clubRepository = mockk<ClubRepository>()
    private val membershipRepository = mockk<MembershipRepository>()
    private val userRepository = mockk<UserRepository>()
    private val notificationService = mockk<NotificationService>()
    private val service = InviteShareService(
        clubService, clubRepository, ClubRoleGuard(clubRepository, membershipRepository),
        membershipRepository, userRepository, notificationService, "clubs_admin_bot",
    )

    private val clubId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()

    private fun club(): Club = mockk(relaxed = true) {
        every { id } returns clubId
        every { ownerId } returns this@InviteShareServiceTest.ownerId
        every { name } returns "Партия"
        every { city } returns "Москва"
        every { description } returns "Партия сильных"
        every { memberCount } returns 4
    }

    private fun membership(userId: UUID, role: MembershipRole, status: MembershipStatus) = Membership(
        id = UUID.randomUUID(), userId = userId, clubId = clubId, status = status, role = role,
        joinedAt = OffsetDateTime.now(), subscriptionExpiresAt = null,
        createdAt = OffsetDateTime.now(), updatedAt = OffsetDateTime.now(),
    )

    private fun user(): UsersRecord = mockk(relaxed = true) { every { telegramId } returns 777L }

    private fun arrangeCommon() {
        every { clubRepository.findById(clubId) } returns club()
        every { clubService.ensureInviteCode(clubId) } returns "abc123"
        every { userRepository.findById(any()) } returns user()
    }

    @Test
    fun `обычный участник может пригласить`() {
        arrangeCommon()
        val memberId = UUID.randomUUID()
        every { membershipRepository.findByUserAndClub(memberId, clubId) } returns
            membership(memberId, MembershipRole.member, MembershipStatus.active)
        val html = slot<String>()
        every { notificationService.savePreparedInviteMessage(any(), capture(html), any(), any()) } returns "prepared-1"

        val share = service.createShare(clubId, memberId)

        assertEquals("https://t.me/clubs_admin_bot?startapp=invite_abc123", share.inviteUrl)
        assertEquals("prepared-1", share.preparedMessageId)
        // У обычного участника клуб «наш», не «мой» — он не организатор.
        assertTrue(html.captured.contains("в наш клуб"), "текст: ${html.captured}")
    }

    @Test
    fun `владелец приглашает и текст от первого лица`() {
        arrangeCommon()
        every { membershipRepository.findByUserAndClub(ownerId, clubId) } returns
            membership(ownerId, MembershipRole.organizer, MembershipStatus.active)
        val html = slot<String>()
        every { notificationService.savePreparedInviteMessage(any(), capture(html), any(), any()) } returns "prepared-1"

        service.createShare(clubId, ownerId)

        assertTrue(html.captured.contains("в мой клуб"), "текст: ${html.captured}")
    }

    @Test
    fun `владелец легаси-клуба без строки membership всё равно приглашает`() {
        arrangeCommon()
        every { membershipRepository.findByUserAndClub(ownerId, clubId) } returns null
        every { notificationService.savePreparedInviteMessage(any(), any(), any(), any()) } returns "prepared-1"

        val share = service.createShare(clubId, ownerId)

        assertEquals("prepared-1", share.preparedMessageId)
    }

    @Test
    fun `не-участник получает 403`() {
        arrangeCommon()
        val strangerId = UUID.randomUUID()
        every { membershipRepository.findByUserAndClub(strangerId, clubId) } returns null

        assertThrows<ForbiddenException> { service.createShare(clubId, strangerId) }
    }

    @Test
    fun `должник без доступа пригласить не может`() {
        arrangeCommon()
        val debtorId = UUID.randomUUID()
        every { membershipRepository.findByUserAndClub(debtorId, clubId) } returns
            membership(debtorId, MembershipRole.member, MembershipStatus.frozen)

        assertThrows<ForbiddenException> { service.createShare(clubId, debtorId) }
    }
}
