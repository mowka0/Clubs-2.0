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
import com.clubs.reputation.ExitObligation
import com.clubs.reputation.ReputationService
import com.clubs.reputation.TrustService
import com.clubs.skladchina.SkladchinaRepository
import java.time.OffsetDateTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MembershipService(
    private val membershipRepository: MembershipRepository,
    private val clubRepository: ClubRepository,
    private val mapper: MembershipMapper,
    private val membershipActivator: MembershipActivator,
    private val eventResponseRepository: EventResponseRepository,
    private val skladchinaRepository: SkladchinaRepository,
    private val applicationRepository: ApplicationRepository,
    private val trustService: TrustService,
    private val reputationService: ReputationService
) {

    private val log = LoggerFactory.getLogger(MembershipService::class.java)

    @Transactional
    fun joinOpenClub(clubId: UUID, userId: UUID): MembershipDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")

        if (club.accessType != AccessType.`open`) {
            throw ValidationException("Club is not open for joining")
        }

        val existing = membershipRepository.findActiveByUserAndClub(userId, clubId)
        if (existing != null) throw ConflictException("Already a member")

        val activeCount = membershipRepository.countActiveByClubId(clubId)
        if (activeCount >= club.memberLimit) throw ValidationException("Club is full")

        return joinClubMembership(club, userId, "open")
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
     *    (event RSVPs + skladchina participation), flip membership to
     *    `cancelled`. Owner cannot leave.
     *  - **Paid** (subscriptionPrice > 0): just flip membership to `cancelled`.
     *    `subscription_expires_at` is preserved — user keeps access until
     *    expire. Cascade is intentionally skipped: existing
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

        return if (hasActivePaidAccess(club, membership)) {
            leavePaidClub(membership, clubId, userId)
        } else {
            leaveFreeClub(membership, clubId, userId)
        }
    }

    /**
     * "Leave" is a soft subscription-cancel (no penalty, no cascade) for anyone who still holds a
     * paid period — even if the club has since been switched to free. Only a genuinely free
     * membership (no active paid period) takes the hard exit-with-obligations path. Routing on the
     * membership's `subscription_expires_at` (not just the club's current price) is load-bearing: a
     * paid member of a paid→free-switched club would otherwise be penalized and stripped of bookings
     * they can still attend until their subscription expires.
     */
    private fun hasActivePaidAccess(club: Club, membership: Membership): Boolean =
        club.subscriptionPrice > 0 || (membership.subscriptionExpiresAt?.isAfter(OffsetDateTime.now()) == true)

    @Transactional
    fun joinByInviteCode(code: String, userId: UUID): MembershipDto {
        val club = clubRepository.findByInviteCode(code) ?: throw NotFoundException("Invite link not found")
        val clubId = club.id

        val existing = membershipRepository.findActiveByUserAndClub(userId, clubId)
        if (existing != null) throw ConflictException("Already a member")

        val activeCount = membershipRepository.countActiveByClubId(clubId)
        if (activeCount >= club.memberLimit) throw ValidationException("Club is full")

        return joinClubMembership(club, userId, "invite")
    }

    fun getUserMemberships(userId: UUID): List<MembershipDto> =
        membershipRepository.findByUserId(userId).map(mapper::toDto)

    /**
     * The authenticated user's reputation for the Profile tab: the all-history global aggregate
     * ("надёжен в N из M клубов") + per-club Trust, split into active clubs and "История" (left
     * clubs that still carry a track record). Trust is computed on-read from the ledger
     * ([TrustService]); the club list + active/История split comes from memberships.
     */
    @Transactional(readOnly = true)
    fun getMyReputation(userId: UUID): MyReputationDto {
        val trust = trustService.computeForUser(userId)
        val trustByClub = trust.perClub.associate { it.clubId to it.trust }
        val (active, history) = membershipRepository.findUserClubsWithReputation(userId).partition { it.active }
        return MyReputationDto(
            global = GlobalTrustDto(
                reliableClubs = trust.global.reliableClubs,
                trackRecordClubs = trust.global.trackRecordClubs,
                score = trust.global.score
            ),
            activeClubs = active.map { mapper.toUserClubReputationDto(it, trustByClub[it.clubId]) },
            historyClubs = history.map { mapper.toUserClubReputationDto(it, trustByClub[it.clubId]) }
        )
    }

    private fun leavePaidClub(membership: Membership, clubId: UUID, userId: UUID): MembershipDto {
        membershipRepository.cancel(membership.id)
        val cascadedApplications = applicationRepository.deleteActiveByUserAndClub(userId, clubId)
        log.info(
            "User cancelled paid subscription via /leave: clubId={} userId={} cascadedApplications={}",
            clubId, userId, cascadedApplications
        )
        return mapper.toDto(membership.copy(status = MembershipStatus.cancelled))
    }

    /**
     * Free-club leave is also "exit-with-obligations" (P1b hole B): open obligations are
     * enumerated and penalized BEFORE the cascade deletes their source rows, otherwise leaving a
     * confirmed booking behind would be free. Penalty (internal) + cascade + waitlist promotion
     * all run in the single [leaveClub] transaction so they commit atomically.
     */
    private fun leaveFreeClub(membership: Membership, clubId: UUID, userId: UUID): MembershipDto {
        // Enumerate BEFORE any delete — the cascade below removes exactly these source rows.
        val eventObligations = eventResponseRepository.findConfirmedActiveEventObligations(userId, clubId)
        val skladchinaObligations = skladchinaRepository.findPendingReputationObligations(userId, clubId)

        // Penalties first: a confirmed booking → no_show (−200), a pending reputation skladchina →
        // skladchina_expired (−40). Idempotent via the ledger UNIQUE — a later natural outcome for
        // the same source collides and the exit row wins, so a double leave never double-counts.
        reputationService.penalizeExit(
            userId, clubId,
            eventObligations.map { ExitObligation(it.eventId, it.eventDatetime) },
            skladchinaObligations.map { ExitObligation(it.skladchinaId, it.deadline) }
        )

        // Hold the per-event slot lock (sorted → deadlock-free, released on commit) across the
        // delete + promotion so waitlist promotion never races a concurrent confirm/decline.
        val freedEventIds = eventObligations.map { it.eventId }.sorted()
        freedEventIds.forEach { eventResponseRepository.lockEventSlots(it) }

        val cascadedSkladchinas = skladchinaRepository.deleteParticipantFromActiveSkladchinasInClub(userId, clubId)
        val cascadedEventResponses = eventResponseRepository.deleteByUserAndClubAndActiveEvents(userId, clubId)
        // Each vacated confirmed slot promotes the next waitlisted member so leaving doesn't shrink the roster.
        val promotedWaitlist = freedEventIds.count { eventResponseRepository.promoteFirstWaitlisted(it) }
        val cascadedApplications = applicationRepository.deleteActiveByUserAndClub(userId, clubId)

        membershipRepository.cancel(membership.id)

        log.info(
            "User left free club: clubId={} userId={} eventNoShows={} skladchinaExpiries={} promotedWaitlist={} " +
                "cascadedSkladchinas={} cascadedEventResponses={} cascadedApplications={}",
            clubId, userId, eventObligations.size, skladchinaObligations.size, promotedWaitlist,
            cascadedSkladchinas, cascadedEventResponses, cascadedApplications
        )
        return mapper.toDto(membership.copy(status = MembershipStatus.cancelled))
    }

    /**
     * Pre-leave preview for the confirm dialog: how many open obligations the caller would break
     * by leaving (and thus lose reliability for). Penalty magnitudes stay server-side (internal,
     * H8) — only counts are returned. Paid clubs keep obligations valid until expire, so they
     * break nothing (zeros).
     */
    @Transactional(readOnly = true)
    fun getLeavePreview(clubId: UUID, userId: UUID): LeavePreviewDto {
        val membership = membershipRepository.findActiveByUserAndClub(userId, clubId)
            ?: throw NotFoundException("Membership not found")
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId == userId) throw ValidationException("Owner cannot leave the club")
        // Same routing as leaveClub: anyone with an active paid period takes the soft cancel
        // (no obligations broken until expire), so the preview is all zeros.
        if (hasActivePaidAccess(club, membership)) return LeavePreviewDto(0, 0, 0)

        val events = eventResponseRepository.findConfirmedActiveEventObligations(userId, clubId).size
        val skladchinas = skladchinaRepository.findPendingReputationObligations(userId, clubId).size
        return LeavePreviewDto(
            eventObligations = events,
            skladchinaObligations = skladchinas,
            totalObligations = events + skladchinas
        )
    }

    /**
     * Joins the validated club (de-Stars, Slice 2). A paid club lands the member in `frozen` — they
     * belong and hold a slot, but have no content access until the organizer confirms the off-platform
     * dues (AccessGateService.markDuesPaid). A free club joins straight to `active`. No Stars invoice.
     */
    private fun joinClubMembership(club: Club, userId: UUID, source: String): MembershipDto {
        val clubId = club.id
        val membership = if (club.subscriptionPrice > 0) {
            membershipActivator.activateFrozen(userId, clubId)
        } else {
            membershipActivator.activateFree(userId, clubId)
        }
        log.info(
            "Joined {} club via {}: clubId={} userId={} status={}",
            if (club.subscriptionPrice > 0) "paid" else "free", source, clubId, userId, membership.status.literal
        )
        return mapper.toDto(membership)
    }
}
