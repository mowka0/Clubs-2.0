-- V102: история согласий на автосписание (docs/modules/platform-billing.md § 5.1b).
--
-- Условие Robokassa для подключения рекуррентных платежей (ответ поддержки 2026-10-05): согласие
-- на автоматические списания — отдельная отметка на форме оплаты, НЕ проставленная по умолчанию,
-- с текстом «Я согласен на автоматические списания согласно условиям оферты», и историю таких
-- согласий магазин обязан хранить. Таблица append-only: строка на каждый чекаут (с отметкой или
-- без — видно, что человек выбрал на этой оплате) и на каждое переключение ползунка «Продлевать
-- автоматически» на странице клуба (выключил = отозвал). Строки не правятся и не удаляются;
-- club_id без FK — история переживает удаление клуба, как chat_trial и funnel_event. Индексов нет
-- намеренно: таблица маленькая, читается только руками при споре.
CREATE TABLE autopay_consent (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    club_id         UUID        NOT NULL,
    user_id         UUID        NOT NULL REFERENCES users(id),
    payment_id      UUID        REFERENCES platform_payment(id),
    subscription_id UUID        REFERENCES service_subscription(id),
    granted         BOOLEAN     NOT NULL,
    source          VARCHAR(16) NOT NULL CHECK (source IN ('CHECKOUT', 'TOGGLE')),
    wording         TEXT        NOT NULL,
    offer_edition   VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE autopay_consent IS
    'История согласий владельцев клубов на автоматические списания (рекуррент Robokassa). Только запись: строка на каждый чекаут и на каждое переключение ползунка автопродления; ничего не обновляется и не удаляется. В логику продукта не входит — ползунок живёт на service_subscription.autopay, отметка чекаута — на platform_payment.autopay_requested.';
COMMENT ON COLUMN autopay_consent.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN autopay_consent.club_id IS
    'Клуб (чат), за подписку которого дано согласие. Без FK: клуб может быть удалён, история остаётся.';
COMMENT ON COLUMN autopay_consent.user_id IS
    'Кто поставил или снял отметку (FK users.id) — владелец клуба на момент действия.';
COMMENT ON COLUMN autopay_consent.payment_id IS
    'Материнский счёт, на чекауте которого сделана отметка (FK platform_payment.id). NULL у переключений ползунка.';
COMMENT ON COLUMN autopay_consent.subscription_id IS
    'Подписка на момент действия (FK service_subscription.id). NULL у первого чекаута: строка подписки появляется с первой оплатой.';
COMMENT ON COLUMN autopay_consent.granted IS
    'true = согласие дано (отметка поставлена / ползунок включён), false = согласия нет (отметка снята при чекауте) или оно отозвано (ползунок выключен).';
COMMENT ON COLUMN autopay_consent.source IS
    'Откуда действие: CHECKOUT = отметка на форме оплаты | TOGGLE = ползунок «Продлевать автоматически» на странице клуба.';
COMMENT ON COLUMN autopay_consent.wording IS
    'Текст рядом с отметкой или ползунком на момент действия — формулировка на экране может смениться, спор разбирается по той, под которой согласие дали.';
COMMENT ON COLUMN autopay_consent.offer_edition IS
    'Редакция публичной оферты («5 октября 2026 года»), действовавшая на момент действия, — из той же генерации, что текст оферты в шите и в боте.';
COMMENT ON COLUMN autopay_consent.created_at IS 'Когда действие совершено.';

-- Отметка по умолчанию снята (требование Robokassa), новая подписка повторяет её: дефолты колонок
-- приводим к факту, чтобы схема не обещала «включено по умолчанию» (R9 спеки переписан).
ALTER TABLE service_subscription ALTER COLUMN autopay SET DEFAULT FALSE;
ALTER TABLE platform_payment ALTER COLUMN autopay_requested SET DEFAULT FALSE;

COMMENT ON COLUMN service_subscription.autopay IS
    'Ползунок владельца «Продлевать автоматически». Включается только явным согласием: отметкой на форме оплаты (по умолчанию снята — требование Robokassa, V102) или ползунком на странице клуба; история согласий — autopay_consent. Выключен = за 3 и 1 день до конца периода приходит DM с кнопкой «Оплатить», списания нет.';
COMMENT ON COLUMN platform_payment.autopay_requested IS
    'Отметка «Я согласен на автоматические списания…» в шите на момент чекаута (только для MOTHER; по умолчанию снята). Переносится на подписку при подтверждении оплаты — строки подписки до первой оплаты ещё нет. Сам факт с текстом и редакцией оферты — в autopay_consent.';
