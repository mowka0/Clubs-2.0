-- Skladchina reputation redesign (docs/backlog/skladchina-reputation-redesign.md):
--
-- 1. 'released' participant status — resolution of F5-02. When a skladchina is
--    closed BEFORE its deadline (goal reached / everyone answered / manual close),
--    pending participants broke no promise — the promise was "answer by the
--    deadline" and the deadline never came. They are moved to 'released' (neutral,
--    NO reputation ledger row) instead of 'expired_no_response' (-40). The
--    glossary wins: "expired = the deadline has passed". ALTER TYPE ... ADD VALUE
--    pattern proven by V19 (Testcontainers run the full Flyway chain).
--
-- 2. skladchinas.reminder_sent_at — dedup flag for the new deadline-reminder
--    scheduler (SkladchinaReminderScheduler): pending participants of a
--    reputation-affecting skladchina get a DM ~24h before the deadline. The -40
--    silence penalty is only legitimate if the system warned twice (creation DM
--    + reminder DM) — launch-blocker of the redesign. Same dedup mechanic as
--    events.confirm_reminder_sent (V21); a timestamp instead of a boolean so the
--    send moment is auditable.
--
-- IF NOT EXISTS keeps both statements idempotent if applied manually for local
-- jOOQ codegen. PostgreSQL 16 allows ALTER TYPE ... ADD VALUE inside the Flyway
-- transaction because the new value is not used within this migration.

ALTER TYPE skladchina_participant_status ADD VALUE IF NOT EXISTS 'released';

ALTER TABLE skladchinas ADD COLUMN IF NOT EXISTS reminder_sent_at TIMESTAMPTZ;
