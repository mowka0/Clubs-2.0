-- V28: split_bill decline-with-approval. Declining a bill must be justified; the organizer
-- approves (→ participant `declined`) or rejects (→ must pay, decline path closed). Mirrors the
-- event-attendance dispute (V6/V24). No new participant status: the participant stays `pending`
-- while a request is open. `decline_rejected` closes the path (like event_responses.dispute_terminal)
-- — no re-request after a reject. Per-template behaviour: only REQUIRES_APPROVAL templates (split_bill)
-- use this; others keep the free instant decline. Idempotent DDL (project convention).
ALTER TABLE skladchina_participants ADD COLUMN IF NOT EXISTS decline_note TEXT;
ALTER TABLE skladchina_participants ADD COLUMN IF NOT EXISTS decline_requested_at TIMESTAMPTZ;
ALTER TABLE skladchina_participants ADD COLUMN IF NOT EXISTS decline_rejected BOOLEAN NOT NULL DEFAULT false;
