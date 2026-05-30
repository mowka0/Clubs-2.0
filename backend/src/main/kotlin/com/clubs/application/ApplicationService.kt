package com.clubs.application

import com.clubs.bot.NotificationService
import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.RateLimitException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ApplicationStatus
import com.clubs.membership.MembershipRepository
import com.clubs.payment.PaymentService
import com.clubs.reputation.PeerStatsAggregate
import com.clubs.reputation.ReputationRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

private const val MAX_APPLICATIONS_PER_DAY = 5

@Service
class ApplicationService(
    private val applicationRepository: ApplicationRepository,
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository,
    private val paymentService: PaymentService,
    private val mapper: ApplicationMapper,
    private val notificationService: NotificationService,
    private val userRepository: UserRepository,
    private val reputationRepository: ReputationRepository
) {

    private val log = LoggerFactory.getLogger(ApplicationService::class.java)

    @Transactional
    fun submitApplication(clubId: UUID, userId: UUID, request: SubmitApplicationRequest): ApplicationDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")

        if (club.accessType != AccessType.closed) {
            throw ValidationException("Club does not accept applications")
        }

        if (club.applicationQuestion != null && request.answerText.isNullOrBlank()) {
            throw ValidationException("Answer is required for this club")
        }

        val existingMembership = membershipRepository.findActiveByUserAndClub(userId, clubId)
        if (existingMembership != null) throw ConflictException("Already a member")

        val activeApp = applicationRepository.findActiveByUserAndClub(userId, clubId)
        if (activeApp != null) {
            val reason = if (activeApp.status == ApplicationStatus.approved)
                "Application already approved — waiting for payment"
            else
                "Application already exists"
            throw ConflictException(reason)
        }

        val todayCount = applicationRepository.countTodayByUser(userId)
        if (todayCount >= MAX_APPLICATIONS_PER_DAY) throw RateLimitException("Too many applications today")

        val application = applicationRepository.create(userId, clubId, request.answerText)
        log.info("Application submitted: id={} clubId={} userId={}", application.id, clubId, userId)

        dispatchApplicationCreatedDm(club, userId)

        return mapper.toDto(application)
    }

    /**
     * Best-effort organizer notification on new application. Failures here must
     * NOT abort the submitApplication transaction — sendApplicationCreatedDM is
     * @Async (fire-and-forget) and the per-message try/catch lives in
     * NotificationService.sendDm. We additionally guard the lookups so a DB
     * miss / NPE never poisons the happy path.
     */
    private fun dispatchApplicationCreatedDm(club: Club, applicantId: UUID) {
        try {
            val organizer = userRepository.findById(club.ownerId)
            if (organizer == null) {
                log.warn("Skipping application-created DM: organizer not found ownerId={} clubId={}", club.ownerId, club.id)
                return
            }
            val applicant = userRepository.findById(applicantId)
            val applicantName = applicant?.let { buildDisplayName(it.firstName, it.lastName) } ?: "Новый пользователь"

            notificationService.sendApplicationCreatedDM(
                organizerTelegramId = organizer.telegramId,
                applicantDisplayName = applicantName,
                clubName = club.name
            )
            log.info(
                "DM dispatched for application-created: clubId={} organizerTelegramId={}",
                club.id, organizer.telegramId
            )
        } catch (e: Exception) {
            log.warn(
                "Failed to dispatch application-created DM (non-fatal): clubId={} applicantId={} error={}",
                club.id, applicantId, e.message
            )
        }
    }

    private fun buildDisplayName(firstName: String, lastName: String?): String =
        if (lastName.isNullOrBlank()) firstName else "$firstName $lastName"

    @Transactional
    fun approveApplication(applicationId: UUID, organizerId: UUID): ApplicationDto {
        val application = applicationRepository.findById(applicationId)
            ?: throw NotFoundException("Application not found")

        val club = clubRepository.findById(application.clubId)
            ?: throw NotFoundException("Club not found")

        if (club.ownerId != organizerId) throw ForbiddenException("Forbidden")

        if (application.status != ApplicationStatus.pending) {
            throw ValidationException("Application is not pending")
        }

        val activeCount = membershipRepository.countActiveByClubId(application.clubId)
        if (activeCount >= (club.memberLimit ?: 0)) throw ValidationException("Club is full")

        val price = club.subscriptionPrice ?: 0
        if (price > 0) {
            paymentService.createInvoice(application.userId, application.clubId)
            log.info(
                "Invoice requested on application approve: applicationId={} clubId={} userId={} price={}",
                applicationId, application.clubId, application.userId, price
            )
        } else {
            membershipRepository.create(application.userId, application.clubId)
            clubRepository.incrementMemberCount(application.clubId)
            log.info(
                "Membership created on application approve (free club): applicationId={} clubId={} userId={}",
                applicationId, application.clubId, application.userId
            )
        }

        val updated = applicationRepository.updateStatus(applicationId, ApplicationStatus.approved)
        log.info("Application approved: id={} clubId={} userId={} organizerId={}", applicationId, application.clubId, application.userId, organizerId)
        return mapper.toDto(updated)
    }

    @Transactional
    fun rejectApplication(applicationId: UUID, organizerId: UUID, reason: String?): ApplicationDto {
        val application = applicationRepository.findById(applicationId)
            ?: throw NotFoundException("Application not found")

        val club = clubRepository.findById(application.clubId)
            ?: throw NotFoundException("Club not found")

        if (club.ownerId != organizerId) throw ForbiddenException("Forbidden")

        if (application.status != ApplicationStatus.pending) {
            throw ValidationException("Application is not pending")
        }

        // DTO @NotBlank/@Size catches empty/short reasons before we get here for
        // human-driven rejects. The nullable parameter keeps the door open for
        // future system-driven rejects (e.g. scheduler) without contract changes.
        // Defense in depth: re-check length AFTER trim. "  ab " passes @Size(min=5)
        // but trims to 2 chars; we treat it as invalid for human-driven rejects.
        val storedReason = reason?.trim()?.ifEmpty { null }
        if (reason != null && (storedReason == null || storedReason.length < 5)) {
            throw ValidationException("Reason must be at least 5 characters after trim")
        }
        val updated = applicationRepository.updateStatus(applicationId, ApplicationStatus.rejected, storedReason)
        log.info(
            "Application rejected: id={} clubId={} userId={} organizerId={}",
            applicationId, application.clubId, application.userId, organizerId
        )
        return mapper.toDto(updated)
    }

    fun getClubApplications(clubId: UUID, organizerId: UUID, status: String?): List<ApplicationDto> {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != organizerId) throw ForbiddenException("Forbidden")

        val statusEnum = status?.let {
            ApplicationStatus.values().find { e -> e.literal == it }
                ?: throw ValidationException("Invalid status: $it")
        }

        return applicationRepository.findByClubId(clubId, statusEnum).map(mapper::toDto)
    }

    fun getMyApplications(userId: UUID): List<ApplicationDto> =
        applicationRepository.findByUserId(userId).map(mapper::toDto)

    /**
     * Cross-club organizer inbox: all pending applications across the caller's
     * owned clubs, enriched with applicant + peer-stats + club brief.
     *
     * Performance contract (docs/modules/applications-inbox.md § Non-functional):
     * ≤5 SQL queries regardless of N applications.
     */
    @Transactional(readOnly = true)
    fun getMyPendingApplications(organizerId: UUID): List<PendingApplicationDto> {
        val clubIds = clubRepository.findIdsByOwnerId(organizerId)
        if (clubIds.isEmpty()) return emptyList()

        val applications = applicationRepository.findPendingByClubIds(clubIds)
        if (applications.isEmpty()) return emptyList()

        val applicantIds = applications.map { it.userId }.toSet()
        val applicantClubIds = applications.map { it.clubId }.toSet()

        val applicantsById = userRepository.findByIds(applicantIds).associateBy { it.id!! }
        val peerStatsByUser = reputationRepository.aggregateByUserIds(applicantIds)
        val clubsById = clubRepository.findByIds(applicantClubIds).associateBy { it.id }

        val now = OffsetDateTime.now()
        return applications.mapNotNull { application ->
            val applicantRecord = applicantsById[application.userId] ?: return@mapNotNull null
            val club = clubsById[application.clubId] ?: return@mapNotNull null
            mapper.toPendingDto(
                application = application,
                applicant = mapper.toApplicantInfo(applicantRecord),
                peerStats = mapper.toPeerStats(peerStatsByUser[application.userId] ?: PeerStatsAggregate.EMPTY),
                club = mapper.toClubBrief(club),
                now = now
            )
        }
    }

    @Transactional(readOnly = true)
    fun getMyPendingApplicationsCount(organizerId: UUID): PendingApplicationsCountDto {
        val clubIds = clubRepository.findIdsByOwnerId(organizerId)
        val count = applicationRepository.countPendingByClubIds(clubIds)
        return PendingApplicationsCountDto(count)
    }
}
