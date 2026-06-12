-- Dedup flags for the two event reminder schedulers (EventReminderScheduler):
--   confirm_reminder_sent    — the "подтверди участие" DM sent ~2h before the event
--                              to going/maybe voters who haven't confirmed yet (Feature A).
--   attendance_reminder_sent — the "отметь явку" DM sent ~24h after the event to the
--                              organizer (Feature B).
-- Both default FALSE; the scheduler flips them so a recurring poll sends each DM exactly once.
-- IF NOT EXISTS keeps the migration idempotent (manual apply for local jOOQ codegen).

ALTER TABLE events ADD COLUMN IF NOT EXISTS confirm_reminder_sent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE events ADD COLUMN IF NOT EXISTS attendance_reminder_sent BOOLEAN NOT NULL DEFAULT FALSE;
