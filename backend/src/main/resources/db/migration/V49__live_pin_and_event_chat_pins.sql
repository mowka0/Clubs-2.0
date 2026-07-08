-- V49: Слайс 3 эпика club-chat-link — «живой закреп + итог встречи».
-- Тумблер live_pin_enabled на привязке чата + таблица сообщений, которые бот ведёт
-- в чате по событию (закреплённый статус и пост-итог). Спека: docs/modules/club-chat-link.md.

ALTER TABLE club_chat_links ADD COLUMN IF NOT EXISTS live_pin_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN club_chat_links.live_pin_enabled IS
    'Тумблер «Живой закреп»: TRUE = бот постит в чат закреплённый статус каждого будущего события (голоса Этапа 1 / гонка за места Этапа 2) и редактирует его, а после отметки явки публикует итог встречи. Включение требует права закреплять сообщения.';

CREATE TABLE IF NOT EXISTS event_chat_pins (
    event_id           UUID PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE,
    chat_id            BIGINT NOT NULL,
    message_id         BIGINT,
    closed_at          TIMESTAMPTZ,
    summary_message_id BIGINT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE event_chat_pins IS
    'Сообщения бота в привязанном чате по конкретному событию («живой закреп», слайс 3 club-chat-link): закреплённый статус, который бот редактирует при изменении ростера, и пост-итог после отметки явки. Одна строка на событие.';
COMMENT ON COLUMN event_chat_pins.event_id IS
    'Событие, о котором рассказывает закреп (PK — у события максимум одно сообщение-статус).';
COMMENT ON COLUMN event_chat_pins.chat_id IS
    'Telegram id чата, куда постился статус — снимок на момент поста (при перепривязке чата старые строки не переезжают).';
COMMENT ON COLUMN event_chat_pins.message_id IS
    'Telegram id сообщения-статуса (NULL = пост не создавался или не удался — например, итог запостился без закрепа).';
COMMENT ON COLUMN event_chat_pins.closed_at IS
    'Когда закреп закрыт (событие стартовало/отменено или тумблер выключен). NULL = живой, бот редактирует его при изменениях ростера.';
COMMENT ON COLUMN event_chat_pins.summary_message_id IS
    'Telegram id поста-итога «Встреча №N прошла» (NULL = итог ещё не постился — гейт от дублей при повторной отметке явки).';
COMMENT ON COLUMN event_chat_pins.created_at IS
    'Когда строка создана (первый пост бота по событию).';
COMMENT ON COLUMN event_chat_pins.updated_at IS
    'Когда строка обновлялась в последний раз (закрытие, итог).';
