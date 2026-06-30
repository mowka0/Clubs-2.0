package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import org.jooq.Condition

/**
 * Canonical "this membership currently grants club CONTENT access" predicate:
 * status is `active`. Access is organizer-controlled (de-Stars, Slice 2): the
 * organizer admits a member (`active`) or gates them pending an off-platform dues
 * payment (`frozen`, no access). `frozen` / `grace_period` / `expired` /
 * `cancelled` all have no access.
 *
 * Single source of truth so the predicate can't drift between call sites — that
 * drift is exactly what let a member vote on an event (and receive its DM) while
 * the same event never appeared in their activities feed. Used by
 * JooqMembershipRepository (isMember / isActiveMemberInActiveClub /
 * findMemberTelegramIds) and JooqEventRepository (findMyFeed).
 *
 * NOTE: this predicate is CONTENT access only. "Belongs to the club" (member
 * rosters, my-clubs list, find-for-management, slot occupancy) is a WIDER set that
 * also includes `frozen` — those predicates live inline in JooqMembershipRepository
 * and are NOT this one. See the status×surface matrix in docs/modules/payment-v2.md.
 */
object MembershipAccess {
    fun hasAccess(): Condition = MEMBERSHIPS.STATUS.eq(MembershipStatus.active)
}
