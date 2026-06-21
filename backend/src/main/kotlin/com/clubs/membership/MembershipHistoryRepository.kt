package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipEvent
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Append-only log of membership-lifecycle transitions (joined / left / rejoined / expired).
 * Written from [JooqMembershipRepository] in the same transaction as the status change so the log
 * can never silently miss a transition. Reads (retention, tenure, L3) land in later slices.
 */
interface MembershipHistoryRepository {
    fun record(userId: UUID, clubId: UUID, event: MembershipEvent, occurredAt: OffsetDateTime)
}
