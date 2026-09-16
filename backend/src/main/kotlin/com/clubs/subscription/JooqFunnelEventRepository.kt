package com.clubs.subscription

import com.clubs.generated.jooq.tables.references.FUNNEL_EVENT
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
class JooqFunnelEventRepository(private val dsl: DSLContext) : FunnelEventRepository {

    override fun record(step: FunnelStep, userId: UUID?, clubId: UUID?, campaign: String?) {
        dsl.insertInto(FUNNEL_EVENT)
            .set(FUNNEL_EVENT.USER_ID, userId)
            .set(FUNNEL_EVENT.CLUB_ID, clubId)
            .set(FUNNEL_EVENT.KIND, step.kind)
            .set(FUNNEL_EVENT.CAMPAIGN, campaign)
            .execute()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun recordDetached(step: FunnelStep, userId: UUID?, clubId: UUID?) {
        record(step, userId, clubId)
    }
}
