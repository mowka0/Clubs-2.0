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
import com.clubs.interest.InterestRepository
import com.clubs.membership.MembershipRepository
import com.clubs.payment.PaymentService
import com.clubs.reputation.PeerStatsAggregate
import com.clubs.reputation.ReputationRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val MAX_APPLICATIONS_PER_DAY = 5
private val RESEND_INVOICE_COOLDOWN: Duration = Duration.ofSeconds(60)

@Service
class ApplicationService(
    private val applicationRepository: ApplicationRepository,
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository,
    private val paymentService: PaymentService,
    private val mapper: ApplicationMapper,
    private val notificationService: NotificationService,
    private val userRepository: UserRepository,
    private val reputationRepository: ReputationRepository,
    private val interestRepository: InterestRepository
) {

    /**
     * In-memory cooldown for `POST /api/applications/{id}/resend-invoice`.
     * Bucket4j RateLimitFilter is keyed by user/IP and applies to ALL endpoints
     * uniformly; we need a per-application cooldown to prevent invoice-spam to
     * Telegram regardless of who's clicking. Single-instance only — acceptable
     * for current deploy (one backend container in Coolify). If we scale out,
     * migrate to Redis.
     */
    private val resendCooldown = ConcurrentHashMap<UUID, OffsetDateTime>()

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
        val interestsByUser = interestRepository.findUserInterestNamesByUserIds(applicantIds)
        val clubsById = clubRepository.findByIds(applicantClubIds).associateBy { it.id }

        val now = OffsetDateTime.now()
        return applications.mapNotNull { application ->
            val applicantRecord = applicantsById[application.userId] ?: return@mapNotNull null
            val club = clubsById[application.clubId] ?: return@mapNotNull null
            val interests = interestsByUser[application.userId].orEmpty()
            mapper.toPendingDto(
                application = application,
                applicant = mapper.toApplicantInfo(applicantRecord, interests),
                peerStats = mapper.toPeerStats(peerStatsByUser[application.userId] ?: PeerStatsAggregate.EMPTY),
                club = mapper.toClubBrief(club),
                now = now
            )
        }
    }

    /**
     * Cross-club action counts for the «Мои клубы» tab-dot:
     *  - inboxCount           — pending applications for the caller's owned clubs (organizer action).
     *  - awaitingPaymentCount — caller's own approved applications with no active membership (applicant action).
     *
     * Single combined response = single cache slot on the frontend. Both fields
     * are scoped to the caller; no IDOR risk.
     */
    @Transactional(readOnly = true)
    fun getMyClubsActionCounts(userId: UUID): PendingApplicationsCountDto {
        val ownedClubIds = clubRepository.findIdsByOwnerId(userId)
        val inboxCount = applicationRepository.countPendingByClubIds(ownedClubIds)
        val awaitingPaymentCount = applicationRepository
            .findApprovedWithoutMembershipByUserId(userId)
            .size
        return PendingApplicationsCountDto(
            inboxCount = inboxCount,
            awaitingPaymentCount = awaitingPaymentCount
        )
    }

    /**
     * Caller's own approved applications whose Stars invoice hasn't been paid
     * yet (no active membership exists). Used by «Ожидают оплаты» section on
     * MyClubsPage. Returns clubs sorted by approvedAt DESC.
     */
    @Transactional(readOnly = true)
    fun getMyAwaitingPaymentApplications(userId: UUID): List<AwaitingPaymentApplicationDto> {
        val applications = applicationRepository.findApprovedWithoutMembershipByUserId(userId)
        if (applications.isEmpty()) return emptyList()

        val clubsById = clubRepository.findByIds(applications.map { it.clubId }.toSet())
            .associateBy { it.id }

        return applications.mapNotNull { application ->
            val club = clubsById[application.clubId] ?: return@mapNotNull null
            mapper.toAwaitingPaymentDto(
                application = application,
                club = mapper.toClubBrief(club),
                subscriptionPrice = club.subscriptionPrice
            )
        }
    }

    /**
     * Re-sends the Stars invoice for an approved-but-unpaid application.
     * Ownership: caller must be the applicant. Rate limit: 1 call per 60s per
     * application (in-memory cooldown — see [resendCooldown] doc).
     * PaymentService.createInvoice may throw on Telegram errors; we let it
     * propagate (GlobalExceptionHandler maps to 5xx) — no swallowing.
     */
    fun resendInvoice(applicationId: UUID, callerUserId: UUID) {
        val application = applicationRepository.findById(applicationId)
            ?: throw NotFoundException("Application not found")
        if (application.userId != callerUserId) {
            throw ForbiddenException("Forbidden")
        }
        if (application.status != ApplicationStatus.approved) {
            throw ValidationException("No payment pending")
        }
        val membership = membershipRepository.findActiveByUserAndClub(callerUserId, application.clubId)
        if (membership != null) {
            throw ValidationException("No payment pending")
        }

        val now = OffsetDateTime.now()
        val previous = resendCooldown[applicationId]
        if (previous != null && Duration.between(previous, now) < RESEND_INVOICE_COOLDOWN) {
            throw RateLimitException("Please wait before resending the invoice")
        }
        resendCooldown[applicationId] = now

        paymentService.createInvoice(application.userId, application.clubId)
        log.info(
            "Invoice resent: applicationId={} userId={} clubId={}",
            applicationId, application.userId, application.clubId
        )
    }
}
