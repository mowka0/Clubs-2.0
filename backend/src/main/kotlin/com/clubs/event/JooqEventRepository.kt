package com.clubs.event

import com.clubs.common.dto.PageResponse
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.enums.Stage_2Vote
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.USERS
import com.clubs.membership.MembershipAccess
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqEventRepository(
    private val dsl: DSLContext,
    private val mapper: EventMapper
) : EventRepository {

    override fun create(request: CreateEventRequest, clubId: UUID, createdBy: UUID): Event {
        val record = dsl.insertInto(EVENTS)
            .set(EVENTS.ID, UUID.randomUUID())
            .set(EVENTS.CLUB_ID, clubId)
            .set(EVENTS.CREATED_BY, createdBy)
            .set(EVENTS.TITLE, request.title)
            .set(EVENTS.DESCRIPTION, request.description)
            .set(EVENTS.LOCATION_TEXT, request.locationText)
            .set(EVENTS.EVENT_DATETIME, request.eventDatetime)
            .set(EVENTS.PARTICIPANT_LIMIT, request.participantLimit)
            .set(EVENTS.VOTING_OPENS_DAYS_BEFORE, request.votingOpensDaysBefore)
            .set(EVENTS.STATUS, EventStatus.upcoming)
            .set(EVENTS.STAGE_2_TRIGGERED, false)
            .set(EVENTS.ATTENDANCE_MARKED, false)
            .set(EVENTS.ATTENDANCE_FINALIZED, false)
            .set(EVENTS.PHOTO_URL, request.photoUrl)
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }

    override fun findById(id: UUID): Event? =
        dsl.selectFrom(EVENTS)
            .where(EVENTS.ID.eq(id))
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun findByClubId(clubId: UUID, status: EventStatus?, page: Int, size: Int): PageResponse<EventListItemDto> {
        var condition = EVENTS.CLUB_ID.eq(clubId)
        status?.let { condition = condition.and(EVENTS.STATUS.eq(it)) }

        val total = dsl.selectCount().from(EVENTS).where(condition)
            .fetchOne(0, Long::class.java) ?: 0L

        val events = dsl.selectFrom(EVENTS)
            .where(condition)
            .orderBy(EVENTS.EVENT_DATETIME.asc())
            .limit(size)
            .offset(page * size)
            .fetch()
            .map(mapper::toDomain)

        val eventIds = events.map { it.id }
        val goingCounts = fetchGoingCounts(eventIds)

        val items = events.map { event -> mapper.toListItemDto(event, goingCounts[event.id] ?: 0) }

        val totalPages = if (size == 0) 0 else ((total + size - 1) / size).toInt()
        return PageResponse(content = items, totalElements = total, totalPages = totalPages, page = page, size = size)
    }

    override fun findAllByClubWithGoingCount(clubId: UUID): List<EventWithGoingCount> {
        val events = dsl.selectFrom(EVENTS)
            .where(EVENTS.CLUB_ID.eq(clubId))
            .orderBy(EVENTS.CREATED_AT.desc(), EVENTS.ID.asc())
            .fetch()
            .map(mapper::toDomain)

        if (events.isEmpty()) return emptyList()

        val goingCounts = fetchGoingCounts(events.map { it.id })
        return events.map { EventWithGoingCount(event = it, goingCount = goingCounts[it.id] ?: 0) }
    }

    override fun findActionRequiredEventIds(clubId: UUID, userId: UUID, now: OffsetDateTime): Set<UUID> {
        // Stage-1 vote pending: voting window open (event_datetime - voting_opens_days_before <= now),
        // status still upcoming, this user has not voted.
        val stage1Pending = EVENTS.STATUS.eq(EventStatus.upcoming)
            .and(EVENT_RESPONSES.STAGE_1_VOTE.isNull)
            .and(
                DSL.condition(
                    "{0} - ({1} * INTERVAL '1 day') <= {2}",
                    EVENTS.EVENT_DATETIME,
                    EVENTS.VOTING_OPENS_DAYS_BEFORE,
                    DSL.value(now)
                )
            )
        // Stage-2 confirmation pending: voted going/maybe in stage 1, not yet confirmed/declined.
        val stage2Pending = EVENTS.STATUS.eq(EventStatus.stage_2)
            .and(EVENT_RESPONSES.STAGE_1_VOTE.`in`(Stage_1Vote.going, Stage_1Vote.maybe))
            .and(EVENT_RESPONSES.STAGE_2_VOTE.isNull)

        return dsl.select(EVENTS.ID)
            .from(EVENTS)
            .leftJoin(EVENT_RESPONSES).on(
                EVENT_RESPONSES.EVENT_ID.eq(EVENTS.ID)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
            )
            .where(EVENTS.CLUB_ID.eq(clubId))
            .and(stage1Pending.or(stage2Pending))
            .fetch(EVENTS.ID)
            .filterNotNull()
            .toSet()
    }

    override fun findMyFeed(userId: UUID, page: Int, size: Int): PageResponse<MyFeedItem> {
        val now = OffsetDateTime.now()

        // Membership access must match the voting/DM access predicate, else a
        // cancelled-but-still-paid member can vote on (and is DM'd about) an event
        // that never shows up in their feed. Shared MembershipAccess predicate.
        val baseCondition = EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_2)
            .and(EVENTS.EVENT_DATETIME.gt(now))
            .and(CLUBS.IS_ACTIVE.eq(true))
            .and(MEMBERSHIPS.USER_ID.eq(userId))
            .and(MembershipAccess.hasAccess(now))

        val total = dsl.select(DSL.countDistinct(EVENTS.ID))
            .from(EVENTS)
            .join(MEMBERSHIPS).on(MEMBERSHIPS.CLUB_ID.eq(EVENTS.CLUB_ID))
            .join(CLUBS).on(CLUBS.ID.eq(EVENTS.CLUB_ID))
            .where(baseCondition)
            .fetchOne(0, Long::class.java) ?: 0L

        // ORDER BY: action-required events first (computed inline via CASE),
        // then chronological. Voting window opens at
        // event_datetime - voting_opens_days_before * 1 day.
        val actionRequiredOrder = DSL.case_()
            .`when`(
                EVENTS.STATUS.eq(EventStatus.upcoming)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.isNull)
                    .and(
                        DSL.condition(
                            "{0} - ({1} * INTERVAL '1 day') <= {2}",
                            EVENTS.EVENT_DATETIME,
                            EVENTS.VOTING_OPENS_DAYS_BEFORE,
                            DSL.value(now)
                        )
                    ),
                1
            )
            .`when`(
                EVENTS.STATUS.eq(EventStatus.stage_2)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.`in`(Stage_1Vote.going, Stage_1Vote.maybe))
                    .and(EVENT_RESPONSES.STAGE_2_VOTE.isNull),
                1
            )
            .otherwise(0)

        val rows = dsl.select(
            EVENTS.ID,
            EVENTS.CLUB_ID,
            EVENTS.CREATED_BY,
            EVENTS.TITLE,
            EVENTS.DESCRIPTION,
            EVENTS.LOCATION_TEXT,
            EVENTS.EVENT_DATETIME,
            EVENTS.PARTICIPANT_LIMIT,
            EVENTS.VOTING_OPENS_DAYS_BEFORE,
            EVENTS.STATUS,
            EVENTS.STAGE_2_TRIGGERED,
            EVENTS.ATTENDANCE_MARKED,
            EVENTS.ATTENDANCE_FINALIZED,
            EVENTS.PHOTO_URL,
            EVENTS.CREATED_AT,
            EVENTS.UPDATED_AT,
            CLUBS.NAME.`as`("club_name"),
            CLUBS.AVATAR_URL.`as`("club_avatar_url"),
            EVENT_RESPONSES.STAGE_1_VOTE.`as`("my_vote"),
            EVENT_RESPONSES.FINAL_STATUS.`as`("my_final_status"),
        )
            .from(EVENTS)
            .join(MEMBERSHIPS).on(MEMBERSHIPS.CLUB_ID.eq(EVENTS.CLUB_ID))
            .join(CLUBS).on(CLUBS.ID.eq(EVENTS.CLUB_ID))
            .leftJoin(EVENT_RESPONSES).on(
                EVENT_RESPONSES.EVENT_ID.eq(EVENTS.ID)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
            )
            .where(baseCondition)
            .orderBy(actionRequiredOrder.desc(), EVENTS.EVENT_DATETIME.asc())
            .limit(size)
            .offset(page * size)
            .fetch()

        val eventIds = rows.map { it.get(EVENTS.ID)!! }
        val goingCounts = fetchGoingCounts(eventIds)
        val confirmedCounts = fetchConfirmedCounts(eventIds)

        val items = rows.map { r ->
            val eventId = r.get(EVENTS.ID)!!
            val event = Event(
                id = eventId,
                clubId = r.get(EVENTS.CLUB_ID)!!,
                createdBy = r.get(EVENTS.CREATED_BY)!!,
                title = r.get(EVENTS.TITLE)!!,
                description = r.get(EVENTS.DESCRIPTION),
                locationText = r.get(EVENTS.LOCATION_TEXT)!!,
                eventDatetime = r.get(EVENTS.EVENT_DATETIME)!!,
                participantLimit = r.get(EVENTS.PARTICIPANT_LIMIT)!!,
                votingOpensDaysBefore = r.get(EVENTS.VOTING_OPENS_DAYS_BEFORE) ?: EventMapper.DEFAULT_VOTING_OPENS_DAYS_BEFORE,
                status = r.get(EVENTS.STATUS) ?: EventStatus.upcoming,
                stage2Triggered = r.get(EVENTS.STAGE_2_TRIGGERED) ?: false,
                attendanceMarked = r.get(EVENTS.ATTENDANCE_MARKED) ?: false,
                attendanceFinalized = r.get(EVENTS.ATTENDANCE_FINALIZED) ?: false,
                photoUrl = r.get(EVENTS.PHOTO_URL),
                createdAt = r.get(EVENTS.CREATED_AT),
                updatedAt = r.get(EVENTS.UPDATED_AT)
            )
            MyFeedItem(
                event = event,
                clubName = r.get("club_name", String::class.java),
                clubAvatarUrl = r.get("club_avatar_url", String::class.java),
                myVote = r.get("my_vote", Stage_1Vote::class.java),
                myFinalStatus = r.get("my_final_status", FinalStatus::class.java),
                goingCount = goingCounts[eventId] ?: 0,
                confirmedCount = confirmedCounts[eventId] ?: 0
            )
        }

        val totalPages = if (size == 0) 0 else ((total + size - 1) / size).toInt()
        return PageResponse(content = items, totalElements = total, totalPages = totalPages, page = page, size = size)
    }

    override fun getVoteCounts(eventId: UUID): Map<String, Int> {
        val going = countVotes(eventId, Stage_1Vote.going)
        val maybe = countVotes(eventId, Stage_1Vote.maybe)
        val notGoing = countVotes(eventId, Stage_1Vote.not_going)
        // Stage-2 confirmed roster size. Mirrors fetchConfirmedCounts / countConfirmed
        // (stage_2_vote = confirmed) — getEvent previously hardcoded this to 0.
        val confirmed = dsl.selectCount().from(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId).and(EVENT_RESPONSES.STAGE_2_VOTE.eq(Stage_2Vote.confirmed)))
            .fetchOne(0, Int::class.java) ?: 0
        return mapOf("going" to going, "maybe" to maybe, "notGoing" to notGoing, "confirmed" to confirmed)
    }

    override fun findEventsToTriggerStage2(): List<Event> {
        val cutoff = OffsetDateTime.now().plusHours(STAGE_2_TRIGGER_HOURS_BEFORE_EVENT)
        return dsl.selectFrom(EVENTS)
            .where(
                EVENTS.STATUS.eq(EventStatus.upcoming)
                    .and(EVENTS.STAGE_2_TRIGGERED.eq(false))
                    .and(EVENTS.EVENT_DATETIME.lessOrEqual(cutoff))
            )
            .fetch()
            .map(mapper::toDomain)
    }

    /**
     * Returns the nearest upcoming event across all clubs.
     * Used by ClubsBot.handleWhoIsGoing (/кто_идет command).
     * Status must be upcoming, stage_1, or stage_2 and event_datetime > now.
     */
    override fun findNextUpcomingEvent(now: OffsetDateTime): Event? =
        dsl.selectFrom(EVENTS)
            .where(
                EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_1, EventStatus.stage_2)
                    .and(EVENTS.EVENT_DATETIME.gt(now))
            )
            .orderBy(EVENTS.EVENT_DATETIME.asc())
            .limit(1)
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun transitionToStage2(id: UUID) {
        dsl.update(EVENTS)
            .set(EVENTS.STATUS, EventStatus.stage_2)
            .set(EVENTS.STAGE_2_TRIGGERED, true)
            .where(EVENTS.ID.eq(id))
            .execute()
    }

    override fun markAttendanceMarked(id: UUID) {
        dsl.update(EVENTS)
            .set(EVENTS.ATTENDANCE_MARKED, true)
            .where(EVENTS.ID.eq(id))
            .execute()
    }

    override fun findEventsNeedingConfirmReminder(now: OffsetDateTime, until: OffsetDateTime): List<Event> =
        dsl.selectFrom(EVENTS)
            .where(
                EVENTS.STATUS.eq(EventStatus.stage_2)
                    .and(EVENTS.CONFIRM_REMINDER_SENT.isFalse)
                    .and(EVENTS.EVENT_DATETIME.greaterThan(now))      // not started yet
                    .and(EVENTS.EVENT_DATETIME.lessOrEqual(until))    // within the "hours before" window
            )
            .fetch()
            .map(mapper::toDomain)

    override fun markConfirmReminderSent(id: UUID) {
        dsl.update(EVENTS)
            .set(EVENTS.CONFIRM_REMINDER_SENT, true)
            .where(EVENTS.ID.eq(id))
            .execute()
    }

    override fun findEventsNeedingAttendanceReminder(cutoff: OffsetDateTime): List<Event> =
        dsl.selectFrom(EVENTS)
            .where(
                EVENTS.ATTENDANCE_MARKED.isFalse
                    .and(EVENTS.ATTENDANCE_REMINDER_SENT.isFalse)
                    .and(EVENTS.EVENT_DATETIME.lessOrEqual(cutoff))   // event was >= "hours after" ago
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    // CC-2: only nag the organizer when there is actually a roster to mark — a
                    // past event with zero confirmed participants has nothing to attend (and
                    // setAttendance only touches final_status=confirmed rows anyway).
                    .andExists(
                        DSL.selectOne().from(EVENT_RESPONSES).where(
                            EVENT_RESPONSES.EVENT_ID.eq(EVENTS.ID)
                                .and(EVENT_RESPONSES.FINAL_STATUS.eq(FinalStatus.confirmed))
                        )
                    )
            )
            .fetch()
            .map(mapper::toDomain)

    override fun markAttendanceReminderSent(id: UUID) {
        dsl.update(EVENTS)
            .set(EVENTS.ATTENDANCE_REMINDER_SENT, true)
            .where(EVENTS.ID.eq(id))
            .execute()
    }

    override fun findOrganizerTelegramId(eventId: UUID): Long? =
        dsl.select(USERS.TELEGRAM_ID)
            .from(EVENTS)
            .join(CLUBS).on(CLUBS.ID.eq(EVENTS.CLUB_ID))
            .join(USERS).on(USERS.ID.eq(CLUBS.OWNER_ID))
            .where(EVENTS.ID.eq(eventId))
            .fetchOne(USERS.TELEGRAM_ID)

    override fun finalizeAttendanceBefore(eventDatetimeCutoff: OffsetDateTime): List<UUID> =
        dsl.update(EVENTS)
            .set(EVENTS.ATTENDANCE_FINALIZED, true)
            .where(
                EVENTS.ATTENDANCE_MARKED.eq(true)
                    .and(EVENTS.ATTENDANCE_FINALIZED.eq(false))
                    .and(EVENTS.EVENT_DATETIME.lessOrEqual(eventDatetimeCutoff))
            )
            .returningResult(EVENTS.ID)
            .fetch()
            .mapNotNull { it.value1() }

    override fun neutrallyFinalizeUnmarkedBefore(eventDatetimeCutoff: OffsetDateTime): List<UUID> =
        dsl.update(EVENTS)
            .set(EVENTS.ATTENDANCE_FINALIZED, true)
            .where(
                EVENTS.ATTENDANCE_MARKED.eq(false)
                    .and(EVENTS.ATTENDANCE_FINALIZED.eq(false))
                    .and(EVENTS.EVENT_DATETIME.lessOrEqual(eventDatetimeCutoff))
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
            )
            .returningResult(EVENTS.ID)
            .fetch()
            .mapNotNull { it.value1() }

    override fun markPastEventsCompleted(cutoff: OffsetDateTime): Int =
        dsl.update(EVENTS)
            .set(EVENTS.STATUS, EventStatus.completed)
            .where(
                EVENTS.EVENT_DATETIME.lessThan(cutoff)
                    .and(EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_1, EventStatus.stage_2))
            )
            .execute()

    private fun countVotes(eventId: UUID, vote: Stage_1Vote): Int =
        dsl.selectCount().from(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId).and(EVENT_RESPONSES.STAGE_1_VOTE.eq(vote)))
            .fetchOne(0, Int::class.java) ?: 0

    private fun fetchGoingCounts(eventIds: List<UUID>): Map<UUID, Int> {
        if (eventIds.isEmpty()) return emptyMap()
        return dsl.select(EVENT_RESPONSES.EVENT_ID, DSL.count())
            .from(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.`in`(eventIds)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.going))
            )
            .groupBy(EVENT_RESPONSES.EVENT_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }
    }

    private fun fetchConfirmedCounts(eventIds: List<UUID>): Map<UUID, Int> {
        if (eventIds.isEmpty()) return emptyMap()
        return dsl.select(EVENT_RESPONSES.EVENT_ID, DSL.count())
            .from(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.`in`(eventIds)
                    .and(EVENT_RESPONSES.STAGE_2_VOTE.eq(Stage_2Vote.confirmed))
            )
            .groupBy(EVENT_RESPONSES.EVENT_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }
    }

    companion object {
        private const val STAGE_2_TRIGGER_HOURS_BEFORE_EVENT = 24L
    }
}
