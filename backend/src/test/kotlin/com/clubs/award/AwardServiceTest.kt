package com.clubs.award

import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.membership.Membership
import com.clubs.common.auth.ClubManagerGuard
import com.clubs.membership.MembershipRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

class AwardServiceTest {

    private lateinit var awardRepository: AwardRepository
    private lateinit var membershipRepository: MembershipRepository
    private lateinit var clubRepository: com.clubs.club.ClubRepository
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var service: AwardService

    private val clubId = UUID.randomUUID()
    private val targetUserId = UUID.randomUUID()
    private val callerId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        awardRepository = mockk(relaxed = true)
        membershipRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        service = AwardService(
            awardRepository, membershipRepository, clubRepository,
            ClubManagerGuard(clubRepository, membershipRepository), AwardMapper(), eventPublisher
        )
        // Default: target is a member; caller is the club owner; the cap/dup checks return
        // "room available" unless overridden.
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns member()
        every { clubRepository.findById(clubId) } returns ownedClub()
        every { awardRepository.countByMember(clubId, targetUserId) } returns 0
        every { awardRepository.existsByLabel(clubId, targetUserId, any()) } returns false
        every { awardRepository.insert(any()) } answers { firstArg() }
    }

    private fun ownedClub(): com.clubs.club.Club {
        val now = OffsetDateTime.now()
        return com.clubs.club.Club(
            id = clubId,
            ownerId = callerId,
            name = "Club",
            description = "d",
            category = com.clubs.generated.jooq.enums.ClubCategory.sport,
            accessType = com.clubs.generated.jooq.enums.AccessType.`open`,
            city = "Moscow",
            district = null,
            memberLimit = 30,
            subscriptionPrice = 0,
            avatarUrl = null,
            rules = null,
            applicationQuestion = null,
            inviteLink = null,
            memberCount = 3,
            isActive = true,
            createdAt = now,
            updatedAt = now
        )
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

    // --- target-матрица (co-organizers, точка 39) ---

    private fun coOrgTarget() = member().copy(role = MembershipRole.co_organizer)

    @Test
    fun `owner can grant an award to a co-organizer`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns coOrgTarget()

        val dto = service.grant(clubId, targetUserId, "⭐", "Опора клуба", callerId)

        assertEquals("Опора клуба", dto.label)
    }

    @Test
    fun `owner cannot grant an award to the organizer row`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns
            member().copy(role = MembershipRole.organizer, userId = callerId)

        assertThrows<ValidationException> { service.grant(clubId, targetUserId, "⭐", "Сам себе", callerId) }
    }

    @Test
    fun `co-org caller cannot grant an award to another co-organizer (403)`() {
        // Клуб принадлежит другому: вызывающий — со-орг, прошедший гейт контроллера.
        every { clubRepository.findById(clubId) } returns ownedClub().copy(ownerId = UUID.randomUUID())
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns coOrgTarget()

        assertThrows<com.clubs.common.exception.ForbiddenException> {
            service.grant(clubId, targetUserId, "⭐", "Не положено", callerId)
        }
    }

    @Test
    fun `co-org caller cannot revoke an award of another co-organizer (403)`() {
        every { clubRepository.findById(clubId) } returns ownedClub().copy(ownerId = UUID.randomUUID())
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns coOrgTarget()

        assertThrows<com.clubs.common.exception.ForbiddenException> {
            service.revoke(clubId, targetUserId, UUID.randomUUID(), callerId)
        }
    }

    @Test
    fun `co-org caller can grant an award to a plain member`() {
        every { clubRepository.findById(clubId) } returns ownedClub().copy(ownerId = UUID.randomUUID())
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns member()

        val dto = service.grant(clubId, targetUserId, "🔥", "Живчик", callerId)

        assertEquals("Живчик", dto.label)
    }
}
