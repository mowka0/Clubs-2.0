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
import com.clubs.membership.MembershipActivator
import com.clubs.membership.MembershipDto
import com.clubs.membership.MembershipMapper
import com.clubs.membership.MembershipRepository
import com.clubs.reputation.ApplicantSignal
import com.clubs.reputation.ApplicantSignalService
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
    private val mapper: ApplicationMapper,
    private val notificationService: NotificationService,
    private val userRepository: UserRepository,
    private val reputationRepository: ReputationRepository,
    private val applicantSignalService: ApplicantSignalService,
    private val interestRepository: InterestRepository,
    private val membershipMapper: MembershipMapper,
    private val membershipActivator: MembershipActivator
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

        // De-Stars: an approved application now means a membership already exists (approve creates it),
        // so the membership check above catches that case first as "Already a member".
        val activeApp = applicationRepository.findActiveByUserAndClub(userId, clubId)
        if (activeApp != null) throw ConflictException("Application already exists")

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
        if (activeCount >= club.memberLimit) throw ValidationException("Club is full")

        // De-Stars (Slice 2): approve creates the membership immediately — no Stars invoice. A paid club
        // lands the applicant in `frozen` (access gated until the organizer confirms the off-platform
        // dues via AccessGateService.markDuesPaid); a free club joins straight to `active`.
        if (club.subscriptionPrice > 0) {
            membershipActivator.activateFrozen(application.userId, application.clubId)
        } else {
            membershipActivator.activateFree(application.userId, application.clubId)
        }
        log.info(
            "Membership created on application approve: applicationId={} clubId={} userId={} paid={}",
            applicationId, application.clubId, application.userId, club.subscriptionPrice > 0
        )

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
        val signalsByUser = applicantSignalService.signalsFor(applicantIds)
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
                peerStats = mapper.toPeerStats(
                    peerStatsByUser[application.userId] ?: PeerStatsAggregate.EMPTY,
                    signalsByUser[application.userId] ?: ApplicantSignal.EMPTY
                ),
                club = mapper.toClubBrief(club),
                now = now
            )
        }
    }

    /**
     * Pending-applications count for the «Мои клубы» tab-dot: pending applications across the caller's
     * owned clubs. (De-Stars Slice 2: the Stars "awaiting payment" counters are gone — approve creates
     * the membership immediately, so that state no longer exists.) Scoped to the caller; no IDOR risk.
     */
    @Transactional(readOnly = true)
    fun getMyClubsActionCounts(userId: UUID): PendingApplicationsCountDto {
        val ownedClubIds = clubRepository.findIdsByOwnerId(userId)
        val inboxCount = applicationRepository.countPendingByClubIds(ownedClubIds)
        return PendingApplicationsCountDto(inboxCount = inboxCount)
    }

    /**
     * Finalises a free-club membership for an approved application that was left in a stuck
     * "approved-without-membership" state (legacy data from before approve always created the
     * membership). Only the applicant can call it; only valid for free clubs (`subscription_price <= 0`)
     * — paid clubs are joined as `frozen` on approve and opened by the organizer (AccessGateService).
     *
     * Delegates to [MembershipActivator.activateFree] which handles both fresh INSERT (no row at all)
     * and reactivation (cancelled / expired row from a prior lifecycle — UNIQUE(user_id, club_id)
     * prevents a second INSERT). Idempotent at the application-level — second call after success
     * returns 400 ("Already a member").
     */
    @Transactional
    fun completeFreeMembership(applicationId: UUID, callerUserId: UUID): MembershipDto {
        val application = applicationRepository.findById(applicationId)
            ?: throw NotFoundException("Application not found")
        if (application.userId != callerUserId) {
            throw ForbiddenException("Forbidden")
        }
        if (application.status != ApplicationStatus.approved) {
            throw ValidationException("Application is not approved")
        }
        val club = clubRepository.findById(application.clubId)
            ?: throw NotFoundException("Club not found")
        if (club.subscriptionPrice > 0) {
            throw ValidationException("Club is not free — the organizer opens access after the dues")
        }
        val existingMembership = membershipRepository.findActiveByUserAndClub(callerUserId, application.clubId)
        if (existingMembership != null) {
            throw ValidationException("Already a member")
        }

        val membership = membershipActivator.activateFree(callerUserId, application.clubId)
        log.info(
            "Free membership completed for stuck application: applicationId={} userId={} clubId={}",
            applicationId, callerUserId, application.clubId
        )
        return membershipMapper.toDto(membership)
    }
}
