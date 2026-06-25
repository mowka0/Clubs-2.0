-- F5-14 event cancellation: optional organizer-provided reason, shown to interested
-- participants in the DM and on the cancelled event page. NULL = no reason given.
ALTER TABLE events ADD COLUMN IF NOT EXISTS cancellation_reason TEXT;
