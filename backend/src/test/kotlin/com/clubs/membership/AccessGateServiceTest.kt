package com.clubs.membership

import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

class AccessGateServiceTest {

    private lateinit var membershipRepository: MembershipRepository
    private lateinit var service: AccessGateService

    private val clubId = UUID.randomUUID()
    private val targetUserId = UUID.randomUUID()
    private val callerId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        membershipRepository = mockk(relaxed = true)
        // Empty base-url mirrors production (uploader returns root-relative "/uploads/...").
        service = AccessGateService(membershipRepository, MembershipMapper(), accessPeriodDays = 30, storageBaseUrl = "")
    }

    private fun membership(status: MembershipStatus, role: MembershipRole = MembershipRole.member): Membership {
        val now = OffsetDateTime.now()
        return Membership(
            id = UUID.randomUUID(),
            userId = targetUserId,
            clubId = clubId,
            status = status,
            role = role,
            joinedAt = now,
            subscriptionExpiresAt = null,
            createdAt = now,
            updatedAt = now
        )
    }

    // --- freezeAccess ---

    @Test
    fun `freezeAccess freezes an active member`() {
        val m = membership(MembershipStatus.active)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.freezeAccess(m.id) } returns 1

        val result = service.freezeAccess(clubId, targetUserId, callerId)

        assertEquals("frozen", result.status)
        verify(exactly = 1) { membershipRepository.freezeAccess(m.id) }
    }

    @Test
    fun `freezeAccess is idempotent when already frozen`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns membership(MembershipStatus.frozen)

        val result = service.freezeAccess(clubId, targetUserId, callerId)

        assertEquals("frozen", result.status)
        verify(exactly = 0) { membershipRepository.freezeAccess(any()) }
    }

    @Test
    fun `freezeAccess rejects a non-active member`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns membership(MembershipStatus.cancelled)

        assertThrows<ValidationException> { service.freezeAccess(clubId, targetUserId, callerId) }
        verify(exactly = 0) { membershipRepository.freezeAccess(any()) }
    }

    @Test
    fun `freezeAccess rejects managing the organizer`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns
            membership(MembershipStatus.active, role = MembershipRole.organizer)

        assertThrows<ValidationException> { service.freezeAccess(clubId, targetUserId, callerId) }
        verify(exactly = 0) { membershipRepository.freezeAccess(any()) }
    }

    @Test
    fun `freezeAccess throws Conflict when the guarded update affects 0 rows (lost race)`() {
        val m = membership(MembershipStatus.active)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.freezeAccess(m.id) } returns 0

        assertThrows<ConflictException> { service.freezeAccess(clubId, targetUserId, callerId) }
    }

    @Test
    fun `freezeAccess throws NotFound when the member does not exist`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns null

        assertThrows<NotFoundException> { service.freezeAccess(clubId, targetUserId, callerId) }
    }

    // --- unfreezeAccess ---

    @Test
    fun `unfreezeAccess unfreezes a frozen member`() {
        val m = membership(MembershipStatus.frozen)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.unfreezeAccess(m.id) } returns 1

        val result = service.unfreezeAccess(clubId, targetUserId, callerId)

        assertEquals("active", result.status)
        verify(exactly = 1) { membershipRepository.unfreezeAccess(m.id) }
    }

    @Test
    fun `unfreezeAccess is idempotent when already active`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns membership(MembershipStatus.active)

        val result = service.unfreezeAccess(clubId, targetUserId, callerId)

        assertEquals("active", result.status)
        verify(exactly = 0) { membershipRepository.unfreezeAccess(any()) }
    }

    // --- markDuesPaid ---

    @Test
    fun `markDuesPaid grants access to a frozen member`() {
        val m = membership(MembershipStatus.frozen)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.markDuesPaid(m.id, callerId, any()) } returns 1

        val result = service.markDuesPaid(clubId, targetUserId, callerId)

        assertEquals("active", result.status)
        verify(exactly = 1) { membershipRepository.markDuesPaid(m.id, callerId, any()) }
    }

    @Test
    fun `markDuesPaid is accepted for an already-active member`() {
        val m = membership(MembershipStatus.active)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.markDuesPaid(m.id, callerId, any()) } returns 1

        val result = service.markDuesPaid(clubId, targetUserId, callerId)

        assertEquals("active", result.status)
        verify(exactly = 1) { membershipRepository.markDuesPaid(m.id, callerId, any()) }
    }

    @Test
    fun `markDuesPaid rejects a non-member (cancelled)`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns membership(MembershipStatus.cancelled)

        assertThrows<ValidationException> { service.markDuesPaid(clubId, targetUserId, callerId) }
        verify(exactly = 0) { membershipRepository.markDuesPaid(any(), any(), any()) }
    }

    @Test
    fun `markDuesPaid extends access by one period from the current future expiry (no lost days)`() {
        // Option B: a member who pays early keeps their remaining days — extend from the current expiry.
        val currentExpiry = OffsetDateTime.now().plusDays(5)
        val m = membership(MembershipStatus.active).copy(subscriptionExpiresAt = currentExpiry)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        val captured = slot<OffsetDateTime>()
        every { membershipRepository.markDuesPaid(m.id, callerId, capture(captured)) } returns 1

        service.markDuesPaid(clubId, targetUserId, callerId)

        assertEquals(currentExpiry.plusDays(30), captured.captured)
    }

    @Test
    fun `markDuesPaid for a frozen member with no prior expiry extends from now`() {
        val m = membership(MembershipStatus.frozen) // subscriptionExpiresAt = null
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        val captured = slot<OffsetDateTime>()
        every { membershipRepository.markDuesPaid(m.id, callerId, capture(captured)) } returns 1
        val before = OffsetDateTime.now()

        service.markDuesPaid(clubId, targetUserId, callerId)

        // ~30 days from now (no future expiry to extend from).
        assert(captured.captured.isAfter(before.plusDays(29)))
        assert(captured.captured.isBefore(before.plusDays(31)))
    }

    // --- claimDues (member-initiated) ---

    private fun callerMembership(status: MembershipStatus) = membership(status).copy(userId = callerId)

    // Production-shape (root-relative) upload URL — exactly what StorageService returns with an empty base-url.
    private val proofUrl = "/uploads/abc-123.jpg"

    @Test
    fun `claimDues records an sbp claim with a screenshot for a frozen member`() {
        val m = callerMembership(MembershipStatus.frozen)
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns m
        every { membershipRepository.claimDues(m.id, "sbp", proofUrl) } returns 1

        val result = service.claimDues(clubId, callerId, "sbp", proofUrl)

        assertEquals("sbp", result.duesClaimMethod)
        verify(exactly = 1) { membershipRepository.claimDues(m.id, "sbp", proofUrl) }
    }

    @Test
    fun `claimDues rejects an sbp claim without a screenshot`() {
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns callerMembership(MembershipStatus.frozen)

        assertThrows<ValidationException> { service.claimDues(clubId, callerId, "sbp", null) }
        verify(exactly = 0) { membershipRepository.claimDues(any(), any(), any()) }
    }

    @Test
    fun `claimDues rejects an sbp claim whose proof is not an uploaded image URL`() {
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns callerMembership(MembershipStatus.frozen)

        // javascript:, an external host, and even an external host with an /uploads/ path must all be
        // rejected — the proof must be OUR own upload (root-relative in prod).
        assertThrows<ValidationException> { service.claimDues(clubId, callerId, "sbp", "javascript:alert(1)") }
        assertThrows<ValidationException> { service.claimDues(clubId, callerId, "sbp", "https://evil.example.com/x.png") }
        assertThrows<ValidationException> { service.claimDues(clubId, callerId, "sbp", "https://evil.example.com/uploads/x.png") }
        verify(exactly = 0) { membershipRepository.claimDues(any(), any(), any()) }
    }

    @Test
    fun `claimDues records a cash claim without a screenshot (proof ignored)`() {
        val m = callerMembership(MembershipStatus.frozen)
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns m
        every { membershipRepository.claimDues(m.id, "cash", null) } returns 1

        // Even if a client sends a URL for cash, the service stores null.
        val result = service.claimDues(clubId, callerId, "cash", "https://cdn/ignored.jpg")

        assertEquals("cash", result.duesClaimMethod)
        verify(exactly = 1) { membershipRepository.claimDues(m.id, "cash", null) }
    }

    @Test
    fun `claimDues rejects an unknown method`() {
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns callerMembership(MembershipStatus.frozen)

        assertThrows<ValidationException> { service.claimDues(clubId, callerId, "crypto", null) }
        verify(exactly = 0) { membershipRepository.claimDues(any(), any(), any()) }
    }

    @Test
    fun `claimDues rejects a non-frozen member`() {
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns callerMembership(MembershipStatus.active)

        assertThrows<ValidationException> { service.claimDues(clubId, callerId, "cash", null) }
        verify(exactly = 0) { membershipRepository.claimDues(any(), any(), any()) }
    }

    @Test
    fun `claimDues throws NotFound when the caller is not a member`() {
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns null

        assertThrows<NotFoundException> { service.claimDues(clubId, callerId, "cash", null) }
    }

    @Test
    fun `claimDues throws Conflict when the guarded update affects 0 rows`() {
        val m = callerMembership(MembershipStatus.frozen)
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns m
        every { membershipRepository.claimDues(m.id, "cash", null) } returns 0

        assertThrows<ConflictException> { service.claimDues(clubId, callerId, "cash", null) }
    }

    // --- unmarkDues ---

    @Test
    fun `unmarkDues clears the dues record without changing status`() {
        val m = membership(MembershipStatus.active)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.unmarkDues(m.id) } returns 1

        val result = service.unmarkDues(clubId, targetUserId, callerId)

        assertEquals("active", result.status)
        verify(exactly = 1) { membershipRepository.unmarkDues(m.id) }
    }
}
