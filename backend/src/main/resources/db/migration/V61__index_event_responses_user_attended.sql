-- V61: Частичный индекс под секцию «История» ленты GET /api/users/me/events.
-- Существующий idx_event_responses_user_id (V9) индексирует ВСЕ отклики пользователя
-- и потому недостаточно селективен для нового предиката: он вытянет все строки user_id
-- и отфильтрует attendance='attended' уже после выборки. Индекс ровно под предикат
-- истории оставляет в дереве только строки с подтверждённой явкой.

CREATE INDEX IF NOT EXISTS idx_event_responses_user_attended
    ON event_responses (user_id)
    WHERE attendance = 'attended';

COMMENT ON INDEX idx_event_responses_user_attended IS
    'Частичный индекс под секцию «История» ленты /me/events: отклики с подтверждённой явкой.';
