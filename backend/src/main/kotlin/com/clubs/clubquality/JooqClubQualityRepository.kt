package com.clubs.clubquality

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.SKLADCHINAS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.Period
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Read-only aggregations over existing tables (`clubs`, `events`, `event_responses`, `skladchinas`).
 * No own schema. Reading these shared jOOQ tables is consistent with
 * [com.clubs.reputation.JooqReputationRepository], which already aggregates events/responses.
 *
 * Time windows are computed in Kotlin and bound as parameters (no SQL `interval`) — deterministic
 * and testable. "Held" = past, non-cancelled event.
 */
@Repository
class JooqClubQualityRepository(private val dsl: DSLContext) : ClubQualityRepository {

    private companion object {
        const val WINDOW_DAYS = 90L
        const val MONTHS_IN_WINDOW = 3.0
        const val CORE_ATTENDANCE_THRESHOLD = 3
    }

    override fun findClubFacts(clubId: UUID): ClubFacts? {
        val createdAt = dsl.select(CLUBS.CREATED_AT)
            .from(CLUBS)
            .where(CLUBS.ID.eq(clubId))
            .fetchOne(CLUBS.CREATED_AT)
            ?: return null

        val now = OffsetDateTime.now()
        val windowStart = now.minusDays(WINDOW_DAYS)

        return ClubFacts(
            meetingsPerMonth = meetingsPerMonth(clubId, now, windowStart),
            avgAttendance = avgAttendance(clubId, now, windowStart),
            coreSize = coreSize(clubId),
            ageMonths = Period.between(createdAt.toLocalDate(), now.toLocalDate())
                .toTotalMonths().toInt().coerceAtLeast(0),
            totalMeetings = totalMeetings(clubId, now),
            successfulSkladchinas = successfulSkladchinas(clubId),
        )
    }

    private fun heldInWindow(clubId: UUID, now: OffsetDateTime, windowStart: OffsetDateTime) =
        EVENTS.CLUB_ID.eq(clubId)
            .and(EVENTS.STATUS.ne(EventStatus.cancelled))
            .and(EVENTS.EVENT_DATETIME.lt(now))
            .and(EVENTS.EVENT_DATETIME.ge(windowStart))

    private fun meetingsPerMonth(clubId: UUID, now: OffsetDateTime, windowStart: OffsetDateTime): Double {
        val held = dsl.selectCount()
            .from(EVENTS)
            .where(heldInWindow(clubId, now, windowStart))
            .fetchOne(0, Int::class.java) ?: 0
        return (held / MONTHS_IN_WINDOW * 10.0).roundToInt() / 10.0
    }

    /**
     * Σ attended responses ÷ count of finalized meetings, over the 90-day window. A meeting where
     * nobody showed up still counts in the denominator (honestly lowers the average). 0 if no
     * finalized meetings.
     */
    private fun avgAttendance(clubId: UUID, now: OffsetDateTime, windowStart: OffsetDateTime): Int {
        val record = dsl.select(
            DSL.count().filterWhere(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.attended)),
            DSL.countDistinct(EVENTS.ID),
        )
            .from(EVENTS)
            .leftJoin(EVENT_RESPONSES).on(EVENT_RESPONSES.EVENT_ID.eq(EVENTS.ID))
            .where(heldInWindow(clubId, now, windowStart).and(EVENTS.ATTENDANCE_FINALIZED.isTrue))
            .fetchOne()

        val attended = record?.value1() ?: 0
        val finalizedMeetings = record?.value2() ?: 0
        return if (finalizedMeetings > 0) {
            (attended.toDouble() / finalizedMeetings).roundToInt()
        } else {
            0
        }
    }

    /** Distinct users with ≥3 attended events for this club, all-time (the club's stable core). */
    private fun coreSize(clubId: UUID): Int =
        dsl.select(EVENT_RESPONSES.USER_ID)
            .from(EVENT_RESPONSES)
            .join(EVENTS).on(EVENTS.ID.eq(EVENT_RESPONSES.EVENT_ID))
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.attended)),
            )
            .groupBy(EVENT_RESPONSES.USER_ID)
            .having(DSL.count().ge(CORE_ATTENDANCE_THRESHOLD))
            .fetch()
            .size

    /** All-time held (past, non-cancelled) events for the club. */
    private fun totalMeetings(clubId: UUID, now: OffsetDateTime): Int =
        dsl.selectCount()
            .from(EVENTS)
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(EVENTS.EVENT_DATETIME.lt(now)),
            )
            .fetchOne(0, Int::class.java) ?: 0

    /** Skladchinas of the club that closed successfully (milestone «первый сбор»). */
    private fun successfulSkladchinas(clubId: UUID): Int =
        dsl.selectCount()
            .from(SKLADCHINAS)
            .where(SKLADCHINAS.CLUB_ID.eq(clubId).and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.closed_success)))
            .fetchOne(0, Int::class.java) ?: 0

    // ---- Batch (Discovery card): one grouped query per metric over the whole page of clubs ----

    override fun findClubCardFacts(clubIds: Collection<UUID>): List<ClubCardFacts> {
        if (clubIds.isEmpty()) return emptyList()
        val ids = clubIds.toSet()
        val now = OffsetDateTime.now()
        val windowStart = now.minusDays(WINDOW_DAYS)

        val createdAt = createdAtByClub(ids)
        if (createdAt.isEmpty()) return emptyList()

        val heldInWindow = heldInWindowCountByClub(ids, now, windowStart)
        val totalHeld = totalHeldCountByClub(ids, now)
        val responders = recentRespondersByClub(ids, windowStart)
        val aliveMembers = aliveMemberCountByClub(ids)
        val skladchinas = successfulSkladchinasByClub(ids)

        return createdAt.map { (clubId, created) ->
            val held = heldInWindow[clubId] ?: 0
            val alive = aliveMembers[clubId] ?: 0
            val responded = responders[clubId] ?: 0
            ClubCardFacts(
                clubId = clubId,
                meetingsPerMonth = (held / MONTHS_IN_WINDOW * 10.0).roundToInt() / 10.0,
                engagementPercent = if (alive > 0) {
                    (responded.toDouble() / alive * 100).roundToInt().coerceIn(0, 100)
                } else {
                    0
                },
                ageMonths = Period.between(created.toLocalDate(), now.toLocalDate())
                    .toTotalMonths().toInt().coerceAtLeast(0),
                totalMeetings = totalHeld[clubId] ?: 0,
                successfulSkladchinas = skladchinas[clubId] ?: 0,
            )
        }
    }

    /** Existing clubs among [ids] → created_at. Ids without a club row are absent (skipped). */
    private fun createdAtByClub(ids: Set<UUID>): Map<UUID, OffsetDateTime> =
        dsl.select(CLUBS.ID, CLUBS.CREATED_AT)
            .from(CLUBS)
            .where(CLUBS.ID.`in`(ids))
            .fetch()
            .associate { it.value1()!! to it.value2()!! }

    private fun heldInWindowCountByClub(ids: Set<UUID>, now: OffsetDateTime, windowStart: OffsetDateTime): Map<UUID, Int> =
        dsl.select(EVENTS.CLUB_ID, DSL.count())
            .from(EVENTS)
            .where(
                EVENTS.CLUB_ID.`in`(ids)
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(EVENTS.EVENT_DATETIME.lt(now))
                    .and(EVENTS.EVENT_DATETIME.ge(windowStart)),
            )
            .groupBy(EVENTS.CLUB_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }

    private fun totalHeldCountByClub(ids: Set<UUID>, now: OffsetDateTime): Map<UUID, Int> =
        dsl.select(EVENTS.CLUB_ID, DSL.count())
            .from(EVENTS)
            .where(
                EVENTS.CLUB_ID.`in`(ids)
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(EVENTS.EVENT_DATETIME.lt(now)),
            )
            .groupBy(EVENTS.CLUB_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }

    /**
     * Distinct members who responded to the club's recent (window-or-upcoming) non-cancelled events.
     * Member-driven signal (voting/going) — the engagement numerator.
     */
    private fun recentRespondersByClub(ids: Set<UUID>, windowStart: OffsetDateTime): Map<UUID, Int> =
        dsl.select(EVENTS.CLUB_ID, DSL.countDistinct(EVENT_RESPONSES.USER_ID))
            .from(EVENTS)
            .join(EVENT_RESPONSES).on(EVENT_RESPONSES.EVENT_ID.eq(EVENTS.ID))
            .where(
                EVENTS.CLUB_ID.`in`(ids)
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(EVENTS.EVENT_DATETIME.ge(windowStart)),
            )
            .groupBy(EVENTS.CLUB_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }

    /** Alive (active + grace_period) memberships per club — the engagement denominator. */
    private fun aliveMemberCountByClub(ids: Set<UUID>): Map<UUID, Int> =
        dsl.select(MEMBERSHIPS.CLUB_ID, DSL.count())
            .from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.`in`(ids)
                    .and(MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.grace_period)),
            )
            .groupBy(MEMBERSHIPS.CLUB_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }

    private fun successfulSkladchinasByClub(ids: Set<UUID>): Map<UUID, Int> =
        dsl.select(SKLADCHINAS.CLUB_ID, DSL.count())
            .from(SKLADCHINAS)
            .where(
                SKLADCHINAS.CLUB_ID.`in`(ids)
                    .and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.closed_success)),
            )
            .groupBy(SKLADCHINAS.CLUB_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }
}
