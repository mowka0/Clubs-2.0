package com.clubs.event

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.enums.Stage_2Vote
import com.clubs.generated.jooq.tables.records.EventResponsesRecord
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.USERS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class EventResponseRepository(private val dsl: DSLContext) {

    fun upsertStage1Vote(eventId: UUID, userId: UUID, vote: Stage_1Vote): EventResponsesRecord {
        val existing = findByEventAndUser(eventId, userId)
        return if (existing != null) {
            dsl.update(EVENT_RESPONSES)
                .set(EVENT_RESPONSES.STAGE_1_VOTE, vote)
                .set(EVENT_RESPONSES.STAGE_1_TIMESTAMP, OffsetDateTime.now())
                .set(EVENT_RESPONSES.UPDATED_AT, OffsetDateTime.now())
                .where(EVENT_RESPONSES.ID.eq(existing.id))
                .returning()
                .fetchOne()!!
        } else {
            dsl.insertInto(EVENT_RESPONSES)
                .set(EVENT_RESPONSES.EVENT_ID, eventId)
                .set(EVENT_RESPONSES.USER_ID, userId)
                .set(EVENT_RESPONSES.STAGE_1_VOTE, vote)
                .set(EVENT_RESPONSES.STAGE_1_TIMESTAMP, OffsetDateTime.now())
                .returning()
                .fetchOne()!!
        }
    }

    fun findByEventAndUser(eventId: UUID, userId: UUID): EventResponsesRecord? =
        dsl.selectFrom(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
            )
            .fetchOne()

    fun countByVote(eventId: UUID): Map<String, Int> {
        val going = dsl.selectCount().from(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId).and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.going)))
            .fetchOne(0, Int::class.java) ?: 0
        val maybe = dsl.selectCount().from(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId).and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.maybe)))
            .fetchOne(0, Int::class.java) ?: 0
        val notGoing = dsl.selectCount().from(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId).and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.not_going)))
            .fetchOne(0, Int::class.java) ?: 0
        return mapOf("going" to going, "maybe" to maybe, "notGoing" to notGoing)
    }

    fun countConfirmed(eventId: UUID): Int =
        dsl.selectCount().from(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_2_VOTE.eq(Stage_2Vote.confirmed))
            )
            .fetchOne(0, Int::class.java) ?: 0

    fun findFirstWaitlisted(eventId: UUID): EventResponsesRecord? =
        dsl.selectFrom(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_2_VOTE.eq(Stage_2Vote.waitlisted))
            )
            .orderBy(EVENT_RESPONSES.STAGE_1_TIMESTAMP.asc())
            .limit(1)
            .fetchOne()

    fun updateStage2Vote(id: UUID, vote: Stage_2Vote, finalStatus: FinalStatus): EventResponsesRecord =
        dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.STAGE_2_VOTE, vote)
            .set(EVENT_RESPONSES.STAGE_2_TIMESTAMP, OffsetDateTime.now())
            .set(EVENT_RESPONSES.FINAL_STATUS, finalStatus)
            .set(EVENT_RESPONSES.UPDATED_AT, OffsetDateTime.now())
            .where(EVENT_RESPONSES.ID.eq(id))
            .returning()
            .fetchOne()!!

    fun findGoingByEventOrderByTimestamp(eventId: UUID): List<EventResponsesRecord> =
        dsl.selectFrom(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.going))
            )
            .orderBy(EVENT_RESPONSES.STAGE_1_TIMESTAMP.asc())
            .fetch()

    fun findMaybeByEventOrderByTimestamp(eventId: UUID): List<EventResponsesRecord> =
        dsl.selectFrom(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.maybe))
            )
            .orderBy(EVENT_RESPONSES.STAGE_1_TIMESTAMP.asc())
            .fetch()

    /**
     * Returns telegram IDs of users who have ANY stage_1_vote response for the event
     * (no filter by vote value). Used by NotificationService.sendStage2Started.
     * NOTE: name reflects current SQL semantics (no Stage_1Vote filter).
     * PRD says reminder should target going+maybe — tracked as GAP in
     * docs/backlog/telegram-bot-prd-gaps.md.
     */
    fun findResponderTelegramIdsByEventId(eventId: UUID): List<Long> =
        dsl.select(USERS.TELEGRAM_ID)
            .from(EVENT_RESPONSES)
            .join(USERS).on(USERS.ID.eq(EVENT_RESPONSES.USER_ID))
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId))
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()

    /**
     * Returns telegram IDs of users whose ATTENDANCE matches the given value
     * for the event. Used by NotificationService.sendAttendanceMarked.
     * Replaces a previous query that filtered by final_status=declined — that
     * was a bug (method name implied attendance), fixed during bot refactor.
     */
    fun findTelegramIdsByEventAndAttendance(eventId: UUID, attendance: AttendanceStatus): List<Long> =
        dsl.select(USERS.TELEGRAM_ID)
            .from(EVENT_RESPONSES)
            .join(USERS).on(USERS.ID.eq(EVENT_RESPONSES.USER_ID))
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(attendance))
            )
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()
}
