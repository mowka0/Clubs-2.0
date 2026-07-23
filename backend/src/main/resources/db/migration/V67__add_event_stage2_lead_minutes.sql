-- Вариативный тайминг Этапа 2 (решение PO 2026-07-23): организатор при создании события
-- выбирает, за сколько до старта открывается подтверждение мест. NULL = глобальный дефолт
-- events.stage2-trigger-minutes-before (1080 = 18 часов). Открытых встреч не касается —
-- они вне двухэтапки (participant_limit IS NULL).
ALTER TABLE events ADD COLUMN IF NOT EXISTS stage2_lead_minutes INT
    CONSTRAINT chk_events_stage2_lead_minutes
        CHECK (stage2_lead_minutes IS NULL OR stage2_lead_minutes BETWEEN 360 AND 2880);

COMMENT ON COLUMN events.stage2_lead_minutes IS
    'За сколько МИНУТ до старта событие переходит в Этап 2 (подтверждение мест); задаёт организатор при создании. NULL = глобальный дефолт (events.stage2-trigger-minutes-before, 1080 = 18 часов). Диапазон 360–2880 (6 часов – 2 дня). Если при создании до события осталось меньше — Этап 2 начнётся сразу (осознанное поведение, форма предупреждает). Для открытых встреч (participant_limit IS NULL) не применяется.';
