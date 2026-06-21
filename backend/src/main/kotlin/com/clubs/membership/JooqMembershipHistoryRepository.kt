package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipEvent
import com.clubs.generated.jooq.tables.references.MEMBERSHIP_HISTORY
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqMembershipHistoryRepository(private val dsl: DSLContext) : MembershipHistoryRepository {

    override fun record(userId: UUID, clubId: UUID, event: MembershipEvent, occurredAt: OffsetDateTime) {
        dsl.insertInto(MEMBERSHIP_HISTORY)
            .set(MEMBERSHIP_HISTORY.USER_ID, userId)
            .set(MEMBERSHIP_HISTORY.CLUB_ID, clubId)
            .set(MEMBERSHIP_HISTORY.EVENT, event)
            .set(MEMBERSHIP_HISTORY.OCCURRED_AT, occurredAt)
            .execute()
    }
}
