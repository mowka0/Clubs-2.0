package com.clubs.membership

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.reputation.ReputationRepository
import com.clubs.user.MemberProfileDto
import com.clubs.user.UserRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.UUID

@Service
class MemberService(
    private val membershipRepository: MembershipRepository,
    private val userRepository: UserRepository,
    private val reputationRepository: ReputationRepository,
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
        return membershipRepository.findClubMembersWithUserInfo(clubId, includeCancelled)
            .map(mapper::toMemberListItemDto)
    }

    fun getMemberProfile(clubId: UUID, userId: UUID, callerId: UUID): MemberProfileDto {
        if (!membershipRepository.isMember(callerId, clubId)) {
            throw ForbiddenException("Not a member of this club")
        }
        val user = userRepository.findById(userId) ?: throw NotFoundException("User not found")
        val reputation = reputationRepository.findByUserAndClub(userId, clubId)
        return MemberProfileDto(
            userId = userId,
            clubId = clubId,
            firstName = user.firstName,
            username = user.telegramUsername,
            avatarUrl = user.avatarUrl,
            reliabilityIndex = reputation?.reliabilityIndex ?: 100,
            promiseFulfillmentPct = reputation?.promiseFulfillmentPct ?: BigDecimal.ZERO,
            totalConfirmations = reputation?.totalConfirmations ?: 0,
            totalAttendances = reputation?.totalAttendances ?: 0
        )
    }
}
