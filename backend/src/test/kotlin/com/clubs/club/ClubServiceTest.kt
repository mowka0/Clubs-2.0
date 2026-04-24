package com.clubs.club

import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.records.ClubsRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jooq.DSLContext
import org.jooq.InsertSetMoreStep
import org.jooq.InsertSetStep
import org.jooq.InsertResultStep
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ClubServiceTest {

    private lateinit var clubRepository: ClubRepository
    private lateinit var dsl: DSLContext
    private lateinit var clubService: ClubService

    @BeforeEach
    fun setUp() {
        clubRepository = mockk(relaxed = true)
        dsl = mockk(relaxed = true)
        clubService = ClubService(clubRepository, dsl)
    }

    @Test
    fun `createClub should return club detail when request is valid`() {
        val ownerId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val request = CreateClubRequest(
            name = "Test Club",
            description = "A test club description",
            category = "sport",
            accessType = "open",
            city = "Moscow",
            memberLimit = 50,
            subscriptionPrice = 100
        )

        val record = ClubsRecord(
            id = clubId,
            ownerId = ownerId,
            name = request.name,
            description = request.description,
            category = ClubCategory.sport,
            accessType = AccessType.`open`,
            city = request.city,
            memberLimit = request.memberLimit,
            subscriptionPrice = request.subscriptionPrice,
            memberCount = 0,
            activityRating = 0,
            isActive = true
        )

        every { clubRepository.countByOwnerId(ownerId) } returns 0
        every { clubRepository.create(request, ownerId, any()) } returns record

        val result = clubService.createClub(request, ownerId)

        assertEquals(clubId, result.id)
        assertEquals("Test Club", result.name)
        assertEquals("sport", result.category)
        assertEquals("open", result.accessType)
        assertEquals("Moscow", result.city)
        assertEquals(50, result.memberLimit)
        assertEquals(100, result.subscriptionPrice)
        verify(exactly = 1) { clubRepository.create(request, ownerId, any()) }
    }

    @Test
    fun `createClub should throw ValidationException when category is invalid`() {
        val ownerId = UUID.randomUUID()
        val request = CreateClubRequest(
            name = "Test Club",
            description = "Description",
            category = "invalid_category",
            accessType = "open",
            city = "Moscow",
            memberLimit = 50,
            subscriptionPrice = 100
        )

        val exception = assertThrows<ValidationException> {
            clubService.createClub(request, ownerId)
        }

        assertEquals("Invalid category: invalid_category", exception.message)
        verify(exactly = 0) { clubRepository.create(any(), any(), any()) }
    }

    @Test
    fun `createClub should throw ValidationException when access type is invalid`() {
        val ownerId = UUID.randomUUID()
        val request = CreateClubRequest(
            name = "Test Club",
            description = "Description",
            category = "sport",
            accessType = "nonexistent",
            city = "Moscow",
            memberLimit = 50,
            subscriptionPrice = 100
        )

        val exception = assertThrows<ValidationException> {
            clubService.createClub(request, ownerId)
        }

        assertEquals("Invalid access type: nonexistent", exception.message)
    }

    @Test
    fun `createClub should throw ConflictException when owner has 10 clubs`() {
        val ownerId = UUID.randomUUID()
        val request = CreateClubRequest(
            name = "Test Club",
            description = "Description",
            category = "sport",
            accessType = "open",
            city = "Moscow",
            memberLimit = 50,
            subscriptionPrice = 100
        )

        every { clubRepository.countByOwnerId(ownerId) } returns 10

        val exception = assertThrows<ConflictException> {
            clubService.createClub(request, ownerId)
        }

        assertEquals("Maximum 10 clubs per organizer", exception.message)
        verify(exactly = 0) { clubRepository.create(any(), any(), any()) }
    }

    @Test
    fun `getClub should throw NotFoundException when club does not exist`() {
        val clubId = UUID.randomUUID()

        every { clubRepository.findById(clubId) } returns null

        val exception = assertThrows<NotFoundException> {
            clubService.getClub(clubId)
        }

        assertEquals("Club not found", exception.message)
    }

    @Test
    fun `getClub should return club detail when club exists`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()

        val record = ClubsRecord(
            id = clubId,
            ownerId = ownerId,
            name = "Existing Club",
            description = "Exists",
            category = ClubCategory.creative,
            accessType = AccessType.closed,
            city = "SPb",
            memberLimit = 20,
            subscriptionPrice = 200,
            memberCount = 5,
            activityRating = 10,
            isActive = true
        )

        every { clubRepository.findById(clubId) } returns record

        val result = clubService.getClub(clubId)

        assertEquals(clubId, result.id)
        assertEquals("Existing Club", result.name)
        assertEquals("creative", result.category)
        assertEquals(5, result.memberCount)
    }

    @Test
    fun `updateClub should throw ForbiddenException when user is not owner`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val differentUserId = UUID.randomUUID()

        val record = ClubsRecord(
            id = clubId,
            ownerId = ownerId,
            name = "My Club",
            description = "Desc",
            category = ClubCategory.sport,
            accessType = AccessType.`open`,
            city = "Moscow",
            memberLimit = 30,
            subscriptionPrice = 0,
            memberCount = 3,
            activityRating = 0,
            isActive = true
        )

        every { clubRepository.findById(clubId) } returns record

        val updateRequest = UpdateClubRequest(name = "New Name")

        val exception = assertThrows<ForbiddenException> {
            clubService.updateClub(clubId, updateRequest, differentUserId)
        }

        assertEquals("Only the club owner can update it", exception.message)
        verify(exactly = 0) { clubRepository.update(any(), any()) }
    }

    @Test
    fun `updateClub should succeed when user is the owner`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()

        val record = ClubsRecord(
            id = clubId,
            ownerId = ownerId,
            name = "My Club",
            description = "Desc",
            category = ClubCategory.sport,
            accessType = AccessType.`open`,
            city = "Moscow",
            memberLimit = 30,
            subscriptionPrice = 0,
            memberCount = 3,
            activityRating = 0,
            isActive = true
        )

        val updatedRecord = ClubsRecord(
            id = clubId,
            ownerId = ownerId,
            name = "Updated Club",
            description = "Desc",
            category = ClubCategory.sport,
            accessType = AccessType.`open`,
            city = "Moscow",
            memberLimit = 30,
            subscriptionPrice = 0,
            memberCount = 3,
            activityRating = 0,
            isActive = true
        )

        every { clubRepository.findById(clubId) } returns record
        every { clubRepository.update(clubId, any()) } returns updatedRecord

        val updateRequest = UpdateClubRequest(name = "Updated Club")
        val result = clubService.updateClub(clubId, updateRequest, ownerId)

        assertEquals("Updated Club", result.name)
        verify(exactly = 1) { clubRepository.update(clubId, updateRequest) }
    }

    private fun makeClubRecord(clubId: UUID, ownerId: UUID): ClubsRecord = ClubsRecord(
        id = clubId,
        ownerId = ownerId,
        name = "Club",
        description = "Desc",
        category = ClubCategory.sport,
        accessType = AccessType.`open`,
        city = "Moscow",
        memberLimit = 30,
        subscriptionPrice = 0,
        memberCount = 3,
        activityRating = 0,
        isActive = true
    )

    @Test
    fun `deleteClub soft-deletes when user is the owner`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        every { clubRepository.findById(clubId) } returns makeClubRecord(clubId, ownerId)

        clubService.deleteClub(clubId, ownerId)

        verify(exactly = 1) { clubRepository.softDelete(clubId) }
    }

    @Test
    fun `deleteClub throws ForbiddenException when user is not owner`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        every { clubRepository.findById(clubId) } returns makeClubRecord(clubId, ownerId)

        val exception = assertThrows<ForbiddenException> {
            clubService.deleteClub(clubId, otherUserId)
        }

        assertEquals("Only the club owner can delete it", exception.message)
        verify(exactly = 0) { clubRepository.softDelete(any()) }
    }

    @Test
    fun `deleteClub throws NotFoundException when club does not exist`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        every { clubRepository.findById(clubId) } returns null

        val exception = assertThrows<NotFoundException> {
            clubService.deleteClub(clubId, userId)
        }

        assertEquals("Club not found", exception.message)
        verify(exactly = 0) { clubRepository.softDelete(any()) }
    }
}
