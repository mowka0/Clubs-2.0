-- V90: сборы и долги v3 — сбор становится обёрткой над долгами между людьми.
--
-- Книга правил сборов прошла четыре редакции за три месяца (MVP → Фаза A → сплит → V89 → «сводят»),
-- каждая чинила один случай. Заменяем её одной идеей: сбор это повод и обёртка, долг живёт между
-- двумя людьми (кто → кому · сколько · до какого), подтверждает получатель. Три вида сбора
-- отличаются только тем, откуда берётся долг и что с ним в срок. Спека: docs/modules/skladchina-v3.md.
--
-- Нумерация: V87/V88 заняты веткой биллинга, V89 — веткой сверки оплат (обе ещё не в master).
-- Миграция обязана накатываться и на схему master (V86), и на схему staging (V89): поэтому
-- старые значения статуса участника сравниваются как текст (на V86 значений V89 в enum нет),
-- а колонка confirmation_requested_at (только V89) удаляется через IF EXISTS.
--
-- В проде на 2026-09-12 два сбора за всё время, оба закрыты: перенос данных формальный.

-- ---------------------------------------------------------------------------------------------
-- 1. Новые типы
-- ---------------------------------------------------------------------------------------------

-- Вид сбора: откуда берётся долг и что с ним в срок.
CREATE TYPE skladchina_kind AS ENUM ('shared', 'per_head', 'voluntary');
COMMENT ON TYPE skladchina_kind IS
    'Вид сбора: shared = «Скинуться» (доли назначает создатель, в срок остаётся долгом, репутация есть); per_head = «Кто берёт?» (сам жмёт «Беру», при «Заказываю» неоплатившие выбывают без долга); voluntary = «По желанию» (долг рождается как claimed при «Перевёл N ₽», репутации нет).';

-- Состояние долга. Открытые: waiting, promised, claimed.
CREATE TYPE debt_status AS ENUM ('waiting', 'promised', 'claimed', 'received', 'forgiven', 'dropped');
COMMENT ON TYPE debt_status IS
    'Состояние долга: waiting = ждём; promised = «Оплачу позже» к дате; claimed = должник говорит, что отдал; received = получатель подтвердил; forgiven = получатель простил (или сбор отменён, или заменён другим человеком); dropped = выбыл из per_head при «Заказываю»/«Передумал». Открытые = первые три.';

-- Состояние подтверждения сальдо пары.
CREATE TYPE debt_settlement_status AS ENUM ('claimed', 'received', 'rejected');
COMMENT ON TYPE debt_settlement_status IS
    'Состояние сальдо пары: claimed = плательщик нажал «Отдал Σ»; received = получатель подтвердил, все долги пары закрыты; rejected = получатель не подтвердил, долги снова открыты.';

-- Итог сбора: только collected или cancelled. Порогов, процентов и «не собран» больше нет.
CREATE TYPE skladchina_status_v3 AS ENUM ('active', 'collected', 'cancelled');

-- ---------------------------------------------------------------------------------------------
-- 2. skladchinas: вид, срок, этап «Кто в деле?», заказ, тихий режим, новый статус
-- ---------------------------------------------------------------------------------------------

ALTER TABLE skladchinas ADD COLUMN IF NOT EXISTS kind skladchina_kind;
-- split_bill и fixed_* были сборами с назначенными долями → shared; voluntary остаётся voluntary.
UPDATE skladchinas
SET kind = CASE WHEN payment_mode::text = 'voluntary' THEN 'voluntary' ELSE 'shared' END::skladchina_kind
WHERE kind IS NULL;
ALTER TABLE skladchinas ALTER COLUMN kind SET NOT NULL;

-- Цель сбора переименована: у shared это общая сумма, у per_head цена за человека,
-- у voluntary ориентир (необязателен).
ALTER TABLE skladchinas RENAME COLUMN total_goal_kopecks TO amount_kopecks;

-- Срок обязателен только у shared и per_head; «По желанию» может жить без срока.
ALTER TABLE skladchinas ALTER COLUMN deadline DROP NOT NULL;

ALTER TABLE skladchinas
    ADD COLUMN IF NOT EXISTS enrollment_until    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS min_participants    INTEGER CHECK (min_participants IS NULL OR min_participants >= 1),
    ADD COLUMN IF NOT EXISTS locked_at           TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS ordered_at          TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS hidden_from_user_id UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS order_reminded_at   TIMESTAMPTZ;

ALTER TABLE skladchinas
    ADD CONSTRAINT chk_skladchinas_deadline_by_kind CHECK (kind = 'voluntary' OR deadline IS NOT NULL);

-- Смена типа статуса: closed_success → collected, closed_failed → cancelled (провала как итога
-- больше нет: сбор либо собран, либо отменён рукой). Индекс (status, deadline) Postgres перестраивает сам.
ALTER TABLE skladchinas ALTER COLUMN status DROP DEFAULT;
ALTER TABLE skladchinas ALTER COLUMN status TYPE skladchina_status_v3
    USING (CASE status::text
               WHEN 'closed_success' THEN 'collected'
               WHEN 'closed_failed'  THEN 'cancelled'
               ELSE status::text
           END)::skladchina_status_v3;
ALTER TABLE skladchinas ALTER COLUMN status SET DEFAULT 'active';
DROP TYPE skladchina_status;
-- Имя типа возвращаем прежнее, чтобы jOOQ-enum остался SkladchinaStatus.
ALTER TYPE skladchina_status_v3 RENAME TO skladchina_status;
COMMENT ON TYPE skladchina_status IS
    'Статус сбора: active = идёт; collected = закрыт сам, когда не осталось открытых долгов (у per_head после «Заказываю», у voluntary только рукой создателя); cancelled = отменён создателем или владельцем клуба, открытые долги прощены.';

-- Вид заменяет пару template + payment_mode; репутация следует из вида; «сведения» больше нет.
ALTER TABLE skladchinas
    DROP COLUMN IF EXISTS payment_mode,
    DROP COLUMN IF EXISTS affects_reputation,
    DROP COLUMN IF EXISTS template,
    DROP COLUMN IF EXISTS confirmation_requested_at,
    DROP COLUMN IF EXISTS closed_by;
DROP TYPE IF EXISTS skladchina_mode;
DROP TYPE IF EXISTS skladchina_template;

COMMENT ON TABLE skladchinas IS
    'Сбор денег внутри клуба: повод и обёртка над долгами (название, вид, срок, реквизиты, чат-пост, пачка долгов в debts). Создать может любой активный участник клуба; отменить — создатель или владелец клуба. Спека: docs/modules/skladchina-v3.md.';
COMMENT ON COLUMN skladchinas.creator_id IS
    'Создатель сбора (FK users.id) — получатель денег по всем его долгам (debts.creditor_id). Любой активный участник клуба.';
COMMENT ON COLUMN skladchinas.kind IS
    'Вид сбора (enum skladchina_kind): shared «Скинуться», per_head «Кто берёт?», voluntary «По желанию». Заменяет пару template + payment_mode; влияние на репутацию следует из вида (только shared).';
COMMENT ON COLUMN skladchinas.amount_kopecks IS
    'Сумма в КОПЕЙКАХ по виду: shared = общая сумма сбора; per_head = цена за человека; voluntary = ориентир (NULL = без ориентира). Бывший total_goal_kopecks.';
COMMENT ON COLUMN skladchinas.deadline IS
    'Срок оплаты. Обязателен для shared и per_head (CHECK chk_skladchinas_deadline_by_kind), у voluntary необязателен. Копируется в debts.due_at при создании долга. Срок не стена: платить после него можно.';
COMMENT ON COLUMN skladchinas.event_id IS
    'shared «после встречи»: встреча-источник, список должников = пришедшие (FK events.id). NULL у остальных сборов.';
COMMENT ON COLUMN skladchinas.enrollment_until IS
    'shared до события: до когда открыт этап «Кто в деле?» (участники отмечаются в skladchina_enrollments, долгов ещё нет). NULL = этапа нет, список задаёт создатель, долги создаются сразу.';
COMMENT ON COLUMN skladchinas.min_participants IS
    'shared с этапом: минимум людей в деле, иначе при заморозке сбор отменяется сам («не набрали», денег никто не переводил). NULL = минимума нет.';
COMMENT ON COLUMN skladchinas.locked_at IS
    'shared с этапом: момент заморозки списка (по enrollment_until шедулером или раньше рукой создателя) — доли посчитаны, долги созданы. NULL = запись ещё открыта.';
COMMENT ON COLUMN skladchinas.ordered_at IS
    'per_head: «Заказываю» нажато — приём закрыт, «Беру» недоступно, неоплатившие waiting/promised переведены в dropped. NULL = заказ ещё не сделан.';
COMMENT ON COLUMN skladchinas.hidden_from_user_id IS
    'voluntary: от кого скрыть сбор (именинник, FK users.id). Скрытый сбор тихий: чат-поста нет, DM всем участникам клуба кроме скрытого; скрытый не видит сбор нигде (лента, прямая ссылка → 404).';
COMMENT ON COLUMN skladchinas.order_reminded_at IS
    'per_head: когда создателю в последний раз ушло напоминание «пора заказывать» (в срок и раз в день после, пока нет ordered_at). Штамп дедупликации шедулера.';
COMMENT ON COLUMN skladchinas.status IS
    'Статус сбора (enum skladchina_status): active = идёт; collected = собран (нет открытых долгов); cancelled = отменён.';
COMMENT ON COLUMN skladchinas.closed_at IS
    'Когда сбор закрыт (collected или cancelled). NULL = ещё активен. Окно статистики клуба считает сборы по этому моменту.';
COMMENT ON COLUMN skladchinas.reminder_sent_at IS
    'Когда в чат клуба ушло напоминание за 24 часа до срока с упоминаниями тех, кто ещё не оплатил (NULL = ещё не отправлялось). Штамп дедупликации.';

-- ---------------------------------------------------------------------------------------------
-- 3. Этап «Кто в деле?»: запись, а не долг
-- ---------------------------------------------------------------------------------------------

-- Отдельная таблица, а не долг с заглушкой суммы: до заморозки доля неизвестна (amount / N),
-- а долг с суммой 0 или с «примерной» суммой попал бы на экран «Долги» как настоящий.
CREATE TABLE IF NOT EXISTS skladchina_enrollments (
    skladchina_id UUID NOT NULL REFERENCES skladchinas(id) ON DELETE CASCADE,
    user_id       UUID NOT NULL REFERENCES users(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (skladchina_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_skladchina_enrollments_user_id ON skladchina_enrollments(user_id);

COMMENT ON TABLE skladchina_enrollments IS
    'Отметки «В деле» на этапе записи shared-сбора до события (skladchinas.enrollment_until). Это запись, не долг: доля считается только при заморозке (locked_at), тогда из каждой строки рождается долг в debts. Создатель в списке по умолчанию.';
COMMENT ON COLUMN skladchina_enrollments.skladchina_id IS 'Сбор с этапом «Кто в деле?» (FK skladchinas.id, каскадное удаление).';
COMMENT ON COLUMN skladchina_enrollments.user_id IS 'Кто отметился «В деле» (FK users.id). «Передумал» до заморозки удаляет строку.';
COMMENT ON COLUMN skladchina_enrollments.created_at IS 'Когда отметился.';

-- ---------------------------------------------------------------------------------------------
-- 4. Сальдо пары и долги
-- ---------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS debt_settlements (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payer_id       UUID NOT NULL REFERENCES users(id),
    payee_id       UUID NOT NULL REFERENCES users(id),
    amount_kopecks BIGINT NOT NULL CHECK (amount_kopecks > 0),
    status         debt_settlement_status NOT NULL DEFAULT 'claimed',
    claimed_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at    TIMESTAMPTZ,
    CONSTRAINT chk_debt_settlements_distinct_parties CHECK (payer_id <> payee_id)
);
CREATE INDEX IF NOT EXISTS idx_debt_settlements_payee_status ON debt_settlements(payee_id, status);
CREATE INDEX IF NOT EXISTS idx_debt_settlements_payer_status ON debt_settlements(payer_id, status);

COMMENT ON TABLE debt_settlements IS
    'Подтверждение сальдо пары: «Отдал Σ» разом по всем открытым долгам двух людей в обе стороны. Сальдо считается только внутри пары, через третьих ничего не схлопывается. Пока сальдо в claimed, одиночные кнопки у долгов пары скрыты.';
COMMENT ON COLUMN debt_settlements.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN debt_settlements.payer_id IS 'Кто переводит разницу (должник по сальдо, FK users.id).';
COMMENT ON COLUMN debt_settlements.payee_id IS 'Кому переводят разницу (получатель по сальдо, FK users.id). Он подтверждает «Получил Σ» / «Не получил».';
COMMENT ON COLUMN debt_settlements.amount_kopecks IS 'Разница сумм открытых долгов пары в КОПЕЙКАХ на момент «Отдал».';
COMMENT ON COLUMN debt_settlements.status IS 'Состояние сальдо (enum debt_settlement_status): claimed / received / rejected.';
COMMENT ON COLUMN debt_settlements.claimed_at IS 'Когда плательщик нажал «Отдал Σ». Для репутации считается моментом оплаты всех долгов сальдо.';
COMMENT ON COLUMN debt_settlements.resolved_at IS 'Когда получатель ответил «Получил» или «Не получил» (NULL = ждём ответа).';

CREATE TABLE IF NOT EXISTS debts (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skladchina_id        UUID NOT NULL REFERENCES skladchinas(id) ON DELETE CASCADE,
    debtor_id            UUID NOT NULL REFERENCES users(id),
    creditor_id          UUID NOT NULL REFERENCES users(id),
    amount_kopecks       BIGINT NOT NULL CHECK (amount_kopecks > 0),
    due_at               TIMESTAMPTZ,
    status               debt_status NOT NULL DEFAULT 'waiting',
    promised_at          DATE,
    claimed_at           TIMESTAMPTZ,
    confirmed_at         TIMESTAMPTZ,
    rejected_at          TIMESTAMPTZ,
    reject_note          TEXT,
    note                 TEXT,
    receipt_url          VARCHAR(500),
    settlement_id        UUID REFERENCES debt_settlements(id),
    reputation_plus_at   TIMESTAMPTZ,
    reputation_minus_at  TIMESTAMPTZ,
    due_reminder_sent_at TIMESTAMPTZ,
    overdue_reminded_at  TIMESTAMPTZ,
    promise_reminded_at  TIMESTAMPTZ,
    claim_reminded_at    TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_debts_skladchina_debtor UNIQUE (skladchina_id, debtor_id)
);
CREATE INDEX IF NOT EXISTS idx_debts_debtor_status   ON debts(debtor_id, status);
CREATE INDEX IF NOT EXISTS idx_debts_creditor_status ON debts(creditor_id, status);
CREATE INDEX IF NOT EXISTS idx_debts_skladchina_id   ON debts(skladchina_id);
CREATE INDEX IF NOT EXISTS idx_debts_settlement_id   ON debts(settlement_id) WHERE settlement_id IS NOT NULL;

COMMENT ON TABLE debts IS
    'Долг между двумя людьми: кто → кому · сколько · за что (сбор) · до какого · состояние. Подтверждает получатель. Один долг на пару (сбор, должник). Экран «Долги» показывает только долги, где пользователь одна из сторон; чужих долгов не видит никто, включая владельца клуба.';
COMMENT ON COLUMN debts.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN debts.skladchina_id IS 'За что: сбор-источник (FK skladchinas.id, каскадное удаление).';
COMMENT ON COLUMN debts.debtor_id IS 'Кто платит (FK users.id). Доля создателя сбора: debtor_id = creditor_id, долг сразу received, чтобы «получено X из Y» сходилось.';
COMMENT ON COLUMN debts.creditor_id IS 'Кому идут деньги (FK users.id) = создатель сбора.';
COMMENT ON COLUMN debts.amount_kopecks IS 'Сумма долга в КОПЕЙКАХ, строго больше нуля. «Изменить сумму» доступно получателю у shared-долга в waiting/promised.';
COMMENT ON COLUMN debts.due_at IS 'Срок оплаты: копия skladchinas.deadline на момент создания долга. NULL у voluntary. Срок не стена: «Отдал» после срока принимается.';
COMMENT ON COLUMN debts.status IS 'Состояние долга (enum debt_status). Переходы описаны в docs/modules/skladchina-v3.md § 2.2; что не в таблице переходов, того нет.';
COMMENT ON COLUMN debts.promised_at IS '«Оплачу позже»: к какой дате должник обещал (статус promised). Напоминания молчат до этой даты.';
COMMENT ON COLUMN debts.claimed_at IS 'Когда должник нажал «Отдал» (или «Отдал Σ» по сальдо). Для репутации это момент оплаты; пока долг claimed, часы просрочки стоят.';
COMMENT ON COLUMN debts.confirmed_at IS 'Когда получатель нажал «Получил» (статус received). Без предшествующего claimed_at это наличные.';
COMMENT ON COLUMN debts.rejected_at IS 'Последнее «Не получил» получателя: долг вернулся в waiting, должнику предложено приложить чек. Отодвигает точку отсчёта просрочки для −40.';
COMMENT ON COLUMN debts.reject_note IS 'Заметка получателя к последнему «Не получил» (NULL = без заметки).';
COMMENT ON COLUMN debts.note IS 'Заметка должника: «не согласен с суммой», размер футболки при «Беру» и т.п. (NULL = нет).';
COMMENT ON COLUMN debts.receipt_url IS 'Чек должника (наше хранилище, только относительный URL загрузки). Виден получателю в строке долга.';
COMMENT ON COLUMN debts.settlement_id IS 'Долг закрывается в составе сальдо пары (FK debt_settlements.id). NULL = одиночный долг. Пока сальдо в claimed, одиночные кнопки скрыты.';
COMMENT ON COLUMN debts.reputation_plus_at IS 'Штамп идемпотентности: когда за этот долг начислено +10 (shared, received вовремя). NULL = не начислялось.';
COMMENT ON COLUMN debts.reputation_minus_at IS 'Штамп идемпотентности: когда за этот долг списано −40 (shared, просрочка дольше DEBT_OVERDUE_WEEKS в waiting/promised). NULL = не списывалось. Один раз на долг.';
COMMENT ON COLUMN debts.due_reminder_sent_at IS 'Когда должнику ушло DM «завтра срок» (NULL = не отправлялось). Штамп дедупликации шедулера.';
COMMENT ON COLUMN debts.overdue_reminded_at IS 'Когда должнику в последний раз ушло DM о просрочке (в день срока, затем раз в 7 дней). NULL = ещё не напоминали.';
COMMENT ON COLUMN debts.promise_reminded_at IS 'Когда должнику ушло DM «вы обещали к сегодня» по promised_at (NULL = не отправлялось). Сбрасывается при новом обещании.';
COMMENT ON COLUMN debts.claim_reminded_at IS 'Когда получателю в последний раз ушло DM «должник говорит, что отдал, ответьте» (claimed дольше 48 ч, раз в 3 дня).';
COMMENT ON COLUMN debts.created_at IS 'Когда долг создан.';
COMMENT ON COLUMN debts.updated_at IS 'Когда долг последний раз менялся.';

-- ---------------------------------------------------------------------------------------------
-- 5. Перенос участников в долги и удаление старой таблицы
-- ---------------------------------------------------------------------------------------------

-- paid / payment_confirmed → received; открытые ответы активных сборов → waiting; остальное → forgiven.
-- Доля создателя всегда received (в v3 создатель себе не должен). Строки без суммы (voluntary без
-- оплаты) долгом не становятся: у долга нет смысла без суммы, а «По желанию» в v3 открытых долгов не имеет.
INSERT INTO debts (skladchina_id, debtor_id, creditor_id, amount_kopecks, due_at, status, claimed_at, confirmed_at, created_at)
SELECT p.skladchina_id,
       p.user_id,
       s.creator_id,
       COALESCE(p.declared_amount_kopecks, p.expected_amount_kopecks),
       s.deadline,
       CASE
           WHEN p.status::text IN ('paid', 'payment_confirmed') OR p.user_id = s.creator_id THEN 'received'
           WHEN p.status::text IN ('pending', 'payment_rejected', 'payment_disputed') AND s.status = 'active' THEN 'waiting'
           ELSE 'forgiven'
       END::debt_status,
       p.paid_at,
       CASE
           WHEN p.status::text IN ('paid', 'payment_confirmed') OR p.user_id = s.creator_id
               THEN COALESCE(p.paid_at, p.created_at)
       END,
       p.created_at
FROM skladchina_participants p
JOIN skladchinas s ON s.id = p.skladchina_id
WHERE COALESCE(p.declared_amount_kopecks, p.expected_amount_kopecks) > 0;

DROP TABLE skladchina_participants;
DROP TYPE skladchina_participant_status;

-- Якорь occurred_at финансовых строк леджера теперь живёт в долге, а не в закрытии сбора.
COMMENT ON COLUMN reputation_ledger.occurred_at IS
    'Время ПОВЕДЕНИЯ, а не обработки: для attendance = events.event_datetime, для finance = момент оплаты долга (debts.claimed_at / confirmed_at) у skladchina_paid и срок долга (debts.due_at) у skladchina_expired. Неизменяемый якорь для recency-decay.';
