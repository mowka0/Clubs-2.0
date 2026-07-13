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
 * Гейт-матрица капабилити-модели (club-roles): owner ✅ любое право / active co-org ✅ делегируемое,
 * ❌ владельческое / frozen|expired|cancelled co-org ❌ / member ❌ / не-член ❌ (fail-close) +
 * инвариант карты RoleCapabilities + target-матрица requireManageableTarget.
 */
class ClubRoleGuardTest {

    private val clubRepository = mockk<ClubRepository>()
    private val membershipRepository = mockk<MembershipRepository>()
    private val guard = ClubRoleGuard(clubRepository, membershipRepository)

    private val clubId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()
    private val callerId = UUID.randomUUID()
    private val club = club(clubId, ownerId)

    // Пробы: делегируемое право (есть у co-org) и владельческое (у co-org его нет).
    private val delegated = ClubCapability.APPROVE_APPLICATIONS
    private val ownerOnly = ClubCapability.DELETE_CLUB

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

    // --- hasCapability: гейт-матрица вызывающего ---

    @Test
    fun `owner has any capability without touching memberships`() {
        assertTrue(guard.hasCapability(club, ownerId, delegated))
        assertTrue(guard.hasCapability(club, ownerId, ownerOnly))
    }

    @Test
    fun `active co-organizer has a delegated capability`() {
        stubCallerMembership(membership(MembershipRole.co_organizer, MembershipStatus.active))
        assertTrue(guard.hasCapability(club, callerId, delegated))
    }

    @Test
    fun `active co-organizer does NOT have an owner-only capability`() {
        stubCallerMembership(membership(MembershipRole.co_organizer, MembershipStatus.active))
        assertFalse(guard.hasCapability(club, callerId, ownerOnly))
    }

    @Test
    fun `frozen co-organizer has no capability (fail-close)`() {
        stubCallerMembership(membership(MembershipRole.co_organizer, MembershipStatus.frozen))
        assertFalse(guard.hasCapability(club, callerId, delegated))
    }

    @Test
    fun `expired co-organizer has no capability (fail-close)`() {
        stubCallerMembership(membership(MembershipRole.co_organizer, MembershipStatus.expired))
        assertFalse(guard.hasCapability(club, callerId, delegated))
    }

    @Test
    fun `cancelled co-organizer has no capability`() {
        stubCallerMembership(membership(MembershipRole.co_organizer, MembershipStatus.cancelled))
        assertFalse(guard.hasCapability(club, callerId, delegated))
    }

    @Test
    fun `active plain member has no capability`() {
        stubCallerMembership(membership(MembershipRole.member, MembershipStatus.active))
        assertFalse(guard.hasCapability(club, callerId, delegated))
    }

    @Test
    fun `non-member has no capability`() {
        stubCallerMembership(null)
        assertFalse(guard.hasCapability(club, callerId, delegated))
    }

    // --- require варианты ---

    @Test
    fun `requireCapability throws Forbidden for a caller without it`() {
        stubCallerMembership(null)
        assertThrows<ForbiddenException> { guard.requireCapability(club, callerId, delegated) }
    }

    @Test
    fun `requireCapability(clubId) throws NotFound when the club is missing`() {
        every { clubRepository.findById(clubId) } returns null
        assertThrows<NotFoundException> { guard.requireCapability(clubId, ownerId, delegated) }
    }

    @Test
    fun `requireCapability(clubId) returns the club for the owner`() {
        every { clubRepository.findById(clubId) } returns club
        assertEquals(club, guard.requireCapability(clubId, ownerId, delegated))
    }

    @Test
    fun `hasCapability(clubId) is false when the club is missing`() {
        every { clubRepository.findById(clubId) } returns null
        assertFalse(guard.hasCapability(clubId, ownerId, delegated))
    }

    // --- RoleCapabilities: инвариант владельческого бакета (PO №5) ---

    @Test
    fun `role capability classification is exhaustive and matches the PO matrix`() {
        // Явная фиксация классификации: новое право в enum ClubCapability сломает этот тест, пока
        // разработчик осознанно не отнесёт его к делегируемым ИЛИ владельческим. Рантайм-дефолт при
        // этом fail-closed (неклассифицированное право уходит во владельческие), но тест форсит выбор.
        val expectedDelegated = setOf(
            ClubCapability.APPROVE_APPLICATIONS, ClubCapability.MANAGE_EVENTS, ClubCapability.MANAGE_SKLADCHINA,
            ClubCapability.MANAGE_MEMBERS, ClubCapability.GRANT_AWARDS, ClubCapability.EDIT_CLUB_SETTINGS,
            ClubCapability.VIEW_FINANCES, ClubCapability.VIEW_STATS, ClubCapability.SEND_INVITES
        )
        val expectedOwnerOnly = setOf(
            ClubCapability.MANAGE_ROLES, ClubCapability.EDIT_PAYMENT_REQUISITES, ClubCapability.MANAGE_CHAT,
            ClubCapability.MANAGE_BILLING, ClubCapability.DELETE_CLUB
        )
        // Разбиение полное и непересекающееся — ловит забытую при расширении enum capability.
        assertEquals(ClubCapability.entries.toSet(), expectedDelegated + expectedOwnerOnly)
        assertTrue((expectedDelegated intersect expectedOwnerOnly).isEmpty())
        // Карта ролей соответствует классификации.
        assertEquals(ClubCapability.entries.toSet(), RoleCapabilities.capabilitiesFor(MembershipRole.organizer))
        assertEquals(expectedDelegated, RoleCapabilities.capabilitiesFor(MembershipRole.co_organizer))
        assertEquals(expectedOwnerOnly, RoleCapabilities.OWNER_ONLY_CAPABILITIES)
        assertTrue(RoleCapabilities.capabilitiesFor(MembershipRole.member).isEmpty())
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
