-- V86: форматы встреч v2 — обычная встреча «минимум по желанию + максимум всегда» и открытая.
--
-- Три формата V85 (min / max / any) схлопываются в два. Различие «число — порог» против «число —
-- потолок» было невидимым флагом при одном и том же счётчике, и путался в нём даже автор модели.
-- Теперь потолок есть у любой встречи с местами (participant_limit), а порог — отдельное
-- необязательное число (min_participants). Формат задаёт только наличие лимита:
--   participant_limit IS NULL                          → открытая (вне репутации, без мест и очереди)
--   participant_limit NOT NULL, min_participants NULL  → обычная без минимума («сколько влезет»)
--   participant_limit NOT NULL, min_participants = N   → обычная с минимумом N, 1 ≤ N ≤ participant_limit
-- Спека: docs/modules/event-formats.md (v2, решение PO 2026-09-02).

-- 1. Порог набора. NULL = минимум выключен. Не выше потолка: минимум — условие СБОРА состава,
--    а собрать больше мест, чем есть, нельзя.
ALTER TABLE events ADD COLUMN IF NOT EXISTS min_participants INTEGER;

-- 2. Отметка «Проводим»: организатор подтвердил, что проведёт встречу составом ниже минимума.
--    Глушит DM «состав распался» и даёт строку в закрепе; минимум и цены отказа не меняет и
--    никогда не сбрасывается — передумать значит «Отменить».
ALTER TABLE events ADD COLUMN IF NOT EXISTS roster_decided_at TIMESTAMPTZ;

-- 3. Отметка предупреждения о недоборе (за events.roster-warning-minutes-before-deadline до
--    дедлайна набора). Ставится в любом случае: тиком — когда момент наступил, а при создании и
--    правке — сразу, если момент уже в прошлом («израсходовано», DM не будет).
ALTER TABLE events ADD COLUMN IF NOT EXISTS roster_warning_sent_at TIMESTAMPTZ;

-- 4. Бэкфил из limit_kind. У бывшего min лимит был порогом без потолка, и состав мог перерасти
--    число — потолок поднимаем до фактического состава, иначе CHECK ниже упал бы. В SET все
--    выражения читают СТАРУЮ строку, поэтому min_participants получает прежний participant_limit.
--    У max минимума не было. participant_limit не обнуляется ни у кого: прошедшие встречи стали
--    бы «открытыми», и репутация поехала бы. На 2026-09-02 строк с min нет ни на staging, ни в
--    проде — правило на случай их появления до выкатки.
UPDATE events e
SET min_participants = e.participant_limit,
    participant_limit = GREATEST(
        e.participant_limit,
        (SELECT count(*)::int FROM event_responses r
         WHERE r.event_id = e.id AND r.stage_2_vote = 'confirmed')
    )
WHERE e.limit_kind = 'min';

ALTER TABLE events DROP CONSTRAINT IF EXISTS chk_events_min_participants;
ALTER TABLE events ADD CONSTRAINT chk_events_min_participants
    CHECK (min_participants IS NULL
        OR (participant_limit IS NOT NULL AND min_participants BETWEEN 1 AND participant_limit));

-- 5. limit_kind больше не нужен: смысл лимита один — потолок, а порог живёт своей колонкой.
ALTER TABLE events DROP CONSTRAINT IF EXISTS chk_events_limit_kind;
ALTER TABLE events DROP COLUMN IF EXISTS limit_kind;

COMMENT ON COLUMN events.participant_limit IS
    'Максимум участников — ПОТОЛОК мест (V86): сверх него голос «Иду» встаёт в очередь на замену. NULL = открытая встреча: без мест, очереди и репутации. У обычной встречи обязателен — NULL читают пять мест кода как маркер открытой встречи.';

COMMENT ON COLUMN events.min_participants IS
    'Минимум участников — ПОРОГ набора (V86), по желанию организатора. NULL = минимум выключен. Условие сбора состава, а не проведения: не набрали к закрытию набора — встреча отменяется (правило ①); после закрытия состав ниже минимума лишь уведомляет организатора (правило ③). 1 ≤ min ≤ participant_limit — держит CHECK chk_events_min_participants.';

COMMENT ON COLUMN events.roster_decided_at IS
    'Когда организатор нажал «Проводим» на закрытом составе ниже минимума (V86). NULL = не нажимал. Глушит DM «состав распался», добавляет строку подтверждения в закреп; минимум, бейдж и цены отказа не меняет. Не сбрасывается и при возврате состава к минимуму — передумать значит отменить встречу.';

COMMENT ON COLUMN events.roster_warning_sent_at IS
    'Когда организатору ушло (или считается израсходованным) предупреждение о недоборе за roster-warning-minutes-before-deadline до дедлайна набора (V86). NULL = ещё не наступило. Ставится в любом случае — DM уходит только при составе ниже минимума; повторных предупреждений нет. Пересчитывается при создании и каждом PUT: момент в прошлом → now(), в будущем → NULL.';

COMMENT ON COLUMN events.is_urgent IS
    'ЛЕГАСИ (V85): формат «⚡ срочная» удалён из продукта, колонка не читается ни рендером, ни созданием и всегда FALSE у новых встреч. Хранится как исторический факт: до 2026-08-31 такие встречи рождались сразу в stage_2, минуя голосование. Формат встречи задаёт participant_limit (NULL = открытая).';

-- 6. Шаблоны встреч зеркалят модель: минимум запоминается и подставляется в форму.
ALTER TABLE event_templates ADD COLUMN IF NOT EXISTS min_participants INTEGER;

UPDATE event_templates SET min_participants = participant_limit WHERE limit_kind = 'min';

ALTER TABLE event_templates DROP CONSTRAINT IF EXISTS chk_event_templates_min_participants;
ALTER TABLE event_templates ADD CONSTRAINT chk_event_templates_min_participants
    CHECK (min_participants IS NULL
        OR (participant_limit IS NOT NULL AND min_participants BETWEEN 1 AND participant_limit));

ALTER TABLE event_templates DROP CONSTRAINT IF EXISTS chk_event_templates_limit_kind;
ALTER TABLE event_templates DROP COLUMN IF EXISTS limit_kind;

COMMENT ON COLUMN event_templates.participant_limit IS
    'Максимум участников — потолок мест (V86). NULL = шаблон открытой встречи (тогда и min_participants, и stage2_lead_minutes NULL).';

COMMENT ON COLUMN event_templates.min_participants IS
    'Минимум участников, запомненный шаблоном (V86): форма подставляет его и включает переключатель. NULL = минимум выключен. 1 ≤ min ≤ participant_limit — CHECK chk_event_templates_min_participants.';

-- 7. Тип больше никем не используется.
DROP TYPE IF EXISTS limit_kind;
