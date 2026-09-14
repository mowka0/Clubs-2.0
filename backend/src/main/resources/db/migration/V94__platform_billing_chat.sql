-- V94: Биллинг платформы за чат (docs/modules/platform-billing.md § 5.1).
-- Модель «первая встреча бесплатно, дальше 199 ₽/мес за чат» (решения PO 2026-09-07,
-- docs/design/monetization-v3-research-2026-09.md § 8). Единица счёта — чат (клуб с привязкой),
-- платит владелец клуба. Планы ёмкости FREE/TRIO/UNLIMITED (V35/V36) мертвы: значения enum
-- в PostgreSQL не удаляются, в коде они больше не используются.
--
-- Про ADD VALUE: новое значение enum нельзя использовать в DML той же транзакции, где оно
-- добавлено (как в V37), поэтому строка прайсинга 'CHAT' живёт в V95.
ALTER TYPE subscription_plan ADD VALUE IF NOT EXISTS 'CHAT';

ALTER TABLE service_subscription
    ADD COLUMN autopay          BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN autopay_possible BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN charge_attempts  INT         NOT NULL DEFAULT 0,
    ADD COLUMN last_charge_at   TIMESTAMPTZ;

COMMENT ON COLUMN service_subscription.autopay IS
    'Ползунок владельца «Продлевать автоматически» (по умолчанию включён). Выключен = за 3 и 1 день до конца периода приходит DM с кнопкой «Оплатить», списания нет.';
COMMENT ON COLUMN service_subscription.autopay_possible IS
    'Материнский платёж прошёл банковской картой — Robokassa делает рекуррентные списания только по картам. false после оплаты СБП: ползунок недоступен, напоминаем как при выключенном.';
COMMENT ON COLUMN service_subscription.charge_attempts IS
    'Сколько дочерних списаний сделано за текущий цикл продления (ретраи в слотах 0/+1/+3 дня от конца периода). Сбрасывается в 0 успешной оплатой.';
COMMENT ON COLUMN service_subscription.last_charge_at IS
    'Когда последний раз отправляли дочернее списание (NULL = ещё не пробовали в этом цикле).';
COMMENT ON COLUMN service_subscription.provider_token IS
    'InvId материнского платежа Robokassa — PreviousInvoiceID для дочерних списаний. NULL до первой успешной оплаты и у стаб-провайдера.';
COMMENT ON COLUMN service_subscription.subject_club_id IS
    'Клуб, за чат которого идёт подписка. У строк ORGANIZER заполнен всегда (с V94); NULL остался только у завершённых легаси-строк платформенного плана ёмкости.';

-- Легаси-строки платформенного плана ёмкости (subject_club_id IS NULL) завершаем: в чат-модели
-- у них нет предмета. Реальных денег за ними нет — провайдер был стабом.
UPDATE service_subscription
   SET status = 'ENDED', updated_at = NOW()
 WHERE payer_role = 'ORGANIZER' AND subject_club_id IS NULL AND status <> 'ENDED';

-- Одна живая подписка на клуб независимо от плательщика (владелец может смениться).
DROP INDEX uq_service_subscription_active_org;
CREATE UNIQUE INDEX uq_service_subscription_live_club
    ON service_subscription (subject_club_id)
    WHERE payer_role = 'ORGANIZER' AND status <> 'ENDED';

-- Организаторские строки теперь всегда club-scoped. NOT VALID: завершённые выше легаси-строки
-- под правило не подпадают, а любые новые вставки и обновления проверяются.
ALTER TABLE service_subscription
    ADD CONSTRAINT chk_service_subscription_org_club
    CHECK (payer_role <> 'ORGANIZER' OR subject_club_id IS NOT NULL) NOT VALID;

-- Платежи платформы: аудит-след денег, привязка InvId → подписка ДО прихода ResultURL,
-- статус дочерних списаний. Не путать с transactions (замороженный Stars-леджер, V8).
-- InvId Robokassa: целое 1..2^63-1, уникальное в рамках магазина; стартуем со 100000, чтобы
-- номера счетов не выглядели как порядковые.
CREATE SEQUENCE platform_payment_inv_seq START 100000;

CREATE TABLE platform_payment (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    club_id          UUID        NOT NULL REFERENCES clubs(id),
    subscription_id  UUID        REFERENCES service_subscription(id),
    inv_id           BIGINT      NOT NULL UNIQUE DEFAULT nextval('platform_payment_inv_seq'),
    kind             VARCHAR(16) NOT NULL CHECK (kind IN ('MOTHER', 'RECURRING')),
    previous_inv_id  BIGINT,
    amount_kopecks   INT         NOT NULL CHECK (amount_kopecks > 0),
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    autopay_requested BOOLEAN    NOT NULL DEFAULT TRUE,
    payment_method   VARCHAR(64),
    provider_fee     NUMERIC(10, 2),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    paid_at          TIMESTAMPTZ
);

-- Почасовой тик шедулера: PENDING старше N часов → опрос статуса у провайдера.
CREATE INDEX idx_platform_payment_pending ON platform_payment (created_at) WHERE status = 'PENDING';
-- Идемпотентность чекаута (живой PENDING MOTHER < 30 мин) и история платежей клуба.
CREATE INDEX idx_platform_payment_club ON platform_payment (club_id, created_at DESC);

COMMENT ON TABLE platform_payment IS
    'Платежи владельцев клубов платформе за чат через провайдера (Robokassa). Один ряд = один счёт (InvId); материнский платёж (MOTHER) со страницы оплаты, дочерние (RECURRING) — автосписания по сохранённой карте.';
COMMENT ON COLUMN platform_payment.club_id IS
    'Клуб (чат), за который выставлен счёт. Заполнен всегда — по нему ResultURL находит или создаёт подписку.';
COMMENT ON COLUMN platform_payment.subscription_id IS
    'Подписка, к которой отнесён платёж. NULL у материнского счёта до подтверждения оплаты: строка подписки появляется вместе с первым успешным платежом — иначе правило грейса дало бы неделю без оплаты.';
COMMENT ON COLUMN platform_payment.inv_id IS
    'Номер счёта у провайдера (InvId Robokassa). По нему ResultURL находит подписку; ключ идемпотентности вебхука — robokassa:paid:<inv_id> в subscription_event.';
COMMENT ON COLUMN platform_payment.kind IS
    'MOTHER = материнский платёж со страницы оплаты (сохраняет карту при Recurring=true) | RECURRING = дочернее списание шедулером по PreviousInvoiceID.';
COMMENT ON COLUMN platform_payment.previous_inv_id IS
    'Для RECURRING: InvId материнского платежа (PreviousInvoiceID). NULL у MOTHER.';
COMMENT ON COLUMN platform_payment.amount_kopecks IS
    'Сумма счёта в копейках, считается на сервере из subscription_pricing на момент выставления — клиент цену не передаёт.';
COMMENT ON COLUMN platform_payment.status IS
    'PENDING = счёт выставлен, подтверждения нет | SUCCEEDED = ResultURL/опрос подтвердил оплату | FAILED = провайдер отказал или счёт протух.';
COMMENT ON COLUMN platform_payment.autopay_requested IS
    'Положение ползунка «Продлевать автоматически» в шите на момент чекаута (только для MOTHER). Переносится на подписку при подтверждении оплаты — строки подписки до первой оплаты ещё нет.';
COMMENT ON COLUMN platform_payment.payment_method IS
    'PaymentMethod из ResultURL (BankCard, SBP, …). Карта ⇒ autopay_possible на подписке.';
COMMENT ON COLUMN platform_payment.provider_fee IS
    'Комиссия провайдера из ResultURL (Fee), рубли с копейками. Для сверки с выпиской, в логику не входит.';
COMMENT ON COLUMN platform_payment.paid_at IS
    'Когда провайдер подтвердил оплату (NULL, пока PENDING/FAILED).';

-- Бесплатная первая встреча — по chat_id, переживает отвязку и удаление клуба (R6): строка
-- club_chat_links удаляется при отвязке, поэтому признак хранится отдельно и без FK.
CREATE TABLE chat_free_meeting (
    chat_id      BIGINT      PRIMARY KEY,
    club_id      UUID        NOT NULL,
    event_id     UUID        NOT NULL,
    used_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    released_at  TIMESTAMPTZ
);

COMMENT ON TABLE chat_free_meeting IS
    'Одна бесплатная встреча на чат Telegram. Строка есть = бесплатная встреча взята; released_at заполнен = встреча отменена до старта и бесплатная возвращена (R5). Переживает отвязку чата, удаление клуба и повторное подключение того же чата новым клубом.';
COMMENT ON COLUMN chat_free_meeting.club_id IS
    'Клуб, чья встреча была бесплатной. Без FK: клуб может быть удалён, признак живёт.';
COMMENT ON COLUMN chat_free_meeting.event_id IS
    'Встреча, записанная как бесплатная. Без FK по той же причине; отмена этой встречи возвращает бесплатную.';
COMMENT ON COLUMN chat_free_meeting.released_at IS
    'Когда бесплатная встреча была возвращена отменой до старта (NULL = использована). Повторное взятие сбрасывает в NULL.';

-- События воронки (день 5 спринта переиспользует таблицу; campaign заполняет парсер /start ad_<…>).
CREATE TABLE funnel_event (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        REFERENCES users(id),
    club_id    UUID,
    kind       VARCHAR(48) NOT NULL,
    campaign   VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_funnel_event_kind_created ON funnel_event (kind, created_at);

COMMENT ON TABLE funnel_event IS
    'Факты воронки для прогона спринта 1.0: free_meeting_used, paywall_seen, checkout_started, payment_succeeded, subscription_ended (биллинг) и шаги привлечения (день 5). Только запись и агрегаты, в логику продукта не входит.';
COMMENT ON COLUMN funnel_event.club_id IS
    'Клуб, к которому относится шаг (NULL для шагов до создания клуба). Без FK: клуб может быть удалён.';
COMMENT ON COLUMN funnel_event.campaign IS
    'Метка рекламной кампании из /start ad_<campaign> (NULL = органика или шаг без атрибуции).';
