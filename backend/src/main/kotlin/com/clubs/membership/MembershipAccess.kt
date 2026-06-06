package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import org.jooq.Condition
import java.time.OffsetDateTime

/**
 * Canonical "this membership currently grants club access" predicate: `active`,
 * OR `cancelled` but still within the paid period. `expired`/`grace_period` have
 * no access.
 *
 * Single source of truth so the predicate can't drift between call sites — that
 * drift is exactly what let a cancelled-but-still-paid member vote on an event
 * (and receive its DM) while the same event never appeared in their activities
 * feed. Used by JooqMembershipRepository (isMember / isActiveMemberInActiveClub /
 * findMemberTelegramIds) and JooqEventRepository (findMyFeed).
 *
 * NOTE: grace_period is intentionally excluded, matching the enforced access
 * model. If the product decides grace_period retains access (PRD §4.7.3.3),
 * change it here once and every call site follows.
 */
object MembershipAccess {
    fun hasAccess(now: OffsetDateTime): Condition =
        MEMBERSHIPS.STATUS.eq(MembershipStatus.active)
            .or(
                MEMBERSHIPS.STATUS.eq(MembershipStatus.cancelled)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.greaterThan(now))
            )
}
