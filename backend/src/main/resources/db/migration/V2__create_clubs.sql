-- V2: Create clubs table with enum types
-- Stores club profiles, settings, and metadata

CREATE TYPE club_category AS ENUM ('sport', 'creative', 'food', 'board_games', 'cinema', 'education', 'travel', 'other');
CREATE TYPE access_type AS ENUM ('open', 'closed', 'private');

CREATE TABLE clubs (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id             UUID NOT NULL REFERENCES users(id),
    name                 VARCHAR(60) NOT NULL,
    description          VARCHAR(500) NOT NULL,
    category             club_category NOT NULL,
    access_type          access_type NOT NULL DEFAULT 'open',
    city                 VARCHAR(255) NOT NULL,
    district             VARCHAR(255),
    member_limit         INT NOT NULL CHECK (member_limit >= 10 AND member_limit <= 80),
    subscription_price   INT NOT NULL DEFAULT 0 CHECK (subscription_price >= 0),
    avatar_url           TEXT,
    rules                TEXT,
    application_question VARCHAR(200),
    invite_link          VARCHAR(255) UNIQUE,
    telegram_group_id    BIGINT,
    activity_rating      INT NOT NULL DEFAULT 0,
    member_count         INT NOT NULL DEFAULT 0,
    is_active            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
