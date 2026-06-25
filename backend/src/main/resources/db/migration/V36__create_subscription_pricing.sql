-- V36: Subscription pricing table (monetization v2, docs/modules/payment-v2.md §3.3).
--
-- Prices live in config keyed by plan + effective_from so launch numbers can be tuned
-- WITHOUT a migration (e.g. the 22% acquiring-VAT recalc). The server always computes the
-- amount from here — never trusts the client. Capacity (max_paid_clubs) is NOT here: it is
-- a stable product invariant carried by the Kotlin SubscriptionPlan extension and guarded
-- by a unit test (the monotone free-floor + no-cliff volume-discount invariant, §3.2/§3.4).
--
-- Launch grid (PO-locked 2026-06-25), kopecks: FREE=0, TRIO=20000 (200₽), UNLIMITED=40000 (400₽).
-- Invariant: UNLIMITED <= 2 * TRIO (no-cliff). 40000 = 2 * 20000 — holds.

CREATE TABLE subscription_pricing (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan            subscription_plan NOT NULL,
    price_kopecks   INT NOT NULL CHECK (price_kopecks >= 0),
    effective_from  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Current price for a plan = row with the latest effective_from <= now.
CREATE INDEX idx_subscription_pricing_plan_effective
    ON subscription_pricing (plan, effective_from DESC);

INSERT INTO subscription_pricing (plan, price_kopecks, effective_from) VALUES
    ('FREE', 0, NOW()),
    ('TRIO', 20000, NOW()),
    ('UNLIMITED', 40000, NOW());
