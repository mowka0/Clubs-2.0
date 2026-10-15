-- V105: funnel_event → product_event, общий журнал продуктовых событий (docs/modules/product-analytics.md § 2).
-- Таблица воронки становится журналом всего, что делает человек в приложении, боте и на сервере:
-- добавляются источник события и детали в JSONB. Переименование сейчас, пока в таблице десятки строк:
-- имя «воронка» для журнала, где большинство строк — открытия экранов, вводило бы в заблуждение,
-- а Grafana и SQL аналитики пишутся против имени, которое останется на годы.

ALTER TABLE funnel_event RENAME TO product_event;
ALTER INDEX idx_funnel_event_kind_created RENAME TO idx_product_event_kind_created;
ALTER TABLE product_event RENAME CONSTRAINT funnel_event_pkey TO product_event_pkey;
-- Журнал переживает удаление пользователя: его события остаются, автор обезличивается (user_id → NULL).
-- Без этого удалить человека из users нельзя было бы, пока за ним есть хоть одно событие.
ALTER TABLE product_event DROP CONSTRAINT funnel_event_user_id_fkey;
ALTER TABLE product_event ADD CONSTRAINT product_event_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE product_event
    ADD COLUMN source VARCHAR(8),
    ADD COLUMN props  JSONB;

-- Существующие строки: шаги привлечения пришли из апдейтов бота, шаги биллинга — из логики сервера.
UPDATE product_event SET source = CASE
    WHEN kind IN ('bot_started', 'chat_connected', 'chat_disconnected') THEN 'bot'
    ELSE 'server'
END;
ALTER TABLE product_event ALTER COLUMN source SET NOT NULL;

-- Под вопросы активации и удержания: «что делал человек после первого входа», «кто вернулся через неделю».
CREATE INDEX idx_product_event_user_created ON product_event (user_id, created_at);

COMMENT ON TABLE product_event IS
    'Журнал продуктовых событий (docs/modules/product-analytics.md): шаги воронки привлечения и биллинга, действия на сервере (вступление, встреча, голос, сбор, долг, обратная связь), нажатия inline-кнопок бота и то, чего сервер не видит, — запуски и открытия экранов Mini App. Только запись и агрегаты (недельный отчёт, Grafana), в логику продукта не входит. Бывшая funnel_event (V97, V100).';
COMMENT ON COLUMN product_event.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN product_event.user_id IS
    'Кто совершил действие (FK users.id, ON DELETE SET NULL — журнал переживает удаление пользователя). NULL у bot_started до первого входа в Mini App, у событий шедулера без актора и у событий удалённых пользователей.';
COMMENT ON COLUMN product_event.telegram_id IS
    'Telegram id того, кто совершил шаг. Заполняется у шагов привлечения (bot_started, chat_connected) и у нажатий кнопок бота, где пользователя в users может ещё не быть; у остальных NULL. Кампания из /start ad_<slug> атрибутируется к подключениям и оплатам по этому id.';
COMMENT ON COLUMN product_event.club_id IS
    'Клуб, к которому относится событие (NULL у экранов и действий без клуба). Без FK: клуб может быть удалён, а журнал должен пережить его.';
COMMENT ON COLUMN product_event.kind IS
    'Вид события строкой (перечисление ProductEventKind). Привлечение: bot_started, chat_connected, chat_disconnected. Биллинг: trial_started, paywall_seen, checkout_started, payment_succeeded, subscription_ended. Сервер: club_joined, event_created, vote_cast, skladchina_created, debt_claimed, feedback_sent. Бот: bot_button_clicked. Mini App: app_opened, screen_opened, onboarding_step, onboarding_completed, pay_sheet_opened. Без enum — набор видов меняется чаще, чем схема.';
COMMENT ON COLUMN product_event.source IS
    'Откуда пришло событие: app — Mini App через POST /api/track; bot — апдейт Telegram-бота; server — бизнес-логика бэкенда (биллинг, сервисы, шедулеры).';
COMMENT ON COLUMN product_event.campaign IS
    'Метка рекламной кампании: slug из /start ad_<slug> (латиница, цифры, «_» и «-», до 64 знаков, приводится к строчным), только у bot_started. NULL = органика или шаг без атрибуции.';
COMMENT ON COLUMN product_event.props IS
    'Детали события плоским JSON-объектом по product-analytics.md § 3 (например {"screen":"club"} или {"vote":"going","eventId":"…"}); до 10 ключей, значения — строка до 100 знаков, число или boolean. NULL, если деталей нет. Персональных данных здесь нет по контракту.';
COMMENT ON COLUMN product_event.created_at IS 'Когда событие записано сервером (клиентское время не принимается).';
