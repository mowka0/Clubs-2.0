# Backend: cleanup membership-записей на удалённые клубы

Обнаружено 2026-04-25 при тесте PR #24 (`feature/pre-design-stores`) на staging.

---

## Статус (обновлено 2026-06-13)

**Частично закрыто** веткой `bugfix/club-delete-cascade`. При soft-delete клуба
(`ClubService.deleteClub`) теперь каскадно отменяются дочерние активности —
но это **статусная отмена, а не cleanup orphan-записей** (Вариант A ниже не реализован
для memberships/applications; для events/skladchinas сделана статусная отмена):

- ✅ **События** — нефинализированные (`upcoming/stage_1/stage_2`,
  `attendance_finalized = false`) → `cancelled` (`EventRepository.cancelActiveEventsByClub`).
  Шедулеры перестают их обрабатывать, фантомных «отметьте явку»-DM нет.
  Финализированные/`completed` не трогаются (репутация сохранена).
  Детали — `docs/modules/events.md` § «Каскадная отмена событий при удалении клуба».
- ✅ **Складчины** — `active` → `cancelled`, их `pending`-участники → `released`
  (репутационно-нейтрально, без ledger-строк; минуя `closeInternal`).
  Детали — `docs/modules/skladchina.md` § «Удаление клуба».
- ⏳ **Заявки (applications)** — **остаётся открытым.** Заявки в удалённый клуб
  по-прежнему висят в «Моих заявках»: `JooqApplicationRepository.findByUserId` не
  джойнит `CLUBS` / не фильтрует `is_active`. **Осознанно не вошло в каскад**
  (scope ветки = события + складчины, продуктовое решение 2026-06-13). Фикс по
  аналогии с membership (фильтр `club.is_active = true` в `findByUserId`) — кандидат
  в отдельную ветку.
- ➖ **Memberships** — каскад **не нужен**: `«Мои клубы»` уже фильтруют `clubs.is_active`
  (см. `docs/modules/membership.md` § AC-2). Orphan-membership в выдачу не попадает.

**Что НЕ изменилось:** репутацию каскад намеренно не трогает (продуктовое требование);
hard-delete и Вариант B (stub-response для soft-deleted) по-прежнему не реализованы —
консьюмеры `useClubQuery(id)` для orphan-application всё ещё получат 404 (см. ниже).

---

## Что не так

В DevTools Console на MyClubsPage / ProfilePage появляется 404 (прежде также на OrganizerPage до его удаления в `feature/restructure-bottom-tabs`):
```
GET /api/clubs/3a3606ec-e747-49a4-896f-dde7be3247ce 404
```

Источник: `useQueries` в этих страницах берёт `clubIds` из `getMyClubs` (memberships) или `getMyApplications` (applications) и для каждого делает `useClubQuery(id)` — нужно для отображения названия клуба.

Если у пользователя есть membership/application, ссылающийся на **soft-deleted** клуб (через `SettingsTab.handleDelete` → `DELETE /api/clubs/:id`), backend возвращает 404 на `GET /api/clubs/:id`, потому что soft-deleted клуб скрыт из ответов.

## Pre-existing? Да

В коде до миграции (`MyClubsPage.tsx` старый):
```ts
useEffect(() => {
  myClubs.forEach(m => {
    getClub(m.clubId).then(setClubDetails).catch(() => {/* silent */});
  });
}, [myClubs]);
```

Catch silently swallowed 404. В новой версии TanStack Query логирует error в консоль — это **прозрачнее**, не регрессия.

## Что не так с backend behavior

Когда клуб soft-удалён, его memberships и applications должны:
- Либо тоже быть soft-удалены / помечены как «orphan»
- Либо `GET /api/clubs/:id` для soft-deleted клуба должен возвращать минимальный stub (`{ id, name: "Удалённый клуб", isDeleted: true }`) для отображения в UI

Сейчас backend оставляет «висячие» membership/application-записи, которые отображаются в UI как пустые строки (без названия клуба).

## Варианты фикса

### Вариант A: Cascade soft-delete на membership/application
При `deleteClub(id)`:
- `UPDATE memberships SET deleted_at = NOW() WHERE club_id = id AND deleted_at IS NULL`
- `UPDATE applications SET deleted_at = NOW() WHERE club_id = id AND deleted_at IS NULL`

Frontend `getMyClubs` / `getMyApplications` уже фильтруют по `deleted_at IS NULL` (или это нужно проверить). Тогда orphan-записи исчезнут из ответов.

**Минус:** теряется история. Если клуб восстановят (есть ли такая операция?) — memberships не вернутся.

### Вариант B: Stub-response для soft-deleted клубов
`GET /api/clubs/:id` для soft-deleted возвращает 200 с `{ id, name: "Клуб удалён", deletedAt: "...", description: null, ... }` вместо 404. Frontend отображает «(Клуб удалён)» в списке membership/application.

**Минус:** добавляет проверку soft-delete во все consumer'ы (`useClubQuery` users), компликация UI.

### Вариант C (рекомендую): A + B комбинированно
- A для cleanup при delete (новые memberships не висят)
- B для legacy данных, которые уже есть в БД от предыдущих delete (если такие existing — backfill миграцией)

### Вариант D: Полное hard-delete с cascade FK
Hard-delete клуба + cascade FK на memberships/applications. Самый чистый, но теряется история и возможность audit trail.

## Frontend defensive fix (можно добавить независимо)

В `MyClubsPage` / `ProfilePage` (после `feature/restructure-bottom-tabs` — двух страницах вместо трёх): для membership с `useQueries` query, у которого `error?.status === 404`, скрыть или отобразить как «(удалён)». Не блокирует backend fix, но улучшает UX немедленно.

```ts
const clubQueries = useQueries({ queries: clubIds.map(...) });
const visibleMemberships = memberships.filter((m, i) => clubQueries[i].error?.status !== 404);
```

## Когда фиксить

Не блокирует, но «приколхозило» в console + если пользователь видит «пустые» строки в списках — теряется доверие.

Кандидат в ветку `bugfix/orphan-memberships-cleanup`. Backend + frontend, ~2-3 часа.
