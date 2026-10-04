-- V100: telegram_id в funnel_event (docs/modules/funnel.md § 3.2).
-- Шаг bot_started пишется на «/start» в личке, когда строки в users ещё нет (она появляется
-- при первом входе в Mini App). Кампания из /start ad_<slug> сходится с последующими шагами
-- по telegram id, поэтому он хранится прямо в факте. Индексов нет намеренно: таблица маленькая,
-- отчёт раз в неделю — добавить при первом медленном запросе.
ALTER TABLE funnel_event ADD COLUMN IF NOT EXISTS telegram_id BIGINT;

COMMENT ON COLUMN funnel_event.telegram_id IS
    'Telegram id того, кто совершил шаг. Заполняется у шагов привлечения (bot_started, chat_connected), где пользователя в users может ещё не быть; у шагов биллинга NULL. Кампания из /start ad_<slug> атрибутируется к подключениям и оплатам по этому id.';

COMMENT ON TABLE funnel_event IS
    'Факты воронки спринта 1.0: шаги привлечения bot_started, chat_connected, chat_disconnected и шаги биллинга trial_started, paywall_seen, checkout_started, payment_succeeded, subscription_ended. Только запись и агрегаты недельного отчёта (FunnelReportScheduler), в логику продукта не входит.';

COMMENT ON COLUMN funnel_event.kind IS
    'Шаг воронки строкой: bot_started (/start в личке), chat_connected (чат привязан к клубу), chat_disconnected (отвязка или бота выгнали), trial_started (первая встреча чата), paywall_seen, checkout_started, payment_succeeded, subscription_ended. Без enum — набор шагов меняется чаще, чем схема.';

COMMENT ON COLUMN funnel_event.campaign IS
    'Метка рекламной кампании: slug из /start ad_<slug> (латиница, цифры, «_» и «-», до 64 знаков, приводится к строчным), только у bot_started. NULL = органика или шаг без атрибуции.';
