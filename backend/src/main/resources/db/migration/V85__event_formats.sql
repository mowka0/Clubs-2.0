-- V85: форматы встреч — «минимум N» / «максимум N» / «сколько придёт».
--
-- Формат встречи = ответ на один вопрос «сколько человек нужно». Раньше ответов было три, но
-- жили они в трёх разных местах: participant_limit IS NULL означало открытую встречу, is_urgent —
-- срочную, а всё остальное «обычную». Теперь смысл лимита несёт сам лимит: limit_kind говорит,
-- читать его как ПОРОГ (min: не наберём — встреча отменится) или как ПОТОЛОК (max: встреча
-- состоится в любом случае, но мест столько).
--
-- Формат «⚡ срочная» уходит из продукта: он отличался только тем, что времени на голосование
-- нет, а это свойство даты, а не формата. Встреча на сегодня = max с уже наступившим дедлайном.
-- Спека: docs/modules/event-formats.md.

-- 1. Как читать participant_limit. Значения ровно два: отсутствие лимита («сколько придёт»)
--    выражается отсутствием лимита, а не третьим значением enum — иначе один факт получил бы
--    два представления, которые могут разъехаться.
CREATE TYPE limit_kind AS ENUM ('min', 'max');

COMMENT ON TYPE limit_kind IS
    'Как читать participant_limit: min — ПОРОГ набора (не наберём к дедлайну — встреча отменяется, верхней границы нет), max — ПОТОЛОК мест (встреча состоится при любом составе, сверх лимита — очередь на замену).';

-- 2. events.limit_kind. NULL строго тогда, когда нет и лимита, — инвариант держит CHECK.
ALTER TABLE events ADD COLUMN IF NOT EXISTS limit_kind limit_kind;

-- Бэкфил: ВСЁ существующее — потолок. Это правда сегодняшнего прода, а не удобное умолчание:
-- participant_limit там означает максимум мест, а порога в проде нет вовсе (ветка с ним не
-- смержена). Срочные встречи попадают в тот же max — у них лимит и был потолком.
UPDATE events SET limit_kind = 'max' WHERE participant_limit IS NOT NULL AND limit_kind IS NULL;

ALTER TABLE events DROP CONSTRAINT IF EXISTS chk_events_limit_kind;
ALTER TABLE events ADD CONSTRAINT chk_events_limit_kind
    CHECK ((participant_limit IS NULL) = (limit_kind IS NULL));

COMMENT ON COLUMN events.limit_kind IS
    'Как читать participant_limit (V85): min — порог набора, max — потолок мест. NULL строго при participant_limit IS NULL — формат «сколько придёт», без ограничений и вне репутации.';

COMMENT ON COLUMN events.participant_limit IS
    'Число участников, смысл которого задаёт limit_kind (V85): при min — сколько человек НУЖНО (наберём — встреча состоится, нет — отменяется в момент дедлайна набора; состав может вырасти выше этого числа), при max — сколько ВСЕГО МЕСТ (сверх лимита — очередь на замену). NULL = формат «сколько придёт»: без лимита, очереди и репутации.';

-- 3. Отметка недобора больше не нужна. Она держала гард «уведомить организатора ровно один раз»,
--    пока недобор был отдельным состоянием: набор продолжался, и тик возвращался к встрече каждую
--    минуту. Теперь дедлайн всегда терминален — min отменяет встречу (статус меняется, тик к ней
--    не вернётся), max закрывает состав. Колонка появилась в V83 и в проде не существует: ветка
--    feature/roster-threshold не смержена, так что данных в ней нет ни в одном окружении.
ALTER TABLE events DROP COLUMN IF EXISTS roster_shortfall_at;

-- 4. is_urgent остаётся ЛЕГАСИ: срочные встречи в истории реально проводились по своим правилам,
--    и стирать этот факт незачем. Ни рендер, ни создание её больше не читают — старые срочные
--    показываются как «не больше N» (решение PO 2026-08-31), что механически и есть правда.
COMMENT ON COLUMN events.is_urgent IS
    'ЛЕГАСИ (V85): формат «⚡ срочная» удалён из продукта, колонка не читается ни рендером, ни созданием и всегда FALSE у новых встреч. Хранится как исторический факт: до 2026-08-31 такие встречи рождались сразу в stage_2, минуя голосование. Формат встречи определяет limit_kind.';

-- 5. Шаблоны встреч несут формат сами. Здесь, в отличие от events, легаси-колонки не оставляем:
--    шаблон — не запись о прошедшей встрече, а живая заготовка формы, и оба флага отображаются на
--    новую модель без потерь (is_open_event = participant_limit IS NULL, is_urgent_event → max).
--    Стухшая копия того же факта хуже удалённой: её пришлось бы вечно писать «на всякий случай».
ALTER TABLE event_templates ADD COLUMN IF NOT EXISTS limit_kind limit_kind;

UPDATE event_templates SET limit_kind = 'max'
WHERE participant_limit IS NOT NULL AND limit_kind IS NULL;

-- Старые инварианты держались на is_open_event / is_urgent_event — снимаем их до удаления колонок.
ALTER TABLE event_templates DROP CONSTRAINT IF EXISTS chk_event_templates_limit;
ALTER TABLE event_templates DROP CONSTRAINT IF EXISTS chk_event_templates_open_stage2;
ALTER TABLE event_templates DROP CONSTRAINT IF EXISTS chk_event_templates_urgent;

ALTER TABLE event_templates DROP COLUMN IF EXISTS is_open_event;
ALTER TABLE event_templates DROP COLUMN IF EXISTS is_urgent_event;

-- Те же два инварианта, что у события: пара «лимит + его смысл» согласована, а у формата без
-- лимита нет и своего интервала набора (набора у него нет вовсе).
ALTER TABLE event_templates ADD CONSTRAINT chk_event_templates_limit_kind
    CHECK ((participant_limit IS NULL) = (limit_kind IS NULL));
ALTER TABLE event_templates ADD CONSTRAINT chk_event_templates_limit_positive
    CHECK (participant_limit IS NULL OR participant_limit > 0);
ALTER TABLE event_templates ADD CONSTRAINT chk_event_templates_open_stage2
    CHECK (participant_limit IS NOT NULL OR stage2_lead_minutes IS NULL);

COMMENT ON COLUMN event_templates.limit_kind IS
    'Как читать participant_limit (V85): min — порог набора, max — потолок мест. NULL строго при participant_limit IS NULL — формат «сколько придёт». Зеркалит events.limit_kind.';
