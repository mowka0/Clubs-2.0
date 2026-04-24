package com.clubs.membership

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.records.ClubsRecord
import com.clubs.generated.jooq.tables.records.MembershipsRecord
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.payment.PaymentService
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MembershipService(
    private val membershipRepository: MembershipRepository,
    private val clubRepository: ClubRepository,
    private val paymentService: PaymentService,
    private val dsl: DSLContext
) {

    private val log = LoggerFactory.getLogger(MembershipService::class.java)

    @Transactional
    fun joinOpenClub(clubId: UUID, userId: UUID): JoinResult {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")

        if (club.accessType != AccessType.`open`) {
            throw ValidationException("Club is not open for joining")
        }

        val existing = membershipRepository.findByUserAndClub(userId, clubId)
        if (existing != null) throw ConflictException("Already a member")

        val activeCount = membershipRepository.countActiveByClubId(clubId)
        if (activeCount >= club.memberLimit) throw ValidationException("Club is full")

        return joinOrRequestPayment(club, userId, "open")
    }

    fun cancelMembership(clubId: UUID, userId: UUID): MembershipDto {
        val membership = membershipRepository.findByUserAndClub(userId, clubId)
            ?: throw NotFoundException("Membership not found")
        if (membership.status == MembershipStatus.cancelled) throw ValidationException("Membership already cancelled")

        dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.cancelled)
            .where(MEMBERSHIPS.USER_ID.eq(userId).and(MEMBERSHIPS.CLUB_ID.eq(clubId)))
            .execute()

        log.info("Membership cancelled: clubId={} userId={}", clubId, userId)
        return membership.toDto().copy(status = "cancelled")
    }

    @Transactional
    fun joinByInviteCode(code: String, userId: UUID): JoinResult {
        val club = clubRepository.findByInviteCode(code) ?: throw NotFoundException("Invite link not found")
        val clubId = club.id!!

        val existing = membershipRepository.findByUserAndClub(userId, clubId)
        if (existing != null) throw ConflictException("Already a member")

        val activeCount = membershipRepository.countActiveByClubId(clubId)
        if (activeCount >= (club.memberLimit ?: 0)) throw ValidationException("Club is full")

        return joinOrRequestPayment(club, userId, "invite")
    }

    private fun joinOrRequestPayment(club: ClubsRecord, userId: UUID, source: String): JoinResult {
        val clubId = club.id!!
        val price = club.subscriptionPrice ?: 0

        return if (price > 0) {
            paymentService.createInvoice(userId, clubId)
            log.info("Invoice requested on {} join: clubId={} userId={} price={}", source, clubId, userId, price)
            JoinResult.PendingPayment(PendingPaymentDto(clubId = clubId, priceStars = price))
        } else {
            val membership = membershipRepository.create(userId, clubId)
            clubRepository.incrementMemberCount(clubId)
            log.info("Joined free club via {}: clubId={} userId={}", source, clubId, userId)
            JoinResult.Joined(membership.toDto())
        }
    }
}

fun MembershipsRecord.toDto() = MembershipDto(
    id = id!!,
    userId = userId,
    clubId = clubId,
    status = status?.literal ?: "active",
    role = role?.literal ?: "member",
    joinedAt = joinedAt,
    subscriptionExpiresAt = subscriptionExpiresAt
)
