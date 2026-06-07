-- V17: reputation_ledger — append-only source of truth for reputation v2 (P1a).
-- See docs/modules/reputation-v2.md. user_club_reputation becomes a derived cache,
-- recomputed from this ledger. Structurally kills bug B (hourly re-inflation):
-- one row per (user, source) + ON CONFLICT DO NOTHING => re-processing is a no-op.

CREATE TYPE reputation_axis AS ENUM ('attendance', 'finance');

CREATE TYPE reputation_kind AS ENUM (
    'ironclad', 'no_show', 'spontaneous', 'spectator', 'confirmed_unresolved',
    'skladchina_paid', 'skladchina_declined', 'skladchina_expired'
);

CREATE TYPE reputation_source AS ENUM ('event', 'skladchina');

CREATE TABLE reputation_ledger (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID              NOT NULL REFERENCES users(id),
    club_id      UUID              NOT NULL REFERENCES clubs(id),
    axis         reputation_axis   NOT NULL,
    kind         reputation_kind   NOT NULL,
    points       INT               NOT NULL,
    -- Behaviour time, NOT processing time. Attendance => events.event_datetime,
    -- finance => skladchinas.closed_at. Same value in live path and V18 backfill
    -- (reproducible anchor for future P1b recency-decay; immutable once written).
    occurred_at  TIMESTAMPTZ       NOT NULL,
    source_type  reputation_source NOT NULL,
    source_id    UUID              NOT NULL,
    created_at   TIMESTAMPTZ       NOT NULL DEFAULT NOW(),
    -- One ledger row per (user, source). `kind` is deliberately NOT in the key:
    -- if a dispute later resolves to a different kind, ON CONFLICT DO NOTHING keeps
    -- the first row instead of appending a second one (which would double-count
    -- confirmations). For both axes a user has exactly one outcome per source.
    CONSTRAINT uq_reputation_ledger_user_source UNIQUE (user_id, source_type, source_id)
);

CREATE INDEX idx_reputation_ledger_user_club ON reputation_ledger (user_id, club_id);
CREATE INDEX idx_reputation_ledger_source    ON reputation_ledger (source_type, source_id);

-- Idempotency claim marker for attendance reputation processing. Set atomically via
-- `UPDATE ... WHERE id=? AND NOT reputation_processed RETURNING id` so the event
-- listener and the hourly poll are mutually exclusive (whoever wins the conditional
-- UPDATE owns the event; the loser no-ops).
ALTER TABLE events ADD COLUMN IF NOT EXISTS reputation_processed BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_events_reputation_pending ON events (id)
    WHERE attendance_finalized AND attendance_marked AND NOT reputation_processed;

-- Display threshold input (see docs § "Право на ошибку"): number of reputation
-- outcomes a user has in a club. Below N=3 the UI shows "Новичок" (no number) so a
-- single early miss doesn't brand a newcomer. The cache still stores the true index.
ALTER TABLE user_club_reputation ADD COLUMN IF NOT EXISTS outcome_count INT NOT NULL DEFAULT 0;
