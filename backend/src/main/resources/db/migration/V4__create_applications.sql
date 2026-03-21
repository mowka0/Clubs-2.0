-- V4: Create applications table with enum type
-- Tracks membership applications for closed clubs

CREATE TYPE application_status AS ENUM ('pending', 'approved', 'rejected', 'auto_rejected');

CREATE TABLE applications (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id),
    club_id         UUID NOT NULL REFERENCES clubs(id),
    answer_text     TEXT,
    status          application_status NOT NULL DEFAULT 'pending',
    rejected_reason TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ,
    UNIQUE (user_id, club_id, status)
);
