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
import org.jooq.impl.DSL
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

    override fun lockEventSlots(eventId: UUID) {
        // Transaction-scoped: auto-released on commit/rollback, so no unlock call and no leak
        // on exception. hashtext on a prefixed key — same pattern as JooqReputationRepository
        // .recompute; the prefix keeps the key space distinct from the recompute locks.
        dsl.execute("SELECT pg_advisory_xact_lock(hashtext(?))", "event-slots:$eventId")
    }

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

    override fun expireUnconfirmedForStartedEvents(now: OffsetDateTime): Int {
        // Started, stage-2-triggered, non-cancelled events. Status-independent on purpose:
        // EventCompletionService flips stage_2 -> completed after a 6h grace, so gating on
        // status = stage_2 would miss no-confirms on events older than the grace.
        val startedTriggeredEventIds = dsl.select(EVENTS.ID)
            .from(EVENTS)
            .where(
                EVENTS.STAGE_2_TRIGGERED.isTrue
                    .and(EVENTS.EVENT_DATETIME.lessOrEqual(now))
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
            )
        return dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.STAGE_2_VOTE, Stage_2Vote.expired_no_confirm)
            .set(EVENT_RESPONSES.FINAL_STATUS, FinalStatus.expired_no_confirm)
            .set(EVENT_RESPONSES.STAGE_2_TIMESTAMP, now)
            .set(EVENT_RESPONSES.UPDATED_AT, now)
            .where(
                EVENT_RESPONSES.STAGE_2_VOTE.isNull
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.`in`(Stage_1Vote.going, Stage_1Vote.maybe))
                    .and(EVENT_RESPONSES.EVENT_ID.`in`(startedTriggeredEventIds))
            )
            .execute()
    }

    override fun findRespondersWithUsers(eventId: UUID): List<EventResponderInfo> =
        dsl.select(
            EVENT_RESPONSES.USER_ID,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
            USERS.AVATAR_URL,
            EVENT_RESPONSES.STAGE_1_VOTE,
            EVENT_RESPONSES.FINAL_STATUS,
            EVENT_RESPONSES.ATTENDANCE,
            EVENT_RESPONSES.DISPUTE_NOTE
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
                    finalStatus = r.get(EVENT_RESPONSES.FINAL_STATUS),
                    attendance = r.get(EVENT_RESPONSES.ATTENDANCE),
                    disputeNote = r.get(EVENT_RESPONSES.DISPUTE_NOTE)
                )
            }

    override fun findUnconfirmedVoterTelegramIds(eventId: UUID): List<Long> =
        dsl.select(USERS.TELEGRAM_ID)
            .from(EVENT_RESPONSES)
            .join(USERS).on(USERS.ID.eq(EVENT_RESPONSES.USER_ID))
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.`in`(Stage_1Vote.going, Stage_1Vote.maybe))
                    .and(EVENT_RESPONSES.STAGE_2_VOTE.isNull)
            )
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()

    override fun findStage2TargetTelegramIds(eventId: UUID): List<Long> =
        dsl.select(USERS.TELEGRAM_ID)
            .from(EVENT_RESPONSES)
            .join(USERS).on(USERS.ID.eq(EVENT_RESPONSES.USER_ID))
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.`in`(Stage_1Vote.going, Stage_1Vote.maybe))
            )
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()

    override fun findTelegramIdsByEventAndUserIds(eventId: UUID, userIds: List<UUID>): List<Long> {
        if (userIds.isEmpty()) return emptyList()
        return dsl.select(USERS.TELEGRAM_ID)
            .from(EVENT_RESPONSES)
            .join(USERS).on(USERS.ID.eq(EVENT_RESPONSES.USER_ID))
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.`in`(userIds))
            )
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()
    }

    override fun setAttendance(eventId: UUID, userId: UUID, attended: Boolean): Int {
        val target = if (attended) AttendanceStatus.attended else AttendanceStatus.absent
        return dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.ATTENDANCE, target)
            // F5-16: a fresh organizer mark re-opens the dispute right (the participant may
            // contest the NEW mark). Idempotent re-submits match 0 rows (IS DISTINCT FROM target
            // below), so a previously resolved/terminal row is never re-opened by a no-op re-mark.
            .set(EVENT_RESPONSES.DISPUTE_TERMINAL, false)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
                    // Only the final roster is markable (PRD §4.4.3). A going/maybe voter
                    // who never confirmed is not on it — reputation ignores non-confirmed
                    // rows anyway, so marking them was a no-op that only cluttered the UI.
                    .and(EVENT_RESPONSES.FINAL_STATUS.eq(FinalStatus.confirmed))
                    // F5-15(1): never overwrite an active dispute — only resolveDispute may move
                    // a `disputed` row. IS DISTINCT FROM, NOT <>: a first mark has attendance=NULL
                    // and `NULL <> 'disputed'` is NULL (row silently skipped → happy-path broken).
                    .and(EVENT_RESPONSES.ATTENDANCE.isDistinctFrom(AttendanceStatus.disputed))
                    // Only genuine transitions: an idempotent re-submit (row already at target)
                    // matches 0 rows. So markedCount counts real changes and `updated > 0 && !attended`
                    // means exactly "newly absent" for the DM (F5-15.2) without a prior-state read.
                    .and(EVENT_RESPONSES.ATTENDANCE.isDistinctFrom(target))
                    // F5-09: never write attendance onto an already-finalized event (TOCTOU with the
                    // finalizer). Subquery on EVENTS — event_responses.attendance_finalized is dead
                    // (never written); the live flag lives on events.
                    .and(EVENT_RESPONSES.EVENT_ID.`in`(notFinalizedEvent(eventId)))
            )
            .execute()
    }

    override fun disputeAbsentAttendance(eventId: UUID, userId: UUID, note: String?): Int =
        dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.ATTENDANCE, AttendanceStatus.disputed)
            .set(EVENT_RESPONSES.DISPUTE_NOTE, note)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.absent))
                    // F5-16: a resolved/terminal mark cannot be re-disputed (ping-pong). The dispute
                    // endpoint has no authz beyond JWT, so this DB guard — not just the UI — is
                    // load-bearing.
                    .and(EVENT_RESPONSES.DISPUTE_TERMINAL.isFalse)
                    // F5-10 (ordering A): if the finalizer already committed attendance_finalized=true
                    // the window is closed — refuse (no spurious disputed row, no false organizer DM).
                    // ATT-2 (resolveExpiredDisputesToAbsent) remains the backstop for ordering B
                    // (dispute commits before ATT-2 in the same finalize txn) — do NOT remove it.
                    .and(EVENT_RESPONSES.EVENT_ID.`in`(notFinalizedEvent(eventId)))
            )
            .execute()

    override fun resolveDisputedAttendance(eventId: UUID, userId: UUID, attended: Boolean): Int =
        dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.ATTENDANCE, if (attended) AttendanceStatus.attended else AttendanceStatus.absent)
            // F5-16: the organizer has ruled — the mark is terminal, no re-dispute.
            .set(EVENT_RESPONSES.DISPUTE_TERMINAL, true)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.disputed))
            )
            .execute()

    override fun resolveExpiredDisputesToAbsent(eventIds: List<UUID>): Int {
        if (eventIds.isEmpty()) return 0
        return dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.ATTENDANCE, AttendanceStatus.absent)
            // F5-16: the window expired with the dispute unresolved — the mark is terminal too
            // (audit symmetry; attendance_finalized already blocks re-dispute, this is belt-and-suspenders).
            .set(EVENT_RESPONSES.DISPUTE_TERMINAL, true)
            .where(
                EVENT_RESPONSES.EVENT_ID.`in`(eventIds)
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.disputed))
                    // Only the final (confirmed) roster feeds reputation. A disputed mark can only
                    // exist on a confirmed row today (setAttendance guards on confirmed), but guard
                    // here too so a non-confirmed disputed row (corruption / future change) can never
                    // be silently mutated while the ledger ignores it. Mirrors setAttendance.
                    .and(EVENT_RESPONSES.FINAL_STATUS.eq(FinalStatus.confirmed))
            )
            .execute()
    }

    // F5-09 / F5-10: "this event is not yet finalized" as a subquery on EVENTS, for use inside
    // EVENT_RESPONSES UPDATEs. event_responses.attendance_finalized is dead (never written) — the
    // authoritative flag is events.attendance_finalized, set by the finalizer.
    private fun notFinalizedEvent(eventId: UUID) =
        DSL.select(EVENTS.ID).from(EVENTS)
            .where(EVENTS.ID.eq(eventId).and(EVENTS.ATTENDANCE_FINALIZED.isFalse))

    override fun findConfirmedActiveEventObligations(userId: UUID, clubId: UUID): List<EventObligation> =
        dsl.select(EVENTS.ID, EVENTS.EVENT_DATETIME)
            .from(EVENT_RESPONSES)
            .join(EVENTS).on(EVENTS.ID.eq(EVENT_RESPONSES.EVENT_ID))
            .where(
                EVENT_RESPONSES.USER_ID.eq(userId)
                    .and(EVENTS.CLUB_ID.eq(clubId))
                    .and(EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_1, EventStatus.stage_2))
                    // A finalized event's outcome belongs to the attendance pipeline — never
                    // override real attendance with an exit no_show.
                    .and(EVENTS.ATTENDANCE_FINALIZED.isFalse)
                    .and(EVENT_RESPONSES.FINAL_STATUS.eq(FinalStatus.confirmed))
            )
            .fetch { r -> EventObligation(r.get(EVENTS.ID)!!, r.get(EVENTS.EVENT_DATETIME)!!) }

    override fun promoteFirstWaitlisted(eventId: UUID): Boolean {
        val first = findFirstWaitlisted(eventId) ?: return false
        updateStage2Vote(first.id, Stage_2Vote.confirmed, FinalStatus.confirmed)
        return true
    }

    override fun deleteByUserAndClubAndActiveEvents(userId: UUID, clubId: UUID): Int {
        val activeEventIds = dsl.select(EVENTS.ID)
            .from(EVENTS)
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_1, EventStatus.stage_2))
                    // Exclude attendance-finalized events (reachable while status is still stage_2 —
                    // finalize flips the flag, a separate sweep flips status later). Their REAL
                    // attendance outcome is owned by the reputation pipeline; deleting the confirmed
                    // row here would erase a not-yet-processed no_show. Same scope the exit
                    // enumeration uses (findConfirmedActiveEventObligations also excludes finalized).
                    .and(EVENTS.ATTENDANCE_FINALIZED.isFalse)
            )
        return dsl.deleteFrom(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.USER_ID.eq(userId)
                    .and(EVENT_RESPONSES.EVENT_ID.`in`(activeEventIds))
            )
            .execute()
    }

    override fun findAttendedUserIds(eventId: UUID): List<UUID> =
        dsl.select(EVENT_RESPONSES.USER_ID)
            .from(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.attended))
            )
            .fetch()
            .mapNotNull { it.value1() }

    private fun countByStage1Vote(eventId: UUID, vote: Stage_1Vote): Int =
        dsl.selectCount().from(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId).and(EVENT_RESPONSES.STAGE_1_VOTE.eq(vote)))
            .fetchOne(0, Int::class.java) ?: 0
}
