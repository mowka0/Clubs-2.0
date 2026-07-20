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
