package com.clubs.award

import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.membership.Membership
import com.clubs.membership.MembershipRepository
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

class AwardServiceTest {

    private lateinit var awardRepository: AwardRepository
    private lateinit var membershipRepository: MembershipRepository
    private lateinit var service: AwardService

    private val clubId = UUID.randomUUID()
    private val targetUserId = UUID.randomUUID()
    private val callerId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        awardRepository = mockk(relaxed = true)
        membershipRepository = mockk(relaxed = true)
        service = AwardService(awardRepository, membershipRepository, AwardMapper())
        // Default: target is a member; the cap/dup checks return "room available" unless overridden.
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns member()
        every { awardRepository.countByMember(clubId, targetUserId) } returns 0
        every { awardRepository.existsByLabel(clubId, targetUserId, any()) } returns false
        every { awardRepository.insert(any()) } answers { firstArg() }
    }

    private fun member(): Membership {
        val now = OffsetDateTime.now()
        return Membership(
            id = UUID.randomUUID(),
            userId = targetUserId,
            clubId = clubId,
            status = MembershipStatus.active,
            role = MembershipRole.member,
            joinedAt = now,
            subscriptionExpiresAt = null,
            createdAt = now,
            updatedAt = now
        )
    }

    // --- grant ---

    @Test
    fun `grant inserts and returns the award`() {
        val result = service.grant(clubId, targetUserId, "🔥", "Активист", callerId)

        assertEquals("🔥", result.emoji)
        assertEquals("Активист", result.label)
        verify(exactly = 1) { awardRepository.insert(any()) }
    }

    @Test
    fun `grant trims emoji and collapses whitespace in the label`() {
        val captured = slot<Award>()
        every { awardRepository.insert(capture(captured)) } answers { firstArg() }

        service.grant(clubId, targetUserId, " 🏆 ", "  Душа   клуба  ", callerId)

        assertEquals("🏆", captured.captured.emoji)
        assertEquals("Душа клуба", captured.captured.label)
        assertEquals(callerId, captured.captured.awardedBy)
    }

    @Test
    fun `grant rejects a non-member target`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns null

        assertThrows<NotFoundException> { service.grant(clubId, targetUserId, "🔥", "Активист", callerId) }
        verify(exactly = 0) { awardRepository.insert(any()) }
    }

    @Test
    fun `grant rejects a blank label after trim`() {
        assertThrows<ValidationException> { service.grant(clubId, targetUserId, "🔥", "   ", callerId) }
        verify(exactly = 0) { awardRepository.insert(any()) }
    }

    @Test
    fun `grant rejects a blank emoji after trim`() {
        assertThrows<ValidationException> { service.grant(clubId, targetUserId, "  ", "Активист", callerId) }
        verify(exactly = 0) { awardRepository.insert(any()) }
    }

    @Test
    fun `grant rejects when the per-member cap is reached`() {
        every { awardRepository.countByMember(clubId, targetUserId) } returns AwardService.MAX_AWARDS_PER_MEMBER

        assertThrows<ValidationException> { service.grant(clubId, targetUserId, "🔥", "Активист", callerId) }
        verify(exactly = 0) { awardRepository.insert(any()) }
    }

    @Test
    fun `grant rejects a duplicate label`() {
        every { awardRepository.existsByLabel(clubId, targetUserId, "Активист") } returns true

        assertThrows<ValidationException> { service.grant(clubId, targetUserId, "🔥", "Активист", callerId) }
        verify(exactly = 0) { awardRepository.insert(any()) }
    }

    @Test
    fun `grant checks the duplicate against the cleaned label`() {
        // The dedup guard must see the normalized label, not the raw input with stray whitespace.
        every { awardRepository.existsByLabel(clubId, targetUserId, "Душа клуба") } returns true

        assertThrows<ValidationException> { service.grant(clubId, targetUserId, "🏆", "  Душа   клуба ", callerId) }
    }

    // --- revoke ---

    @Test
    fun `revoke deletes a matching award`() {
        val awardId = UUID.randomUUID()
        every { awardRepository.delete(awardId, clubId, targetUserId) } returns 1

        service.revoke(clubId, targetUserId, awardId, callerId)

        verify(exactly = 1) { awardRepository.delete(awardId, clubId, targetUserId) }
    }

    @Test
    fun `revoke throws NotFound when nothing was deleted`() {
        val awardId = UUID.randomUUID()
        every { awardRepository.delete(awardId, clubId, targetUserId) } returns 0

        assertThrows<NotFoundException> { service.revoke(clubId, targetUserId, awardId, callerId) }
    }

    // --- reads ---

    @Test
    fun `getMemberAwards maps domain to dto`() {
        every { awardRepository.findByMember(clubId, targetUserId) } returns listOf(
            Award(UUID.randomUUID(), clubId, targetUserId, "🔥", "Активист", callerId, OffsetDateTime.now())
        )

        val result = service.getMemberAwards(clubId, targetUserId)

        assertEquals(1, result.size)
        assertEquals("Активист", result[0].label)
    }

    @Test
    fun `getSuggestions maps domain to dto`() {
        every { awardRepository.findSuggestions(clubId, any()) } returns listOf(
            AwardSuggestion("🏆", "Душа клуба")
        )

        val result = service.getSuggestions(clubId)

        assertEquals(1, result.size)
        assertEquals("🏆", result[0].emoji)
        assertEquals("Душа клуба", result[0].label)
    }

    @Test
    fun `getClubAwardsByMember groups awards per user for the roster`() {
        val otherUserId = UUID.randomUUID()
        every { awardRepository.findByClub(clubId) } returns listOf(
            Award(UUID.randomUUID(), clubId, targetUserId, "🔥", "Активист", callerId, OffsetDateTime.now()),
            Award(UUID.randomUUID(), clubId, targetUserId, "🏆", "Душа клуба", callerId, OffsetDateTime.now()),
            Award(UUID.randomUUID(), clubId, otherUserId, "⭐", "Новичок года", callerId, OffsetDateTime.now())
        )

        val result = service.getClubAwardsByMember(clubId)

        assertEquals(2, result.size)
        assertEquals(listOf("Активист", "Душа клуба"), result[targetUserId]?.map { it.label })
        assertEquals(listOf("Новичок года"), result[otherUserId]?.map { it.label })
    }
}
