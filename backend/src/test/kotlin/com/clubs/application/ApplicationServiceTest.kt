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
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.interest.InterestRepository
import com.clubs.membership.Membership
import com.clubs.membership.MembershipActivator
import com.clubs.membership.MembershipMapper
import com.clubs.membership.MembershipRepository
import com.clubs.reputation.ApplicantSignalService
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
    private lateinit var mapper: ApplicationMapper
    private lateinit var notificationService: NotificationService
    private lateinit var userRepository: UserRepository
    private lateinit var reputationRepository: ReputationRepository
    private lateinit var applicantSignalService: ApplicantSignalService
    private lateinit var interestRepository: InterestRepository
    private lateinit var membershipMapper: MembershipMapper
    private lateinit var membershipActivator: MembershipActivator
    private lateinit var applicationService: ApplicationService

    @BeforeEach
    fun setUp() {
        applicationRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        membershipRepository = mockk(relaxed = true)
        mapper = ApplicationMapper()
        notificationService = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        reputationRepository = mockk(relaxed = true)
        applicantSignalService = mockk(relaxed = true)
        interestRepository = mockk(relaxed = true)
        membershipMapper = MembershipMapper()
        membershipActivator = mockk(relaxed = true)
        applicationService = ApplicationService(
            applicationRepository,
            clubRepository,
            membershipRepository,
            mapper,
            notificationService,
            userRepository,
            reputationRepository,
            applicantSignalService,
            interestRepository,
            membershipMapper,
            membershipActivator
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
    fun `submitApplication self-heals an orphaned approved application after removal`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val club = createClosedClub(clubId, ownerId, subscriptionPrice = 0)
        val request = SubmitApplicationRequest(answerText = "again")
        val orphanApp = createPendingApplication(userId, clubId, null)
            .copy(status = ApplicationStatus.approved)
        val cancelledMembership = com.clubs.membership.Membership(
            id = UUID.randomUUID(), userId = userId, clubId = clubId,
            status = com.clubs.generated.jooq.enums.MembershipStatus.cancelled,
            role = com.clubs.generated.jooq.enums.MembershipRole.member,
            joinedAt = OffsetDateTime.now(), subscriptionExpiresAt = null,
            createdAt = OffsetDateTime.now(), updatedAt = OffsetDateTime.now()
        )

        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findActiveByUserAndClub(userId, clubId) } returns null
        every { applicationRepository.findActiveByUserAndClub(userId, clubId) } returns orphanApp
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns cancelledMembership
        every { applicationRepository.countTodayByUser(userId) } returns 0
        every { applicationRepository.create(userId, clubId, "again") } returns createPendingApplication(userId, clubId, "again")

        val result = applicationService.submitApplication(clubId, userId, request)

        assertEquals("pending", result.status)
        // The stale approved row is cleared, then a fresh application is created — no «уже существует».
        verify(exactly = 1) { applicationRepository.deleteActiveByUserAndClub(userId, clubId) }
        verify(exactly = 1) { applicationRepository.create(userId, clubId, "again") }
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

        // De-Stars: the Stars "waiting for payment" wording is gone — an existing active application blocks re-apply.
        assertEquals("Application already exists", exception.message)
        verify(exactly = 0) { applicationRepository.create(any(), any(), any()) }
    }

    @Test
    fun `approveApplication for paid club creates frozen membership (no invoice)`() {
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
        // De-Stars: paid approve creates the membership straight to `frozen` — no Stars invoice.
        verify(exactly = 1) { membershipActivator.activateFrozen(userId, clubId) }
        verify(exactly = 0) { membershipActivator.activateFree(any(), any()) }
    }

    @Test
    fun `approveApplication notifies a paid-club applicant to pay`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val organizerId = UUID.randomUUID()
        val application = Application(applicationId, userId, clubId, null, ApplicationStatus.pending, null, OffsetDateTime.now(), null)
        val club = createClosedClub(clubId, organizerId, subscriptionPrice = 500)
        every { applicationRepository.findById(applicationId) } returns application
        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.countActiveByClubId(clubId) } returns 5
        every { applicationRepository.updateStatus(applicationId, ApplicationStatus.approved) } returns application.copy(status = ApplicationStatus.approved, resolvedAt = OffsetDateTime.now())
        val applicant = mockk<UsersRecord>(relaxed = true) { every { telegramId } returns 77L }
        every { userRepository.findById(userId) } returns applicant

        applicationService.approveApplication(applicationId, organizerId)

        verify(exactly = 1) { notificationService.sendApplicationApprovedDM(77L, "Closed Club", clubId, paid = true) }
    }

    @Test
    fun `approveApplication notifies a free-club applicant (welcome, not paid)`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val organizerId = UUID.randomUUID()
        val application = Application(applicationId, userId, clubId, null, ApplicationStatus.pending, null, OffsetDateTime.now(), null)
        val club = createClosedClub(clubId, organizerId, subscriptionPrice = 0)
        every { applicationRepository.findById(applicationId) } returns application
        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.countActiveByClubId(clubId) } returns 5
        every { applicationRepository.updateStatus(applicationId, ApplicationStatus.approved) } returns application.copy(status = ApplicationStatus.approved, resolvedAt = OffsetDateTime.now())
        val applicant = mockk<UsersRecord>(relaxed = true) { every { telegramId } returns 88L }
        every { userRepository.findById(userId) } returns applicant

        applicationService.approveApplication(applicationId, organizerId)

        verify(exactly = 1) { notificationService.sendApplicationApprovedDM(88L, "Closed Club", clubId, paid = false) }
    }

    @Test
    fun `approveApplication for free club creates active membership via activateFree`() {
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
        verify(exactly = 1) { membershipActivator.activateFree(userId, clubId) }
        verify(exactly = 0) { membershipActivator.activateFrozen(any(), any()) }
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
        verify(exactly = 0) { membershipActivator.activateFree(any(), any()) }
        verify(exactly = 0) { membershipActivator.activateFrozen(any(), any()) }
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
    fun `getMyClubsActionCounts returns pending inbox count`() {
        // De-Stars: the Stars "awaiting payment" counters are gone; only the pending-inbox count remains.
        val userId = UUID.randomUUID()
        val ownedClubIds = listOf(UUID.randomUUID(), UUID.randomUUID())

        every { clubRepository.findIdsByOwnerId(userId) } returns ownedClubIds
        every { applicationRepository.countPendingByClubIds(ownedClubIds) } returns 3

        val result = applicationService.getMyClubsActionCounts(userId)

        assertEquals(3, result.inboxCount)
    }

    @Test
    fun `completeFreeMembership delegates to MembershipActivator for fresh insert`() {
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
            subscriptionExpiresAt = null,
            createdAt = now,
            updatedAt = now
        )

        every { applicationRepository.findById(applicationId) } returns approved
        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findActiveByUserAndClub(userId, clubId) } returns null
        every { membershipActivator.activateFree(userId, clubId) } returns createdMembership

        val result = applicationService.completeFreeMembership(applicationId, userId)

        assertEquals(userId, result.userId)
        assertEquals(clubId, result.clubId)
        assertEquals("active", result.status)
        verify(exactly = 1) { membershipActivator.activateFree(userId, clubId) }
    }

    @Test
    fun `completeFreeMembership reactivates cancelled membership via activator`() {
        // Repro for staging DuplicateKeyException: a cancelled membership exists
        // (status NOT in active/grace_period). `findActiveByUserAndClub` returns
        // null, so we don't 400. The activator must reactivate the dead row
        // instead of INSERTing (which would explode the UNIQUE constraint).
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
        val reactivated = Membership(
            id = UUID.randomUUID(),
            userId = userId,
            clubId = clubId,
            status = com.clubs.generated.jooq.enums.MembershipStatus.active,
            role = com.clubs.generated.jooq.enums.MembershipRole.member,
            joinedAt = now,
            subscriptionExpiresAt = null,
            createdAt = now.minusDays(60),
            updatedAt = now
        )

        every { applicationRepository.findById(applicationId) } returns approved
        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findActiveByUserAndClub(userId, clubId) } returns null
        every { membershipActivator.activateFree(userId, clubId) } returns reactivated

        val result = applicationService.completeFreeMembership(applicationId, userId)

        assertEquals("active", result.status)
        assertEquals(userId, result.userId)
        verify(exactly = 1) { membershipActivator.activateFree(userId, clubId) }
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
        verify(exactly = 0) { membershipActivator.activateFree(any(), any()) }
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
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
        assertEquals("Club is not free — the organizer opens access after the dues", ex.message)
        verify(exactly = 0) { membershipActivator.activateFree(any(), any()) }
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
        verify(exactly = 0) { membershipActivator.activateFree(any(), any()) }
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
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

    @Test
    fun `cancelApplication lets the applicant withdraw their own pending application`() {
        val applicationId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val application = createPendingApplication(userId, clubId, "I want to join").copy(id = applicationId)
        val cancelled = application.copy(status = ApplicationStatus.cancelled, resolvedAt = OffsetDateTime.now())

        every { applicationRepository.findById(applicationId) } returns application
        every { applicationRepository.updateStatus(applicationId, ApplicationStatus.cancelled) } returns cancelled

        val result = applicationService.cancelApplication(applicationId, userId)

        assertEquals("cancelled", result.status)
        verify(exactly = 1) { applicationRepository.updateStatus(applicationId, ApplicationStatus.cancelled) }
    }

    @Test
    fun `cancelApplication rejects a caller who is not the applicant`() {
        val applicationId = UUID.randomUUID()
        val application = createPendingApplication(UUID.randomUUID(), UUID.randomUUID(), null).copy(id = applicationId)
        every { applicationRepository.findById(applicationId) } returns application

        assertThrows<ForbiddenException> { applicationService.cancelApplication(applicationId, UUID.randomUUID()) }
        verify(exactly = 0) { applicationRepository.updateStatus(any(), ApplicationStatus.cancelled) }
    }

    @Test
    fun `cancelApplication rejects a non-pending application`() {
        val applicationId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val approved = createPendingApplication(userId, UUID.randomUUID(), null)
            .copy(id = applicationId, status = ApplicationStatus.approved)
        every { applicationRepository.findById(applicationId) } returns approved

        assertThrows<ValidationException> { applicationService.cancelApplication(applicationId, userId) }
        verify(exactly = 0) { applicationRepository.updateStatus(any(), ApplicationStatus.cancelled) }
    }
}
