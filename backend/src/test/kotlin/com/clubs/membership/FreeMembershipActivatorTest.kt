package com.clubs.membership

import com.clubs.club.ClubRepository
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

class FreeMembershipActivatorTest {

    private lateinit var membershipRepository: MembershipRepository
    private lateinit var clubRepository: ClubRepository
    private lateinit var activator: FreeMembershipActivator

    @BeforeEach
    fun setUp() {
        membershipRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        activator = FreeMembershipActivator(membershipRepository, clubRepository)
    }

    private fun makeMembership(
        id: UUID,
        userId: UUID,
        clubId: UUID,
        status: MembershipStatus
    ): Membership {
        val now = OffsetDateTime.now()
        return Membership(
            id = id,
            userId = userId,
            clubId = clubId,
            status = status,
            role = MembershipRole.member,
            joinedAt = now.minusDays(60),
            subscriptionExpiresAt = null,
            createdAt = now.minusDays(60),
            updatedAt = now.minusDays(60)
        )
    }

    @Test
    fun `activate creates fresh membership and bumps member_count when no row exists`() {
        val userId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val fresh = makeMembership(UUID.randomUUID(), userId, clubId, MembershipStatus.active)

        every { membershipRepository.findByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.create(userId, clubId) } returns fresh

        val result = activator.activate(userId, clubId)

        assertEquals(fresh, result)
        verify(exactly = 1) { membershipRepository.create(userId, clubId) }
        verify(exactly = 1) { clubRepository.incrementMemberCount(clubId) }
        verify(exactly = 0) { membershipRepository.reactivateFree(any()) }
    }

    @Test
    fun `activate reactivates cancelled membership and re-bumps member_count`() {
        // After PR-1 (club-leave) the free-leave flow decrements member_count.
        // Rejoin therefore must increment again to keep member_count in lock-step
        // with the count of active rows.
        val userId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val cancelled = makeMembership(membershipId, userId, clubId, MembershipStatus.cancelled)
        val reactivated = makeMembership(membershipId, userId, clubId, MembershipStatus.active)

        every { membershipRepository.findByUserAndClub(userId, clubId) } returns cancelled
        every { membershipRepository.reactivateFree(membershipId) } returns reactivated

        val result = activator.activate(userId, clubId)

        assertEquals(reactivated, result)
        verify(exactly = 1) { membershipRepository.reactivateFree(membershipId) }
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
        verify(exactly = 1) { clubRepository.incrementMemberCount(clubId) }
    }

    @Test
    fun `activate reactivates expired membership and re-bumps member_count`() {
        val userId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val expired = makeMembership(membershipId, userId, clubId, MembershipStatus.expired)
        val reactivated = makeMembership(membershipId, userId, clubId, MembershipStatus.active)

        every { membershipRepository.findByUserAndClub(userId, clubId) } returns expired
        every { membershipRepository.reactivateFree(membershipId) } returns reactivated

        val result = activator.activate(userId, clubId)

        assertEquals(reactivated, result)
        verify(exactly = 1) { membershipRepository.reactivateFree(membershipId) }
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
        verify(exactly = 1) { clubRepository.incrementMemberCount(clubId) }
    }

    @Test
    fun `activate throws IllegalStateException when active membership exists`() {
        // Defence in depth: callers must check this case first and surface the
        // appropriate context-specific error (ConflictException / ValidationException).
        val userId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val active = makeMembership(UUID.randomUUID(), userId, clubId, MembershipStatus.active)

        every { membershipRepository.findByUserAndClub(userId, clubId) } returns active

        assertThrows<IllegalStateException> { activator.activate(userId, clubId) }

        verify(exactly = 0) { membershipRepository.create(any(), any()) }
        verify(exactly = 0) { membershipRepository.reactivateFree(any()) }
        verify(exactly = 0) { clubRepository.incrementMemberCount(any()) }
    }

    @Test
    fun `activate throws IllegalStateException when grace_period membership exists`() {
        val userId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val grace = makeMembership(UUID.randomUUID(), userId, clubId, MembershipStatus.grace_period)

        every { membershipRepository.findByUserAndClub(userId, clubId) } returns grace

        assertThrows<IllegalStateException> { activator.activate(userId, clubId) }

        verify(exactly = 0) { membershipRepository.create(any(), any()) }
        verify(exactly = 0) { membershipRepository.reactivateFree(any()) }
    }
}
