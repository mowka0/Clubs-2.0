-- V6: Create event_responses table with enum types
-- Tracks user votes through stage 1, stage 2, and attendance

CREATE TYPE stage_1_vote AS ENUM ('going', 'maybe', 'not_going');
CREATE TYPE stage_2_vote AS ENUM ('confirmed', 'declined', 'waitlisted');
CREATE TYPE final_status AS ENUM ('confirmed', 'waitlisted', 'declined');
CREATE TYPE attendance_status AS ENUM ('attended', 'absent', 'disputed');

CREATE TABLE event_responses (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id              UUID NOT NULL REFERENCES events(id),
    user_id               UUID NOT NULL REFERENCES users(id),
    stage_1_vote          stage_1_vote,
    stage_1_timestamp     TIMESTAMPTZ,
    stage_2_vote          stage_2_vote,
    stage_2_timestamp     TIMESTAMPTZ,
    final_status          final_status,
    attendance            attendance_status,
    attendance_finalized  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (event_id, user_id)
);
