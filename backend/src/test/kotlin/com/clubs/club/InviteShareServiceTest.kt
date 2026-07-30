package com.clubs.club

import com.clubs.bot.NotificationService
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.generated.jooq.enums.AccessType
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Личные приглашения. Два поведения, оба — решения PO 2026-07-30:
 *  - звать может ЛЮБОЙ участник клуба, не только владелец / со-организатор;
 *  - приглашение из Telegram ведёт на ЗАЯВКУ (заявочный код), а прямую ссылку «мимо заявки»
 *    копирует только менеджер.
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
    private val directUrl = "https://t.me/clubs_admin_bot?startapp=invite_direct1"
    private val applyUrl = "https://t.me/clubs_admin_bot?startapp=invite_apply1"

    /** Кнопка prepared message — сюда попадает ссылка, которая реально уходит приглашённому. */
    private val buttonUrl = slot<String>()
    private val messageHtml = slot<String>()

    private fun arrange(accessType: AccessType = AccessType.closed) {
        every { clubRepository.findById(clubId) } returns mockk(relaxed = true) {
            every { id } returns clubId
            every { ownerId } returns this@InviteShareServiceTest.ownerId
            every { name } returns "Партия"
            every { city } returns "Москва"
            every { description } returns "Партия сильных"
            every { memberCount } returns 4
            every { this@mockk.accessType } returns accessType
        }
        every { clubService.ensureInviteCode(clubId) } returns "direct1"
        every { clubService.ensureApplyInviteCode(clubId) } returns "apply1"
        every { userRepository.findById(any()) } returns mockk<UsersRecord>(relaxed = true) {
            every { telegramId } returns 777L
        }
        every {
            notificationService.savePreparedInviteMessage(any(), capture(messageHtml), any(), capture(buttonUrl))
        } returns "prepared-1"
    }

    private fun membership(userId: UUID, role: MembershipRole, status: MembershipStatus) = Membership(
        id = UUID.randomUUID(), userId = userId, clubId = clubId, status = status, role = role,
        joinedAt = OffsetDateTime.now(), subscriptionExpiresAt = null,
        createdAt = OffsetDateTime.now(), updatedAt = OffsetDateTime.now(),
    )

    @Test
    fun `обычный участник закрытого клуба копирует заявочную ссылку, обхода ему не дают`() {
        arrange()
        val memberId = UUID.randomUUID()
        every { membershipRepository.findByUserAndClub(memberId, clubId) } returns
            membership(memberId, MembershipRole.member, MembershipStatus.active)

        val share = service.createShare(clubId, memberId)

        assertEquals(applyUrl, share.inviteUrl, "участнику копируется заявочная ссылка")
        assertFalse(share.linkBypassesApproval, "обхода одобрения у участника быть не может")
        assertEquals(applyUrl, buttonUrl.captured, "в Telegram уходит заявочная ссылка")
        // У обычного участника клуб «наш», не «мой» — он не организатор.
        assertTrue(messageHtml.captured.contains("в наш клуб"), "текст: ${messageHtml.captured}")
    }

    @Test
    fun `менеджер закрытого клуба копирует прямую ссылку и получает предупреждение`() {
        arrange()
        every { membershipRepository.findByUserAndClub(ownerId, clubId) } returns
            membership(ownerId, MembershipRole.organizer, MembershipStatus.active)

        val share = service.createShare(clubId, ownerId)

        assertEquals(directUrl, share.inviteUrl, "менеджеру копируется прямая ссылка")
        assertTrue(share.linkBypassesApproval, "по прямой ссылке вход идёт мимо заявки — надо подписать")
        // Само приглашение в Telegram всё равно заявочное: одобрение остаётся за организатором.
        assertEquals(applyUrl, buttonUrl.captured)
        assertTrue(messageHtml.captured.contains("в мой клуб"), "текст: ${messageHtml.captured}")
    }

    @Test
    fun `в открытом клубе подписывать нечего — одобрения там нет`() {
        arrange(accessType = AccessType.`open`)
        every { membershipRepository.findByUserAndClub(ownerId, clubId) } returns
            membership(ownerId, MembershipRole.organizer, MembershipStatus.active)

        val share = service.createShare(clubId, ownerId)

        assertEquals(directUrl, share.inviteUrl)
        assertFalse(share.linkBypassesApproval)
    }

    @Test
    fun `владелец легаси-клуба без строки membership всё равно приглашает`() {
        arrange()
        every { membershipRepository.findByUserAndClub(ownerId, clubId) } returns null

        val share = service.createShare(clubId, ownerId)

        assertEquals("prepared-1", share.preparedMessageId)
        assertEquals(directUrl, share.inviteUrl, "владелец — менеджер и без строки membership")
    }

    @Test
    fun `не-участник получает 403`() {
        arrange()
        val strangerId = UUID.randomUUID()
        every { membershipRepository.findByUserAndClub(strangerId, clubId) } returns null

        assertThrows<ForbiddenException> { service.createShare(clubId, strangerId) }
    }

    @Test
    fun `должник без доступа пригласить не может`() {
        arrange()
        val debtorId = UUID.randomUUID()
        every { membershipRepository.findByUserAndClub(debtorId, clubId) } returns
            membership(debtorId, MembershipRole.member, MembershipStatus.frozen)

        assertThrows<ForbiddenException> { service.createShare(clubId, debtorId) }
    }
}
