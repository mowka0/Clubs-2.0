-- V15: Add optional photo to events, surfaced in the unified activity feed.
-- Nullable: existing events have no photo. Mirrors skladchinas.photo_url.

ALTER TABLE events ADD COLUMN IF NOT EXISTS photo_url TEXT;
