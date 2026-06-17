-- V29: split_bill decline-rejection reason. When the organizer REJECTS a decline request they must
-- now justify why the participant should still pay (mirrors the participant's mandatory decline
-- reason, V28). Stored so the rejected participant sees it on the skladchina page and in the DM.
-- Idempotent DDL (project convention; Testcontainers replay the full Flyway chain).
ALTER TABLE skladchina_participants ADD COLUMN IF NOT EXISTS decline_reject_note TEXT;
