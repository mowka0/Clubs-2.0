-- V31: Append-only membership lifecycle log.
--
-- One row per membership status transition that matters for retention/churn:
--   joined    — a brand-new MEMBER membership row is created (free join, paid join). The organizer's
--               own membership is NOT logged (the owner is always present / never churns).
--   left      — a member cancels / leaves (active|grace_period → cancelled). Logged at the moment of
--               the cancel decision, NOT at access-loss: a paid member who cancels keeps access until
--               subscription_expires_at, and that later access-loss is intentionally not a separate
--               event (cancelled rows never enter the grace→expired scan). Read `left` as churn-intent.
--   rejoined  — a previously dead membership comes back (cancelled|expired → active). A renewal while
--               still active/grace is NOT logged (the member never left).
--   expired   — a subscription lapses past grace (grace_period → expired).
--
-- Written from the single chokepoint (JooqMembershipRepository) in the SAME transaction as the
-- status change, so the log can never silently miss a transition. Append-only — never updated or
-- deleted. NO backfill from memberships.joined_at: invented history is a garbage signal, so we
-- accept blindness for one churn cycle and let the log build cleanly from now on.
--
-- This migration + the write path only. Reads (retention, tenure weights, L3 churn signal) land later.

CREATE TYPE membership_event AS ENUM ('joined', 'left', 'rejoined', 'expired');

CREATE TABLE membership_history (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id),
    club_id     UUID NOT NULL REFERENCES clubs(id),
    event       membership_event NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Retention/churn scans a club's timeline; tenure reads a single user's timeline within a club.
CREATE INDEX idx_membership_history_club_occurred ON membership_history(club_id, occurred_at);
CREATE INDEX idx_membership_history_user_club ON membership_history(user_id, club_id, occurred_at);
