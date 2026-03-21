-- V5: Create events table with enum type
-- Stores club events with two-stage voting lifecycle

CREATE TYPE event_status AS ENUM ('upcoming', 'stage_1', 'stage_2', 'completed', 'cancelled');

CREATE TABLE events (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    club_id                   UUID NOT NULL REFERENCES clubs(id),
    created_by                UUID NOT NULL REFERENCES users(id),
    title                     VARCHAR(255) NOT NULL,
    description               TEXT,
    location_text             VARCHAR(500) NOT NULL,
    event_datetime            TIMESTAMPTZ NOT NULL,
    participant_limit         INT NOT NULL CHECK (participant_limit > 0),
    voting_opens_days_before  INT NOT NULL DEFAULT 5 CHECK (voting_opens_days_before >= 1 AND voting_opens_days_before <= 14),
    status                    event_status NOT NULL DEFAULT 'upcoming',
    stage_2_triggered         BOOLEAN NOT NULL DEFAULT FALSE,
    attendance_marked         BOOLEAN NOT NULL DEFAULT FALSE,
    attendance_finalized      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
