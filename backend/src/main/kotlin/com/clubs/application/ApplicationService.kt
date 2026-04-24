package com.clubs.application

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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ApplicationService(
    private val applicationRepository: ApplicationRepository,
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository,
    private val paymentService: PaymentService
) {

    private val log = LoggerFactory.getLogger(ApplicationService::class.java)

    fun submitApplication(clubId: UUID, userId: UUID, request: SubmitApplicationRequest): ApplicationDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")

        if (club.accessType != AccessType.closed) {
            throw ValidationException("Club does not accept applications")
        }

        if (club.applicationQuestion != null && request.answerText.isNullOrBlank()) {
            throw ValidationException("Answer is required for this club")
        }

        val existingMembership = membershipRepository.findByUserAndClub(userId, clubId)
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
        if (todayCount >= 5) throw RateLimitException("Too many applications today")

        val application = applicationRepository.create(userId, clubId, request.answerText)
        log.info("Application submitted: id={} clubId={} userId={}", application.id, clubId, userId)
        return application.toDto()
    }

    @Transactional
    fun approveApplication(applicationId: UUID, organizerId: UUID): ApplicationDto {
        val application = applicationRepository.findById(applicationId)
            ?: throw NotFoundException("Application not found")

        val club = clubRepository.findById(application.clubId!!)
            ?: throw NotFoundException("Club not found")

        if (club.ownerId != organizerId) throw ForbiddenException("Forbidden")

        if (application.status != ApplicationStatus.pending) {
            throw ValidationException("Application is not pending")
        }

        val activeCount = membershipRepository.countActiveByClubId(application.clubId!!)
        if (activeCount >= (club.memberLimit ?: 0)) throw ValidationException("Club is full")

        val price = club.subscriptionPrice ?: 0
        if (price > 0) {
            paymentService.createInvoice(application.userId!!, application.clubId!!)
            log.info(
                "Invoice requested on application approve: applicationId={} clubId={} userId={} price={}",
                applicationId, application.clubId, application.userId, price
            )
        } else {
            membershipRepository.create(application.userId!!, application.clubId!!)
            clubRepository.incrementMemberCount(application.clubId!!)
            log.info(
                "Membership created on application approve (free club): applicationId={} clubId={} userId={}",
                applicationId, application.clubId, application.userId
            )
        }

        val updated = applicationRepository.updateStatus(applicationId, ApplicationStatus.approved)
        log.info("Application approved: id={} clubId={} userId={} organizerId={}", applicationId, application.clubId, application.userId, organizerId)
        return updated.toDto()
    }

    fun rejectApplication(applicationId: UUID, organizerId: UUID, reason: String?): ApplicationDto {
        val application = applicationRepository.findById(applicationId)
            ?: throw NotFoundException("Application not found")

        val club = clubRepository.findById(application.clubId!!)
            ?: throw NotFoundException("Club not found")

        if (club.ownerId != organizerId) throw ForbiddenException("Forbidden")

        if (application.status != ApplicationStatus.pending) {
            throw ValidationException("Application is not pending")
        }

        val updated = applicationRepository.updateStatus(applicationId, ApplicationStatus.rejected, reason)
        log.info("Application rejected: id={} clubId={} userId={} organizerId={} reason={}", applicationId, application.clubId, application.userId, organizerId, reason)
        return updated.toDto()
    }

    fun getClubApplications(clubId: UUID, organizerId: UUID, status: String?): List<ApplicationDto> {
        clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")

        val club = clubRepository.findById(clubId)!!
        if (club.ownerId != organizerId) throw ForbiddenException("Forbidden")

        val statusEnum = status?.let {
            ApplicationStatus.values().find { e -> e.literal == it }
                ?: throw ValidationException("Invalid status: $it")
        }

        return applicationRepository.findByClubId(clubId, statusEnum).map { it.toDto() }
    }

    fun getMyApplications(userId: UUID): List<ApplicationDto> =
        applicationRepository.findByUserId(userId).map { it.toDto() }
}
