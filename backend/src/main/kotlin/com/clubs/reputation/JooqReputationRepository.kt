package com.clubs.reputation

import com.clubs.generated.jooq.enums.EventStatus
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

    // --- Пайплайн леджера ---

    override fun claimEvent(eventId: UUID): Boolean =
        dsl.update(EVENTS)
            .set(EVENTS.REPUTATION_PROCESSED, true)
            .where(
                EVENTS.ID.eq(eventId)
                    .and(EVENTS.REPUTATION_PROCESSED.isFalse)
                    // Защитная проверка: никогда не помечать событие, которое фактически не
                    // финализировано+отмечено, или которое было отменено (например, каскадом
                    // при удалении клуба) — репутация не должна начисляться по нему, независимо
                    // от того, какой вызывающий код передал нам этот id.
                    .and(EVENTS.ATTENDANCE_FINALIZED.isTrue)
                    .and(EVENTS.ATTENDANCE_MARKED.isTrue)
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
            )
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

    override fun findTrustOutcomesByUser(userId: UUID): List<ClubLedgerOutcome> {
        val l = REPUTATION_LEDGER
        return dsl.select(l.CLUB_ID, l.KIND, l.OCCURRED_AT)
            .from(l)
            .where(l.USER_ID.eq(userId))
            .fetch { r ->
                ClubLedgerOutcome(
                    clubId = r.get(l.CLUB_ID)!!,
                    kind = r.get(l.KIND)!!,
                    occurredAt = r.get(l.OCCURRED_AT)!!
                )
            }
    }

    override fun findOutcomesByUserIds(userIds: Collection<UUID>): Map<UUID, List<ClubLedgerOutcome>> {
        if (userIds.isEmpty()) return emptyMap()
        val l = REPUTATION_LEDGER
        return dsl.select(l.USER_ID, l.CLUB_ID, l.KIND, l.OCCURRED_AT)
            .from(l)
            .where(l.USER_ID.`in`(userIds))
            .fetchGroups({ it.get(l.USER_ID)!! }, { r ->
                ClubLedgerOutcome(
                    clubId = r.get(l.CLUB_ID)!!,
                    kind = r.get(l.KIND)!!,
                    occurredAt = r.get(l.OCCURRED_AT)!!
                )
            })
    }

    override fun findClubMemberOutcomes(clubId: UUID): List<MemberLedgerOutcome> {
        val l = REPUTATION_LEDGER
        return dsl.select(l.USER_ID, l.KIND, l.OCCURRED_AT)
            .from(l)
            .where(l.CLUB_ID.eq(clubId))
            .fetch { r ->
                MemberLedgerOutcome(
                    userId = r.get(l.USER_ID)!!,
                    kind = r.get(l.KIND)!!,
                    occurredAt = r.get(l.OCCURRED_AT)!!
                )
            }
    }

    override fun recompute(userId: UUID, clubId: UUID) {
        // Сериализуем recompute по паре (user, club) между транзакциями, чтобы агрегат леджера
        // читался против стабильного снапшота. Без этого два конкурентных recompute из разных
        // источников (посещаемость события + финансы складчины) для одной и той же пары могли бы
        // не увидеть ещё не закоммиченную строку леджера друг друга под READ COMMITTED и затереть
        // кэш (lost update). Advisory-лок транзакции снимается автоматически при коммите.
        dsl.execute("SELECT pg_advisory_xact_lock(hashtext(?))", "$userId:$clubId")

        val l = REPUTATION_LEDGER
        val attendanceRow = l.AXIS.eq(ReputationAxis.attendance)
        val attendedKinds = l.KIND.`in`(ReputationKind.ironclad, ReputationKind.spontaneous)

        // Trust 0-100 (P1b) классифицирует исходы ПО KIND, а не по points (V18 задним числом
        // проставил устаревшие величины, поэтому points врут на границе V18; kept/broke по kind
        // не зависит от величины). Эти кэшированные счётчики дают read-проекциям знаменатель без
        // затухания, не пересканируя леджер целиком; сам Trust с учётом затухания вычисляется
        // на чтении из occurred_at.
        // neutral = outcome - kept - broke (confirmed_unresolved + исторический skladchina_declined).
        val keptKinds = l.KIND.`in`(ReputationKind.ironclad, ReputationKind.spontaneous, ReputationKind.skladchina_paid)
        val brokeKinds = l.KIND.`in`(ReputationKind.no_show, ReputationKind.spectator, ReputationKind.skladchina_expired)

        val rec = dsl.select(
            DSL.sum(l.POINTS),
            DSL.count().filterWhere(attendanceRow),
            DSL.count().filterWhere(attendanceRow.and(attendedKinds)),
            DSL.count().filterWhere(l.KIND.eq(ReputationKind.spontaneous)),
            DSL.count(),
            DSL.max(l.OCCURRED_AT),
            DSL.count().filterWhere(keptKinds),
            DSL.count().filterWhere(brokeKinds)
        )
            .from(l)
            .where(l.USER_ID.eq(userId).and(l.CLUB_ID.eq(clubId)))
            .fetchOne()!!

        val outcome = rec.value5() ?: 0
        // Нет строк леджера для этой пары (user, club): кэшировать нечего. В боевом
        // пути recompute вызывается сразу после append, поэтому outcome >= 1; эта
        // проверка важна только для защитных / будущих сценариев удаления.
        if (outcome == 0) return

        val reliability = (rec.value1() ?: BigDecimal.ZERO).toInt()
        val confirmations = rec.value2() ?: 0
        val attendances = rec.value3() ?: 0
        val spontaneity = rec.value4() ?: 0
        val updatedAt = rec.value6() ?: OffsetDateTime.now()
        val kept = rec.value7() ?: 0
        val broke = rec.value8() ?: 0
        val neutral = outcome - kept - broke
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
            .set(USER_CLUB_REPUTATION.KEPT_COUNT, kept)
            .set(USER_CLUB_REPUTATION.BROKE_COUNT, broke)
            .set(USER_CLUB_REPUTATION.NEUTRAL_COUNT, neutral)
            .set(USER_CLUB_REPUTATION.UPDATED_AT, updatedAt)
            .onConflict(USER_CLUB_REPUTATION.USER_ID, USER_CLUB_REPUTATION.CLUB_ID)
            .doUpdate()
            .set(USER_CLUB_REPUTATION.RELIABILITY_INDEX, reliability)
            .set(USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT, fulfillmentPct)
            .set(USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS, confirmations)
            .set(USER_CLUB_REPUTATION.TOTAL_ATTENDANCES, attendances)
            .set(USER_CLUB_REPUTATION.SPONTANEITY_COUNT, spontaneity)
            .set(USER_CLUB_REPUTATION.OUTCOME_COUNT, outcome)
            .set(USER_CLUB_REPUTATION.KEPT_COUNT, kept)
            .set(USER_CLUB_REPUTATION.BROKE_COUNT, broke)
            .set(USER_CLUB_REPUTATION.NEUTRAL_COUNT, neutral)
            .set(USER_CLUB_REPUTATION.UPDATED_AT, updatedAt)
            .execute()
    }
}
