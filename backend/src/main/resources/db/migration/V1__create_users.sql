-- V1: Create users table
-- Stores Telegram user profiles synced during authentication

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    telegram_id     BIGINT NOT NULL UNIQUE,
    telegram_username VARCHAR(255),
    first_name      VARCHAR(255) NOT NULL,
    last_name       VARCHAR(255),
    avatar_url      TEXT,
    city            VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
