-- De-Stars: member-initiated dues payment claim. A frozen member declares they paid the off-platform
-- dues (СБП with a screenshot, or cash with an attestation); the organizer reviews and still opens
-- access manually via «Взнос получен» (honor-system preserved — this is evidence, not auto-confirmation).
ALTER TABLE memberships
    ADD COLUMN IF NOT EXISTS dues_claimed_at  TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS dues_claim_method TEXT NULL,
    ADD COLUMN IF NOT EXISTS dues_proof_url   TEXT NULL;

COMMENT ON COLUMN memberships.dues_claimed_at IS
    'Когда участник заявил об оплате взноса (NULL = заявки нет). Сбрасывается, когда организатор подтверждает доступ («Взнос получен») или замораживает участника.';
COMMENT ON COLUMN memberships.dues_claim_method IS
    'Способ оплаты, заявленный участником: ''sbp'' (перевод по СБП со скриншотом) или ''cash'' (наличные, без скриншота). NULL = заявки нет.';
COMMENT ON COLUMN memberships.dues_proof_url IS
    'URL скриншота оплаты для заявки по СБП (NULL для наличных или когда заявки нет). Виден только организаторам клуба.';
