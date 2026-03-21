-- V8: Create transactions table with enum types
-- Records Telegram Stars payment history for subscriptions

CREATE TYPE transaction_type AS ENUM ('subscription', 'renewal');
CREATE TYPE transaction_status AS ENUM ('completed', 'failed', 'refunded');

CREATE TABLE transactions (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                      UUID NOT NULL REFERENCES users(id),
    club_id                      UUID NOT NULL REFERENCES clubs(id),
    membership_id                UUID REFERENCES memberships(id),
    type                         transaction_type NOT NULL,
    status                       transaction_status NOT NULL DEFAULT 'completed',
    amount                       INT NOT NULL CHECK (amount >= 0),
    platform_fee                 INT NOT NULL DEFAULT 0 CHECK (platform_fee >= 0),
    organizer_revenue            INT NOT NULL DEFAULT 0 CHECK (organizer_revenue >= 0),
    telegram_payment_charge_id   VARCHAR(255),
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
