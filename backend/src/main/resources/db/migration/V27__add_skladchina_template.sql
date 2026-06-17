-- V27: skladchina templates — split_bill first.
-- See docs/backlog/skladchina-templates-architecture.md.
-- One engine + per-template strategy: `template` selects the strategy. `event_id` links a
-- split_bill skladchina back to the event whose bill is being split (reused later by phase D
-- booking). Existing rows default to 'custom' (the Phase A behaviour). No backfill needed.
-- Idempotent DDL (project convention; Testcontainers replay the full Flyway chain).

CREATE TYPE skladchina_template AS ENUM ('custom', 'split_bill', 'gear', 'booking', 'birthday');

ALTER TABLE skladchinas ADD COLUMN IF NOT EXISTS template skladchina_template NOT NULL DEFAULT 'custom';
ALTER TABLE skladchinas ADD COLUMN IF NOT EXISTS event_id UUID REFERENCES events(id);

CREATE INDEX IF NOT EXISTS idx_skladchinas_event_id ON skladchinas(event_id);
