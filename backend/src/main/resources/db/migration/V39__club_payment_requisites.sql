-- SBP dues: organizer's off-platform payment details, surfaced to members who owe dues («Оплатить по СБП»).
-- Money flows member→organizer directly (honor-system, like skladchina) — the platform stays out of the flow,
-- the organizer still confirms «Взнос получен». No auto-confirmation (an SBP P2P transfer doesn't notify us).
ALTER TABLE clubs
    ADD COLUMN IF NOT EXISTS payment_link        TEXT NULL,
    ADD COLUMN IF NOT EXISTS payment_method_note TEXT NULL;

COMMENT ON COLUMN clubs.payment_link IS
    'Реквизиты организатора для членского взноса (СБП-ссылка / номер / ссылка банка). NULL = не задано (кнопки «Оплатить» нет). Виден только участникам клуба (active/frozen), не гостям/заявителям на рассмотрении.';
COMMENT ON COLUMN clubs.payment_method_note IS
    'Подсказка к реквизитам (например «Тинькофф, СБП по номеру…»). NULL = нет.';
