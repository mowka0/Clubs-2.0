package com.clubs.event

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.enums.Stage_2Vote
import java.time.OffsetDateTime
import java.util.UUID

interface EventResponseRepository {

    fun upsertStage1Vote(eventId: UUID, userId: UUID, vote: Stage_1Vote): EventResponse

    fun findByEventAndUser(eventId: UUID, userId: UUID): EventResponse?

    fun countByVote(eventId: UUID): Map<String, Int>

    fun countConfirmed(eventId: UUID): Int

    /**
     * S2-01/F5-07/F5-11: takes a per-event transaction-scoped Postgres advisory lock
     * (`pg_advisory_xact_lock`) serializing Stage 2 slot mutations. Must be called inside a
     * transaction; released automatically on commit/rollback. Both confirm and decline take it
     * before reading slot state, so capacity checks and waitlist promotion never race.
     */
    fun lockEventSlots(eventId: UUID)

    fun findFirstWaitlisted(eventId: UUID): EventResponse?

    fun updateStage2Vote(id: UUID, vote: Stage_2Vote, finalStatus: FinalStatus): EventResponse

    fun findGoingByEventOrderByTimestamp(eventId: UUID): List<EventResponse>

    fun findMaybeByEventOrderByTimestamp(eventId: UUID): List<EventResponse>

    /**
     * Feature A auto-expire: for every started, stage-2-triggered, non-cancelled event,
     * moves going/maybe responses that were never confirmed (stage_2_vote IS NULL) to
     * [com.clubs.generated.jooq.enums.Stage_2Vote.expired_no_confirm] /
     * [com.clubs.generated.jooq.enums.FinalStatus.expired_no_confirm]. Single bulk update;
     * the NULL predicate makes it idempotent and leaves confirmed/waitlisted/declined
     * untouched. Returns rows updated.
     */
    fun expireUnconfirmedForStartedEvents(now: OffsetDateTime): Int

    /**
     * Telegram ids of going/maybe voters who have NOT yet confirmed (stage_2_vote IS NULL).
     * Target of the Feature A "подтверди участие" reminder (~2h before the event).
     */
    fun findUnconfirmedVoterTelegramIds(eventId: UUID): List<Long>

    /**
     * Telegram ids of going/maybe voters — the users who must confirm participation when
     * Stage 2 starts (PRD §4.4.2 step 1). not_going voters have nothing to confirm and are
     * excluded (GAP-009). Used by NotificationService.sendStage2Started.
     */
    fun findStage2TargetTelegramIds(eventId: UUID): List<Long>

    /**
     * F5-15(2): telegram IDs for the given (eventId, userIds) — the participants who NEWLY became
     * absent in this mark. Used by NotificationService.sendAttendanceMarked so a re-mark does not
     * re-DM everyone already marked absent. Empty input → empty result (no query).
     */
    fun findTelegramIdsByEventAndUserIds(eventId: UUID, userIds: List<UUID>): List<Long>

    /**
     * Bulk-sets ATTENDANCE for the given (eventId, userId) pair to attended/absent.
     * Returns number of rows updated (0 if user has no response row).
     */
    fun setAttendance(eventId: UUID, userId: UUID, attended: Boolean): Int

    /**
     * Marks an absent attendance as disputed, storing an optional free-text [note] from the
     * participant. Returns rows updated (0 if user is not absent).
     */
    fun disputeAbsentAttendance(eventId: UUID, userId: UUID, note: String?): Int

    /**
     * Resolves a disputed attendance into attended/absent. Returns rows updated (0 if not disputed).
     */
    fun resolveDisputedAttendance(eventId: UUID, userId: UUID, attended: Boolean): Int

    /**
     * ATT-2: at finalization, converts any still-`disputed` attendance on the given events back to
     * `absent` (the dispute window expired without an organizer correction → the original mark
     * stands). Returns rows updated. Empty input → 0 (no query).
     */
    fun resolveExpiredDisputesToAbsent(eventIds: List<UUID>): Int

    /**
     * Cascade-delete on club leave: removes [userId]'s responses to all
     * non-finalised events of [clubId] (status IN upcoming/stage_1/stage_2).
     * Completed and cancelled events are preserved — attendance history is
     * the source of truth for reputation. Returns number of rows deleted.
     */
    fun deleteByUserAndClubAndActiveEvents(userId: UUID, clubId: UUID): Int

    /**
     * Returns all responders to the event (those with a stage-1 vote) joined with
     * their user info, ordered going → maybe → not_going then by vote timestamp.
     * Used to render the "who's coming" list on the event page.
     */
    fun findRespondersWithUsers(eventId: UUID): List<EventResponderInfo>
}

/** Repository row: a responder's user info + raw vote/final-status/attendance enums. */
data class EventResponderInfo(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val stage1Vote: Stage_1Vote?,
    val finalStatus: FinalStatus?,
    val attendance: AttendanceStatus?,
    val disputeNote: String?
)
