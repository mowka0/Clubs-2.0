-- V83: порог набора для формата «🎟 Встреча с местами».
--
-- participant_limit меняет смысл: было «максимум мест», стало «сколько человек нужно».
-- Голос «Иду» кладёт в состав сразу, отдельного подтверждения у формата больше нет.
-- Спека: docs/modules/event-roster-threshold.md.

-- 1. Отметка недобора: момент, когда набор закрылся неполным составом и организатору ушёл DM
--    с выбором (продлить / провести меньшим составом / отменить). NULL = недобора нет либо
--    организатор уже продлил набор. По ней же считается дедлайн автоотмены при молчании.
ALTER TABLE events ADD COLUMN IF NOT EXISTS roster_shortfall_at TIMESTAMPTZ;

COMMENT ON COLUMN events.roster_shortfall_at IS
    'Когда набор закрылся НЕДОБОРОМ и организатору ушёл DM с выбором (V83). NULL = недобора нет или организатор продлил набор. Молчание дольше events.roster-shortfall-response-minutes от этого момента → автоотмена встречи.';

-- 2. Нижняя граница интервала набора опускается с 1080 (18 ч) до 60 (1 ч).
--    Пресеты формы теперь 6 ч / 12 ч / 18 ч / 36 ч / 3 дня (валидация DTO: 360..7200), а ещё
--    короче значение появляется единственным путём — продлением набора из DM организатору
--    («+6 часов» сдвигает дедлайн ближе к встрече, уменьшая lead).
ALTER TABLE events DROP CONSTRAINT IF EXISTS chk_events_stage2_lead_minutes;
ALTER TABLE events ADD CONSTRAINT chk_events_stage2_lead_minutes
    CHECK (stage2_lead_minutes IS NULL OR stage2_lead_minutes BETWEEN 60 AND 7200);

COMMENT ON COLUMN events.stage2_lead_minutes IS
    'За сколько МИНУТ до старта закрывается НАБОР СОСТАВА (V83; до этого — «переход в Этап 2»); задаёт организатор при создании. NULL = глобальный дефолт (events.stage2-trigger-minutes-before, 1080 = 18 часов). Диапазон 60–7200: пресеты формы 360–7200 (6 часов – 3 дня), значения ниже 360 рождаются только продлением набора из DM организатору при недоборе. Для открытых встреч (participant_limit IS NULL) не применяется.';

-- Шаблоны встреч (V79) носят копию того же ограничения — иначе пресет 6 ч не сохранить в шаблон.
ALTER TABLE event_templates DROP CONSTRAINT IF EXISTS chk_event_templates_stage2_bounds;
ALTER TABLE event_templates ADD CONSTRAINT chk_event_templates_stage2_bounds
    CHECK (stage2_lead_minutes IS NULL OR stage2_lead_minutes BETWEEN 60 AND 7200);

-- 3. Два новых вида репутации: отказ от места ВНУТРИ порога отказа (4 ч до встречи). Прежде такой
--    отказ был просто запрещён, и человек молча не приходил, получая −200 (no_show). Теперь он
--    возможен, но платный, и цена зависит от того, нашлась ли замена в очереди.
--    IF NOT EXISTS — идемпотентность для локального jOOQ-codegen (как V45).
ALTER TYPE reputation_kind ADD VALUE IF NOT EXISTS 'late_decline_covered';
ALTER TYPE reputation_kind ADD VALUE IF NOT EXISTS 'late_decline_uncovered';

-- 4. Бэкфил состава у уже созданных встреч с местами, которые ещё набирают (status = upcoming).
--    Без него их состав на момент закрытия набора оказался бы пустым: голоса «Иду» лежат в
--    stage_1_vote, а состав живёт в stage_2_vote. Порядок — по времени голоса, ровно так же,
--    как его теперь строит VoteService: первые participant_limit голосов в состав, остальные в очередь.
WITH ranked AS (
    SELECT r.id,
           e.participant_limit,
           ROW_NUMBER() OVER (
               PARTITION BY r.event_id
               ORDER BY r.stage_1_timestamp ASC NULLS LAST, r.created_at ASC
           ) AS position
    FROM event_responses r
    JOIN events e ON e.id = r.event_id
    WHERE e.status = 'upcoming'
      AND e.participant_limit IS NOT NULL
      AND e.is_urgent = FALSE
      AND r.stage_1_vote = 'going'
      AND r.stage_2_vote IS NULL
)
UPDATE event_responses er
SET stage_2_vote = CASE WHEN ranked.position <= ranked.participant_limit
                        THEN 'confirmed'::stage_2_vote ELSE 'waitlisted'::stage_2_vote END,
    final_status = CASE WHEN ranked.position <= ranked.participant_limit
                        THEN 'confirmed'::final_status ELSE 'waitlisted'::final_status END,
    -- Метка Этапа 2 = метка голоса: место занято в момент голосования, а не в момент бэкфила.
    stage_2_timestamp = er.stage_1_timestamp,
    updated_at = NOW()
FROM ranked
WHERE er.id = ranked.id;
