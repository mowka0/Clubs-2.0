package com.clubs.membership

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
    private lateinit var activator: FreeMembershipActivator

    @BeforeEach
    fun setUp() {
        membershipRepository = mockk(relaxed = true)
        activator = FreeMembershipActivator(membershipRepository)
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
    fun `activate creates fresh membership when no row exists`() {
        val userId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val fresh = makeMembership(UUID.randomUUID(), userId, clubId, MembershipStatus.active)

        every { membershipRepository.findByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.create(userId, clubId) } returns fresh

        val result = activator.activate(userId, clubId)

        assertEquals(fresh, result)
        verify(exactly = 1) { membershipRepository.create(userId, clubId) }
        verify(exactly = 0) { membershipRepository.reactivateFree(any()) }
    }

    @Test
    fun `activate reactivates cancelled membership`() {
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
    }

    @Test
    fun `activate reactivates expired membership`() {
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
