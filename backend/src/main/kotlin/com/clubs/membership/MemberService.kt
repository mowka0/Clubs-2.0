package com.clubs.membership

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.interest.InterestRepository
import com.clubs.reputation.ReputationPolicy
import com.clubs.reputation.ReputationRepository
import com.clubs.reputation.TrustService
import com.clubs.user.MemberProfileDto
import com.clubs.user.UserRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class MemberService(
    private val membershipRepository: MembershipRepository,
    private val userRepository: UserRepository,
    private val reputationRepository: ReputationRepository,
    private val trustService: TrustService,
    private val interestRepository: InterestRepository,
    private val mapper: MembershipMapper
) {

    fun getClubMembers(
        clubId: UUID,
        callerId: UUID,
        includeCancelled: Boolean = false
    ): List<MemberListItemDto> {
        if (!membershipRepository.isMember(callerId, clubId)) {
            throw ForbiddenException("Not a member of this club")
        }
        // Per-member Trust comes from one batch ledger read. Order: organizer first (they do not
        // accrue Trust in their own club — anti-farm rule 1 — so by Trust alone they'd sort last),
        // then everyone else by the DISPLAYED Trust, newcomers / suppressed rows at the bottom.
        val trustByUser = trustService.trustForClubMembers(clubId)
        return membershipRepository.findClubMembersWithUserInfo(clubId, includeCancelled)
            .map { mapper.toMemberListItemDto(it, trustByUser[it.userId]) }
            .sortedWith(
                compareByDescending<MemberListItemDto> { it.role == "organizer" }
                    .thenByDescending { it.trust ?: Int.MIN_VALUE }
            )
    }

    fun getMemberProfile(clubId: UUID, userId: UUID, callerId: UUID): MemberProfileDto {
        if (!membershipRepository.isMember(callerId, clubId)) {
            throw ForbiddenException("Not a member of this club")
        }
        val user = userRepository.findById(userId) ?: throw NotFoundException("User not found")
        val membership = membershipRepository.findByUserAndClub(userId, clubId)
        val reputation = reputationRepository.findByUserAndClub(userId, clubId)
        // "Право на ошибку": show the real index only once a track record exists; below
        // the threshold (or no row, or owner in own club) the whole block is suppressed
        // and the frontend renders "Новичок" / the organizer framing (by role).
        val show = reputation != null && ReputationPolicy.isShown(reputation.outcomeCount)
        // One ledger read powers both per-club rings (Trust + skladchina); null below the gate.
        val summary = if (show) trustService.clubSummary(userId, clubId) else null
        return MemberProfileDto(
            userId = userId,
            clubId = clubId,
            firstName = user.firstName,
            username = user.telegramUsername,
            avatarUrl = user.avatarUrl,
            bio = user.bio,
            interests = interestRepository.findUserInterestNames(userId),
            role = (membership?.role ?: MembershipRole.member).literal,
            trust = summary?.trust,
            promiseFulfillmentPct = if (show) reputation!!.promiseFulfillmentPct else null,
            totalConfirmations = if (show) reputation!!.totalConfirmations else null,
            totalAttendances = if (show) reputation!!.totalAttendances else null,
            spontaneityCount = if (show) reputation!!.spontaneityCount else null,
            skladchinaPaid = summary?.skladchinaPaid,
            skladchinaTotal = summary?.skladchinaTotal
        )
    }
}
