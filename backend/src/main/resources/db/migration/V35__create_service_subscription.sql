-- V35: Platform service-fee subscription engine (monetization v2).
-- See docs/modules/payment-v2.md for the locked model.
--
-- The platform charges its OWN recurring service fee for a capacity plan (how many
-- paid clubs an organizer may run). This is NOT the Stars member-dues flow (transactions
-- table, V8) — that stays as a frozen ledger. This engine is payer_role-parameterized:
-- ORGANIZER (live) and MEMBER (built but gated behind MEMBER_PAYS_ENABLED, default off).
--
-- Flat monthly subscription (PO 2026-06-25): runs the whole paid period regardless of
-- club activity, does NOT pause, ends only at period end. No freeze-by-hours, no aggregate
-- flag. State machine: ACTIVE -> CANCELLED_PENDING_END -> ENDED, plus transient PAST_DUE.

CREATE TYPE subscription_payer_role AS ENUM ('ORGANIZER', 'MEMBER');
CREATE TYPE subscription_status AS ENUM ('ACTIVE', 'CANCELLED_PENDING_END', 'PAST_DUE', 'ENDED');
CREATE TYPE subscription_plan AS ENUM ('FREE', 'TRIO', 'UNLIMITED');

CREATE TABLE service_subscription (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payer_user_id       UUID NOT NULL REFERENCES users(id),
    payer_role          subscription_payer_role NOT NULL,
    plan                subscription_plan NOT NULL,
    -- NULL = platform-wide (organizer capacity plan); club-scoped for member-pays (phase 2).
    subject_club_id     UUID REFERENCES clubs(id),
    status              subscription_status NOT NULL DEFAULT 'ACTIVE',
    -- End of the currently paid period. Sole driver of "turns off at period end".
    current_period_end  TIMESTAMPTZ NOT NULL,
    -- Merchant tokenization handle (saved card / СБП-subscription). Stub provider leaves it null.
    provider_token      VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- One live organizer plan per user (platform-wide). Non-terminal states only, so an ENDED
-- row never blocks re-subscribing. FREE is the implicit no-row default, never inserted.
CREATE UNIQUE INDEX uq_service_subscription_active_org
    ON service_subscription (payer_user_id)
    WHERE payer_role = 'ORGANIZER' AND status <> 'ENDED';

-- One live member subscription per (user, club). Forward-compat for phase 2 (gated off now).
CREATE UNIQUE INDEX uq_service_subscription_active_member
    ON service_subscription (payer_user_id, subject_club_id)
    WHERE payer_role = 'MEMBER' AND status <> 'ENDED';

CREATE INDEX idx_service_subscription_payer ON service_subscription (payer_user_id, status);

-- Lifecycle scheduler scans non-terminal subscriptions whose period has elapsed.
CREATE INDEX idx_service_subscription_period_end
    ON service_subscription (current_period_end)
    WHERE status IN ('ACTIVE', 'CANCELLED_PENDING_END', 'PAST_DUE');

-- Idempotency ledger for inbound provider webhooks (out-of-order / retry protection).
-- provider_event_id UNIQUE mirrors the Stars charge-id dedup pattern (uq_transactions_telegram_charge_id, V12).
CREATE TABLE subscription_event (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id     UUID NOT NULL REFERENCES service_subscription(id) ON DELETE CASCADE,
    provider_event_id   VARCHAR(255) NOT NULL,
    kind                VARCHAR(64) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_subscription_event_provider_event_id
    ON subscription_event (provider_event_id);

CREATE INDEX idx_subscription_event_subscription_id
    ON subscription_event (subscription_id);
