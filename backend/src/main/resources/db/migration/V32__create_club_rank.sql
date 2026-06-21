-- V32: club_rank — INTERNAL L3 hidden rank for a club (subject = place, anchor = club_id).
--
-- This table is the stored output of the periodic L3 recompute (ClubRankScheduler). It is INTERNAL:
-- `rank_score` / `effective_k` are NEVER serialized to any DTO (design §4 "L3 невидим и необъясним";
-- an enforcement test asserts no *Dto exposes them). The only thing that ever leaves the server is a
-- single boolean — "★ Топ-5 в категории" — derived from this table at read time.
--
-- No `category_changed_at` / cooldown column: `clubs.category` is set-once at create (no update path
-- exists), so a cooldown would defend a mutation that cannot happen (YAGNI). Category immutability is
-- a PRECONDITION of L3 v1; if category-edit is ever added it becomes a prerequisite-locked feature.
--
-- `owner_id` is denormalized here so the category leaderboard can cap one owner to a single ranked
-- club per category (kills "category manufacturing" — populating a sparse category with self-owned
-- clubs) without re-joining `clubs` on every read.

CREATE TABLE club_rank (
    club_id      UUID PRIMARY KEY REFERENCES clubs(id),
    owner_id     UUID          NOT NULL REFERENCES users(id),
    category     club_category NOT NULL,
    -- Composite L3 score in [0, ~1]. PROVISIONAL: weights/anchors are principled defaults, NOT
    -- calibrated (impossible on a ~10-club prod). Internal only.
    rank_score   NUMERIC(8, 4) NOT NULL DEFAULT 0,
    -- Passed the credibility-weighted min-K existence gate (Σcredibility(core) ≥ EFFECTIVE_K).
    -- A club below the gate is UNRANKED (is_ranked = false) — NOT "low rank".
    is_ranked    BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Σcredibility of the credible core, internal debug/anomaly input. Never serialized.
    effective_k  NUMERIC(8, 4) NOT NULL DEFAULT 0,
    computed_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Category leaderboard read: filter ranked clubs in a category, ordered by score, grouped by owner.
CREATE INDEX idx_club_rank_cat_owner_score ON club_rank (category, owner_id, rank_score DESC)
    WHERE is_ranked;
