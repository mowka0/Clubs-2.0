package com.clubs.application

import com.clubs.bot.NotificationService
import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ApplicationStatus
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.interest.InterestRepository
import com.clubs.membership.Membership
import com.clubs.membership.MembershipMapper
import com.clubs.membership.MembershipRepository
import com.clubs.payment.PaymentService
import com.clubs.reputation.ReputationRepository
import com.clubs.user.UserRepository
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
    private lateinit var mapper: ApplicationMapper
    private lateinit var notificationService: NotificationService
    private lateinit var userRepository: UserRepository
    private lateinit var reputationRepository: ReputationRepository
    private lateinit var interestRepository: InterestRepository
    private lateinit var membershipMapper: MembershipMapper
    private lateinit var applicationService: ApplicationService

    @BeforeEach
    fun setUp() {
        applicationRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        membershipRepository = mockk(relaxed = true)
        paymentService = mockk(relaxed = true)
        mapper = ApplicationMapper()
        notificationService = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        reputationRepository = mockk(relaxed = true)
        interestRepository = mockk(relaxed = true)
        membershipMapper = MembershipMapper()
        applicationService = ApplicationService(
            applicationRepository,
            clubRepository,
            membershipRepository,
            paymentService,
            mapper,
            notificationService,
            userRepository,
            reputationRepository,
            interestRepository,
            membershipMapper
        )
    }

    private fun createClosedClub(
        clubId: UUID,
        ownerId: UUID,
        applicationQuestion: String? = null,
        memberLimit: Int = 50,
        memberCount: Int = 5,
        subscriptionPrice: Int = 100
    ): Club {
        val now = OffsetDateTime.now()
        return Club(
            id = clubId,
            ownerId = ownerId,
            name = "Closed Club",
            description = "A closed club",
            category = ClubCategory.sport,
            accessType = AccessType.closed,
            city = "Moscow",
            district = null,
            memberLimit = memberLimit,
            subscriptionPrice = subscriptionPrice,
            avatarUrl = null,
            rules = null,
            applicationQuestion = applicationQuestion,
            inviteLink = null,
            memberCount = memberCount,
            activityRating = 0,
            isActive = true,
            telegramGroupId = null,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun createPendingApplication(userId: UUID, clubId: UUID, answerText: String? = null): Application =
        Application(
            id = UUID.randomUUID(),
            userId = userId,
            clubId = clubId,
            answerText = answerText,
            status = ApplicationStatus.pending,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = null
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
        every { membershipRepository.findActiveByUserAndClub(userId, clubId) } returns null
        every { applicationRepository.findActiveByUserAndClub(userId, clubId) } returns null
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

        val now = OffsetDateTime.now()
        val openClub = Club(
            id = clubId,
            ownerId = ownerId,
            name = "Open Club",
            description = "An open club",
            category = ClubCategory.sport,
            accessType = AccessType.`open`,
            city = "Moscow",
            district = null,
            memberLimit = 50,
            subscriptionPrice = 0,
            avatarUrl = null,
            rules = null,
            applicationQuestion = null,
            inviteLink = null,
            memberCount = 0,
            activityRating = 0,
            isActive = true,
            telegramGroupId = null,
            createdAt = now,
            updatedAt = now
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
    fun `submitApplication rejects when user already has approved application awaiting payment`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val organizerId = UUID.randomUUID()
        val club = createClosedClub(clubId, organizerId)
        val approvedApp = Application(
            id = UUID.randomUUID(),
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.approved,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = OffsetDateTime.now()
        )

        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findActiveByUserAndClub(userId, clubId) } returns null
        every { applicationRepository.findActiveByUserAndClub(userId, clubId) } returns approvedApp

        val exception = assertThrows<ConflictException> {
            applicationService.submitApplication(clubId, userId, SubmitApplicationRequest(answerText = "test"))
        }

        assertEquals("Application already approved — waiting for payment", exception.message)
        verify(exactly = 0) { applicationRepository.create(any(), any(), any()) }
    }

    @Test
    fun `approveApplication for paid club sends invoice and does NOT create membership`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val organizerId = UUID.randomUUID()

        val application = Application(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.pending,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = null
        )

        val club = createClosedClub(clubId, organizerId, subscriptionPrice = 500)

        val approvedApplication = Application(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.approved,
            rejectedReason = null,
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

        val application = Application(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.pending,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = null
        )

        val club = createClosedClub(clubId, organizerId, subscriptionPrice = 0)

        val approvedApplication = Application(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.approved,
            rejectedReason = null,
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

        val application = Application(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.pending,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = null
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

        val application = Application(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.pending,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = null
        )

        val club = createClosedClub(clubId, organizerId)

        val rejectedApplication = Application(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            answerText = null,
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

        val application = Application(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.pending,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = null
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
    fun `resendInvoice succeeds when application is approved and no active membership exists`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val approved = Application(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.approved,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = OffsetDateTime.now()
        )

        every { applicationRepository.findById(applicationId) } returns approved
        every { membershipRepository.findActiveByUserAndClub(userId, clubId) } returns null
        every { clubRepository.findById(clubId) } returns createClosedClub(clubId, UUID.randomUUID())

        applicationService.resendInvoice(applicationId, userId)

        verify(exactly = 1) { paymentService.createInvoice(userId, clubId) }
    }

    @Test
    fun `resendInvoice throws ForbiddenException when caller is not the applicant`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val approved = Application(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.approved,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = OffsetDateTime.now()
        )

        every { applicationRepository.findById(applicationId) } returns approved

        assertThrows<ForbiddenException> {
            applicationService.resendInvoice(applicationId, otherUserId)
        }
        verify(exactly = 0) { paymentService.createInvoice(any(), any()) }
    }

    @Test
    fun `resendInvoice throws ValidationException when application is not approved`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val pending = createPendingApplication(userId, clubId).copy(id = applicationId)

        every { applicationRepository.findById(applicationId) } returns pending

        val ex = assertThrows<ValidationException> {
            applicationService.resendInvoice(applicationId, userId)
        }
        assertEquals("No payment pending", ex.message)
        verify(exactly = 0) { paymentService.createInvoice(any(), any()) }
    }

    @Test
    fun `resendInvoice throws ValidationException when active membership already exists`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val approved = Application(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.approved,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = OffsetDateTime.now()
        )
        val now = OffsetDateTime.now()
        val membership = com.clubs.membership.Membership(
            id = UUID.randomUUID(),
            userId = userId,
            clubId = clubId,
            status = com.clubs.generated.jooq.enums.MembershipStatus.active,
            role = com.clubs.generated.jooq.enums.MembershipRole.member,
            joinedAt = now,
            subscriptionExpiresAt = now.plusDays(30),
            createdAt = now,
            updatedAt = now
        )

        every { applicationRepository.findById(applicationId) } returns approved
        every { membershipRepository.findActiveByUserAndClub(userId, clubId) } returns membership

        val ex = assertThrows<ValidationException> {
            applicationService.resendInvoice(applicationId, userId)
        }
        assertEquals("No payment pending", ex.message)
        verify(exactly = 0) { paymentService.createInvoice(any(), any()) }
    }

    @Test
    fun `getMyClubsActionCounts returns combined inbox awaiting payment and organizer awaiting payment counts`() {
        val userId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val ownedClubIds = listOf(UUID.randomUUID(), UUID.randomUUID())
        val approved = Application(
            id = UUID.randomUUID(),
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.approved,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = OffsetDateTime.now()
        )
        val organizerAwaitingApp1 = Application(
            id = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            clubId = ownedClubIds[0],
            answerText = null,
            status = ApplicationStatus.approved,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = OffsetDateTime.now()
        )
        val organizerAwaitingApp2 = organizerAwaitingApp1.copy(
            id = UUID.randomUUID(),
            userId = UUID.randomUUID()
        )

        every { clubRepository.findIdsByOwnerId(userId) } returns ownedClubIds
        every { applicationRepository.countPendingByClubIds(ownedClubIds) } returns 3
        every { applicationRepository.findApprovedWithoutMembershipByUserId(userId) } returns listOf(approved)
        every { applicationRepository.findApprovedWithoutMembershipByClubIds(ownedClubIds) } returns
            listOf(organizerAwaitingApp1, organizerAwaitingApp2)

        val result = applicationService.getMyClubsActionCounts(userId)

        assertEquals(3, result.inboxCount)
        assertEquals(1, result.awaitingPaymentCount)
        assertEquals(2, result.organizerAwaitingPaymentCount)
    }

    @Test
    fun `getOrganizerAwaitingPaymentApplicants returns applicants from all owned clubs`() {
        val organizerId = UUID.randomUUID()
        val clubAId = UUID.randomUUID()
        val clubBId = UUID.randomUUID()
        val ownedClubIds = listOf(clubAId, clubBId)
        val applicantA = UUID.randomUUID()
        val applicantB = UUID.randomUUID()
        val appA = Application(
            id = UUID.randomUUID(),
            userId = applicantA,
            clubId = clubAId,
            answerText = null,
            status = ApplicationStatus.approved,
            rejectedReason = null,
            createdAt = OffsetDateTime.now().minusHours(2),
            resolvedAt = OffsetDateTime.now().minusHours(1)
        )
        val appB = Application(
            id = UUID.randomUUID(),
            userId = applicantB,
            clubId = clubBId,
            answerText = null,
            status = ApplicationStatus.approved,
            rejectedReason = null,
            createdAt = OffsetDateTime.now().minusHours(3),
            resolvedAt = OffsetDateTime.now().minusHours(2)
        )
        val clubA = createClosedClub(clubAId, organizerId, subscriptionPrice = 250)
        val clubB = createClosedClub(clubBId, organizerId, subscriptionPrice = 400)
        val applicantARecord = mockk<com.clubs.generated.jooq.tables.records.UsersRecord>(relaxed = true).also {
            every { it.id } returns applicantA
            every { it.firstName } returns "Alice"
            every { it.lastName } returns null
            every { it.telegramUsername } returns "alice_a"
            every { it.avatarUrl } returns null
        }
        val applicantBRecord = mockk<com.clubs.generated.jooq.tables.records.UsersRecord>(relaxed = true).also {
            every { it.id } returns applicantB
            every { it.firstName } returns "Bob"
            every { it.lastName } returns "Brown"
            every { it.telegramUsername } returns null
            every { it.avatarUrl } returns null
        }

        every { clubRepository.findIdsByOwnerId(organizerId) } returns ownedClubIds
        every { applicationRepository.findApprovedWithoutMembershipByClubIds(ownedClubIds) } returns listOf(appA, appB)
        every { userRepository.findByIds(setOf(applicantA, applicantB)) } returns listOf(applicantARecord, applicantBRecord)
        every { clubRepository.findByIds(setOf(clubAId, clubBId)) } returns listOf(clubA, clubB)

        val result = applicationService.getOrganizerAwaitingPaymentApplicants(organizerId)

        assertEquals(2, result.size)
        val clubIds = result.map { it.club.id }.toSet()
        assertEquals(setOf(clubAId, clubBId), clubIds)
        val byApplication = result.associateBy { it.applicationId }
        assertEquals("Alice", byApplication[appA.id]?.firstName)
        assertEquals(250, byApplication[appA.id]?.subscriptionPrice)
        assertEquals("Bob", byApplication[appB.id]?.firstName)
        assertEquals(400, byApplication[appB.id]?.subscriptionPrice)
    }

    @Test
    fun `getOrganizerAwaitingPaymentApplicants returns empty list for non-organizer`() {
        val callerId = UUID.randomUUID()
        every { clubRepository.findIdsByOwnerId(callerId) } returns emptyList()

        val result = applicationService.getOrganizerAwaitingPaymentApplicants(callerId)

        assertEquals(0, result.size)
        verify(exactly = 0) { applicationRepository.findApprovedWithoutMembershipByClubIds(any()) }
        verify(exactly = 0) { userRepository.findByIds(any()) }
    }

    @Test
    fun `getOrganizerAwaitingPaymentApplicants returns empty list when only free clubs are owned`() {
        // Repository guarantees free clubs (subscription_price = 0) never appear
        // in findApprovedWithoutMembershipByClubIds — verify Service trusts that
        // contract and surfaces an empty list when the repo returns nothing.
        val organizerId = UUID.randomUUID()
        val freeClubId = UUID.randomUUID()
        every { clubRepository.findIdsByOwnerId(organizerId) } returns listOf(freeClubId)
        every { applicationRepository.findApprovedWithoutMembershipByClubIds(listOf(freeClubId)) } returns emptyList()

        val result = applicationService.getOrganizerAwaitingPaymentApplicants(organizerId)

        assertEquals(0, result.size)
        verify(exactly = 0) { userRepository.findByIds(any()) }
        verify(exactly = 0) { clubRepository.findByIds(any()) }
    }

    @Test
    fun `completeFreeMembership creates membership and increments member_count for free club`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val organizerId = UUID.randomUUID()

        val approved = Application(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.approved,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = OffsetDateTime.now()
        )
        val club = createClosedClub(clubId, organizerId, subscriptionPrice = 0)
        val now = OffsetDateTime.now()
        val createdMembership = Membership(
            id = UUID.randomUUID(),
            userId = userId,
            clubId = clubId,
            status = com.clubs.generated.jooq.enums.MembershipStatus.active,
            role = com.clubs.generated.jooq.enums.MembershipRole.member,
            joinedAt = now,
            subscriptionExpiresAt = now.plusDays(30),
            createdAt = now,
            updatedAt = now
        )

        every { applicationRepository.findById(applicationId) } returns approved
        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findActiveByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.create(userId, clubId) } returns createdMembership

        val result = applicationService.completeFreeMembership(applicationId, userId)

        assertEquals(userId, result.userId)
        assertEquals(clubId, result.clubId)
        assertEquals("active", result.status)
        verify(exactly = 1) { membershipRepository.create(userId, clubId) }
        verify(exactly = 1) { clubRepository.incrementMemberCount(clubId) }
    }

    @Test
    fun `completeFreeMembership throws ForbiddenException when caller is not the applicant`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val approved = Application(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.approved,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = OffsetDateTime.now()
        )

        every { applicationRepository.findById(applicationId) } returns approved

        assertThrows<ForbiddenException> {
            applicationService.completeFreeMembership(applicationId, otherUserId)
        }
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
        verify(exactly = 0) { clubRepository.incrementMemberCount(any()) }
    }

    @Test
    fun `completeFreeMembership throws ValidationException when club is paid`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val organizerId = UUID.randomUUID()
        val approved = Application(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.approved,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = OffsetDateTime.now()
        )
        val club = createClosedClub(clubId, organizerId, subscriptionPrice = 250)

        every { applicationRepository.findById(applicationId) } returns approved
        every { clubRepository.findById(clubId) } returns club

        val ex = assertThrows<ValidationException> {
            applicationService.completeFreeMembership(applicationId, userId)
        }
        assertEquals("Club is not free — pay the invoice instead", ex.message)
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
    }

    @Test
    fun `completeFreeMembership throws ValidationException when membership already exists`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val organizerId = UUID.randomUUID()
        val approved = Application(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.approved,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = OffsetDateTime.now()
        )
        val club = createClosedClub(clubId, organizerId, subscriptionPrice = 0)
        val now = OffsetDateTime.now()
        val existing = Membership(
            id = UUID.randomUUID(),
            userId = userId,
            clubId = clubId,
            status = com.clubs.generated.jooq.enums.MembershipStatus.active,
            role = com.clubs.generated.jooq.enums.MembershipRole.member,
            joinedAt = now,
            subscriptionExpiresAt = now.plusDays(30),
            createdAt = now,
            updatedAt = now
        )

        every { applicationRepository.findById(applicationId) } returns approved
        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findActiveByUserAndClub(userId, clubId) } returns existing

        val ex = assertThrows<ValidationException> {
            applicationService.completeFreeMembership(applicationId, userId)
        }
        assertEquals("Already a member", ex.message)
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
        verify(exactly = 0) { clubRepository.incrementMemberCount(any()) }
    }

    @Test
    fun `approveApplication should throw ValidationException when application is not pending`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val organizerId = UUID.randomUUID()

        val application = Application(
            id = applicationId,
            userId = userId,
            clubId = clubId,
            answerText = null,
            status = ApplicationStatus.approved,
            rejectedReason = null,
            createdAt = OffsetDateTime.now(),
            resolvedAt = null
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
