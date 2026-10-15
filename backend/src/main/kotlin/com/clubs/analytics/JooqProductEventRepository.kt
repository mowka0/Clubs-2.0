package com.clubs.analytics

import com.clubs.generated.jooq.tables.references.PRODUCT_EVENT
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
class JooqProductEventRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) : ProductEventRepository {

    override fun record(
        kind: ProductEventKind,
        userId: UUID?,
        clubId: UUID?,
        campaign: String?,
        telegramId: Long?,
        props: Map<String, Any?>?,
        source: EventSource,
    ) {
        dsl.insertInto(PRODUCT_EVENT)
            .set(PRODUCT_EVENT.USER_ID, userId)
            .set(PRODUCT_EVENT.CLUB_ID, clubId)
            .set(PRODUCT_EVENT.KIND, kind.kind)
            .set(PRODUCT_EVENT.SOURCE, source.literal)
            .set(PRODUCT_EVENT.CAMPAIGN, campaign)
            .set(PRODUCT_EVENT.TELEGRAM_ID, telegramId)
            .set(PRODUCT_EVENT.PROPS, toJsonb(props))
            .execute()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun recordDetached(kind: ProductEventKind, userId: UUID?, clubId: UUID?) {
        record(kind, userId, clubId)
    }

    override fun recordAll(events: List<NewProductEvent>) {
        if (events.isEmpty()) return
        val rows = events.map { e ->
            dsl.newRecord(PRODUCT_EVENT).apply {
                this.userId = e.userId
                this.clubId = e.clubId
                this.kind = e.kind.kind
                this.source = e.source.literal
                this.props = toJsonb(e.props)
            }
        }
        dsl.batchInsert(rows).execute()
    }

    // null-значения выбрасываются, пустые детали — NULL, а не «{}»: так в SQL «есть ли детали» читается по IS NULL.
    private fun toJsonb(props: Map<String, Any?>?): JSONB? =
        props?.filterValues { it != null }?.takeIf { it.isNotEmpty() }?.let { JSONB.jsonb(objectMapper.writeValueAsString(it)) }
}
