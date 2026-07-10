-- Учёт банов строгого режима (слайс 5, фидбек PO со staging 2026-07-08):
-- при отвязке чата бот уходит и снять баны больше некому — храним, кого забанили МЫ,
-- чтобы отвязка могла снять все наши баны (ручные баны организатора не трогаем).

CREATE TABLE IF NOT EXISTS chat_strict_bans (
    club_id     UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    telegram_id BIGINT NOT NULL,
    banned_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (club_id, telegram_id)
);

COMMENT ON TABLE chat_strict_bans IS
    'Баны, наложенные строгим режимом чата (слайс 5 club-chat-link): кого бот забанил за уход из клуба. Нужна для снятия ВСЕХ наших банов при отвязке чата (бот уходит — иначе баны навсегда). Ручные баны организатора здесь не учитываются и не снимаются.';
COMMENT ON COLUMN chat_strict_bans.club_id IS
    'Клуб, чей строгий режим наложил бан (каскадно удаляется с клубом).';
COMMENT ON COLUMN chat_strict_bans.telegram_id IS
    'Telegram id забаненного — unban работает по нему, снапшот на момент бана.';
COMMENT ON COLUMN chat_strict_bans.banned_at IS
    'Когда бан был наложен ботом.';
