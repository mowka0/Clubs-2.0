-- V57: гео к событию (Яндекс.Карты) — точка на карте + опциональное уточнение к месту.
-- Колонки nullable ТОЛЬКО ради легаси-событий, созданных до фичи: для новых событий
-- lat/lon обязательны на уровне CreateEventRequest (решение PO: fail-closed —
-- без работающей карты событие не создаётся, текстового фолбэка нет).

ALTER TABLE events
    ADD COLUMN IF NOT EXISTS location_lat  DOUBLE PRECISION NULL,
    ADD COLUMN IF NOT EXISTS location_lon  DOUBLE PRECISION NULL,
    ADD COLUMN IF NOT EXISTS location_hint VARCHAR(200) NULL;

-- Инвариант «оба NULL или оба заданы» закреплён в БД, а не только в DTO: любой будущий
-- write-путь не сможет молча записать половинную точку (фронт при этом показал бы легаси-вид).
ALTER TABLE events
    ADD CONSTRAINT chk_events_location_pair CHECK ((location_lat IS NULL) = (location_lon IS NULL));

COMMENT ON COLUMN events.location_lat IS
    'Широта точки места события (WGS-84, [-90..90]). NULL = легаси-событие без гео-точки; иначе задана вместе с location_lon.';
COMMENT ON COLUMN events.location_lon IS
    'Долгота точки места события (WGS-84, [-180..180]). NULL = легаси-событие без гео-точки; иначе задана вместе с location_lat.';
COMMENT ON COLUMN events.location_hint IS
    'Опциональное уточнение организатора к месту («Вход со двора, домофон 12»), не входит в адрес. NULL = нет уточнения.';
