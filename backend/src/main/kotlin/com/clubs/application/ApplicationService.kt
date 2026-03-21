package com.clubs.application

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.RateLimitException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ApplicationStatus
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.membership.MembershipRepository
import com.clubs.membership.MembershipService
import com.clubs.membership.toDto
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ApplicationService(
    private val applicationRepository: ApplicationRepository,
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository,
    private val membershipService: MembershipService,
    private val dsl: DSLContext
) {

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

        val pendingApp = applicationRepository.findPendingByUserAndClub(userId, clubId)
        if (pendingApp != null) throw ConflictException("Application already exists")

        val todayCount = applicationRepository.countTodayByUser(userId)
        if (todayCount >= 5) throw RateLimitException("Too many applications today")

        val application = applicationRepository.create(userId, clubId, request.answerText)
        return application.toDto()
    }

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

        membershipRepository.create(application.userId!!, application.clubId!!)

        dsl.update(CLUBS)
            .set(CLUBS.MEMBER_COUNT, (club.memberCount ?: 0) + 1)
            .where(CLUBS.ID.eq(application.clubId))
            .execute()

        val updated = applicationRepository.updateStatus(applicationId, ApplicationStatus.approved)
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
