package com.clubs.reputation

import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.USER_CLUB_REPUTATION
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqReputationRepository(
    private val dsl: DSLContext,
    private val mapper: ReputationMapper
) : ReputationRepository {

    override fun findByUserAndClub(userId: UUID, clubId: UUID): Reputation? {
        return dsl.selectFrom(USER_CLUB_REPUTATION)
            .where(
                USER_CLUB_REPUTATION.USER_ID.eq(userId)
                    .and(USER_CLUB_REPUTATION.CLUB_ID.eq(clubId))
            )
            .fetchOne()
            ?.let(mapper::toDomain)
    }

    override fun save(reputation: Reputation) {
        val existing = dsl.selectCount()
            .from(USER_CLUB_REPUTATION)
            .where(
                USER_CLUB_REPUTATION.USER_ID.eq(reputation.userId)
                    .and(USER_CLUB_REPUTATION.CLUB_ID.eq(reputation.clubId))
            )
            .fetchOne(0, Int::class.java) ?: 0

        if (existing == 0) {
            dsl.insertInto(USER_CLUB_REPUTATION)
                .set(USER_CLUB_REPUTATION.USER_ID, reputation.userId)
                .set(USER_CLUB_REPUTATION.CLUB_ID, reputation.clubId)
                .set(USER_CLUB_REPUTATION.RELIABILITY_INDEX, reputation.reliabilityIndex)
                .set(USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT, reputation.promiseFulfillmentPct)
                .set(USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS, reputation.totalConfirmations)
                .set(USER_CLUB_REPUTATION.TOTAL_ATTENDANCES, reputation.totalAttendances)
                .set(USER_CLUB_REPUTATION.SPONTANEITY_COUNT, reputation.spontaneityCount)
                .execute()
        } else {
            dsl.update(USER_CLUB_REPUTATION)
                .set(USER_CLUB_REPUTATION.RELIABILITY_INDEX, reputation.reliabilityIndex)
                .set(USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT, reputation.promiseFulfillmentPct)
                .set(USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS, reputation.totalConfirmations)
                .set(USER_CLUB_REPUTATION.TOTAL_ATTENDANCES, reputation.totalAttendances)
                .set(USER_CLUB_REPUTATION.SPONTANEITY_COUNT, reputation.spontaneityCount)
                .set(USER_CLUB_REPUTATION.UPDATED_AT, OffsetDateTime.now())
                .where(
                    USER_CLUB_REPUTATION.USER_ID.eq(reputation.userId)
                        .and(USER_CLUB_REPUTATION.CLUB_ID.eq(reputation.clubId))
                )
                .execute()
        }
    }

    override fun findFinalizedEvents(): List<FinalizedEventRef> {
        return dsl.select(EVENTS.ID, EVENTS.CLUB_ID)
            .from(EVENTS)
            .where(
                EVENTS.ATTENDANCE_FINALIZED.eq(true)
                    .and(EVENTS.ATTENDANCE_MARKED.eq(true))
            )
            .fetch { record ->
                FinalizedEventRef(
                    eventId = record.get(EVENTS.ID)!!,
                    clubId = record.get(EVENTS.CLUB_ID)!!
                )
            }
    }

    override fun findResponsesByEvent(eventId: UUID): List<ResponseForReputation> {
        return dsl.select(
            EVENT_RESPONSES.USER_ID,
            EVENT_RESPONSES.STAGE_1_VOTE,
            EVENT_RESPONSES.FINAL_STATUS,
            EVENT_RESPONSES.ATTENDANCE
        )
            .from(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId))
            .fetch { record ->
                ResponseForReputation(
                    userId = record.get(EVENT_RESPONSES.USER_ID)!!,
                    stage1Vote = record.get(EVENT_RESPONSES.STAGE_1_VOTE),
                    finalStatus = record.get(EVENT_RESPONSES.FINAL_STATUS),
                    attendance = record.get(EVENT_RESPONSES.ATTENDANCE)
                )
            }
    }
}
