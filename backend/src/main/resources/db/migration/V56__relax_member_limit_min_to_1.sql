-- club-invites: минимум лимита участников временно 10 → 1 (решение PO 2026-07-11) —
-- тест заполняемости полного клуба («Попроситься» + «Расширить клуб и принять всех»).
-- Зеркалит Bean Validation в CreateClubRequest / UpdateClubRequest. Возврат минимума —
-- отдельной миграцией по решению PO.

ALTER TABLE clubs DROP CONSTRAINT IF EXISTS clubs_member_limit_check;
ALTER TABLE clubs ADD CONSTRAINT clubs_member_limit_check CHECK (member_limit >= 1 AND member_limit <= 80);

COMMENT ON COLUMN clubs.member_limit IS
    'Лимит числа участников клуба, от 1 до 80 (CHECK; минимум временно 1 для теста заполняемости, PO 2026-07-11). Занятое место = membership в active/frozen/expired.';
