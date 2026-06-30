package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Encapsulates the "create-or-reactivate" lifecycle for a membership. Two target states:
 *
 *  - [activateFree]  → `active`  (free club: instant access).
 *  - [activateFrozen] → `frozen` (paid club, de-Stars Slice 2: the member belongs but access is
 *    gated until the organizer confirms the off-platform dues — see [AccessGateService]).
 *
 * Both share the same branch logic so the create/reactivate decision lives in one place:
 *   - no row at all → INSERT a fresh membership in the target status.
 *   - row exists and is alive (active / frozen / grace_period) → caller's bug; throw IllegalState
 *     (caller must check `findActiveByUserAndClub` BEFORE invoking and surface the right HTTP error).
 *   - row exists and is dead (cancelled / expired) → reactivate into the target status.
 *
 * Contract: the caller guarantees the club has been validated (type, member-limit, ownership).
 */
@Component
class MembershipActivator(
    private val membershipRepository: MembershipRepository
) {

    private val log = LoggerFactory.getLogger(MembershipActivator::class.java)

    /** @throws IllegalStateException if an alive membership already exists (caller must pre-check). */
    fun activateFree(userId: UUID, clubId: UUID): Membership = activate(userId, clubId, frozen = false)

    /** @throws IllegalStateException if an alive membership already exists (caller must pre-check). */
    fun activateFrozen(userId: UUID, clubId: UUID): Membership = activate(userId, clubId, frozen = true)

    private fun activate(userId: UUID, clubId: UUID, frozen: Boolean): Membership {
        val existing = membershipRepository.findByUserAndClub(userId, clubId)
        val membership = when {
            existing == null ->
                if (frozen) membershipRepository.createFrozen(userId, clubId)
                else membershipRepository.create(userId, clubId)
            existing.status.isAlive() ->
                // Defence in depth — callers should have rejected this case already.
                throw IllegalStateException("Active membership already exists: id=${existing.id}")
            else ->
                if (frozen) membershipRepository.reactivateFrozen(existing.id)
                else membershipRepository.reactivateFree(existing.id)
        }
        log.info(
            "Membership {} ({}): userId={} clubId={}",
            if (existing == null) "created" else "reactivated",
            if (frozen) "frozen" else "active", userId, clubId
        )
        return membership
    }

    // "Alive" = the membership still belongs to the club, so it must NOT be silently reactivated as a
    // fresh join. `frozen` (organizer-gated, pending off-platform dues) belongs too.
    private fun MembershipStatus.isAlive(): Boolean =
        this == MembershipStatus.active ||
            this == MembershipStatus.frozen ||
            this == MembershipStatus.grace_period
}
