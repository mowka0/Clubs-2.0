package com.clubs.common.auth

import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.membership.Membership
import com.clubs.membership.MembershipRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Гейт-матрица единого предиката «менеджер клуба» (co-organizers, тест-план спеки):
 * owner ✅ / active co-org ✅ / frozen co-org ❌ / expired co-org ❌ / cancelled co-org ❌ /
 * member ❌ / не-член ❌ (fail-close) + target-матрица requireManageableTarget.
 */
class ClubManagerGuardTest {

    private val clubRepository = mockk<ClubRepository>()
    private val membershipRepository = mockk<MembershipRepository>()
    private val guard = ClubManagerGuard(clubRepository, membershipRepository)

    private val clubId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()
    private val callerId = UUID.randomUUID()
    private val club = club(clubId, ownerId)

    private fun club(id: UUID, owner: UUID): Club {
        val now = OffsetDateTime.now()
        return Club(
            id = id, ownerId = owner, name = "Club", description = "d",
            category = ClubCategory.sport, accessType = AccessType.`open`, city = "Moscow",
            district = null, memberLimit = 30, subscriptionPrice = 0, avatarUrl = null,
            rules = null, applicationQuestion = null, inviteLink = null, memberCount = 3,
            isActive = true, createdAt = now, updatedAt = now
        )
    }

    private fun membership(
        role: MembershipRole,
        status: MembershipStatus,
        userId: UUID = callerId
    ): Membership {
        val now = OffsetDateTime.now()
        return Membership(
            id = UUID.randomUUID(), userId = userId, clubId = clubId, status = status, role = role,
            joinedAt = now, subscriptionExpiresAt = null, createdAt = now, updatedAt = now
        )
    }

    private fun stubCallerMembership(m: Membership?) {
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns m
    }

    // --- isManager: гейт-матрица вызывающего ---

    @Test
    fun `owner is a manager without touching memberships`() {
        assertTrue(guard.isManager(club, ownerId))
    }

    @Test
    fun `active co-organizer is a manager`() {
        stubCallerMembership(membership(MembershipRole.co_organizer, MembershipStatus.active))
        assertTrue(guard.isManager(club, callerId))
    }

    @Test
    fun `frozen co-organizer is NOT a manager (fail-close)`() {
        stubCallerMembership(membership(MembershipRole.co_organizer, MembershipStatus.frozen))
        assertFalse(guard.isManager(club, callerId))
    }

    @Test
    fun `expired co-organizer is NOT a manager (fail-close)`() {
        stubCallerMembership(membership(MembershipRole.co_organizer, MembershipStatus.expired))
        assertFalse(guard.isManager(club, callerId))
    }

    @Test
    fun `cancelled co-organizer is NOT a manager`() {
        stubCallerMembership(membership(MembershipRole.co_organizer, MembershipStatus.cancelled))
        assertFalse(guard.isManager(club, callerId))
    }

    @Test
    fun `active plain member is NOT a manager`() {
        stubCallerMembership(membership(MembershipRole.member, MembershipStatus.active))
        assertFalse(guard.isManager(club, callerId))
    }

    @Test
    fun `non-member is NOT a manager`() {
        stubCallerMembership(null)
        assertFalse(guard.isManager(club, callerId))
    }

    // --- require- варианты ---

    @Test
    fun `requireManager throws Forbidden for a non-manager`() {
        stubCallerMembership(null)
        assertThrows<ForbiddenException> { guard.requireManager(club, callerId) }
    }

    @Test
    fun `requireClubManager throws NotFound when the club is missing`() {
        every { clubRepository.findById(clubId) } returns null
        assertThrows<NotFoundException> { guard.requireClubManager(clubId, ownerId) }
    }

    @Test
    fun `requireClubManager returns the club for the owner`() {
        every { clubRepository.findById(clubId) } returns club
        assertEquals(club, guard.requireClubManager(clubId, ownerId))
    }

    @Test
    fun `isClubManager is false when the club is missing`() {
        every { clubRepository.findById(clubId) } returns null
        assertFalse(guard.isClubManager(clubId, ownerId))
    }

    // --- isActiveManagerMembership (для уже загруженной строки вызывающего) ---

    @Test
    fun `isActiveManagerMembership matrix`() {
        assertTrue(guard.isActiveManagerMembership(membership(MembershipRole.organizer, MembershipStatus.active)))
        assertTrue(guard.isActiveManagerMembership(membership(MembershipRole.co_organizer, MembershipStatus.active)))
        assertFalse(guard.isActiveManagerMembership(membership(MembershipRole.co_organizer, MembershipStatus.frozen)))
        assertFalse(guard.isActiveManagerMembership(membership(MembershipRole.co_organizer, MembershipStatus.expired)))
        assertFalse(guard.isActiveManagerMembership(membership(MembershipRole.member, MembershipStatus.active)))
        assertFalse(guard.isActiveManagerMembership(null))
    }

    // --- requireManageableTarget: per-target матрица ---

    private val targetUserId = UUID.randomUUID()

    @Test
    fun `owner can manage a plain member`() {
        guard.requireManageableTarget(club, membership(MembershipRole.member, MembershipStatus.active, targetUserId), ownerId)
    }

    @Test
    fun `owner can manage a co-organizer`() {
        guard.requireManageableTarget(club, membership(MembershipRole.co_organizer, MembershipStatus.active, targetUserId), ownerId)
    }

    @Test
    fun `owner cannot manage the organizer row (himself)`() {
        assertThrows<ValidationException> {
            guard.requireManageableTarget(club, membership(MembershipRole.organizer, MembershipStatus.active, ownerId), ownerId)
        }
    }

    @Test
    fun `co-org caller can manage a plain member`() {
        guard.requireManageableTarget(club, membership(MembershipRole.member, MembershipStatus.active, targetUserId), callerId)
    }

    @Test
    fun `co-org caller cannot manage another co-organizer`() {
        assertThrows<ForbiddenException> {
            guard.requireManageableTarget(club, membership(MembershipRole.co_organizer, MembershipStatus.active, targetUserId), callerId)
        }
    }

    @Test
    fun `co-org caller cannot manage the owner`() {
        assertThrows<ForbiddenException> {
            guard.requireManageableTarget(club, membership(MembershipRole.organizer, MembershipStatus.active, ownerId), callerId)
        }
    }
}
