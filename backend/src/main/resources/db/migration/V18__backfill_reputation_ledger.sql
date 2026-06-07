-- V18: Backfill reputation_ledger from history + rebuild the user_club_reputation cache.
-- One-time data migration for reputation v2 (P1a). See docs/modules/reputation-v2.md.
--
-- IRREVERSIBLE: user_club_reputation is rebuilt from scratch. A forensic snapshot is
-- taken first, BUT the pre-image is itself inflation-corrupt (bug B) — it is for
-- comparison only, never restore into it. Post-migration reliability/counters are
-- EXPECTED to be lower than pre-migration for already-finalized events; the correctness
-- oracle is event_responses (recomputed below), not the old cache.

-- 1. Forensic snapshot of the pre-image (corrupt by bug B — comparison only).
CREATE TABLE user_club_reputation_pre_v18 AS SELECT * FROM user_club_reputation;

-- 2. Attendance ledger from confirmed responses of finalized + marked events.
--    Anti-farm rule 1: skip rows where the beneficiary is the club owner.
--    kind/points reproduce ReputationService.computeDeltas exactly; disputed/null
--    attendance -> confirmed_unresolved (0 points, still counts a confirmation).
INSERT INTO reputation_ledger (user_id, club_id, axis, kind, points, occurred_at, source_type, source_id)
SELECT
    er.user_id,
    e.club_id,
    'attendance'::reputation_axis,
    k.kind,
    CASE k.kind
        WHEN 'ironclad'    THEN 100
        WHEN 'no_show'     THEN -50
        WHEN 'spontaneous' THEN 30
        WHEN 'spectator'   THEN -20
        ELSE 0
    END,
    e.event_datetime,
    'event'::reputation_source,
    e.id
FROM event_responses er
JOIN events e ON e.id = er.event_id
JOIN clubs c ON c.id = e.club_id
CROSS JOIN LATERAL (
    SELECT (CASE
        WHEN er.attendance = 'attended' AND er.stage_1_vote = 'going' THEN 'ironclad'
        WHEN er.attendance = 'attended' AND er.stage_1_vote = 'maybe' THEN 'spontaneous'
        WHEN er.attendance = 'absent'   AND er.stage_1_vote = 'going' THEN 'no_show'
        WHEN er.attendance = 'absent'   AND er.stage_1_vote = 'maybe' THEN 'spectator'
        ELSE 'confirmed_unresolved'
    END)::reputation_kind AS kind
) k
WHERE er.final_status = 'confirmed'
  AND e.attendance_finalized = TRUE
  AND e.attendance_marked = TRUE
  AND er.user_id <> c.owner_id
ON CONFLICT (user_id, source_type, source_id) DO NOTHING;

-- 3. Finance ledger from closed skladchina history. reputation_applied is intentionally
--    NOT a filter (it is set unconditionally even on a failed / zero delta) — the
--    participant status is the reliable key; the ledger UNIQUE guarantees idempotency.
INSERT INTO reputation_ledger (user_id, club_id, axis, kind, points, occurred_at, source_type, source_id)
SELECT
    p.user_id,
    s.club_id,
    'finance'::reputation_axis,
    (CASE p.status
        WHEN 'paid'                THEN 'skladchina_paid'
        WHEN 'declined'            THEN 'skladchina_declined'
        WHEN 'expired_no_response' THEN 'skladchina_expired'
    END)::reputation_kind,
    CASE p.status
        WHEN 'paid'                THEN 10
        WHEN 'declined'            THEN -5
        WHEN 'expired_no_response' THEN -25
    END,
    s.closed_at,
    'skladchina'::reputation_source,
    s.id
FROM skladchina_participants p
JOIN skladchinas s ON s.id = p.skladchina_id
JOIN clubs c ON c.id = s.club_id
WHERE s.affects_reputation = TRUE
  AND s.status <> 'active'
  AND s.closed_at IS NOT NULL
  AND p.status IN ('paid', 'declined', 'expired_no_response')
  AND p.user_id <> c.owner_id
ON CONFLICT (user_id, source_type, source_id) DO NOTHING;

-- 4. Mark all finalized + marked events as reputation-processed (claim satisfied).
UPDATE events SET reputation_processed = TRUE
WHERE attendance_finalized = TRUE AND attendance_marked = TRUE;

-- 5. Rebuild the cache from the ledger (single source of truth). FILTER-based aggregate
--    matches JooqReputationRepository.recompute; ROUND on numeric = half-away-from-zero
--    = HALF_UP for the non-negative fulfillment ratio (parity with the live BigDecimal).
DELETE FROM user_club_reputation;
INSERT INTO user_club_reputation (
    user_id, club_id, reliability_index, promise_fulfillment_pct,
    total_confirmations, total_attendances, spontaneity_count, outcome_count, updated_at
)
SELECT
    user_id,
    club_id,
    COALESCE(SUM(points), 0),
    CASE WHEN COUNT(*) FILTER (WHERE axis = 'attendance') > 0
        THEN ROUND(
            (COUNT(*) FILTER (WHERE axis = 'attendance' AND kind IN ('ironclad', 'spontaneous')) * 100.0)::numeric
            / COUNT(*) FILTER (WHERE axis = 'attendance'),
            2)
        ELSE 0
    END,
    COUNT(*) FILTER (WHERE axis = 'attendance'),
    COUNT(*) FILTER (WHERE axis = 'attendance' AND kind IN ('ironclad', 'spontaneous')),
    COUNT(*) FILTER (WHERE kind = 'spontaneous'),
    COUNT(*),
    MAX(occurred_at)
FROM reputation_ledger
GROUP BY user_id, club_id;

-- 6. Audit: how many owner self-deal (user, club) attendance pairs rule 1 suppressed.
DO $$
DECLARE
    suppressed_pairs INT;
BEGIN
    SELECT COUNT(*) INTO suppressed_pairs FROM (
        SELECT er.user_id, e.club_id
        FROM event_responses er
        JOIN events e ON e.id = er.event_id
        JOIN clubs c ON c.id = e.club_id
        WHERE er.final_status = 'confirmed'
          AND e.attendance_finalized = TRUE
          AND e.attendance_marked = TRUE
          AND er.user_id = c.owner_id
        GROUP BY er.user_id, e.club_id
    ) t;
    RAISE NOTICE 'V18 reputation backfill: suppressed % owner self-deal (user,club) attendance pair(s) by anti-farm rule 1', suppressed_pairs;
END $$;
