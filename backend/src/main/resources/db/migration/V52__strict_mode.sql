-- Слайс 5 чат-интеграции: «Строгий режим» (docs/modules/club-chat-link.md § Слайс 5).
-- Должники (frozen/expired) переводятся ботом в «только чтение», покинувшие клуб — в бан.

ALTER TABLE club_chat_links
    ADD COLUMN IF NOT EXISTS strict_mode_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS can_restrict_members BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN club_chat_links.strict_mode_enabled IS
    'Тумблер «Строгий режим» (слайс 5): TRUE = бот мьютит должников клуба (frozen/expired) в чате и банит покинувших клуб (кик, отказ, отклонённая заявка, выход, истёкшая отменённая подписка). Включение требует права «Блокировка пользователей» (can_restrict_members).';

COMMENT ON COLUMN club_chat_links.can_restrict_members IS
    'Право бота «Блокировка пользователей» в чате (зеркалит can_pin_messages/can_invite_users; обновляется из my_chat_member и «Проверить права ещё раз»). Для строк, привязанных до V52, — FALSE до первого refresh.';
