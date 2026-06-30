package com.clubs.clubquality

import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ApplicationStatus
import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.MembershipEvent
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.TransactionStatus
import com.clubs.generated.jooq.enums.TransactionType
import com.clubs.generated.jooq.tables.references.APPLICATIONS
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.MEMBERSHIP_HISTORY
import com.clubs.generated.jooq.tables.references.SKLADCHINAS
import com.clubs.generated.jooq.tables.references.SKLADCHINA_PARTICIPANTS
import com.clubs.generated.jooq.tables.references.TRANSACTIONS
import com.clubs.generated.jooq.tables.references.USERS
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Read-only windowed aggregations for the owner «Статистика» panel. Time windows are computed in
 * Kotlin and bound as parameters (no SQL `interval`) — deterministic and testable, consistent with
 * [JooqClubQualityRepository]. Trends compare a current window against the equal-length prior window
 * and are suppressed (null) when the prior window has no baseline to compare against (§9.4–§9.5).
 */
@Repository
class JooqClubStatsRepository(private val dsl: DSLContext) : ClubStatsRepository {

    private companion object {
        const val RETENTION_WINDOW_DAYS = 30L
        const val ENGAGEMENT_WINDOW_DAYS = 90L
        const val SKLADCHINA_WINDOW_DAYS = 90L
        const val ATTENTION_WINDOW_DAYS = 90L
        const val STALE_APPLICATION_HOURS = 24L
    }

    /** A percent metric over a window plus whether that window had a comparison baseline. */
    private data class WindowValue(val value: Int, val hasBase: Boolean)

    override fun findClubStats(clubId: UUID): ClubStats? {
        // IS_ACTIVE filter mirrors the @RequiresOrganizer aspect (which rejects an inactive club with
        // 404 before this runs) — keeps the repository self-consistent if ever called outside that gate.
        val meta = dsl.select(CLUBS.SUBSCRIPTION_PRICE, CLUBS.ACCESS_TYPE)
            .from(CLUBS).where(CLUBS.ID.eq(clubId).and(CLUBS.IS_ACTIVE.isTrue)).fetchOne()
            ?: return null
        val isPaid = (meta.value1() ?: 0) > 0
        val isClosed = meta.value2() == AccessType.closed

        val now = OffsetDateTime.now()
        val w30 = now.minusDays(RETENTION_WINDOW_DAYS)
        val w60 = now.minusDays(RETENTION_WINDOW_DAYS * 2)
        val w90 = now.minusDays(ENGAGEMENT_WINDOW_DAYS)
        val w180 = now.minusDays(ENGAGEMENT_WINDOW_DAYS * 2)
        val sklStart = now.minusDays(SKLADCHINA_WINDOW_DAYS)
        val sklPrior = now.minusDays(SKLADCHINA_WINDOW_DAYS * 2)
        val attentionStart = now.minusDays(ATTENTION_WINDOW_DAYS)
        val staleBefore = now.minusHours(STALE_APPLICATION_HOURS)

        val retCur = retentionWindow(clubId, w30, now)
        val retPrior = retentionWindow(clubId, w60, w30)

        // The prior window reuses the current alive-member count as its denominator — no historical
        // membership snapshot exists (no membership_history backfill, §9.4), so the trend reads the
        // movement in distinct responders against today's roster. Intended; don't "fix" to a snapshot.
        val alive = aliveMembers(clubId)
        val engCur = engagementWindow(clubId, alive, w90, null)
        val engPrior = engagementWindow(clubId, alive, w180, w90)

        val sklCur = skladchinaWindow(clubId, sklStart, now)
        val sklPriorValue = skladchinaWindow(clubId, sklPrior, sklStart)

        val pending = if (isClosed) pendingApplications(clubId, staleBefore) else null

        return ClubStats(
            clubType = if (isPaid) ClubType.paid else ClubType.free,
            retentionPercent = if (isPaid && retCur.hasBase) retCur.value else null,
            retentionTrend = if (isPaid && retCur.hasBase) trend(retCur, retPrior) else null,
            churnedThisPeriod = churnedMemberCount(clubId, w30),
            rejoinedThisPeriod = rejoinCount(clubId, w30, now),
            engagementPercent = engCur.value,
            engagementTrend = trend(engCur, engPrior),
            skladchinaPaidPercent = if (sklCur.hasBase) sklCur.value else null,
            skladchinaPaidTrend = if (sklCur.hasBase) trend(sklCur, sklPriorValue) else null,
            pendingApplications = pending?.first,
            stalePendingApplications = pending?.second,
            attendanceDisputes = disputeCount(clubId),
            totalMeetings = totalMeetings(clubId, now),
            autoRejectedApplications = if (isClosed) autoRejectedCount(clubId, attentionStart) else null,
            cancelledMeetings = cancelledMeetings(clubId, attentionStart),
        )
    }

    /** Trend of [current] vs [prior]; null when [prior] has no baseline (can't tell "low" from "no data"). */
    private fun trend(current: WindowValue, prior: WindowValue): Trend? {
        if (!prior.hasBase) return null
        val delta = current.value - prior.value
        val direction = when {
            delta > 0 -> TrendDirection.up
            delta < 0 -> TrendDirection.down
            else -> TrendDirection.flat
        }
        return Trend(direction, delta)
    }

    /** Paid renewal rate: distinct renewers ÷ (renewers + churned), over [start, end). */
    private fun retentionWindow(clubId: UUID, start: OffsetDateTime, end: OffsetDateTime): WindowValue {
        val renewers = dsl.select(DSL.countDistinct(TRANSACTIONS.USER_ID))
            .from(TRANSACTIONS)
            .where(
                TRANSACTIONS.CLUB_ID.eq(clubId)
                    .and(TRANSACTIONS.TYPE.eq(TransactionType.renewal))
                    .and(TRANSACTIONS.STATUS.eq(TransactionStatus.completed))
                    .and(TRANSACTIONS.CREATED_AT.ge(start))
                    .and(TRANSACTIONS.CREATED_AT.lt(end)),
            )
            .fetchOne(0, Int::class.java) ?: 0
        val churned = churnCount(clubId, start, end)
        val base = renewers + churned
        val value = if (base > 0) (renewers.toDouble() / base * 100).roundToInt().coerceIn(0, 100) else 0
        return WindowValue(value, base > 0)
    }

    /**
     * Churn *events* (`left` + `expired`) in [start, end) — the flow count that feeds the renewal-rate
     * ratio (denominator alongside renewals). Distinct from [churnedMemberCount]/[findChurnedMembers],
     * which count the win-back *roster* (distinct people still gone now). Free clubs never `expired`.
     */
    private fun churnCount(clubId: UUID, start: OffsetDateTime, end: OffsetDateTime): Int =
        dsl.selectCount().from(MEMBERSHIP_HISTORY)
            .where(
                MEMBERSHIP_HISTORY.CLUB_ID.eq(clubId)
                    .and(MEMBERSHIP_HISTORY.EVENT.`in`(MembershipEvent.left, MembershipEvent.expired))
                    .and(MEMBERSHIP_HISTORY.OCCURRED_AT.ge(start))
                    .and(MEMBERSHIP_HISTORY.OCCURRED_AT.lt(end)),
            )
            .fetchOne(0, Int::class.java) ?: 0

    /**
     * Win-back predicate: a member with a `left`/`expired` event since [since] who does NOT currently
     * belong to the club (so left-then-rejoined people are excluded — they're back). "Belongs" includes
     * `frozen` (gated pending dues, still a member) so a frozen member is NOT counted as churned. Shared
     * by [churnedMemberCount] (the «Ушли/Не продлили за месяц» lever) and [findChurnedMembers] (the
     * drill-down roster) so the lever value equals the roster length. Both LEFT JOIN `memberships`.
     */
    private fun churnedMemberCondition(clubId: UUID, since: OffsetDateTime): Condition =
        MEMBERSHIP_HISTORY.CLUB_ID.eq(clubId)
            .and(MEMBERSHIP_HISTORY.EVENT.`in`(MembershipEvent.left, MembershipEvent.expired))
            .and(MEMBERSHIP_HISTORY.OCCURRED_AT.ge(since))
            .and(
                MEMBERSHIPS.STATUS.isNull
                    .or(
                        MEMBERSHIPS.STATUS.notIn(
                            MembershipStatus.active,
                            MembershipStatus.frozen,
                            MembershipStatus.grace_period,
                        ),
                    ),
            )

    /** Distinct members still gone now who left/expired since [since] — the «Ушли за месяц» lever. */
    private fun churnedMemberCount(clubId: UUID, since: OffsetDateTime): Int =
        dsl.select(DSL.countDistinct(MEMBERSHIP_HISTORY.USER_ID))
            .from(MEMBERSHIP_HISTORY)
            .leftJoin(MEMBERSHIPS)
            .on(
                MEMBERSHIPS.USER_ID.eq(MEMBERSHIP_HISTORY.USER_ID)
                    .and(MEMBERSHIPS.CLUB_ID.eq(MEMBERSHIP_HISTORY.CLUB_ID)),
            )
            .where(churnedMemberCondition(clubId, since))
            .fetchOne(0, Int::class.java) ?: 0

    override fun findChurnedMembers(clubId: UUID): List<ChurnedMember> {
        val since = OffsetDateTime.now().minusDays(RETENTION_WINDOW_DAYS)
        val lastLeft = DSL.max(MEMBERSHIP_HISTORY.OCCURRED_AT)
        return dsl.select(MEMBERSHIP_HISTORY.USER_ID, USERS.FIRST_NAME, USERS.LAST_NAME, USERS.AVATAR_URL, lastLeft)
            .from(MEMBERSHIP_HISTORY)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIP_HISTORY.USER_ID))
            .leftJoin(MEMBERSHIPS)
            .on(
                MEMBERSHIPS.USER_ID.eq(MEMBERSHIP_HISTORY.USER_ID)
                    .and(MEMBERSHIPS.CLUB_ID.eq(MEMBERSHIP_HISTORY.CLUB_ID)),
            )
            .where(churnedMemberCondition(clubId, since))
            .groupBy(MEMBERSHIP_HISTORY.USER_ID, USERS.FIRST_NAME, USERS.LAST_NAME, USERS.AVATAR_URL)
            .orderBy(lastLeft.desc())
            .fetch()
            .map {
                ChurnedMember(
                    userId = it.value1()!!,
                    firstName = it.value2()!!,
                    lastName = it.value3(),
                    avatarUrl = it.value4(),
                    leftAt = it.value5()!!,
                )
            }
    }

    private fun rejoinCount(clubId: UUID, start: OffsetDateTime, end: OffsetDateTime): Int =
        dsl.selectCount().from(MEMBERSHIP_HISTORY)
            .where(
                MEMBERSHIP_HISTORY.CLUB_ID.eq(clubId)
                    .and(MEMBERSHIP_HISTORY.EVENT.eq(MembershipEvent.rejoined))
                    .and(MEMBERSHIP_HISTORY.OCCURRED_AT.ge(start))
                    .and(MEMBERSHIP_HISTORY.OCCURRED_AT.lt(end)),
            )
            .fetchOne(0, Int::class.java) ?: 0

    /** Alive (active + grace_period) memberships — the engagement denominator. */
    private fun aliveMembers(clubId: UUID): Int =
        dsl.selectCount().from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.eq(clubId)
                    .and(MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.grace_period)),
            )
            .fetchOne(0, Int::class.java) ?: 0

    /**
     * Distinct responders ÷ [alive], over non-cancelled events with `event_datetime >= start` (and
     * `< end` when [end] is given). The current window passes `end = null` to include upcoming events —
     * matching the Discovery card's engagement; the prior window bounds both ends. hasBase = events in window.
     */
    private fun engagementWindow(
        clubId: UUID,
        alive: Int,
        start: OffsetDateTime,
        end: OffsetDateTime?,
    ): WindowValue {
        var condition = EVENTS.CLUB_ID.eq(clubId)
            .and(EVENTS.STATUS.ne(EventStatus.cancelled))
            .and(EVENTS.EVENT_DATETIME.ge(start))
        if (end != null) condition = condition.and(EVENTS.EVENT_DATETIME.lt(end))

        val record = dsl.select(DSL.countDistinct(EVENT_RESPONSES.USER_ID), DSL.countDistinct(EVENTS.ID))
            .from(EVENTS)
            .leftJoin(EVENT_RESPONSES).on(EVENT_RESPONSES.EVENT_ID.eq(EVENTS.ID))
            .where(condition)
            .fetchOne()
        val responders = record?.value1() ?: 0
        val events = record?.value2() ?: 0
        val value = if (alive > 0) (responders.toDouble() / alive * 100).roundToInt().coerceIn(0, 100) else 0
        return WindowValue(value, events > 0)
    }

    /**
     * Paid share among settled participants of skladchinas closed in [start, end). Settled =
     * {paid, declined, expired_no_response}; `pending` (undecided) and `released` (let off the hook)
     * are excluded. hasBase = at least one settled participant.
     */
    private fun skladchinaWindow(clubId: UUID, start: OffsetDateTime, end: OffsetDateTime): WindowValue {
        val settled = listOf(
            SkladchinaParticipantStatus.paid,
            SkladchinaParticipantStatus.declined,
            SkladchinaParticipantStatus.expired_no_response,
        )
        val record = dsl.select(
            DSL.count().filterWhere(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.paid)),
            DSL.count().filterWhere(SKLADCHINA_PARTICIPANTS.STATUS.`in`(settled)),
        )
            .from(SKLADCHINA_PARTICIPANTS)
            .join(SKLADCHINAS).on(SKLADCHINAS.ID.eq(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID))
            .where(
                SKLADCHINAS.CLUB_ID.eq(clubId)
                    .and(SKLADCHINAS.CLOSED_AT.ge(start))
                    .and(SKLADCHINAS.CLOSED_AT.lt(end)),
            )
            .fetchOne()
        val paid = record?.value1() ?: 0
        val total = record?.value2() ?: 0
        val value = if (total > 0) (paid.toDouble() / total * 100).roundToInt().coerceIn(0, 100) else 0
        return WindowValue(value, total > 0)
    }

    /** Returns (pending count, stale subset older than 24h). */
    private fun pendingApplications(clubId: UUID, staleBefore: OffsetDateTime): Pair<Int, Int> {
        val record = dsl.select(
            DSL.count(),
            DSL.count().filterWhere(APPLICATIONS.CREATED_AT.lt(staleBefore)),
        )
            .from(APPLICATIONS)
            .where(APPLICATIONS.CLUB_ID.eq(clubId).and(APPLICATIONS.STATUS.eq(ApplicationStatus.pending)))
            .fetchOne()
        return (record?.value1() ?: 0) to (record?.value2() ?: 0)
    }

    private fun autoRejectedCount(clubId: UUID, start: OffsetDateTime): Int =
        dsl.selectCount().from(APPLICATIONS)
            .where(
                APPLICATIONS.CLUB_ID.eq(clubId)
                    .and(APPLICATIONS.STATUS.eq(ApplicationStatus.auto_rejected))
                    .and(APPLICATIONS.RESOLVED_AT.ge(start)),
            )
            .fetchOne(0, Int::class.java) ?: 0

    /**
     * Attendance disputes ever raised against the club's marks (cumulative, all-time). `attendance =
     * disputed` is transient — a resolved/expired dispute flips back to attended/absent and persists only
     * as `dispute_terminal = true` (and the `dispute_note` it stored), so counting `disputed` alone would
     * read ~0 after every event finalizes. We union the live state with both persistent markers to count
     * the organizer's actual dispute track record. The signal is "a member raised a dispute", regardless
     * of who won the resolution (§9.5; design §2 «споры (member-raised)»).
     */
    private fun disputeCount(clubId: UUID): Int =
        dsl.selectCount().from(EVENT_RESPONSES)
            .join(EVENTS).on(EVENTS.ID.eq(EVENT_RESPONSES.EVENT_ID))
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(
                        EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.disputed)
                            .or(EVENT_RESPONSES.DISPUTE_TERMINAL.isTrue)
                            .or(EVENT_RESPONSES.DISPUTE_NOTE.isNotNull),
                    ),
            )
            .fetchOne(0, Int::class.java) ?: 0

    /** All-time held (past, non-cancelled) events — the «N из M» denominator for disputes. */
    private fun totalMeetings(clubId: UUID, now: OffsetDateTime): Int =
        dsl.selectCount().from(EVENTS)
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(EVENTS.EVENT_DATETIME.lt(now)),
            )
            .fetchOne(0, Int::class.java) ?: 0

    private fun cancelledMeetings(clubId: UUID, start: OffsetDateTime): Int =
        dsl.selectCount().from(EVENTS)
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.eq(EventStatus.cancelled))
                    .and(EVENTS.EVENT_DATETIME.ge(start)),
            )
            .fetchOne(0, Int::class.java) ?: 0
}
