-- V12: Partial UNIQUE index on transactions.telegram_payment_charge_id.
-- Guarantees idempotency for successful_payment webhook (Telegram may retry).
-- Partial (WHERE NOT NULL) because charge_id is optional for historical / failed rows.

CREATE UNIQUE INDEX IF NOT EXISTS uq_transactions_telegram_charge_id
    ON transactions (telegram_payment_charge_id)
    WHERE telegram_payment_charge_id IS NOT NULL;
