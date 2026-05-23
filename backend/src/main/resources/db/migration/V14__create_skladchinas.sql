-- V14: Create skladchinas (сборы) — community fund-raising инструмент.
-- See docs/modules/skladchina.md for product spec.

CREATE TYPE skladchina_mode AS ENUM ('fixed_equal', 'fixed_individual', 'voluntary');
CREATE TYPE skladchina_status AS ENUM ('active', 'closed_success', 'closed_failed', 'cancelled');
CREATE TYPE skladchina_participant_status AS ENUM ('pending', 'paid', 'declined', 'expired_no_response');

CREATE TABLE skladchinas (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    club_id             UUID NOT NULL REFERENCES clubs(id),
    creator_id          UUID NOT NULL REFERENCES users(id),

    title               VARCHAR(255) NOT NULL,
    description         TEXT,
    rules               TEXT,
    photo_url           VARCHAR(500),

    payment_mode        skladchina_mode NOT NULL,
    total_goal_kopecks  BIGINT CHECK (total_goal_kopecks IS NULL OR total_goal_kopecks > 0),
    payment_link        TEXT NOT NULL,
    payment_method_note TEXT,

    deadline            TIMESTAMPTZ NOT NULL,
    affects_reputation  BOOLEAN NOT NULL DEFAULT false,

    status              skladchina_status NOT NULL DEFAULT 'active',
    closed_at           TIMESTAMPTZ,
    closed_by           UUID REFERENCES users(id),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_skladchinas_club_id ON skladchinas(club_id);
CREATE INDEX idx_skladchinas_status_deadline ON skladchinas(status, deadline);

CREATE TABLE skladchina_participants (
    skladchina_id            UUID NOT NULL REFERENCES skladchinas(id) ON DELETE CASCADE,
    user_id                  UUID NOT NULL REFERENCES users(id),

    expected_amount_kopecks  BIGINT CHECK (expected_amount_kopecks IS NULL OR expected_amount_kopecks > 0),
    declared_amount_kopecks  BIGINT CHECK (declared_amount_kopecks IS NULL OR declared_amount_kopecks > 0),

    status                   skladchina_participant_status NOT NULL DEFAULT 'pending',

    paid_at                  TIMESTAMPTZ,
    declined_at              TIMESTAMPTZ,
    reputation_applied       BOOLEAN NOT NULL DEFAULT false,

    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (skladchina_id, user_id)
);

CREATE INDEX idx_skladchina_participants_user_id ON skladchina_participants(user_id);
