package com.clubs.membership

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.records.ClubsRecord
import com.clubs.generated.jooq.tables.records.MembershipsRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MembershipServiceTest {

    private lateinit var membershipRepository: MembershipRepository
    private lateinit var clubRepository: ClubRepository
    private lateinit var dsl: DSLContext
    private lateinit var membershipService: MembershipService

    @BeforeEach
    fun setUp() {
        membershipRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        dsl = mockk(relaxed = true)
        membershipService = MembershipService(membershipRepository, clubRepository, dsl)
    }

    private fun createOpenClubRecord(clubId: UUID, ownerId: UUID, memberLimit: Int = 50, memberCount: Int = 5): ClubsRecord =
        ClubsRecord(
            id = clubId,
            ownerId = ownerId,
            name = "Open Club",
            description = "An open club",
            category = ClubCategory.sport,
            accessType = AccessType.`open`,
            city = "Moscow",
            memberLimit = memberLimit,
            subscriptionPrice = 0,
            memberCount = memberCount,
            activityRating = 0,
            isActive = true
        )

    private fun createMembershipRecord(userId: UUID, clubId: UUID): MembershipsRecord {
        val now = OffsetDateTime.now()
        return MembershipsRecord(
            id = UUID.randomUUID(),
            userId = userId,
            clubId = clubId,
            status = MembershipStatus.active,
            role = MembershipRole.member,
            joinedAt = now,
            subscriptionExpiresAt = now.plusDays(30)
        )
    }

    @Test
    fun `joinOpenClub should create membership and return dto for open club`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val club = createOpenClubRecord(clubId, ownerId)
        val membership = createMembershipRecord(userId, clubId)

        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.countActiveByClubId(clubId) } returns 5
        every { membershipRepository.create(userId, clubId) } returns membership

        val result = membershipService.joinOpenClub(clubId, userId)

        assertEquals(userId, result.userId)
        assertEquals(clubId, result.clubId)
        assertEquals("active", result.status)
        assertEquals("member", result.role)
        assertNotNull(result.joinedAt)
        verify(exactly = 1) { membershipRepository.create(userId, clubId) }
    }

    @Test
    fun `joinOpenClub should throw NotFoundException when club does not exist`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()

        every { clubRepository.findById(clubId) } returns null

        val exception = assertThrows<NotFoundException> {
            membershipService.joinOpenClub(clubId, userId)
        }

        assertEquals("Club not found", exception.message)
    }

    @Test
    fun `joinOpenClub should throw ValidationException when club is not open`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()

        val closedClub = ClubsRecord(
            id = clubId,
            ownerId = ownerId,
            name = "Closed Club",
            description = "Closed",
            category = ClubCategory.sport,
            accessType = AccessType.closed,
            city = "Moscow",
            memberLimit = 50,
            subscriptionPrice = 0,
            memberCount = 0,
            activityRating = 0,
            isActive = true
        )

        every { clubRepository.findById(clubId) } returns closedClub

        val exception = assertThrows<ValidationException> {
            membershipService.joinOpenClub(clubId, userId)
        }

        assertEquals("Club is not open for joining", exception.message)
    }

    @Test
    fun `joinOpenClub should throw ConflictException when user is already a member`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val club = createOpenClubRecord(clubId, ownerId)
        val existingMembership = createMembershipRecord(userId, clubId)

        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns existingMembership

        val exception = assertThrows<ConflictException> {
            membershipService.joinOpenClub(clubId, userId)
        }

        assertEquals("Already a member", exception.message)
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
    }

    @Test
    fun `joinOpenClub should throw ValidationException when club is full`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val club = createOpenClubRecord(clubId, ownerId, memberLimit = 20, memberCount = 20)

        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.countActiveByClubId(clubId) } returns 20

        val exception = assertThrows<ValidationException> {
            membershipService.joinOpenClub(clubId, userId)
        }

        assertEquals("Club is full", exception.message)
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
    }

    @Test
    fun `cancelMembership should mark membership as cancelled`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val membership = createMembershipRecord(userId, clubId)

        every { membershipRepository.findByUserAndClub(userId, clubId) } returns membership

        val result = membershipService.cancelMembership(clubId, userId)

        assertEquals("cancelled", result.status)
        assertEquals(userId, result.userId)
        assertEquals(clubId, result.clubId)
    }

    @Test
    fun `cancelMembership should throw NotFoundException when membership does not exist`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()

        every { membershipRepository.findByUserAndClub(userId, clubId) } returns null

        val exception = assertThrows<NotFoundException> {
            membershipService.cancelMembership(clubId, userId)
        }

        assertEquals("Membership not found", exception.message)
    }

    @Test
    fun `cancelMembership should throw ValidationException when already cancelled`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val now = OffsetDateTime.now()
        val cancelledMembership = MembershipsRecord(
            id = UUID.randomUUID(),
            userId = userId,
            clubId = clubId,
            status = MembershipStatus.cancelled,
            role = MembershipRole.member,
            joinedAt = now,
            subscriptionExpiresAt = now.plusDays(30)
        )

        every { membershipRepository.findByUserAndClub(userId, clubId) } returns cancelledMembership

        val exception = assertThrows<ValidationException> {
            membershipService.cancelMembership(clubId, userId)
        }

        assertEquals("Membership already cancelled", exception.message)
    }
}
