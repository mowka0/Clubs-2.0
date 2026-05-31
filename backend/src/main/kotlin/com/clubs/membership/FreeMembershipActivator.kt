package com.clubs.membership

import com.clubs.club.ClubRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Encapsulates the "create-or-reactivate" lifecycle for a **free-club**
 * membership. The same flow is needed in three places:
 *
 *  1. [MembershipService.joinOpenClub] / `joinByInviteCode` — free branch.
 *  2. [com.clubs.application.ApplicationService.approveApplication] — free branch.
 *  3. [com.clubs.application.ApplicationService.completeFreeMembership] — stuck-app recovery.
 *
 * Without this helper each call site has to choose between:
 *   - `findActiveByUserAndClub` → `create` — explodes with
 *     `DuplicateKeyException` if a `cancelled` / `expired` row already
 *     exists for `(user_id, club_id)` (UNIQUE constraint).
 *   - `findByUserAndClub` → `create` — same explosion.
 *
 * The contract is: caller guarantees the club is free and has been validated
 * (member-limit, ownership, etc.). The helper picks the correct branch:
 *  - no row at all → INSERT + increment `clubs.member_count`.
 *  - row exists with status active / grace_period → caller's bug; throw IllegalState
 *    (caller must check this BEFORE invoking — different call sites surface
 *    different HTTP errors here, e.g. 409 vs 400).
 *  - row exists with status cancelled / expired → reactivate; **don't**
 *    increment `member_count` (it was incremented on the original join and
 *    is left alone on cancel; double-bumping skews capacity).
 */
@Component
class FreeMembershipActivator(
    private val membershipRepository: MembershipRepository,
    private val clubRepository: ClubRepository
) {

    private val log = LoggerFactory.getLogger(FreeMembershipActivator::class.java)

    /**
     * @throws IllegalStateException if an active / grace_period membership already exists.
     *   Callers MUST check `findActiveByUserAndClub` first and surface the appropriate
     *   business error (ConflictException / ValidationException with context-specific message).
     */
    fun activate(userId: UUID, clubId: UUID): Membership {
        val existing = membershipRepository.findByUserAndClub(userId, clubId)
        return when {
            existing == null -> {
                val created = membershipRepository.create(userId, clubId)
                clubRepository.incrementMemberCount(clubId)
                log.info("Free membership created: userId={} clubId={}", userId, clubId)
                created
            }
            existing.status.isAlive() -> {
                // Defence in depth — callers should have rejected this case already.
                throw IllegalStateException("Active membership already exists: id=${existing.id}")
            }
            else -> {
                val reactivated = membershipRepository.reactivateFree(existing.id)
                log.info(
                    "Free membership reactivated: id={} userId={} clubId={} previousStatus={}",
                    existing.id, userId, clubId, existing.status.literal
                )
                reactivated
            }
        }
    }

    private fun com.clubs.generated.jooq.enums.MembershipStatus.isAlive(): Boolean =
        this == com.clubs.generated.jooq.enums.MembershipStatus.active ||
            this == com.clubs.generated.jooq.enums.MembershipStatus.grace_period
}
