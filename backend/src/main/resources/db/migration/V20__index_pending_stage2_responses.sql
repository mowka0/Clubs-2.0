-- Supports the Feature A auto-expire sweep (Stage2Service.expireUnconfirmedParticipants,
-- runs every events.stage2-expire-poll-ms, default 5 min):
--   UPDATE event_responses SET ... WHERE stage_2_vote IS NULL AND stage_1_vote IN (...)
--     AND event_id IN (started, triggered, non-cancelled events)
--
-- The self-limiting predicate is `stage_2_vote IS NULL`: after each sweep only the few
-- not-yet-acted Stage-2 rows remain NULL. A partial index on exactly that predicate keeps
-- the recurring sweep O(pending rows) instead of scanning event_responses / all past events
-- on every poll. Mirrors the V17 idx_events_reputation_pending pattern (a partial index
-- whose predicate matches a scheduled sweep's WHERE). event_id is the indexed column so the
-- semi-join to events resolves by PK.

CREATE INDEX IF NOT EXISTS idx_event_responses_pending_stage2
    ON event_responses (event_id)
    WHERE stage_2_vote IS NULL;
