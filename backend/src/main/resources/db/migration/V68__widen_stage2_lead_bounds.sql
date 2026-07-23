-- Пересмотр границ интервала Этапа 2 (решение PO 2026-07-23, вечер): подтверждение мест может
-- открываться сильно заранее — пресеты теперь 18 ч / 36 ч / 3 дня / 5 дней. «Ближе 18 часов»
-- закрывает отдельный формат «Срочная встреча»: событие рождается сразу в stage_2, свой интервал
-- ему не нужен. Отдельная миграция, потому что V67 уже применена на staging (checksum Flyway).

-- Тестовые значения ниже нового минимума (6–12 ч, созданные на staging до пересмотра)
-- поднимаются до минимума, иначе новый CHECK не встанет.
UPDATE events SET stage2_lead_minutes = 1080
    WHERE stage2_lead_minutes IS NOT NULL AND stage2_lead_minutes < 1080;

ALTER TABLE events DROP CONSTRAINT IF EXISTS chk_events_stage2_lead_minutes;
ALTER TABLE events ADD CONSTRAINT chk_events_stage2_lead_minutes
    CHECK (stage2_lead_minutes IS NULL OR stage2_lead_minutes BETWEEN 1080 AND 7200);

COMMENT ON COLUMN events.stage2_lead_minutes IS
    'За сколько МИНУТ до старта событие переходит в Этап 2 (подтверждение мест); задаёт организатор при создании. NULL = глобальный дефолт (events.stage2-trigger-minutes-before, 1080 = 18 часов). Диапазон 1080–7200 (18 часов – 5 дней, V68). Встречи «ближе 18 часов» создаются форматом «Срочная встреча» — событие рождается сразу в stage_2 без Этапа 1. Для открытых встреч (participant_limit IS NULL) не применяется.';
