-- Слайс 4 чат-интеграции: «Титулы наград» (docs/modules/club-chat-link.md § Слайс 4).
-- Последняя награда участника становится титулом рядом с именем в клубном чате.

ALTER TABLE club_chat_links
    ADD COLUMN IF NOT EXISTS award_titles_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS can_promote_members BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN club_chat_links.award_titles_enabled IS
    'Тумблер «Титулы наград» (слайс 4): TRUE = бот показывает последнюю награду участника титулом рядом с именем в чате (повышение в админа с минимальным правом + custom title). Включение требует права «Назначение администраторов» (can_promote_members).';

COMMENT ON COLUMN club_chat_links.can_promote_members IS
    'Право бота «Назначение администраторов» в чате (зеркалит can_restrict_members; обновляется из my_chat_member и «Проверить права ещё раз»). Для строк, привязанных до V54, — FALSE до первого refresh.';

CREATE TABLE IF NOT EXISTS chat_award_titles (
    club_id     UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    telegram_id BIGINT NOT NULL,
    title       VARCHAR(16) NOT NULL,
    titled_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (club_id, telegram_id)
);

COMMENT ON TABLE chat_award_titles IS
    'Титулы наград, выставленные ботом в клубном чате (слайс 4 club-chat-link): кого бот повысил в «минимального админа» и какой титул стоит. Нужна для снятия титулов при выключении тумблера/отвязке, отката при отзыве награды и для строгого режима (перед mute/ban титулованного бот сначала разжалует). «Настоящие» админы, назначенные организатором, здесь не учитываются и не трогаются.';
COMMENT ON COLUMN chat_award_titles.club_id IS
    'Клуб, чей чат титулован (каскадно удаляется с клубом).';
COMMENT ON COLUMN chat_award_titles.telegram_id IS
    'Telegram id титулованного участника — снапшот на момент выставления.';
COMMENT ON COLUMN chat_award_titles.title IS
    'Текущий титул (название последней награды, обрезанное до 16 символов — лимит Telegram).';
COMMENT ON COLUMN chat_award_titles.titled_at IS
    'Когда титул был выставлен/обновлён ботом.';
