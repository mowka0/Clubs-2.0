package com.clubs.event

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.enums.Stage_2Vote
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.USERS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqEventResponseRepository(
    private val dsl: DSLContext,
    private val mapper: EventResponseMapper
) : EventResponseRepository {

    override fun upsertStage1Vote(eventId: UUID, userId: UUID, vote: Stage_1Vote): EventResponse {
        val existing = dsl.selectFrom(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
            )
            .fetchOne()

        val record = if (existing != null) {
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
        return mapper.toDomain(record)
    }

    override fun findByEventAndUser(eventId: UUID, userId: UUID): EventResponse? =
        dsl.selectFrom(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
            )
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun countByVote(eventId: UUID): Map<String, Int> {
        val going = countByStage1Vote(eventId, Stage_1Vote.going)
        val maybe = countByStage1Vote(eventId, Stage_1Vote.maybe)
        val notGoing = countByStage1Vote(eventId, Stage_1Vote.not_going)
        return mapOf("going" to going, "maybe" to maybe, "notGoing" to notGoing)
    }

    override fun countConfirmed(eventId: UUID): Int =
        dsl.selectCount().from(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_2_VOTE.eq(Stage_2Vote.confirmed))
            )
            .fetchOne(0, Int::class.java) ?: 0

    override fun findFirstWaitlisted(eventId: UUID): EventResponse? =
        dsl.selectFrom(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_2_VOTE.eq(Stage_2Vote.waitlisted))
            )
            .orderBy(EVENT_RESPONSES.STAGE_1_TIMESTAMP.asc())
            .limit(1)
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun updateStage2Vote(id: UUID, vote: Stage_2Vote, finalStatus: FinalStatus): EventResponse {
        val record = dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.STAGE_2_VOTE, vote)
            .set(EVENT_RESPONSES.STAGE_2_TIMESTAMP, OffsetDateTime.now())
            .set(EVENT_RESPONSES.FINAL_STATUS, finalStatus)
            .set(EVENT_RESPONSES.UPDATED_AT, OffsetDateTime.now())
            .where(EVENT_RESPONSES.ID.eq(id))
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }

    override fun findGoingByEventOrderByTimestamp(eventId: UUID): List<EventResponse> =
        dsl.selectFrom(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.going))
            )
            .orderBy(EVENT_RESPONSES.STAGE_1_TIMESTAMP.asc())
            .fetch()
            .map(mapper::toDomain)

    override fun findMaybeByEventOrderByTimestamp(eventId: UUID): List<EventResponse> =
        dsl.selectFrom(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.maybe))
            )
            .orderBy(EVENT_RESPONSES.STAGE_1_TIMESTAMP.asc())
            .fetch()
            .map(mapper::toDomain)

    override fun findRespondersWithUsers(eventId: UUID): List<EventResponderInfo> =
        dsl.select(
            EVENT_RESPONSES.USER_ID,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
            USERS.AVATAR_URL,
            EVENT_RESPONSES.STAGE_1_VOTE,
            EVENT_RESPONSES.FINAL_STATUS
        )
            .from(EVENT_RESPONSES)
            .join(USERS).on(USERS.ID.eq(EVENT_RESPONSES.USER_ID))
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.isNotNull)
            )
            .orderBy(EVENT_RESPONSES.STAGE_1_VOTE.asc(), EVENT_RESPONSES.STAGE_1_TIMESTAMP.asc())
            .fetch { r ->
                EventResponderInfo(
                    userId = r.get(EVENT_RESPONSES.USER_ID)!!,
                    firstName = r.get(USERS.FIRST_NAME)!!,
                    lastName = r.get(USERS.LAST_NAME),
                    avatarUrl = r.get(USERS.AVATAR_URL),
                    stage1Vote = r.get(EVENT_RESPONSES.STAGE_1_VOTE),
                    finalStatus = r.get(EVENT_RESPONSES.FINAL_STATUS)
                )
            }

    override fun findResponderTelegramIdsByEventId(eventId: UUID): List<Long> =
        dsl.select(USERS.TELEGRAM_ID)
            .from(EVENT_RESPONSES)
            .join(USERS).on(USERS.ID.eq(EVENT_RESPONSES.USER_ID))
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId))
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()

    override fun findTelegramIdsByEventAndAttendance(eventId: UUID, attendance: AttendanceStatus): List<Long> =
        dsl.select(USERS.TELEGRAM_ID)
            .from(EVENT_RESPONSES)
            .join(USERS).on(USERS.ID.eq(EVENT_RESPONSES.USER_ID))
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(attendance))
            )
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()

    override fun setAttendance(eventId: UUID, userId: UUID, attended: Boolean): Int =
        dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.ATTENDANCE, if (attended) AttendanceStatus.attended else AttendanceStatus.absent)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
            )
            .execute()

    override fun disputeAbsentAttendance(eventId: UUID, userId: UUID): Int =
        dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.ATTENDANCE, AttendanceStatus.disputed)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.absent))
            )
            .execute()

    override fun resolveDisputedAttendance(eventId: UUID, userId: UUID, attended: Boolean): Int =
        dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.ATTENDANCE, if (attended) AttendanceStatus.attended else AttendanceStatus.absent)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.disputed))
            )
            .execute()

    override fun deleteByUserAndClubAndActiveEvents(userId: UUID, clubId: UUID): Int {
        val activeEventIds = dsl.select(EVENTS.ID)
            .from(EVENTS)
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_1, EventStatus.stage_2))
            )
        return dsl.deleteFrom(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.USER_ID.eq(userId)
                    .and(EVENT_RESPONSES.EVENT_ID.`in`(activeEventIds))
            )
            .execute()
    }

    private fun countByStage1Vote(eventId: UUID, vote: Stage_1Vote): Int =
        dsl.selectCount().from(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId).and(EVENT_RESPONSES.STAGE_1_VOTE.eq(vote)))
            .fetchOne(0, Int::class.java) ?: 0
}
