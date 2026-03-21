-- V3: Create memberships table with enum types
-- Tracks user-club membership status and subscription lifecycle

CREATE TYPE membership_status AS ENUM ('active', 'grace_period', 'cancelled', 'expired');
CREATE TYPE membership_role AS ENUM ('member', 'organizer');

CREATE TABLE memberships (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id),
    club_id                 UUID NOT NULL REFERENCES clubs(id),
    status                  membership_status NOT NULL DEFAULT 'active',
    role                    membership_role NOT NULL DEFAULT 'member',
    joined_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    subscription_expires_at TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, club_id)
);
