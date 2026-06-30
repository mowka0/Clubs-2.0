-- V37: Гейт доступа к клубу (de-Stars decoupling, Slice 2).
-- Доступ к платному клубу становится организатор-управляемым: статус 'frozen' + колонки учёта взноса
-- (honor-system; деньги участник->организатор идут МИМО платформы, как складчина). Заменяет Telegram
-- Stars-платёж как драйвер доступа.
--
-- Про ADD VALUE: на PG<12 нельзя в транзакционном блоке, и новое значение нельзя использовать в той же
-- транзакции, где оно добавлено. Мы на PG16 (ADD VALUE в транзакции разрешён), и эта миграция НИГДЕ не
-- использует 'frozen' в DML (только DDL), поэтому оборачивание Flyway безопасно. Любой backfill,
-- проставляющий 'frozen' существующим строкам, ОБЯЗАН быть отдельной поздней миграцией.
ALTER TYPE membership_status ADD VALUE IF NOT EXISTS 'frozen';

-- ADD COLUMN не ссылается на значение enum, поэтому безопасно в той же миграции.
ALTER TABLE memberships
    ADD COLUMN IF NOT EXISTS access_frozen_at    TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS dues_marked_paid_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS dues_marked_by      UUID NULL REFERENCES users(id);

-- Документация схемы (читается через psql \d+ memberships, без необходимости лезть в код).
COMMENT ON COLUMN memberships.status IS
    'Жизненный цикл членства: active = состоит и есть доступ к контенту | frozen = состоит, но доступ закрыт до подтверждения внеплатформенного взноса (de-Stars Slice 2) | grace_period/expired = легаси Stars-истечение, сейчас не используется (dormant) | cancelled = вышел из клуба.';
COMMENT ON COLUMN memberships.access_frozen_at IS
    'Когда организатор заморозил доступ участника (NULL = не заморожен). Проставляется вместе со status=frozen; очищается при разморозке или отметке взноса.';
COMMENT ON COLUMN memberships.dues_marked_paid_at IS
    'Когда организатор последний раз подтвердил получение внеплатформенного взноса (NULL = не отмечено). Honor-system: деньги идут участник->организатор мимо платформы, приложение лишь хранит отметку.';
COMMENT ON COLUMN memberships.dues_marked_by IS
    'Организатор (user), отметивший взнос полученным; FK users(id). NULL, если взнос не отмечен.';
