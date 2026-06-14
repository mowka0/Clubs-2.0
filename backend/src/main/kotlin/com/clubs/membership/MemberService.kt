package com.clubs.membership

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.reputation.ReputationPolicy
import com.clubs.reputation.ReputationRepository
import com.clubs.reputation.TrustService
import com.clubs.reputation.XpService
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
    private val xpService: XpService,
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
        val members = membershipRepository.findClubMembersWithUserInfo(clubId, includeCancelled)
        // GLOBAL level name per member, one batch query (no N+1), shown alongside per-club Trust.
        val levelByUser = xpService.publicLevelNames(members.map { it.userId })
        return members
            .map { mapper.toMemberListItemDto(it, trustByUser[it.userId], levelByUser[it.userId]) }
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
        val trust = if (show) trustService.trustForUserInClub(userId, clubId) else null
        // Global level — independent of this club's track record (a newcomer here may carry a level
        // earned elsewhere), so it is computed separately from the per-club `show` gate.
        val levelName = xpService.publicLevelNames(listOf(userId))[userId]
        return MemberProfileDto(
            userId = userId,
            clubId = clubId,
            firstName = user.firstName,
            username = user.telegramUsername,
            avatarUrl = user.avatarUrl,
            role = (membership?.role ?: MembershipRole.member).literal,
            trust = trust,
            promiseFulfillmentPct = if (show) reputation!!.promiseFulfillmentPct else null,
            totalConfirmations = if (show) reputation!!.totalConfirmations else null,
            totalAttendances = if (show) reputation!!.totalAttendances else null,
            spontaneityCount = if (show) reputation!!.spontaneityCount else null,
            levelName = levelName
        )
    }
}
