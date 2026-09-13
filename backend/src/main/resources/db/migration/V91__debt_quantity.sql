-- Сборы и долги v3, правка PO по staging 2026-09-13: в сборе «Кто берёт?» один человек может взять
-- несколько штук (три билета). Долг хранит количество, сумма = количество × цена за штуку.
-- У shared / voluntary количество всегда 1.

ALTER TABLE debts ADD COLUMN IF NOT EXISTS quantity INTEGER NOT NULL DEFAULT 1;
ALTER TABLE debts DROP CONSTRAINT IF EXISTS debts_quantity_positive;
ALTER TABLE debts ADD CONSTRAINT debts_quantity_positive CHECK (quantity >= 1);

COMMENT ON COLUMN debts.quantity IS 'Сколько штук берёт должник (per_head: билеты, футболки). У shared/voluntary всегда 1. amount_kopecks = quantity × цена за штуку на момент «Беру».';
