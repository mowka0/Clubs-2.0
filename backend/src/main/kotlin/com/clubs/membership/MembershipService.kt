package com.clubs.membership

import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.payment.PaymentService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MembershipService(
    private val membershipRepository: MembershipRepository,
    private val clubRepository: ClubRepository,
    private val paymentService: PaymentService,
    private val mapper: MembershipMapper
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

    private fun joinOrRequestPayment(club: Club, userId: UUID, source: String): JoinResult {
        val clubId = club.id
        val price = club.subscriptionPrice

        return if (price > 0) {
            paymentService.createInvoice(userId, clubId)
            log.info("Invoice requested on {} join: clubId={} userId={} price={}", source, clubId, userId, price)
            JoinResult.PendingPayment(PendingPaymentDto(clubId = clubId, priceStars = price))
        } else {
            val membership = membershipRepository.create(userId, clubId)
            clubRepository.incrementMemberCount(clubId)
            log.info("Joined free club via {}: clubId={} userId={}", source, clubId, userId)
            JoinResult.Joined(mapper.toDto(membership))
        }
    }
}
