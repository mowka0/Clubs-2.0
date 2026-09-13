-- Сборы и долги v3, правка PO по staging 2026-09-13: перед списанием −40 за просрочку должник
-- получает два предупреждения — за неделю и за день до момента списания. Штампы делают их
-- идемпотентными (ставятся до отправки DM, как остальные напоминания по долгу).

ALTER TABLE debts ADD COLUMN IF NOT EXISTS minus_week_reminded_at TIMESTAMPTZ;
ALTER TABLE debts ADD COLUMN IF NOT EXISTS minus_day_reminded_at  TIMESTAMPTZ;

COMMENT ON COLUMN debts.minus_week_reminded_at IS 'Когда должнику ушло DM «через неделю −40» (за 7 дней до момента списания = greatest(due_at, rejected_at) + debts.overdue-weeks). NULL = не отправлялось.';
COMMENT ON COLUMN debts.minus_day_reminded_at IS 'Когда должнику ушло DM «завтра −40» (за 1 день до момента списания). NULL = не отправлялось.';
