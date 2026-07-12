package com.clubs.membership

import com.clubs.bot.NotificationService
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * PUT /role (co-organizers): промоут/демоут, 400 (self / владелец / role=organizer /
 * не-active промоут / лимит 5), 404 (нет membership / cancelled), идемпотентность,
 * 409-гонка (rows-affected guard), best-effort DM.
 */
class MemberRoleServiceTest {

    private lateinit var membershipRepository: MembershipRepository
    private lateinit var clubRepository: ClubRepository
    private lateinit var userRepository: UserRepository
    private lateinit var notificationService: NotificationService
    private lateinit var service: MemberRoleService

    private val clubId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()
    private val targetUserId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        membershipRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        service = MemberRoleService(
            membershipRepository, clubRepository, userRepository, notificationService, MembershipMapper()
        )
        every { membershipRepository.countCoOrganizers(clubId) } returns 0
        every { membershipRepository.updateRole(any(), any(), any()) } returns 1
    }

    private fun membership(
        role: MembershipRole = MembershipRole.member,
        status: MembershipStatus = MembershipStatus.active
    ): Membership {
        val now = OffsetDateTime.now()
        return Membership(
            id = UUID.randomUUID(), userId = targetUserId, clubId = clubId, status = status,
            role = role, joinedAt = now, subscriptionExpiresAt = null, createdAt = now, updatedAt = now
        )
    }

    private fun stubTarget(m: Membership?) {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
    }

    // --- happy path ---

    @Test
    fun `promotes an active member to co_organizer`() {
        val m = membership()
        stubTarget(m)

        val result = service.changeRole(clubId, targetUserId, ownerId, "co_organizer")

        assertEquals("co_organizer", result.role)
        verify(exactly = 1) { membershipRepository.updateRole(m.id, MembershipRole.member, MembershipRole.co_organizer) }
    }

    @Test
    fun `demotes a co_organizer back to member`() {
        val m = membership(role = MembershipRole.co_organizer)
        stubTarget(m)

        val result = service.changeRole(clubId, targetUserId, ownerId, "member")

        assertEquals("member", result.role)
        verify(exactly = 1) { membershipRepository.updateRole(m.id, MembershipRole.co_organizer, MembershipRole.member) }
    }

    @Test
    fun `demotes a frozen co_organizer (demote works in any live status)`() {
        stubTarget(membership(role = MembershipRole.co_organizer, status = MembershipStatus.frozen))

        val result = service.changeRole(clubId, targetUserId, ownerId, "member")

        assertEquals("member", result.role)
    }

    // --- идемпотентность ---

    @Test
    fun `repeated promote of an existing co_organizer is a no-op 200`() {
        stubTarget(membership(role = MembershipRole.co_organizer))

        val result = service.changeRole(clubId, targetUserId, ownerId, "co_organizer")

        assertEquals("co_organizer", result.role)
        verify(exactly = 0) { membershipRepository.updateRole(any(), any(), any()) }
        verify(exactly = 0) { notificationService.sendDirectMessageWithDeepLink(any(), any(), any(), any()) }
    }

    @Test
    fun `repeated demote of a plain member is a no-op 200`() {
        stubTarget(membership(role = MembershipRole.member))

        val result = service.changeRole(clubId, targetUserId, ownerId, "member")

        assertEquals("member", result.role)
        verify(exactly = 0) { membershipRepository.updateRole(any(), any(), any()) }
    }

    // --- 400 ---

    @Test
    fun `rejects changing own role`() {
        assertThrows<ValidationException> { service.changeRole(clubId, ownerId, ownerId, "co_organizer") }
    }

    @Test
    fun `rejects demoting the owner (organizer target)`() {
        stubTarget(membership(role = MembershipRole.organizer))
        assertThrows<ValidationException> { service.changeRole(clubId, targetUserId, ownerId, "member") }
    }

    @Test
    fun `rejects role=organizer in the body (ownership transfer is out of scope)`() {
        assertThrows<ValidationException> { service.changeRole(clubId, targetUserId, ownerId, "organizer") }
    }

    @Test
    fun `rejects an unknown role value`() {
        assertThrows<ValidationException> { service.changeRole(clubId, targetUserId, ownerId, "moderator") }
    }

    @Test
    fun `rejects promoting a frozen member (У-9)`() {
        stubTarget(membership(status = MembershipStatus.frozen))
        assertThrows<ValidationException> { service.changeRole(clubId, targetUserId, ownerId, "co_organizer") }
        verify(exactly = 0) { membershipRepository.updateRole(any(), any(), any()) }
    }

    @Test
    fun `rejects promoting an expired member (У-9)`() {
        stubTarget(membership(status = MembershipStatus.expired))
        assertThrows<ValidationException> { service.changeRole(clubId, targetUserId, ownerId, "co_organizer") }
    }

    @Test
    fun `rejects the 6th co-organizer (limit У-3)`() {
        stubTarget(membership())
        every { membershipRepository.countCoOrganizers(clubId) } returns MemberRoleService.CO_ORGANIZER_LIMIT

        assertThrows<ValidationException> { service.changeRole(clubId, targetUserId, ownerId, "co_organizer") }
        verify(exactly = 0) { membershipRepository.updateRole(any(), any(), any()) }
    }

    // --- 404 ---

    @Test
    fun `throws NotFound when the target has no membership`() {
        stubTarget(null)
        assertThrows<NotFoundException> { service.changeRole(clubId, targetUserId, ownerId, "co_organizer") }
    }

    @Test
    fun `throws NotFound when the target membership is cancelled`() {
        stubTarget(membership(status = MembershipStatus.cancelled))
        assertThrows<NotFoundException> { service.changeRole(clubId, targetUserId, ownerId, "co_organizer") }
    }

    // --- 409 ---

    @Test
    fun `throws Conflict when the guarded update affects 0 rows (concurrent role change)`() {
        stubTarget(membership())
        every { membershipRepository.updateRole(any(), any(), any()) } returns 0

        assertThrows<ConflictException> { service.changeRole(clubId, targetUserId, ownerId, "co_organizer") }
    }

    // --- DM best-effort (У-6) ---

    @Test
    fun `promotion DMs the target a deep-link`() {
        stubTarget(membership())
        val user = mockk<com.clubs.generated.jooq.tables.records.UsersRecord>(relaxed = true) {
            every { telegramId } returns 42L
        }
        every { userRepository.findById(targetUserId) } returns user

        service.changeRole(clubId, targetUserId, ownerId, "co_organizer")

        verify(exactly = 1) {
            notificationService.sendDirectMessageWithDeepLink(42L, any(), "/clubs/$clubId", any())
        }
    }

    @Test
    fun `demotion DMs a plain message`() {
        stubTarget(membership(role = MembershipRole.co_organizer))
        val user = mockk<com.clubs.generated.jooq.tables.records.UsersRecord>(relaxed = true) {
            every { telegramId } returns 42L
        }
        every { userRepository.findById(targetUserId) } returns user

        service.changeRole(clubId, targetUserId, ownerId, "member")

        verify(exactly = 1) { notificationService.sendDirectMessage(42L, any()) }
    }

    @Test
    fun `DM failure never breaks the role change`() {
        stubTarget(membership())
        every { userRepository.findById(targetUserId) } throws RuntimeException("telegram down")

        val result = service.changeRole(clubId, targetUserId, ownerId, "co_organizer")

        assertEquals("co_organizer", result.role)
    }

    // --- anti-TOCTOU: сериализация промоутов ---

    @Test
    fun `promote takes the club role lock before counting the limit`() {
        stubTarget(membership())

        service.changeRole(clubId, targetUserId, ownerId, "co_organizer")

        verifyOrder {
            membershipRepository.lockRoleChanges(clubId)
            membershipRepository.countCoOrganizers(clubId)
            membershipRepository.updateRole(any(), MembershipRole.member, MembershipRole.co_organizer)
        }
    }

    @Test
    fun `demote does not take the role lock (no limit involved)`() {
        stubTarget(membership(role = MembershipRole.co_organizer))

        service.changeRole(clubId, targetUserId, ownerId, "member")

        verify(exactly = 0) { membershipRepository.lockRoleChanges(any()) }
    }
}
