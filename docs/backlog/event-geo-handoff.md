# Хэндофф: гео к событию (Яндекс.Карты) — ветка `feature/event-geo`

> **СТАТУС 2026-07-11: ✅ реализовано (см. `docs/modules/event-geo.md`), решение PO: fail-closed.**
> План ниже выполнен; документ сохранён как история.

> Подготовлен 2026-07-11 при закрытии сессии (в ней же: club-invites ✅ в прод PR #107,
> telegrambots 9.5 PR #106). Команда старта новой сессии: **«продолжи с ветки feature/event-geo»**.
> Код фичи НЕ начат — есть ветка, одобренные мокапы и этот план.

## Решения PO (все приняты 2026-07-11)

1. **Вариант 2 с поиском по адресу**: место события = обязательная точка на карте.
   В форме создания события вместо текстового поля «Место» — кнопка **«Добавить место»** →
   шит-пикер: строка поиска адреса → «Найти» (геокодер) → карта прыгает к результату →
   **пин закреплён в центре, тянешь карту — уточняешь точку** → «Готово».
2. **Участнику — статичная мини-карта с пином** (Static API) + кнопки «🧭 Маршрут» и
   «Открыть в Картах» (deep-link, бесключевой).
3. **Опциональное поле-уточнение** к месту («Вход со двора, домофон 12») — отдельное от адреса.
4. **Мокапы ОДОБРЕНЫ PO**: `docs/design/event-geo/mockups/01-geo-flow.html` (локально,
   mockups/ в .gitignore; кадры A — форма, B — пикер, C — страница события).
   ⚠️ Урок сессии club-invites: **реализовывать точно по мокапам**, PO сверяет пиксельно.

## Ключи Яндекса (выданы PO 2026-07-11, тариф бесплатный ~100 запросов/сутки на сервис)

- Значения ключей: у PO (кабинет developer.tech.yandex.ru) и в памяти агента
  (`project_event_geo`). В git НЕ класть; hook safety-guard блокирует запись `.env`-файлов
  агентом — **`frontend/.env.local` (gitignored) создаёт PO руками**:
  `VITE_YANDEX_MAPS_API_KEY` (JavaScript API) и `VITE_YANDEX_STATIC_API_KEY` (Static API).
- Для staging/prod: **PO добавляет те же две переменные в env обоих приложений Coolify**
  (staging + production) — они уходят в build args фронта (см. план ниже). Напомнить PO
  про referer-ограничение ключей в кабинете (домены sslip.io + localhost).
- Лимит 100/сутки на сервис → бережём запросы: геокодинг ТОЛЬКО по кнопке «Найти»
  (никакого live-саджеста), обратный геокодинг — один раз по «Готово».

## План реализации (проверен разведкой, код не начат)

### Env-проводка (фронт собирается в Docker без env сейчас!)
- `frontend/Dockerfile`: `ARG VITE_YANDEX_MAPS_API_KEY` + `ARG VITE_YANDEX_STATIC_API_KEY`
  → `ENV` перед `RUN npm run build`.
- `docker-compose.prod.yml` → `frontend.build.args`: `VITE_YANDEX_MAPS_API_KEY: ${VITE_YANDEX_MAPS_API_KEY}`
  (и второй). Coolify подставит из env приложения.
- `.env.example`: два плейсхолдера.

### Бэкенд
- Сейчас у события только `location_text VARCHAR(500) NOT NULL` (V5). Редактирования
  события нет (только создание/отмена) — пикер нужен только в форме создания.
- **V57**: `events.location_lat DOUBLE PRECISION NULL`, `location_lon NULL`,
  `location_hint VARCHAR(200) NULL` + русские COMMENT ON. Нумерация: max = V56.
- **jOOQ codegen ОБЯЗАТЕЛЕН** (новые колонки). ⚠️ НЕ гнать против локальной `clubs` (она на V18!).
  Рецепт: одноразовый `docker run -d -p 5433:5432 -e POSTGRES_USER=clubs -e POSTGRES_PASSWORD=clubs
  -e POSTGRES_DB=clubs postgres:16-alpine` → прогнать ВСЕ миграции по порядку
  (`ls db/migration | sort -V`, psql через docker exec -i) →
  `DB_URL=jdbc:postgresql://localhost:5433/clubs DB_USER=clubs DB_PASSWORD=clubs ./gradlew generateJooq`
  → снести контейнер.
- `Event` domain / `EventDto`(4 DTO с locationText) / `CreateEventRequest` / `EventMapper` /
  `JooqEventRepository` (insert + селекты): + `locationLat: Double?`, `locationLon: Double?`,
  `locationHint: String?`. Валидация: lat ∈ [-90,90], lon ∈ [-180,180], «оба или ни одного»
  (@Size(max=200) на hint).
- **Обязательность точки — на ФРОНТЕ** (форма не пускает без места); бэкенд толерантен
  (nullable) — легаси-события без точки + fail-open при недоступности CDN Яндекса.
  Micro-вопрос PO при старте: ок ли такой fail-open (PO говорил «обязательный параметр»).

### Фронтенд
- `utils/yandexMaps.ts` (ИЗОЛЯЦИЯ провайдера — решение сессии: вся карта в одном
  компоненте + одном util'е, чтобы смена на 2ГИС/OSM была дешёвой):
  - `loadYmaps3()` — скрипт-лоадер `https://api-maps.yandex.ru/v3/?apikey=<KEY>&lang=ru_RU`;
  - `geocode(text)` / `reverseGeocode(lat, lon)` — HTTP-геокодер
    `https://geocode-maps.yandex.ru/1.x/?apikey=<JS-KEY>&geocode=...&format=json`.
    ⚠️ Геокодер должен работать по ключу JS API (связка «JavaScript API и HTTP Геокодер»);
    если 403 — попросить PO подключить «API Геокодера» в кабинете (отдельный пункт);
  - `staticMapUrl(lat, lon)` — `https://static-maps.yandex.ru/v1?apikey=<STATIC-KEY>&ll={lon},{lat}&z=16&size=650,300&pt={lon},{lat},pm2rdm`
    (⚠️ у Яндекса порядок **lon,lat**!);
  - `routeUrl(lat, lon)` — `https://yandex.ru/maps/?rtext=~{lat},{lon}`;
    `openMapUrl(lat, lon)` — `https://yandex.ru/maps/?pt={lon},{lat}&z=17`.
- `LocationPickerSheet.tsx` (кадр B): rd-шит по паттерну InviteSheet (createPortal,
  rd-sheet-*); строка поиска + кнопка «Найти» → geocode → `map.setLocation`; карта ymaps3
  (`YMap` + `YMapDefaultSchemeLayer`), пин = абсолютный div по центру контейнера;
  «Готово» → координаты центра + reverseGeocode → адрес; «Отмена». Дефолт-центр — Москва.
  Ошибка загрузки CDN → сообщение в шите.
- `CreateEventPage` (кадр A): вместо input «Место» — дашед-кнопка «Добавить место»
  (стиль rd-invite-row) → после выбора карточка адреса с «Изменить» + поле
  «Уточнение к месту (необязательно)». Валидация: место обязательно.
  location_text = адрес из reverse-геокодера.
- `EventPage` (кадр C): в блоке места — адрес + серое уточнение + `<img>` статичной карты
  (тап → openMapUrl) + кнопки «🧭 Маршрут» / «Открыть в Картах». Событ十я без координат —
  текст как сейчас (обратная совместимость).
- `types/api.ts`: EventDto/CreateEventBody + три поля.

### Тесты / прочее
- Бэкенд: валидация пары координат/диапазонов в тестах EventService (или Bean Validation тест).
- Фронт: чистый tsc (rm кэш!), существующие тесты создания события могут требовать
  обновления (обязательное место в форме!) — `CreateEventPage`-тесты проверить.
- Смоук-лист PO: создать событие с точкой (поиск + уточнение пином) → мини-карта на
  странице → «Маршрут» открывает Яндекс.Карты с путём; старое событие — текст без карты;
  событие в закрытом/платном клубе — регресс.

## Контекст сессии (важное)

- Тарифная паника PO снята: 100 запросов/сутки на сервис (PO сначала прочёл как «в месяц»),
  нашего масштаба хватает с запасом; при превышении Яндекс отвечает ошибкой, не счётом.
- «Чисто Static API» обсуждался: участник не теряет ничего, орг теряет весь пикер
  (и поиск, и пин) — отвергнуто, делаем оба.
- Прошлая фича (club-invites) — в проде, хвосты в [[project-club-invites]] памяти:
  вернуть минимум лимита участников (V56 временно 1), вариант Б мультивыбора не решён.
