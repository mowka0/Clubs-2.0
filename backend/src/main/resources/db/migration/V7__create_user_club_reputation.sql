-- V7: Create user_club_reputation table
-- Per-club reputation metrics recalculated after attendance finalization

CREATE TABLE user_club_reputation (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID NOT NULL REFERENCES users(id),
    club_id                  UUID NOT NULL REFERENCES clubs(id),
    reliability_index        INT NOT NULL DEFAULT 100,
    promise_fulfillment_pct  NUMERIC(5,2) NOT NULL DEFAULT 0,
    total_confirmations      INT NOT NULL DEFAULT 0,
    total_attendances        INT NOT NULL DEFAULT 0,
    spontaneity_count        INT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, club_id)
);
