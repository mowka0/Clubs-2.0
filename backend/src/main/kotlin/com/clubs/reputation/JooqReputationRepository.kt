package com.clubs.reputation

import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.ReputationAxis
import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.REPUTATION_LEDGER
import com.clubs.generated.jooq.tables.references.USER_CLUB_REPUTATION
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqReputationRepository(
    private val dsl: DSLContext,
    private val mapper: ReputationMapper
) : ReputationRepository {

    override fun findByUserAndClub(userId: UUID, clubId: UUID): Reputation? =
        dsl.selectFrom(USER_CLUB_REPUTATION)
            .where(USER_CLUB_REPUTATION.USER_ID.eq(userId).and(USER_CLUB_REPUTATION.CLUB_ID.eq(clubId)))
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun findLatestByUserId(userId: UUID): Reputation? =
        dsl.selectFrom(USER_CLUB_REPUTATION)
            .where(USER_CLUB_REPUTATION.USER_ID.eq(userId))
            .orderBy(USER_CLUB_REPUTATION.UPDATED_AT.desc())
            .limit(1)
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun aggregateByUserIds(userIds: Collection<UUID>): Map<UUID, PeerStatsAggregate> {
        if (userIds.isEmpty()) return emptyMap()

        val clubCount = DSL.count(USER_CLUB_REPUTATION.CLUB_ID)
        val confirmSum = DSL.coalesce(DSL.sum(USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS), DSL.`val`(0))
        val attendSum = DSL.coalesce(DSL.sum(USER_CLUB_REPUTATION.TOTAL_ATTENDANCES), DSL.`val`(0))

        return dsl.select(USER_CLUB_REPUTATION.USER_ID, clubCount, confirmSum, attendSum)
            .from(USER_CLUB_REPUTATION)
            .where(USER_CLUB_REPUTATION.USER_ID.`in`(userIds))
            .groupBy(USER_CLUB_REPUTATION.USER_ID)
            .fetch()
            .associate { record ->
                val userId = record.get(USER_CLUB_REPUTATION.USER_ID)!!
                userId to PeerStatsAggregate(
                    memberClubCount = record.get(clubCount) ?: 0,
                    totalConfirmations = record.get(confirmSum).toInt(),
                    totalAttendances = record.get(attendSum).toInt()
                )
            }
    }

    // --- Ledger pipeline ---

    override fun claimEvent(eventId: UUID): Boolean =
        dsl.update(EVENTS)
            .set(EVENTS.REPUTATION_PROCESSED, true)
            .where(EVENTS.ID.eq(eventId).and(EVENTS.REPUTATION_PROCESSED.isFalse))
            .execute() > 0

    override fun findPendingFinalizedEventIds(): List<UUID> =
        dsl.select(EVENTS.ID)
            .from(EVENTS)
            .where(
                EVENTS.ATTENDANCE_FINALIZED.isTrue
                    .and(EVENTS.ATTENDANCE_MARKED.isTrue)
                    .and(EVENTS.REPUTATION_PROCESSED.isFalse)
            )
            .fetch(EVENTS.ID)
            .filterNotNull()

    override fun findEventContext(eventId: UUID): EventReputationContext? =
        dsl.select(EVENTS.CLUB_ID, CLUBS.OWNER_ID, EVENTS.EVENT_DATETIME)
            .from(EVENTS)
            .join(CLUBS).on(CLUBS.ID.eq(EVENTS.CLUB_ID))
            .where(EVENTS.ID.eq(eventId))
            .fetchOne { r ->
                EventReputationContext(
                    clubId = r.get(EVENTS.CLUB_ID)!!,
                    ownerId = r.get(CLUBS.OWNER_ID)!!,
                    eventDatetime = r.get(EVENTS.EVENT_DATETIME)!!
                )
            }

    override fun findConfirmedResponses(eventId: UUID): List<ConfirmedResponse> =
        dsl.select(EVENT_RESPONSES.USER_ID, EVENT_RESPONSES.STAGE_1_VOTE, EVENT_RESPONSES.ATTENDANCE)
            .from(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.FINAL_STATUS.eq(FinalStatus.confirmed))
            )
            .fetch { r ->
                ConfirmedResponse(
                    userId = r.get(EVENT_RESPONSES.USER_ID)!!,
                    stage1Vote = r.get(EVENT_RESPONSES.STAGE_1_VOTE),
                    attendance = r.get(EVENT_RESPONSES.ATTENDANCE)
                )
            }

    override fun appendLedgerIfAbsent(entries: List<LedgerEntry>) {
        entries.forEach { e ->
            dsl.insertInto(REPUTATION_LEDGER)
                .set(REPUTATION_LEDGER.USER_ID, e.userId)
                .set(REPUTATION_LEDGER.CLUB_ID, e.clubId)
                .set(REPUTATION_LEDGER.AXIS, e.axis)
                .set(REPUTATION_LEDGER.KIND, e.kind)
                .set(REPUTATION_LEDGER.POINTS, e.points)
                .set(REPUTATION_LEDGER.OCCURRED_AT, e.occurredAt)
                .set(REPUTATION_LEDGER.SOURCE_TYPE, e.sourceType)
                .set(REPUTATION_LEDGER.SOURCE_ID, e.sourceId)
                .onConflict(REPUTATION_LEDGER.USER_ID, REPUTATION_LEDGER.SOURCE_TYPE, REPUTATION_LEDGER.SOURCE_ID)
                .doNothing()
                .execute()
        }
    }

    override fun recompute(userId: UUID, clubId: UUID) {
        val l = REPUTATION_LEDGER
        val attendanceRow = l.AXIS.eq(ReputationAxis.attendance)
        val attendedKinds = l.KIND.`in`(ReputationKind.ironclad, ReputationKind.spontaneous)

        val rec = dsl.select(
            DSL.sum(l.POINTS),
            DSL.count().filterWhere(attendanceRow),
            DSL.count().filterWhere(attendanceRow.and(attendedKinds)),
            DSL.count().filterWhere(l.KIND.eq(ReputationKind.spontaneous)),
            DSL.count(),
            DSL.max(l.OCCURRED_AT)
        )
            .from(l)
            .where(l.USER_ID.eq(userId).and(l.CLUB_ID.eq(clubId)))
            .fetchOne()!!

        val outcome = rec.value5() ?: 0
        // No ledger rows for this (user, club): nothing to cache. In the live path
        // recompute is only called right after an append, so outcome >= 1; this
        // guard only matters for defensive / future delete paths.
        if (outcome == 0) return

        val reliability = (rec.value1() ?: BigDecimal.ZERO).toInt()
        val confirmations = rec.value2() ?: 0
        val attendances = rec.value3() ?: 0
        val spontaneity = rec.value4() ?: 0
        val updatedAt = rec.value6() ?: OffsetDateTime.now()
        val fulfillmentPct = if (confirmations > 0) {
            BigDecimal(attendances * 100).divide(BigDecimal(confirmations), 2, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }

        dsl.insertInto(USER_CLUB_REPUTATION)
            .set(USER_CLUB_REPUTATION.USER_ID, userId)
            .set(USER_CLUB_REPUTATION.CLUB_ID, clubId)
            .set(USER_CLUB_REPUTATION.RELIABILITY_INDEX, reliability)
            .set(USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT, fulfillmentPct)
            .set(USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS, confirmations)
            .set(USER_CLUB_REPUTATION.TOTAL_ATTENDANCES, attendances)
            .set(USER_CLUB_REPUTATION.SPONTANEITY_COUNT, spontaneity)
            .set(USER_CLUB_REPUTATION.OUTCOME_COUNT, outcome)
            .set(USER_CLUB_REPUTATION.UPDATED_AT, updatedAt)
            .onConflict(USER_CLUB_REPUTATION.USER_ID, USER_CLUB_REPUTATION.CLUB_ID)
            .doUpdate()
            .set(USER_CLUB_REPUTATION.RELIABILITY_INDEX, reliability)
            .set(USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT, fulfillmentPct)
            .set(USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS, confirmations)
            .set(USER_CLUB_REPUTATION.TOTAL_ATTENDANCES, attendances)
            .set(USER_CLUB_REPUTATION.SPONTANEITY_COUNT, spontaneity)
            .set(USER_CLUB_REPUTATION.OUTCOME_COUNT, outcome)
            .set(USER_CLUB_REPUTATION.UPDATED_AT, updatedAt)
            .execute()
    }
}
