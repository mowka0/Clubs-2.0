-- TASK-043: Add telegram_group_id to clubs for linking Telegram group chats
ALTER TABLE clubs ADD COLUMN IF NOT EXISTS telegram_group_id BIGINT;
