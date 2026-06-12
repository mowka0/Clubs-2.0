-- ATT-3: optional free-text note a participant can attach when disputing their attendance.
-- Nullable; shown to the organizer in the "Оспоренные отметки" resolve block. Idempotent DDL.
ALTER TABLE event_responses ADD COLUMN IF NOT EXISTS dispute_note TEXT;
