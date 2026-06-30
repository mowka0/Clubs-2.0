-- Member admin profile (Variant B) S1: private organizer note about a member.
ALTER TABLE memberships
    ADD COLUMN IF NOT EXISTS organizer_note TEXT NULL;

COMMENT ON COLUMN memberships.organizer_note IS
    'Приватная заметка организатора об участнике (NULL = нет). Видна только организаторам клуба (owner/co-org), не самому участнику. Косметика — на доступ/репутацию не влияет.';
