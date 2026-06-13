-- Пакет 1 F5 (attendance/dispute integrity), design:
-- docs/backlog/package1-attendance-dispute-design.md
--
-- 1. events.attendance_marked_at — решение (б)=A. The dispute window must be
--    measured from when the organizer actually marked attendance, not from
--    event_datetime: a late mark (e.g. 47h after the event) otherwise leaves the
--    participant ~1h to dispute before ATT-2 converts it to no_show (-200), while
--    the "оспорьте" DM only fires at mark time. finalizeAttendanceBefore gates on
--    COALESCE(attendance_marked_at, event_datetime) so legacy marked-but-not-finalized
--    rows fall back to the old basis — NO backfill needed, no regression. (F5-05/ATT-1)
--
-- 2. event_responses.dispute_terminal — решение F5-16=A. Makes an organizer's
--    resolve('absent') terminal: without it disputeAttendance gates only on
--    attendance='absent', so a rejected dispute can be re-opened endlessly (ping-pong).
--    A boolean, NOT a 4th attendance_status enum value — a new enum value would not map
--    in ReputationPolicy.attendanceKind (silent ledger drop) and is irreversible.
--    DEFAULT FALSE is the safe state for historical rows (only ever ADDS a dispute block;
--    already-finalized rows are additionally protected by events.attendance_finalized).
--
-- Idempotent DDL (V19/V21/V22/V23 pattern; Testcontainers replay the full Flyway chain).
-- No index: finalizeAttendanceBefore filters attendance_marked=true AND
-- attendance_finalized=false (a tiny set) before touching the timestamp.

ALTER TABLE events ADD COLUMN IF NOT EXISTS attendance_marked_at TIMESTAMPTZ;

ALTER TABLE event_responses ADD COLUMN IF NOT EXISTS dispute_terminal BOOLEAN NOT NULL DEFAULT FALSE;
