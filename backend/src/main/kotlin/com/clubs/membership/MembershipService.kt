package com.clubs.membership

import com.clubs.application.ApplicationRepository
import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.event.EventResponseRepository
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.payment.PaymentService
import com.clubs.skladchina.SkladchinaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MembershipService(
    private val membershipRepository: MembershipRepository,
    private val clubRepository: ClubRepository,
    private val paymentService: PaymentService,
    private val mapper: MembershipMapper,
    private val freeMembershipActivator: FreeMembershipActivator,
    private val eventResponseRepository: EventResponseRepository,
    private val skladchinaRepository: SkladchinaRepository,
    private val applicationRepository: ApplicationRepository
) {

    private val log = LoggerFactory.getLogger(MembershipService::class.java)

    @Transactional
    fun joinOpenClub(clubId: UUID, userId: UUID): JoinResult {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")

        if (club.accessType != AccessType.`open`) {
            throw ValidationException("Club is not open for joining")
        }

        val existing = membershipRepository.findActiveByUserAndClub(userId, clubId)
        if (existing != null) throw ConflictException("Already a member")

        val activeCount = membershipRepository.countActiveByClubId(clubId)
        if (activeCount >= club.memberLimit) throw ValidationException("Club is full")

        return joinOrRequestPayment(club, userId, "open")
    }

    @Transactional
    fun cancelMembership(clubId: UUID, userId: UUID): MembershipDto {
        val membership = membershipRepository.findActiveByUserAndClub(userId, clubId)
            ?: throw NotFoundException("Membership not found")
        if (membership.status == MembershipStatus.cancelled) throw ValidationException("Membership already cancelled")

        membershipRepository.cancel(membership.id)

        log.info("Membership cancelled: clubId={} userId={}", clubId, userId)
        return mapper.toDto(membership.copy(status = MembershipStatus.cancelled))
    }

    /**
     * Leave-club operation. Behaviour branches by club type:
     *  - **Free** (subscriptionPrice <= 0): cascade-clean active obligations
     *    (event RSVPs + skladchina participation), decrement `member_count`,
     *    flip membership to `cancelled`. Owner cannot leave.
     *  - **Paid** (subscriptionPrice > 0): just flip membership to `cancelled`.
     *    `subscription_expires_at` and `member_count` are preserved — user
     *    keeps access until expire. Cascade is intentionally skipped: existing
     *    RSVPs/skladchina participation stay valid until expire.
     *
     * Cascade NEVER touches `user_club_reputation`, `transactions`, or
     * completed events/skladchinas — preserves cross-club reputation
     * aggregate and financial audit trail.
     */
    @Transactional
    fun leaveClub(clubId: UUID, userId: UUID): MembershipDto {
        val membership = membershipRepository.findActiveByUserAndClub(userId, clubId)
            ?: throw NotFoundException("Membership not found")

        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")

        if (club.ownerId == userId) {
            log.warn("Owner attempted to leave own club: clubId={} userId={}", clubId, userId)
            throw ValidationException("Owner cannot leave the club")
        }

        return if (club.subscriptionPrice > 0) {
            leavePaidClub(membership, clubId, userId)
        } else {
            leaveFreeClub(membership, clubId, userId)
        }
    }

    @Transactional
    fun joinByInviteCode(code: String, userId: UUID): JoinResult {
        val club = clubRepository.findByInviteCode(code) ?: throw NotFoundException("Invite link not found")
        val clubId = club.id

        val existing = membershipRepository.findActiveByUserAndClub(userId, clubId)
        if (existing != null) throw ConflictException("Already a member")

        val activeCount = membershipRepository.countActiveByClubId(clubId)
        if (activeCount >= club.memberLimit) throw ValidationException("Club is full")

        return joinOrRequestPayment(club, userId, "invite")
    }

    fun getUserMemberships(userId: UUID): List<MembershipDto> =
        membershipRepository.findByUserId(userId).map(mapper::toDto)

    @Transactional(readOnly = true)
    fun getUserClubsWithReputation(userId: UUID): List<UserClubReputationDto> =
        membershipRepository.findUserClubsWithReputation(userId).map(mapper::toUserClubReputationDto)

    private fun leavePaidClub(membership: Membership, clubId: UUID, userId: UUID): MembershipDto {
        membershipRepository.cancel(membership.id)
        val cascadedApplications = applicationRepository.deleteActiveByUserAndClub(userId, clubId)
        log.info(
            "User cancelled paid subscription via /leave: clubId={} userId={} cascadedApplications={}",
            clubId, userId, cascadedApplications
        )
        return mapper.toDto(membership.copy(status = MembershipStatus.cancelled))
    }

    private fun leaveFreeClub(membership: Membership, clubId: UUID, userId: UUID): MembershipDto {
        val cascadedSkladchinas = skladchinaRepository.deleteParticipantFromActiveSkladchinasInClub(userId, clubId)
        val cascadedEventResponses = eventResponseRepository.deleteByUserAndClubAndActiveEvents(userId, clubId)
        val cascadedApplications = applicationRepository.deleteActiveByUserAndClub(userId, clubId)

        membershipRepository.cancel(membership.id)
        clubRepository.decrementMemberCountSafely(clubId, 1)

        log.info(
            "User left free club: clubId={} userId={} cascadedSkladchinas={} cascadedEventResponses={} cascadedApplications={}",
            clubId, userId, cascadedSkladchinas, cascadedEventResponses, cascadedApplications
        )
        return mapper.toDto(membership.copy(status = MembershipStatus.cancelled))
    }

    private fun joinOrRequestPayment(club: Club, userId: UUID, source: String): JoinResult {
        val clubId = club.id
        val price = club.subscriptionPrice

        return if (price > 0) {
            paymentService.createInvoice(userId, clubId)
            log.info("Invoice requested on {} join: clubId={} userId={} price={}", source, clubId, userId, price)
            JoinResult.PendingPayment(PendingPaymentDto(clubId = clubId, priceStars = price))
        } else {
            val membership = freeMembershipActivator.activate(userId, clubId)
            log.info("Joined free club via {}: clubId={} userId={}", source, clubId, userId)
            JoinResult.Joined(mapper.toDto(membership))
        }
    }
}
