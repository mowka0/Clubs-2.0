-- V50: Слайс 3.5 эпика club-chat-link — «живой статус сбора» (складчины в чате).
-- Тумблер skladchina_status_enabled на привязке чата + таблица сообщений-статусов,
-- которые бот ведёт в чате по складчине. Спека: docs/modules/club-chat-link.md § Слайс 3.5.

ALTER TABLE club_chat_links ADD COLUMN IF NOT EXISTS skladchina_status_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN club_chat_links.skladchina_status_enabled IS
    'Тумблер «Статус сборов в чате»: TRUE = бот постит в чат живой статус каждой активной складчины («Скинулись N из M», дедлайн, упоминания ещё не ответивших) и редактирует его при изменении прогресса, а напоминание за 24ч до дедлайна важного сбора шлёт в чат вместо DM (DM — только тем, кого нет в чате). Отдельный от live_pin_enabled — публичные упоминания организатор включает осознанно.';

CREATE TABLE IF NOT EXISTS skladchina_chat_posts (
    skladchina_id UUID PRIMARY KEY REFERENCES skladchinas(id) ON DELETE CASCADE,
    chat_id       BIGINT NOT NULL,
    message_id    BIGINT NOT NULL,
    closed_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE skladchina_chat_posts IS
    'Сообщение-статус бота в привязанном чате по конкретной складчине («живой статус сбора», слайс 3.5 club-chat-link): прогресс «Скинулись N из M», дедлайн и упоминания ещё не ответивших. Бот редактирует его при изменении прогресса. Одна строка на складчину; поста-итога нет — финал это последний edit.';
COMMENT ON COLUMN skladchina_chat_posts.skladchina_id IS
    'Складчина, о которой рассказывает статус (PK — у складчины максимум одно сообщение-статус).';
COMMENT ON COLUMN skladchina_chat_posts.chat_id IS
    'Telegram id чата, куда постился статус — снимок на момент поста (при перепривязке чата старые строки не переезжают).';
COMMENT ON COLUMN skladchina_chat_posts.message_id IS
    'Telegram id сообщения-статуса. NOT NULL: строка создаётся только после успешной отправки поста (неудачный пост строку не оставляет — повторная попытка при следующем включении тумблера).';
COMMENT ON COLUMN skladchina_chat_posts.closed_at IS
    'Когда статус закрыт (складчина закрыта/отменена или тумблер выключен). NULL = живой, бот редактирует его при изменениях прогресса.';
COMMENT ON COLUMN skladchina_chat_posts.created_at IS
    'Когда строка создана (первый пост бота по складчине).';
COMMENT ON COLUMN skladchina_chat_posts.updated_at IS
    'Когда строка обновлялась в последний раз (закрытие статуса).';
