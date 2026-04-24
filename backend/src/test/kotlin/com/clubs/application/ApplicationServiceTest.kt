package com.clubs.application

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ApplicationStatus
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.tables.records.ApplicationsRecord
import com.clubs.generated.jooq.tables.records.ClubsRecord
import com.clubs.membership.MembershipRepository
import com.clubs.payment.PaymentService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ApplicationServiceTest {

    private lateinit var applicationRepository: ApplicationRepository
    private lateinit var clubRepository: ClubRepository
    private lateinit var membershipRepository: MembershipRepository
    private lateinit var paymentService: PaymentService
    private lateinit var applicationService: ApplicationService

    @BeforeEach
    fun setUp() {
        applicationRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        membershipRepository = mockk(relaxed = true)
        paymentService = mockk(relaxed = true)
        applicationService = ApplicationService(
            applicationRepository, clubRepository, membershipRepository, paymentService
        )
    }

    private fun createClosedClub(
        clubId: UUID,
        ownerId: UUID,
        applicationQuestion: String? = null,
        memberLimit: Int = 50,
        memberCount: Int = 5,
        subscriptionPrice: Int = 100
    ): ClubsRecord =
        ClubsRecord(
            id = clubId,
            ownerId = ownerId,
            name = "Closed Club",
            description = "A closed club",
            category = ClubCategory.sport,
            accessType = AccessType.closed,
            city = "Moscow",
            memberLimit = memberLimit,
            subscriptionPrice = subscriptionPrice,
            applicationQuestion = applicationQuestion,
            memberCount = memberCount,
            activityRating = 0,
            isActive = true
        )

    private fun createPendingApplication(userId: UUID, clubId: UUID, answerText: String? = null): ApplicationsRecord =
        ApplicationsRecord(
            id = UUID.randomUUID(),
            userId = userId,
            clubId = clubId,
            answerText = answerText,
            status = ApplicationStatus.pending,
            createdAt = OffsetDateTime.now()
        )

    @Test
    fun `submitApplication should create application for closed club`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val club = createClosedClub(clubId, ownerId)
        val request = SubmitApplicationRequest(answerText = "I want to join")
        val application = createPendingApplication(userId, clubId, "I want to join")

        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns null
        every { applicationRepository.findPendingByUserAndClub(userId, clubId) } returns null
        every { applicationRepository.countTodayByUser(userId) } returns 0
        every { applicationRepository.create(userId, clubId, "I want to join") } returns application

        val result = applicationService.submitApplication(clubId, userId, request)

        assertEquals(userId, result.userId)
        assertEquals(clubId, result.clubId)
        assertEquals("pending", result.status)
        verify(exactly = 1) { applicationRepository.create(userId, clubId, "I want to join") }
    }

    @Test
    fun `submitApplication should throw ValidationException when club is not closed`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()

        val openClub = ClubsRecord(
            id = clubId,
            ownerId = ownerId,
            name = "Open Club",
            description = "An open club",
            category = ClubCategory.sport,
            accessType = AccessType.`open`,
            city = "Moscow",
            memberLimit = 50,
            subscriptionPrice = 0,
            memberCount = 0,
            activityRating = 0,
            isActive = true
        )

        every { clubRepository.findById(clubId) } returns openClub

        val exception = assertThrows<ValidationException> {
            applicationService.submitApplication(clubId, userId, SubmitApplicationRequest())
        }

        assertEquals("Club does not accept applications", exception.message)
    }

    @Test
    fun `submitApplication should throw ValidationException when answer is required but empty`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val club = createClosedClub(clubId, ownerId, applicationQuestion = "Why do you want to join?")

        every { clubRepository.findById(clubId) } returns club

        val exception = assertThrows<ValidationException> {
            applicationService.submitApplication(clubId, userId, SubmitApplicationRequest(answerText = ""))
        }

        assertEquals("Answer is required for this club", exception.message)
    }

    @Test
    fun `submitApplication should throw ValidationException when answer is required but null`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val club = createClosedClub(clubId, ownerId, applicationQuestion = "Why do you want to join?")

        every { clubRepository.findById(clubId) } returns club

        val exception = assertThrows<ValidationException> {
            applicationService.submitApplication(clubId, userId, SubmitApplicationRequest(answerText = null))
        }

        assertEquals("Answer is required for this club", exception.message)
    }

    @Test
    fun `approveApplication for paid club sends invoice and does NOT create membership`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val organizerId = UUID.randomUUID()

        val application = ApplicationsRecord(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            status = ApplicationStatus.pending,
            createdAt = OffsetDateTime.now()
        )

        val club = createClosedClub(clubId, organizerId, subscriptionPrice = 500)

        val approvedApplication = ApplicationsRecord(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            status = ApplicationStatus.approved,
            createdAt = OffsetDateTime.now(),
            resolvedAt = OffsetDateTime.now()
        )

        every { applicationRepository.findById(applicationId) } returns application
        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.countActiveByClubId(clubId) } returns 5
        every { applicationRepository.updateStatus(applicationId, ApplicationStatus.approved) } returns approvedApplication

        val result = applicationService.approveApplication(applicationId, organizerId)

        assertEquals("approved", result.status)
        assertEquals(userId, result.userId)
        assertEquals(clubId, result.clubId)
        assertNotNull(result.resolvedAt)
        verify(exactly = 1) { paymentService.createInvoice(userId, clubId) }
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
        verify(exactly = 0) { clubRepository.incrementMemberCount(any()) }
    }

    @Test
    fun `approveApplication for free club creates membership and increments member_count`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val organizerId = UUID.randomUUID()

        val application = ApplicationsRecord(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            status = ApplicationStatus.pending,
            createdAt = OffsetDateTime.now()
        )

        val club = createClosedClub(clubId, organizerId, subscriptionPrice = 0)

        val approvedApplication = ApplicationsRecord(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            status = ApplicationStatus.approved,
            createdAt = OffsetDateTime.now(),
            resolvedAt = OffsetDateTime.now()
        )

        every { applicationRepository.findById(applicationId) } returns application
        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.countActiveByClubId(clubId) } returns 5
        every { applicationRepository.updateStatus(applicationId, ApplicationStatus.approved) } returns approvedApplication

        val result = applicationService.approveApplication(applicationId, organizerId)

        assertEquals("approved", result.status)
        verify(exactly = 1) { membershipRepository.create(userId, clubId) }
        verify(exactly = 1) { clubRepository.incrementMemberCount(clubId) }
        verify(exactly = 0) { paymentService.createInvoice(any(), any()) }
    }

    @Test
    fun `approveApplication should throw ForbiddenException when user is not organizer`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val organizerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()

        val application = ApplicationsRecord(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            status = ApplicationStatus.pending,
            createdAt = OffsetDateTime.now()
        )

        val club = createClosedClub(clubId, organizerId)

        every { applicationRepository.findById(applicationId) } returns application
        every { clubRepository.findById(clubId) } returns club

        val exception = assertThrows<ForbiddenException> {
            applicationService.approveApplication(applicationId, otherUserId)
        }

        assertEquals("Forbidden", exception.message)
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
    }

    @Test
    fun `rejectApplication should update status to rejected`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val organizerId = UUID.randomUUID()

        val application = ApplicationsRecord(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            status = ApplicationStatus.pending,
            createdAt = OffsetDateTime.now()
        )

        val club = createClosedClub(clubId, organizerId)

        val rejectedApplication = ApplicationsRecord(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            status = ApplicationStatus.rejected,
            rejectedReason = "Not a good fit",
            createdAt = OffsetDateTime.now(),
            resolvedAt = OffsetDateTime.now()
        )

        every { applicationRepository.findById(applicationId) } returns application
        every { clubRepository.findById(clubId) } returns club
        every { applicationRepository.updateStatus(applicationId, ApplicationStatus.rejected, "Not a good fit") } returns rejectedApplication

        val result = applicationService.rejectApplication(applicationId, organizerId, "Not a good fit")

        assertEquals("rejected", result.status)
        assertEquals("Not a good fit", result.rejectedReason)
        assertEquals(userId, result.userId)
    }

    @Test
    fun `rejectApplication should throw ForbiddenException when user is not organizer`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val organizerId = UUID.randomUUID()
        val randomUserId = UUID.randomUUID()

        val application = ApplicationsRecord(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            status = ApplicationStatus.pending,
            createdAt = OffsetDateTime.now()
        )

        val club = createClosedClub(clubId, organizerId)

        every { applicationRepository.findById(applicationId) } returns application
        every { clubRepository.findById(clubId) } returns club

        val exception = assertThrows<ForbiddenException> {
            applicationService.rejectApplication(applicationId, randomUserId, null)
        }

        assertEquals("Forbidden", exception.message)
    }

    @Test
    fun `approveApplication should throw ValidationException when application is not pending`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val organizerId = UUID.randomUUID()

        val application = ApplicationsRecord(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            status = ApplicationStatus.approved,
            createdAt = OffsetDateTime.now()
        )

        val club = createClosedClub(clubId, organizerId)

        every { applicationRepository.findById(applicationId) } returns application
        every { clubRepository.findById(clubId) } returns club

        val exception = assertThrows<ValidationException> {
            applicationService.approveApplication(applicationId, organizerId)
        }

        assertEquals("Application is not pending", exception.message)
    }
}
