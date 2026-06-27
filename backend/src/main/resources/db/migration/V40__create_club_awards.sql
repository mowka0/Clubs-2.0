-- Member admin profile (Variant B) S2: club-local awards an organizer grants to a member.
-- Pure cosmetic recognition — NOT global earned-badges; they never affect reputation/XP/rank (R4).
CREATE TABLE IF NOT EXISTS club_awards (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    club_id     UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    emoji       TEXT NOT NULL,
    label       TEXT NOT NULL,
    awarded_by  UUID NOT NULL REFERENCES users(id),
    awarded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (club_id, user_id, label)
);

COMMENT ON TABLE club_awards IS
    'Награды уровня клуба, которые организатор вручает участнику (club-local, НЕ глобальные earned-бейджи репутации). Чистая косметика: на репутацию/XP/ранг не влияют (R4). Видны всем участникам клуба (R3).';
COMMENT ON COLUMN club_awards.club_id IS 'Клуб, в котором выдана награда (FK clubs.id, каскадное удаление вместе с клубом).';
COMMENT ON COLUMN club_awards.user_id IS 'Участник-получатель награды (FK users.id, каскадное удаление вместе с пользователем).';
COMMENT ON COLUMN club_awards.emoji IS 'Одиночный эмодзи-значок награды (отображается чипом на карточке участника).';
COMMENT ON COLUMN club_awards.label IS 'Короткое название награды, до 40 символов (например «Активист», «Душа клуба»).';
COMMENT ON COLUMN club_awards.awarded_by IS 'Кто выдал награду — организатор/со-организатор (FK users.id). Для аудита, в UI не показывается.';
COMMENT ON COLUMN club_awards.awarded_at IS 'Когда награда выдана.';

-- Suggestions ("как интересы") read distinct labels per club; the member card reads all awards by
-- (club_id, user_id). The UNIQUE (club_id, user_id, label) index serves both (club_id leading column).
