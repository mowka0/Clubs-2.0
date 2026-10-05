-- V101: статус-пост сбора в чате может уходить картинкой (фото сбора — обычно чек) с подписью,
-- как живой закреп встречи (PO 2026-10-05). У фото-сообщения нет текста, и правки идут через
-- editMessageCaption, а не editMessageText — флаг говорит, каким методом редактировать
-- (зеркало event_chat_pins.has_photo). Старые посты — текстовые, дефолт FALSE верен для них.
ALTER TABLE skladchina_chat_posts ADD COLUMN IF NOT EXISTS has_photo BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN skladchina_chat_posts.has_photo IS
    'TRUE = статус вышел картинкой сбора с подписью, правки через editMessageCaption; FALSE = текстовый пост (нет фото, подпись длиннее лимита Telegram, сбой отправки картинки или пост до V101).';
