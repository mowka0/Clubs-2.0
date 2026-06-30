package com.clubs.membership

import com.clubs.application.ApplicationRepository
import com.clubs.award.AwardService
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.interest.InterestRepository
import com.clubs.reputation.ReputationPolicy
import com.clubs.reputation.ReputationRepository
import com.clubs.reputation.TrustService
import com.clubs.user.MemberProfileDto
import com.clubs.user.UserRepository
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

// Access expiring within this window surfaces in «Скоро закончится» + triggers the red-dot badge.
private const val EXPIRING_SOON_DAYS = 7L

@Service
class MemberService(
    private val membershipRepository: MembershipRepository,
    private val userRepository: UserRepository,
    private val reputationRepository: ReputationRepository,
    private val trustService: TrustService,
    private val interestRepository: InterestRepository,
    private val awardService: AwardService,
    private val applicationRepository: ApplicationRepository,
    private val mapper: MembershipMapper
) {

    fun getClubMembers(clubId: UUID, callerId: UUID): List<MemberListItemDto> {
        // One lookup gives both the access gate and the viewer's role. status==active mirrors
        // MembershipAccess.hasAccess (a frozen viewer has no access). The organizer additionally sees
        // each member's access state + paid-through date (de-Stars dashboard); regular members don't.
        val caller = membershipRepository.findByUserAndClub(callerId, clubId)
        if (caller == null || caller.status != MembershipStatus.active) {
            throw ForbiddenException("Not a member of this club")
        }
        val forOrganizer = caller.role == MembershipRole.organizer
        // Per-member Trust comes from one batch ledger read. Order: organizer first (they do not
        // accrue Trust in their own club — anti-farm rule 1 — so by Trust alone they'd sort last),
        // then everyone else by the DISPLAYED Trust, newcomers / suppressed rows at the bottom.
        val trustByUser = trustService.trustForClubMembers(clubId)
        // Club-local awards for the roster chips (R3), one query grouped per member — no N+1.
        val awardsByUser = awardService.getClubAwardsByMember(clubId)
        return membershipRepository.findClubMembersWithUserInfo(clubId, includeFrozen = forOrganizer)
            .map { mapper.toMemberListItemDto(it, trustByUser[it.userId], awardsByUser[it.userId] ?: emptyList(), forOrganizer) }
            .sortedWith(
                compareByDescending<MemberListItemDto> { it.role == "organizer" }
                    .thenByDescending { it.trust ?: Int.MIN_VALUE }
            )
    }

    /**
     * Red-dot feed for [clubId] (organizer-only, gated by @RequiresOrganizer on the controller):
     * members whose paid access ends within the next week + members frozen pending a first dues
     * confirmation. The dot lights when either count is > 0.
     */
    fun getAttention(clubId: UUID): MemberAttentionDto {
        val now = OffsetDateTime.now()
        val clubs = listOf(clubId)
        return MemberAttentionDto(
            expiringSoon = membershipRepository.countExpiringSoonByClubs(clubs, now, now.plusDays(EXPIRING_SOON_DAYS)),
            awaitingDues = membershipRepository.countFrozenByClubs(clubs)
        )
    }

    /**
     * Cross-club «Ждут оплаты» for [callerId]: every `frozen` member across the clubs they own, so the
     * organizer confirms dues from «Мои клубы» without entering each club. Non-owners get an empty list
     * (the query filters by `clubs.owner_id`), so no authz gate is needed on the endpoint.
     */
    fun getOrganizerAwaitingDues(callerId: UUID): List<OrganizerDuesMemberDto> =
        membershipRepository.findFrozenMembersByOwner(callerId).map(mapper::toOrganizerDuesDto)

    fun getMemberProfile(clubId: UUID, userId: UUID, callerId: UUID): MemberProfileDto {
        val caller = membershipRepository.findByUserAndClub(callerId, clubId)
        if (caller == null || caller.status != MembershipStatus.active) {
            throw ForbiddenException("Not a member of this club")
        }
        val callerIsOrganizer = caller.role == MembershipRole.organizer
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
            // Club-local awards (S2) — public to every member (R3), so no organizer gate here.
            awards = awardService.getMemberAwards(clubId, userId),
            role = (membership?.role ?: MembershipRole.member).literal,
            trust = summary?.trust,
            promiseFulfillmentPct = if (show) reputation!!.promiseFulfillmentPct else null,
            totalConfirmations = if (show) reputation!!.totalConfirmations else null,
            totalAttendances = if (show) reputation!!.totalAttendances else null,
            spontaneityCount = if (show) reputation!!.spontaneityCount else null,
            skladchinaPaid = summary?.skladchinaPaid,
            skladchinaTotal = summary?.skladchinaTotal,
            // Organizer-only: the member's paid access window end. null for regular viewers and for
            // free memberships (no expiry). Drives «Подписка активна до …» on the organizer card.
            subscriptionExpiresAt = if (callerIsOrganizer) membership?.subscriptionExpiresAt else null,
            // Organizer-only: the private note (member admin S1). null for regular viewers.
            organizerNote = if (callerIsOrganizer) membership?.organizerNote else null,
            // Organizer-only: the member's dues claim + payment screenshot (de-Stars). null for regular viewers.
            duesClaimedAt = if (callerIsOrganizer) membership?.duesClaimedAt else null,
            duesClaimMethod = if (callerIsOrganizer) membership?.duesClaimMethod else null,
            duesProofUrl = if (callerIsOrganizer) membership?.duesProofUrl else null,
            // Organizer-only: the member's join-application answer (closed clubs). null for open clubs / no question.
            applicationAnswer = if (callerIsOrganizer) {
                applicationRepository.findActiveByUserAndClub(userId, clubId)?.answerText
            } else null
        )
    }
}
