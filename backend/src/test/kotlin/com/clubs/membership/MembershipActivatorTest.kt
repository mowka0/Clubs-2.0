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

class MembershipActivatorTest {

    private lateinit var membershipRepository: MembershipRepository
    private lateinit var activator: MembershipActivator

    @BeforeEach
    fun setUp() {
        membershipRepository = mockk(relaxed = true)
        activator = MembershipActivator(membershipRepository, eventPublisher = mockk(relaxed = true))
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

    // --- activateFree (free club → active) ---

    @Test
    fun `activateFree creates fresh active membership when no row exists`() {
        val userId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val fresh = makeMembership(UUID.randomUUID(), userId, clubId, MembershipStatus.active)

        every { membershipRepository.findByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.create(userId, clubId) } returns fresh

        val result = activator.activateFree(userId, clubId)

        assertEquals(fresh, result)
        verify(exactly = 1) { membershipRepository.create(userId, clubId) }
        verify(exactly = 0) { membershipRepository.createFrozen(any(), any()) }
        verify(exactly = 0) { membershipRepository.reactivateFree(any()) }
    }

    @Test
    fun `activateFree reactivates cancelled membership via reactivateFree`() {
        val userId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val cancelled = makeMembership(membershipId, userId, clubId, MembershipStatus.cancelled)
        val reactivated = makeMembership(membershipId, userId, clubId, MembershipStatus.active)

        every { membershipRepository.findByUserAndClub(userId, clubId) } returns cancelled
        every { membershipRepository.reactivateFree(membershipId) } returns reactivated

        val result = activator.activateFree(userId, clubId)

        assertEquals(reactivated, result)
        verify(exactly = 1) { membershipRepository.reactivateFree(membershipId) }
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
    }

    // --- activateFrozen (paid club → frozen) ---

    @Test
    fun `activateFrozen creates fresh frozen membership when no row exists`() {
        val userId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val fresh = makeMembership(UUID.randomUUID(), userId, clubId, MembershipStatus.frozen)

        every { membershipRepository.findByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.createFrozen(userId, clubId) } returns fresh

        val result = activator.activateFrozen(userId, clubId)

        assertEquals(fresh, result)
        verify(exactly = 1) { membershipRepository.createFrozen(userId, clubId) }
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
        verify(exactly = 0) { membershipRepository.reactivateFrozen(any()) }
    }

    @Test
    fun `activateFrozen reactivates cancelled membership via reactivateFrozen`() {
        val userId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val cancelled = makeMembership(membershipId, userId, clubId, MembershipStatus.cancelled)
        val reactivated = makeMembership(membershipId, userId, clubId, MembershipStatus.frozen)

        every { membershipRepository.findByUserAndClub(userId, clubId) } returns cancelled
        every { membershipRepository.reactivateFrozen(membershipId) } returns reactivated

        val result = activator.activateFrozen(userId, clubId)

        assertEquals(reactivated, result)
        verify(exactly = 1) { membershipRepository.reactivateFrozen(membershipId) }
        verify(exactly = 0) { membershipRepository.createFrozen(any(), any()) }
    }

    // --- alive guard (now includes frozen) ---

    @Test
    fun `activate throws IllegalStateException when active membership exists`() {
        val userId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns
            makeMembership(UUID.randomUUID(), userId, clubId, MembershipStatus.active)

        assertThrows<IllegalStateException> { activator.activateFree(userId, clubId) }
        assertThrows<IllegalStateException> { activator.activateFrozen(userId, clubId) }
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
        verify(exactly = 0) { membershipRepository.createFrozen(any(), any()) }
    }

    @Test
    fun `activate throws IllegalStateException when frozen membership exists`() {
        // De-Stars: a frozen member already belongs — must not be silently reactivated as a fresh join.
        val userId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns
            makeMembership(UUID.randomUUID(), userId, clubId, MembershipStatus.frozen)

        assertThrows<IllegalStateException> { activator.activateFrozen(userId, clubId) }
        assertThrows<IllegalStateException> { activator.activateFree(userId, clubId) }
        verify(exactly = 0) { membershipRepository.createFrozen(any(), any()) }
        verify(exactly = 0) { membershipRepository.reactivateFrozen(any()) }
    }

    @Test
    fun `activate throws IllegalStateException when expired membership exists`() {
        // Оживление expired (2026-07-06): должник по продлению — всё ещё участник; повторное
        // вступление не должно молча стереть его жизненный цикл (путь назад — оплата взноса).
        val userId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns
            makeMembership(UUID.randomUUID(), userId, clubId, MembershipStatus.expired)

        assertThrows<IllegalStateException> { activator.activateFree(userId, clubId) }
        assertThrows<IllegalStateException> { activator.activateFrozen(userId, clubId) }
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
        verify(exactly = 0) { membershipRepository.reactivateFree(any()) }
        verify(exactly = 0) { membershipRepository.reactivateFrozen(any()) }
    }
}
