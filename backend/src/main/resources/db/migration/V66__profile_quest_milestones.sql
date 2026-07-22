-- Профиль-квест (PO 2026-07-22): одноразовые вехи заполнения профиля.
-- Осознанное исключение из принципа «XP только за участие» (reputation-v2 §H3):
-- профильный XP одноразовый, кап 50 (= порог уровня 2 «Свой»).
-- NULL = веха не достигнута; проставленная метка НИКОГДА не сбрасывается —
-- так XP не убывает, даже если пользователь потом очистит поле (инвариант AC-P1b-6).

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS quest_city_at      timestamptz,
    ADD COLUMN IF NOT EXISTS quest_interests_at timestamptz,
    ADD COLUMN IF NOT EXISTS quest_bio_at       timestamptz;

COMMENT ON COLUMN users.quest_city_at IS
    'Веха профиль-квеста «Город» (+10 XP): когда город впервые заполнен. NULL = не достигнута. Одноразовая, не сбрасывается при очистке поля.';
COMMENT ON COLUMN users.quest_interests_at IS
    'Веха профиль-квеста «Интересы» (+25 XP): когда впервые появился ≥1 интерес. NULL = не достигнута. Одноразовая, не сбрасывается при очистке.';
COMMENT ON COLUMN users.quest_bio_at IS
    'Веха профиль-квеста «О себе» (+15 XP): когда bio впервые заполнено. NULL = не достигнута. Одноразовая, не сбрасывается при очистке поля.';

-- Backfill: существующие пользователи с уже заполненными полями честно получают вехи
-- (и +до 50 XP). Квест у них завершён → карточка-квест им не показывается.
UPDATE users SET quest_city_at = now()
    WHERE quest_city_at IS NULL AND city IS NOT NULL AND btrim(city) <> '';
UPDATE users SET quest_bio_at = now()
    WHERE quest_bio_at IS NULL AND bio IS NOT NULL AND btrim(bio) <> '';
UPDATE users u SET quest_interests_at = now()
    WHERE quest_interests_at IS NULL
      AND EXISTS (SELECT 1 FROM user_interests ui WHERE ui.user_id = u.id);
