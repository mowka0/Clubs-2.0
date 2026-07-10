-- Слайс 4 переработан на Member Tags (фидбек PO 2026-07-10): Bot API 9.5 (март 2026) даёт
-- тег обычному участнику БЕЗ повышения в админы (setChatMemberTag, право бота can_manage_tags).
-- Механика «нулевой админ + custom title» (V54) заменена тегами; переименования — честные имена.

ALTER TABLE club_chat_links RENAME COLUMN award_titles_enabled TO award_tags_enabled;
ALTER TABLE club_chat_links DROP COLUMN IF EXISTS can_promote_members;
ALTER TABLE club_chat_links ADD COLUMN IF NOT EXISTS can_manage_tags BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN club_chat_links.award_tags_enabled IS
    'Тумблер «Теги наград» (слайс 4): TRUE = последняя награда участника видна тегом рядом с именем в чате (setChatMemberTag) + двусторонняя синхронизация тег↔награда шедулером. Включение требует права бота «Управление тегами» (can_manage_tags).';
COMMENT ON COLUMN club_chat_links.can_manage_tags IS
    'Право бота «Управление тегами» (Bot API 9.5, can_manage_tags). Библиотека бота его не знает — читается прямым HTTP-вызовом getChatMember при привязке/refresh/my_chat_member.';

ALTER TABLE chat_award_titles RENAME TO chat_award_tags;
ALTER TABLE chat_award_tags RENAME COLUMN title TO tag;
ALTER TABLE chat_award_tags RENAME COLUMN titled_at TO tagged_at;

COMMENT ON TABLE chat_award_tags IS
    'Теги наград, выставленные ботом в клубном чате (слайс 4): кому бот поставил тег и какой. Нужна для снятия тегов при выключении тумблера/отвязке/уходе из клуба и дедупа перевыставления. Теги, поставленные организатором руками (или самим участником при can_edit_tag), здесь не учитываются.';
COMMENT ON COLUMN chat_award_tags.club_id IS
    'Клуб, чей чат тегирован (каскадно удаляется с клубом).';
COMMENT ON COLUMN chat_award_tags.telegram_id IS
    'Telegram id участника с тегом — снапшот на момент выставления.';
COMMENT ON COLUMN chat_award_tags.tag IS
    'Текущий тег (название последней награды, обрезанное до 16 символов — лимит тега Telegram).';
COMMENT ON COLUMN chat_award_tags.tagged_at IS
    'Когда тег был выставлен/обновлён ботом.';
