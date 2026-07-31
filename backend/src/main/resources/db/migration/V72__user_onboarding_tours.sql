-- V72: пер-экранный онбординг — у каждого экрана свой независимый тур.
--
-- До этого «пройден ли онбординг» жил одним флагом users.onboarded_at: показали карусель
-- первого входа — значит, всё. Новая модель (решение PO 2026-07-31) считает прохождение
-- ОТДЕЛЬНО по каждому экрану: пройденный тур страницы клуба ничего не говорит про тур
-- профиля. Одной колонкой это уже не выражается, поэтому старый флаг переезжает сюда
-- как тур 'INTRO', а сама колонка удаляется — два источника правды об одном факте
-- разъезжаются рано или поздно.
--
-- tour_key — varchar, а НЕ enum-тип Postgres: набор туров задаётся интерфейсом и меняется
-- вместе с ним (появился экран — появился тур). PG-enum требовал бы ALTER TYPE на каждый
-- такой случай; допустимые значения проверяет enum OnboardingTour на бэкенде.

CREATE TABLE IF NOT EXISTS user_onboarding_tours (
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tour_key     varchar(32) NOT NULL,
    completed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, tour_key)
);

COMMENT ON TABLE user_onboarding_tours IS
    'Пройденные туры онбординга: одна строка = один экран, пройденный этим пользователем до конца. Строки нет — тур не пройден.';
COMMENT ON COLUMN user_onboarding_tours.user_id IS
    'Кто прошёл тур. При удалении пользователя строки уходят каскадом.';
COMMENT ON COLUMN user_onboarding_tours.tour_key IS
    'Экран: INTRO (три слайда первого входа), WELCOME (сцена «Ты в клубе!» после первого вступления), PROFILE, DISCOVERY (главная), CLUB (страница клуба глазами участника), CLUB_OWNER (она же глазами владельца после создания клуба), CLUB_MANAGE (настройки клуба), MY_CLUBS, ACTIVITIES. Допустимые значения задаёт enum OnboardingTour на бэкенде.';
COMMENT ON COLUMN user_onboarding_tours.completed_at IS
    'Когда тур пройден. Отдельного индекса нет: строки читаются только по user_id, а это ведущая колонка первичного ключа.';

-- Перелив: прошедшие старую карусель получают тур INTRO с исходной меткой времени —
-- иначе первый же вход показал бы им её заново.
INSERT INTO user_onboarding_tours (user_id, tour_key, completed_at)
SELECT id, 'INTRO', onboarded_at
FROM users
WHERE onboarded_at IS NOT NULL
ON CONFLICT DO NOTHING;

ALTER TABLE users DROP COLUMN IF EXISTS onboarded_at;
