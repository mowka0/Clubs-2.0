package com.clubs.event

import com.clubs.common.dto.PageResponse
import com.clubs.generated.jooq.enums.EventStatus
import java.time.OffsetDateTime
import java.util.UUID

interface EventRepository {

    fun create(request: CreateEventRequest, clubId: UUID, createdBy: UUID): Event

    fun findById(id: UUID): Event?

    fun findByClubId(clubId: UUID, status: EventStatus?, page: Int, size: Int): PageResponse<EventListItemDto>

    /**
     * Returns ALL events of the given club (any status, any datetime) paired with the
     * cached going-vote count. Used by the unified activity feed where pagination /
     * sorting is decided at a higher level (in-memory merge with skladchinas).
     */
    fun findAllByClubWithGoingCount(clubId: UUID): List<EventWithGoingCount>

    /**
     * IDs of the club's events that require an action from [userId] right now:
     * a stage-1 vote (voting open, not yet voted) or a stage-2 confirmation
     * (voted going/maybe, not yet confirmed). Used by the activity feed to pin
     * action-required events to the top. Same predicate as [findMyFeed]'s
     * action-required ordering.
     */
    fun findActionRequiredEventIds(clubId: UUID, userId: UUID, now: OffsetDateTime): Set<UUID>

    fun findMyFeed(userId: UUID, page: Int, size: Int): PageResponse<MyFeedItem>

    fun getVoteCounts(eventId: UUID): Map<String, Int>

    fun findEventsToTriggerStage2(): List<Event>

    fun findNextUpcomingEvent(now: OffsetDateTime): Event?

    fun transitionToStage2(id: UUID)

    fun markAttendanceMarked(id: UUID)

    // --- Reminder schedulers (EventReminderScheduler) ---

    /**
     * Feature A: events in stage_2 that start within (now, until] and whose confirm reminder
     * hasn't been sent. `until` = now + the "hours before" window (default 2h).
     */
    fun findEventsNeedingConfirmReminder(now: OffsetDateTime, until: OffsetDateTime): List<Event>

    fun markConfirmReminderSent(id: UUID)

    /**
     * Feature B: past, non-cancelled events whose attendance is still unmarked and whose
     * organizer reminder hasn't been sent. [cutoff] = now - the "hours after" window (default 24h).
     */
    fun findEventsNeedingAttendanceReminder(cutoff: OffsetDateTime): List<Event>

    fun markAttendanceReminderSent(id: UUID)

    /** Telegram id of the event's club organizer (owner), or null if unset. */
    fun findOrganizerTelegramId(eventId: UUID): Long?

    /** Finalizes attendance for past, marked, not-yet-finalized events. Returns the finalized event ids. */
    fun finalizeAttendanceBefore(eventDatetimeCutoff: OffsetDateTime): List<UUID>

    /**
     * EXP-2: neutrally finalizes past, **unmarked**, not-yet-finalized, non-cancelled events whose
     * datetime is at/before [eventDatetimeCutoff]. Sets `attendance_finalized = true` while leaving
     * `attendance_marked = false`, so the reputation pipeline (which claims only marked+finalized
     * events) never produces ledger rows for them — the event simply doesn't count. Returns the
     * finalized event ids (for logging). NO AttendanceFinalizedEvent is published for these.
     */
    fun neutrallyFinalizeUnmarkedBefore(eventDatetimeCutoff: OffsetDateTime): List<UUID>

    /**
     * Moves active events (upcoming / stage_1 / stage_2) whose datetime is before [cutoff]
     * to [EventStatus.completed]. Does not touch already-completed or cancelled events.
     * Returns the number of rows updated.
     */
    fun markPastEventsCompleted(cutoff: OffsetDateTime): Int
}

data class EventWithGoingCount(
    val event: Event,
    val goingCount: Int
)
