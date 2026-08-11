-- Шаблоны встреч: именованные заготовки формы создания события для клуба.
--
-- Клуб проводит одну и ту же встречу многократно («Разговорный клуб по вторникам»), и каждый
-- раз организатор заполняет девять полей, из которых меняется одно — дата. Дороже всего место:
-- открыть пикер, найти адрес, поставить точку. Шаблон хранит всё, кроме даты, и предзаполняет
-- обычную форму создания — она остаётся полностью редактируемой.
--
-- Это НЕ расписание: авто-создание встреч по календарю сюда не входит, решение создать встречу
-- остаётся за человеком. И НЕ новый формат события: на выходе обычная строка events, создаваемая
-- тем же POST /api/clubs/{id}/events. Спека: docs/modules/event-templates.md.

CREATE TABLE IF NOT EXISTS event_templates (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    club_id             UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    -- Ярлык списка, отдельный от title: в клубе бывают «Разговорный клуб (вторники)» и
    -- «Разговорный клуб (суббота, новички)» с одинаковым названием самой встречи.
    name                VARCHAR(60) NOT NULL,
    title               VARCHAR(255) NOT NULL,
    description         TEXT,
    location_text       VARCHAR(500),
    location_lat        DOUBLE PRECISION,
    location_lon        DOUBLE PRECISION,
    location_hint       VARCHAR(200),
    participant_limit   INTEGER,
    is_open_event       BOOLEAN NOT NULL DEFAULT FALSE,
    is_urgent_event     BOOLEAN NOT NULL DEFAULT FALSE,
    stage2_lead_minutes INTEGER,
    photo_url           VARCHAR(1024),
    -- Вместо даты (она меняется всегда) шаблон помнит день недели и время — форма подставляет
    -- ближайшее будущее совпадение. Оба NULL-able: без дня недели поле даты остаётся пустым.
    default_weekday     SMALLINT,
    default_time        TIME,
    created_by          UUID NOT NULL REFERENCES users (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Инварианты зеркалят события (V57/V58/V62/V68/V69): шаблон, из которого нельзя собрать
    -- валидную встречу, бесполезен, и ловить это лучше здесь, чем при создании события.
    CONSTRAINT chk_event_templates_location_pair
        CHECK ((location_lat IS NULL) = (location_lon IS NULL)),
    CONSTRAINT chk_event_templates_limit
        CHECK (
            (is_open_event AND participant_limit IS NULL)
            OR (NOT is_open_event AND participant_limit IS NOT NULL AND participant_limit > 0)
        ),
    CONSTRAINT chk_event_templates_open_stage2
        CHECK (NOT is_open_event OR stage2_lead_minutes IS NULL),
    CONSTRAINT chk_event_templates_urgent
        CHECK (NOT is_urgent_event OR (NOT is_open_event AND stage2_lead_minutes IS NULL)),
    CONSTRAINT chk_event_templates_stage2_bounds
        CHECK (stage2_lead_minutes IS NULL OR stage2_lead_minutes BETWEEN 1080 AND 7200),
    CONSTRAINT chk_event_templates_weekday
        CHECK (default_weekday IS NULL OR default_weekday BETWEEN 1 AND 7)
);

COMMENT ON TABLE event_templates IS
    'Именованные заготовки формы создания встречи, привязанные к клубу (не более 10 на клуб, '
    'лимит держит сервис). Хранят всё, кроме даты; применение шаблона открывает обычную форму '
    'создания с заполненными полями, ничего не блокируя. Расписанием и отдельным форматом '
    'события НЕ являются.';

COMMENT ON COLUMN event_templates.club_id IS
    'Клуб-владелец (FK clubs.id, каскадное удаление вместе с клубом).';
COMMENT ON COLUMN event_templates.name IS
    'Имя шаблона в списке выбора — отдельно от title, потому что у двух шаблонов может совпадать '
    'название встречи. Уникально в клубе без учёта регистра (uq_event_templates_club_name).';
COMMENT ON COLUMN event_templates.title IS 'Название будущей встречи (ложится в events.title).';
COMMENT ON COLUMN event_templates.description IS
    'Заготовка описания встречи; NULL = описания нет. Разовые детали («тема недели») организатор '
    'дописывает в форме.';
COMMENT ON COLUMN event_templates.location_text IS
    'Адрес места из обратного геокодера; NULL = место не сохранено.';
COMMENT ON COLUMN event_templates.location_lat IS
    'Широта точки места (WGS-84); NULL = точки нет. Инвариант: широта и долгота задаются вместе.';
COMMENT ON COLUMN event_templates.location_lon IS
    'Долгота точки места (WGS-84); NULL = точки нет.';
COMMENT ON COLUMN event_templates.location_hint IS
    'Уточнение к месту («вход со двора, домофон 12»); NULL = нет.';
COMMENT ON COLUMN event_templates.participant_limit IS
    'Лимит участников будущей встречи; NULL = открытая встреча (согласовано с is_open_event).';
COMMENT ON COLUMN event_templates.is_open_event IS
    'TRUE = шаблон открытой встречи: без лимита участников и целиком вне репутации (см. V62).';
COMMENT ON COLUMN event_templates.is_urgent_event IS
    'TRUE = шаблон срочной встречи: событие родится сразу в stage_2, без Этапа 1 (см. V69). '
    'Несовместимо с is_open_event и со своим stage2_lead_minutes.';
COMMENT ON COLUMN event_templates.stage2_lead_minutes IS
    'За сколько минут до старта встреча уйдёт в подтверждение мест; NULL = глобальный дефолт '
    'events.stage2-trigger-minutes-before. Диапазон 1080..7200 зеркалит V68.';
COMMENT ON COLUMN event_templates.photo_url IS
    'Фото-афиша будущей встречи; NULL = нет. Переносится в форму видимой и снимается кнопкой '
    '«Убрать» — на афише может быть запечена дата прошлой встречи.';
COMMENT ON COLUMN event_templates.default_weekday IS
    'День недели повторов, 1 = понедельник … 7 = воскресенье (ISO-8601). NULL = не задан, форма '
    'оставит дату пустой. Значение — в ЛОКАЛЬНОЙ зоне организатора: его вычисляет клиент, потому '
    'что events.event_datetime хранится как TIMESTAMPTZ и вывод дня недели из UTC промахнулся бы.';
COMMENT ON COLUMN event_templates.default_time IS
    'Время начала повторов в локальной зоне организатора (см. default_weekday); NULL = не задано.';
COMMENT ON COLUMN event_templates.created_by IS
    'Кто создал шаблон (FK users.id). Правка и удаление разрешены любому с MANAGE_EVENTS в клубе, '
    'а не только автору: шаблон принадлежит клубу, а не человеку.';
COMMENT ON COLUMN event_templates.created_at IS 'Когда шаблон был создан.';
COMMENT ON COLUMN event_templates.updated_at IS
    'Когда шаблон последний раз перезаписан (в том числе через «Обновить шаблон» из формы создания).';

-- Регистронезависимая уникальность имени: иначе в списке выбора появятся два визуально
-- одинаковых пункта, и «Обновить шаблон» станет неоднозначным.
CREATE UNIQUE INDEX IF NOT EXISTS uq_event_templates_club_name
    ON event_templates (club_id, lower(name));
COMMENT ON INDEX uq_event_templates_club_name IS
    'Уникальность имени шаблона внутри клуба без учёта регистра; заодно основной индекс выборки '
    'шаблонов клуба (club_id — ведущая колонка).';
