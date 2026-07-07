-- V47: Связка клуб ↔ телеграм-чат (эпик club-chat-link, слайсы 1-2: привязка + дверь).
-- Заменяет легаси clubs.telegram_group_id (V2/V10): та колонка хранила только id чата,
-- привязывалась вручную через REST без верификации и никогда не использовалась фронтом.

CREATE TABLE IF NOT EXISTS club_chat_links (
    club_id           UUID PRIMARY KEY REFERENCES clubs(id) ON DELETE CASCADE,
    chat_id           BIGINT NOT NULL UNIQUE,
    chat_title        VARCHAR(255),
    linked_by_user_id UUID NOT NULL REFERENCES users(id),
    linked_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    bot_status        VARCHAR(20) NOT NULL DEFAULT 'member',
    can_pin_messages  BOOLEAN NOT NULL DEFAULT FALSE,
    can_invite_users  BOOLEAN NOT NULL DEFAULT FALSE,
    door_enabled      BOOLEAN NOT NULL DEFAULT FALSE,
    door_invite_link  TEXT,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE club_chat_links IS
    'Привязка телеграм-группы к клубу (один клуб = один чат, один чат = один клуб). Создаётся ботом при добавлении в группу владельцем клуба по deep link ?startgroup=<club_id>. Спека: docs/modules/club-chat-link.md';
COMMENT ON COLUMN club_chat_links.club_id IS
    'Клуб, к которому привязан чат (PK — у клуба максимум один чат).';
COMMENT ON COLUMN club_chat_links.chat_id IS
    'Telegram id группы/супергруппы (UNIQUE — чат нельзя привязать к двум клубам). При миграции группы в супергруппу обновляется по migrate_to_chat_id.';
COMMENT ON COLUMN club_chat_links.chat_title IS
    'Название чата на момент привязки/последнего refresh (NULL = неизвестно). Только для отображения в UI.';
COMMENT ON COLUMN club_chat_links.linked_by_user_id IS
    'Кто привязал чат (владелец клуба на момент привязки — гейт безопасности проверяет владение).';
COMMENT ON COLUMN club_chat_links.linked_at IS
    'Когда чат привязан.';
COMMENT ON COLUMN club_chat_links.bot_status IS
    'Статус БОТА в чате из my_chat_member: administrator (полноценная работа) | member (бот в чате без прав) | left/kicked (бот удалён — фичи гаснут, привязка сохраняется до отвязки владельцем).';
COMMENT ON COLUMN club_chat_links.can_pin_messages IS
    'Есть ли у бота право закреплять сообщения (для «живого закрепа», слайс 3). Обновляется по my_chat_member и при refresh.';
COMMENT ON COLUMN club_chat_links.can_invite_users IS
    'Есть ли у бота право приглашать участников (нужно для «двери»: создание invite link и одобрение заявок).';
COMMENT ON COLUMN club_chat_links.door_enabled IS
    'Тумблер «Вход в чат через заявки»: TRUE = бот выдал door_invite_link и обрабатывает chat_join_request (членство в чате = членство в клубе).';
COMMENT ON COLUMN club_chat_links.door_invite_link IS
    'Ссылка-приглашение createChatInviteLink(creates_join_request=true) (NULL = дверь выключена). Видна только участникам с доступом + владельцу.';
COMMENT ON COLUMN club_chat_links.updated_at IS
    'Когда строка обновлялась в последний раз (права, тумблеры, миграция chat_id).';

-- Переносим легаси-привязки: права неизвестны (false) → UI покажет состояние
-- «проверьте права», кнопка refresh дообогатит. linked_by = текущий владелец клуба.
INSERT INTO club_chat_links (club_id, chat_id, linked_by_user_id, bot_status)
SELECT c.id, c.telegram_group_id, c.owner_id, 'member'
FROM clubs c
WHERE c.telegram_group_id IS NOT NULL
ON CONFLICT DO NOTHING;

ALTER TABLE clubs DROP COLUMN IF EXISTS telegram_group_id;
