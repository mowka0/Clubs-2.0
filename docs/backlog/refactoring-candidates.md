# Refactoring / cleanup candidates

Кандидаты на рефакторинг и чистку, выявленные при аудите репозитория (2026-06-21).
Ничего не блокируют. Делать **отдельным проходом**, не во время фич.

## RF-1: осиротевший backend-эндпоинт `GET /api/clubs/{clubId}/skladchinas/active`

**Контекст:** при чистке мёртвого фронт-кода (2026-06-21) удалён единственный
клиент этого эндпоинта — фронтовый хук `useClubActiveSkladchinasQuery` + фетчер
`getClubActiveSkladchinas` (UI-потребитель убран ещё в #44).

**Что осталось без клиента:**
- `SkladchinaController.getClubActiveSkladchinas`
  (`@GetMapping("/api/clubs/{clubId}/skladchinas/active")`)
- `SkladchinaQueryService.getClubActiveSkladchinas(clubId, callerId)`

**Пометка:** эндпоинт рабочий, но мёртвый (нет вызывающей стороны). Решить при
бэкенд-проходе: удалить controller-метод + service-метод, либо оставить как
публичный API. Если удалять — проверить, что `MySkladchinaListItemDto` ещё
используется другими чтениями (используется: `getMySkladchinas`). Бэкенд в чистке
2026-06-21 не трогали намеренно (scope = фронт + конфиг + доки).

> Тот же паттерн возможен у `GET /api/clubs/{clubId}/applications`: фронтовый
> `useClubApplicationsQuery` + `getClubApplications` удалены в той же чистке,
> backend-эндпоинт оставлен. Проверить при том же проходе.

## RF-2: обёртка `FoxErrorState` над FoxEmpty (ревью волны 3, 2026-07-20)

Рецепт error-сцены повторён в 5 местах (DiscoveryPage, MyClubsPage, EventsTab,
SkladchinasTab, InvitePage): `fox-error.png` + `variant="error"` + title/description
+ «Повторить» с хаптикой и `refetch`. Порог DRY (3+) пройден — напрашивается
`FoxErrorState({ title, description, onRetry })` в shared. Отложено по правилу
«boilerplate-рефакторинг отдельным проходом, не во время фич».

## RF-3: `safeStorageGet/Set` в `shared/lib` (ревью панели чата, 2026-07-20)

Один и тот же `try/catch`-обвяз вокруг `localStorage` (в части клиентов Telegram
доступ бросает) написан уже трижды: `store/useThemeStore.ts:26-31,57`,
`components/CityPicker.tsx:70,87-90`, `components/club/ClubChatConnectBanner.tsx`.
Порог правила `.claude/rules/frontend.md` § «Общие утилиты» («3+ мест → `shared/lib/`»)
пройден. Отложено по правилу «boilerplate-рефакторинг отдельным проходом, не во время фич».

Заодно там же — разнобой в именовании ключей: `clubs.cityChoice` (точка),
`clubs-theme-mode` (дефис), `clubs:chat-banner-dismissed:<clubId>` (двоеточие).
Привести к одному разделителю.

## RF-4: три вариации ghost-кнопки (ревью панели чата, 2026-07-20)

`.rd-ghost-btn` (заливной `--surface`), `.rd-fox-btn` (плоский `--accent`) и
`.rd-chat-panel-ghost` (прозрачный, `--glass-border`, `width:100%`). Каждая
оправдана своим мокапом, но три ghost-кнопки в одном приложении означают, что
токен кнопки не определён. Решение — за дизайном: либо модификатор
`.rd-ghost-btn.rd-on-glass`, либо явная система кнопок.

## RF-5: Telegram-вызовы освобождения чата внутри транзакции (ревью багфикса чата, 2026-07-20)

`ChatLinkService.releaseLink` делает N последовательных сетевых вызовов (unpin на каждый
закреп, unmute на каждого должника, unban на каждый бан, снятие каждого тега, revoke, leave)
внутри `@Transactional`. В `deleteClub` это происходит **после** трёх каскадных UPDATE'ов,
то есть записи `events`/`skladchinas`/`applications` держатся заблокированными на время
похода в Telegram — у крупного клуба это десятки RTT под локами. Плюс Telegram-сторона
не откатывается: если транзакция упадёт после освобождения (окно — один UPDATE `softDelete`),
бот уже вышел, а строка привязки воскреснет откатом.

Фикс: вынести Telegram-часть за границу транзакции — `@TransactionalEventListener(AFTER_COMMIT)`
или `@Async`. Тот же паттерн уже помечен как отложенный hardening в `StrictModeService`
(«вызывается в транзакции тумблера»). Делать вместе для всей чат-механики, отдельным проходом.

## RF-6: нет рейт-лимита на групповой `/start` (ревью багфикса чата, 2026-07-20)

После фикса бот не покидает чат с живой привязкой, поэтому отказы больше не гасятся уходом.
Спам-петля закрыта точечно (посторонним в занятом чате бот молчит), но общего троттлинга
на командном пути бота нет — ни `bucket4j`, ни фильтра по chatId. Владелец клуба может
слать `/start` в цикле и получать ответ на каждое. Кандидат: троттлить отказы по `chatId`
(один в N минут).

## RF-7: `findMyFeed` событий — переписать OR-объединение на UNION ALL при росте данных (ревью истории событий, 2026-07-20)

После добавления «Истории» лента `/me/events` строится одним запросом с OR двух
предикатов через LEFT JOIN'ы. Ревью показало: Postgres не делает OR-to-UNION
трансформацию через джойны, driving relation — `EVENTS⋈CLUBS(is_active)`, то есть
скан всех событий всех активных клубов; частичный индекс V61
(`event_responses(user_id) WHERE attendance='attended'`) в таком плане, вероятно,
не используется. На текущем объёме неизмеримо (staging/prod — десятки событий),
EXPLAIN на крошечной базе неинформативен (seq scan там оптимален при любой форме).

Триггер: рост событий до тысяч ИЛИ p95 `/me/events` > 300ms (порог из
events-feed.md § Non-functional). Фикс: UNION ALL двух половин (каждая стартует
со своего индекса: memberships(user) / V61) с ORDER BY поверх объединения —
4-ключевой сортировочный паттерн сохраняется. Индекс V61 уже в схеме и ждёт.

## Прочие кандидаты из аудита (structure, не dead-code) — НЕ СЕЙЧАС

- **DRY: пагинация.** Инвариант `(total + size - 1) / size` скопирован 4× — в
  `JooqEventRepository` (2×), `JooqClubRepository` (1×) и как private
  `computeTotalPages` в `JooqSkladchinaRepository`. Напрашивается фабрика
  `PageResponse.of(content, total, page, size)` в `common/dto/PageResponse.kt`.
- **Длинные методы:** `findMyFeed` в `JooqSkladchinaRepository` (~130 строк) и
  `JooqEventRepository` (~115 строк) — батч-агрегаты просятся в private-хелперы.
- **Крупные фронт-страницы (>480 строк):** `MyClubsPage` (690), `EventPage` (670),
  `SkladchinaPage` (557), `ClubPage` (517), `OrganizerClubManage` (489) — мешают
  data-wiring + хендлеры + большой JSX; кандидаты на декомпозицию.

> Заметка: крупные jOOQ-репозитории (`JooqSkladchinaRepository` 609,
> `ApplicationService` 458 и т.д.) по аудиту **size-justified** — наборы мелких
> CRUD-методов, не god-object. Не переписывать ради метрики.
