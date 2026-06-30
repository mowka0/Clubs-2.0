-- V43: Add a 'cancelled' application status (applicant self-withdrawal).
--
-- A user who applied to a club by mistake (or changed their mind before the organizer decided) can now
-- withdraw their own PENDING application. The application moves to a dedicated terminal status 'cancelled'
-- — distinct from 'rejected' (organizer declined) and 'auto_rejected' (timed out): the applicant chose to
-- close it themselves.
--
-- 'cancelled' is NOT an active status, so the partial unique index applications_one_active_per_user_club
-- (V42, WHERE status IN ('pending','approved')) lets the user re-apply later — same as rejected/auto_rejected.
--
-- ALTER TYPE ... ADD VALUE runs inside the Flyway transaction on PostgreSQL 16 because the new value is not
-- USED within this migration (it is only referenced by application code at runtime). Same mechanism as V19.
-- IF NOT EXISTS keeps the migration idempotent if the value was applied manually for local jOOQ codegen.

ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'cancelled';
