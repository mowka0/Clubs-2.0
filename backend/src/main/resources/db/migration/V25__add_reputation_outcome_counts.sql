-- P1b PR-0: kept/broke/neutral outcome counts on the reputation cache.
-- Trust 0-100 (P1b) is a Bayesian DECAYED fraction of KEPT promises, classified BY KIND
-- (never by points): V18 backfilled historic rows with stale magnitudes (no_show -50,
-- spontaneous +30, spectator -20, skladchina_expired -25), so reliability_index = Σpoints
-- is unreliable across the V18 boundary, while kept/broke-by-kind is magnitude-independent.
--   kept    = ironclad, spontaneous, skladchina_paid
--   broke   = no_show, spectator, skladchina_expired
--   neutral = confirmed_unresolved, skladchina_declined (historic) — excluded from the
--             Bayesian denominator. Invariant: neutral_count = outcome_count - kept - broke.
-- These cheap COUNT(*) FILTER columns give read-projections a non-decay denominator without
-- re-scanning the ledger; the DECAY-weighted Trust is computed on-read from
-- reputation_ledger.occurred_at (decay changes with time, so it is never cached).
--
-- No data backfill: the ledger is the full append-only source of truth (survives leave/delete),
-- so a one-off recompute(user, club) over all pairs fills these columns from real history.
-- That recompute pass is run by the application after deploy, NOT in this DDL (DDL stays pure).
-- Idempotent DDL (V19/V21/V23/V24 pattern; Testcontainers replay the full Flyway chain).
ALTER TABLE user_club_reputation ADD COLUMN IF NOT EXISTS kept_count    INT NOT NULL DEFAULT 0;
ALTER TABLE user_club_reputation ADD COLUMN IF NOT EXISTS broke_count   INT NOT NULL DEFAULT 0;
ALTER TABLE user_club_reputation ADD COLUMN IF NOT EXISTS neutral_count INT NOT NULL DEFAULT 0;
