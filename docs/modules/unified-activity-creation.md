# Module: Unified Activity Creation (единая вкладка «Активности»)

> **Status:** ✅ Реализовано в `feature/unified-activity-creation` (post-flight
> docs alignment 2026-05-24). Спецификация ниже описывает фактическую реализацию.
>
> Связанные специи (не дублируем — ссылаемся):
> - `docs/modules/events.md` — backend events модуль (источник правды по event-flow)
> - `docs/modules/skladchina.md` — backend skladchina модуль (источник правды по skladchina-flow)
> - `docs/modules/club-page-unified.md` — `ClubPage` структура и таб-навигация для members
> - `docs/modules/events-feed.md` — `/me/events` агрегированная лента (паттерн для club-level аналога)

---

## Цель

Сейчас организатор клуба создаёт «активности» (события + сборы) в **двух разных
табах** `OrganizerClubManage` (`События`, `Сборы`), каждый со своим inline-CTA
и формой. По мере роста списка типов активностей (afisha, опросы, гарантированные
встречи) эта модель **разлетится**: добавление каждого типа = новая вкладка,
новый CTA, новый паттерн. Уже сейчас на 6 табах (`Участники · Заявки · События
· Сборы · Финансы · ⚙`) TabsList скроллится горизонтально на мобильном.

Цель — **один entry point на создание любой активности** (через Modal-picker
«+ Создать», см. § Picker — Modal vs ActionSheet) и **единая лента активностей** где события и сборы рендерятся в
телеграм-чат-стиле (newest-created сверху, группировка по дню). Структурно — это
4-я вкладка вместо двух (`События` + `Сборы` → `Активности`). Так
организатору проще ориентироваться, а добавление нового типа активности — это
+1 опция в picker'е + +1 case в карточке, без изменения навигации.

Параллельно — переиспользуем эту же ленту в **read-only** виде на ClubPage
(member view): сейчас там `ClubEventsTab` показывает только события, складчины
не видны вообще. Member должен видеть весь набор активностей клуба одним
списком.

---

## Scope

### Входит (MVP)

**Backend:**
- Новый endpoint `GET /api/clubs/{id}/activities` — унифицированная лента
  событий+сборов из одного клуба, page-based pagination, sort `createdAt DESC`
- DTO `ActivityItemDto` — discriminated union (поле `type: 'event' | 'skladchina'`)
- Новый сервис `ActivityService` в пакете `com.clubs.activity` (новый пакет)
- Расширение `SkladchinaRepository` методом `findAllByClub(clubId, includeCompleted, page, size)`
  — текущий `findActiveByClub` отдаёт только active, для unified-feed нужны
  completed/failed тоже (для dimmed UX)
- Существующие endpoints (`/api/clubs/{id}/events`, `/api/clubs/{clubId}/skladchinas/active`)
  **сохраняются** — они используются в других контекстах (`/me/events`,
  legacy-вызовы) и не ломаются

**Frontend:**
- Новая страница `CreateEventPage` (`/clubs/:id/events/new`) — миррор
  `CreateSkladchinaPage`, полноэкранная, с back-кнопкой и sticky bottom submit
- Новый picker-компонент `CreateActivityPicker` на базе `Modal` из
  `@telegram-apps/telegram-ui` (2 опции: 🗓 Событие, 💰 Сбор; см. § Picker — Modal vs ActionSheet)
- Новая вкладка `ActivitiesManageTab` в `OrganizerClubManage` (заменяет
  `EventsTab` + `SkladchinaManageTab`)
- Новый компонент `ActivityCard` — рендерит и event-карточку, и skladchina-карточку
  (внутри switch по `type`), поддерживает dimmed-вариант для completed
- Новый api/queries слой `api/activities.ts` + `queries/activities.ts`
- Доработка `ClubPage` / `ClubEventsTab` → новый `ClubActivitiesTab` (read-only,
  без `+ Создать`-кнопки); таб переименовывается «События» → «Активности»
- Router: добавить `/clubs/:id/events/new`

### НЕ входит (v2 / отдельные PR)

- Afisha / опросы / другие новые типы активностей — picker заранее экстенсибилен,
  но добавление типа = отдельная задача
- Inline quick-actions в карточке (RSVP / mark-paid прямо из ленты) — карточка
  по тапу ведёт на детальную страницу
- Cursor-based pagination — page-based (консистентно с `findByClubId`/`findMyFeed`)
- Past-only фильтр / dedicated «Архив» секция — completed inline dimmed в основной ленте
- Real-time обновления (WebSocket/SSE) — invalidation после mutations
- Drag-to-reorder / pin-to-top активностей
- Group-by-week (вместо by-day) — только day-grouping в v1
- Скрытие completed `includeCompleted=false` в UI чипом — backend поддерживает
  параметр, frontend в v1 показывает «всё»

---

## User Stories

### US-1 (Critical): Organizer — единая точка создания

**Как** организатор клуба
**Я хочу** одну кнопку `+ Создать` в табе «Активности», которая открывает picker
с типами (Событие / Сбор)
**Чтобы** не выбирать таб-«Сбор», таб-«Событие» в зависимости от типа активности

### US-2 (Critical): Organizer — единая лента наблюдения

**Как** организатор клуба
**Я хочу** видеть события и сборы одним списком, отсортированным по
`createdAt DESC` (как чат)
**Чтобы** «свежесозданное сверху» работало одинаково и для событий, и для сборов,
без переключения табов

### US-3 (Important): Organizer — completed inline, не отдельным табом

**Как** организатор клуба
**Я хочу** видеть завершённые активности **в той же ленте, dimmed**, по тапу
открывать детальный экран как обычно
**Чтобы** не уходить «в архив» ради сводки «что было на прошлой неделе»

### US-4 (Important): Member — единая лента просмотра

**Как** member клуба
**Я хочу** видеть в табе «Активности» (на `/clubs/:id`) и события, и сборы клуба
одним списком
**Чтобы** не открывать SkladchinaPage отдельно через DM-уведомление, а видеть
сборы рядом с событиями

### US-5 (Important): Member — без `+ Создать`

**Как** member клуба (не organizer)
**Я хочу** видеть только список активностей **без** кнопки `+ Создать`
**Чтобы** UI не предлагал недоступное действие

### US-6 (Nice): Organizer — фильтр по типу

**Как** организатор клуба с большим количеством активностей
**Я хочу** chips-фильтр `Все · События · Сборы`
**Чтобы** быстро сузить список до одного типа

### US-7 (Nice): Member — тот же фильтр

**Как** member клуба
**Я хочу** тот же `Все · События · Сборы` фильтр в `/clubs/:id`
**Чтобы** не пролистывать события когда меня интересует только сбор

### US-8 (Edge): Empty state

**Как** новый organizer/member пустого клуба
**Я хочу** видеть осмысленный empty state «В клубе пока нет активностей»
**Чтобы** понимать что список не сломался, а действительно пуст

---

## Files to create / modify

### Backend

| Path | Action | Purpose |
|---|---|---|
| `backend/src/main/kotlin/com/clubs/activity/ActivityController.kt` | NEW | REST-endpoint `GET /api/clubs/{id}/activities` |
| `backend/src/main/kotlin/com/clubs/activity/ActivityService.kt` | NEW | Бизнес-логика: запросить events + skladchinas, смержить, отсортировать, разрезать на страницу |
| `backend/src/main/kotlin/com/clubs/activity/mapper/ActivityMapper.kt` | NEW | Маппинг `Event → ActivityItemDto.EventActivity`, `Skladchina → ActivityItemDto.SkladchinaActivity` |
| `backend/src/main/kotlin/com/clubs/activity/dto/ActivityItemDto.kt` | NEW | sealed class (см. § «API контракт») |
| `backend/src/main/kotlin/com/clubs/skladchina/SkladchinaRepository.kt` | MODIFY | Добавить метод `findAllByClubWithAggregates(clubId, includeCompleted): List<SkladchinaWithAggregates>` + объявить новый data class `SkladchinaWithAggregates` (нейтральный к caller'у, без `myStatus`-поля из `MySkladchinaFeedItem`). Пагинация делается на уровне `ActivityService` после in-memory merge — репозиторий отдаёт полный список. |
| `backend/src/main/kotlin/com/clubs/skladchina/JooqSkladchinaRepository.kt` | MODIFY | Реализация нового метода с batch-aggregates (collected/participants/paid) по тому же паттерну что `findMyFeed`, но без `userId`-логики. |
| `backend/src/main/kotlin/com/clubs/event/EventRepository.kt` | MODIFY (опционально) | Если нужен метод `findAllByClub(clubId, page, size)` без `status`-фильтра. Текущий `findByClubId` принимает nullable `EventStatus?` — можно переиспользовать с `null` |

> **Замечание про слияние:** `ActivityService` тянет события и сборы из
> существующих репозиториев, мержит in-memory. **Альтернатива** — единый SQL
> через `UNION ALL` поверх `events` и `skladchinas` с server-side
> sort+limit+offset — лучше с т.з. pagination правильности (см. § «Decisions and
> open questions» #1). MVP: in-memory merge с over-fetch (взять
> `(page+1)*size` каждого типа, смержить, отрезать страницу). Если станет
> узким — мигрировать на UNION в отдельной задаче.

### Frontend

| Path | Action | Purpose |
|---|---|---|
| `frontend/src/pages/CreateEventPage.tsx` | NEW | Полноэкранная форма создания события — миррор `CreateSkladchinaPage` |
| `frontend/src/components/manage/CreateActivityPicker.tsx` | NEW | `Modal`-picker с двумя опциями (Событие / Сбор). См. § «Picker — Modal vs ActionSheet» — `ActionSheet` в `@telegram-apps/telegram-ui` v2 не экспортируется, поэтому используется `Modal` со списком кнопок. |
| `frontend/src/components/manage/ActivitiesManageTab.tsx` | NEW | Organizer-таб — заменяет `EventsTab` + `SkladchinaManageTab` |
| `frontend/src/components/club/ClubActivitiesTab.tsx` | NEW | Read-only таб для member view (`ClubPage`) — заменяет `ClubEventsTab` |
| `frontend/src/components/manage/ActivityCard.tsx` | NEW | Унифицированная карточка (внутри switch по `type`) |
| `frontend/src/components/manage/ActivityFilterChips.tsx` | NEW | Chips `Все · События · Сборы` |
| `frontend/src/components/manage/ActivityFeedList.tsx` | NEW | Группировка по дню (`Сегодня`/`Вчера`/`4 мая`, рендерится uppercase через CSS `text-transform`) + рендер карточек + IntersectionObserver-sentinel для infinite scroll |
| `frontend/src/api/activities.ts` | NEW | `getClubActivities(clubId, params)` |
| `frontend/src/queries/activities.ts` | NEW | `useClubActivitiesQuery` (InfiniteQuery) |
| `frontend/src/queries/queryKeys.ts` | MODIFY | Добавить `activities.byClub(clubId, filters)` |
| `frontend/src/types/api.ts` | MODIFY | Добавить TS-типы `ActivityItemDto`, `ActivityType`, `EventActivityDto`, `SkladchinaActivityDto` |
| `frontend/src/pages/OrganizerClubManage.tsx` | MODIFY | Удалить `EventsTab` (~lines 380-663) + `SkladchinaManageTab` (~lines 72-130) + `EventDetailModal` (~lines 313-377) полностью — подтверждено user'ом, dead code; `TabKey` сократить до `members \| applications \| activities \| finances \| settings`; `TAB_LABELS` обновить; добавить `ActivitiesManageTab` |
| `frontend/src/pages/ClubPage.tsx` | MODIFY | `TabId` сменить `events` → `activities`; обновить лейбл «События» → «Активности»; импортировать `ClubActivitiesTab` вместо `ClubEventsTab` |
| `frontend/src/components/club/ClubEventsTab.tsx` | REMOVE | После миграции — удалить файл |
| `frontend/src/router.tsx` | MODIFY | Добавить route `/clubs/:id/events/new` → lazy `CreateEventPage` |
| `frontend/src/queries/events.ts` | MODIFY | Расширить invalidation в `useCreateEventMutation.onSuccess`: invalidate `activities.byClub(clubId)` |
| `frontend/src/queries/skladchina.ts` | MODIFY | Аналогично — invalidate `activities.byClub(clubId)` в `useCreateSkladchinaMutation.onSuccess` |

### Routing reference

| Path | Page | Auth |
|---|---|---|
| `/clubs/:id` → tab `activities` | `ClubPage` → `ClubActivitiesTab` (read-only) | member или organizer |
| `/clubs/:id/manage` → tab `activities` | `OrganizerClubManage` → `ActivitiesManageTab` | organizer |
| `/clubs/:id/events/new` | `CreateEventPage` | organizer |
| `/clubs/:id/skladchina/new` | `CreateSkladchinaPage` (существует) | organizer |

---

## API контракт

### `GET /api/clubs/{id}/activities`

Унифицированная лента активностей одного клуба. Возвращает события + складчины
смерженные и отсортированные по `createdAt DESC`.

**Authorization:** `@RequiresMembership(clubIdParam = "id")` — доступ имеют active
members **и** organizer (organizer всегда active member клуба по контракту
`MembershipService`).

**Path parameter:**
| Name | Type | Note |
|---|---|---|
| `id` | UUID | club ID |

**Query parameters:**
| Name | Type | Default | Note |
|---|---|---|---|
| `type` | string | `null` (= все) | `event` \| `skladchina`. Невалидное значение → 400 `VALIDATION_ERROR`. |
| `includeCompleted` | boolean | `true` | `false` исключает event.status=`completed`/`cancelled` и skladchina.status=`closed_success`/`closed_failed`/`cancelled` |
| `page` | int | `0` | 0-based |
| `size` | int | `20` | max `50`; > 50 → 400 |

**Response 200:** `PageResponse<ActivityItemDto>`

```json
{
  "content": [
    {
      "type": "event",
      "id": "11111111-1111-1111-1111-111111111111",
      "clubId": "00000000-0000-0000-0000-000000000aaa",
      "title": "Йога в парке",
      "createdAt": "2026-05-22T18:30:00Z",
      "isCompleted": false,
      "eventDatetime": "2026-05-30T11:00:00+03:00",
      "locationText": "Парк Горького, главный вход",
      "participantLimit": 20,
      "goingCount": 12,
      "status": "upcoming"
    },
    {
      "type": "skladchina",
      "id": "22222222-2222-2222-2222-222222222222",
      "clubId": "00000000-0000-0000-0000-000000000aaa",
      "title": "Бронь сауны на 1 июня",
      "createdAt": "2026-05-22T10:00:00Z",
      "isCompleted": false,
      "paymentMode": "fixed_equal",
      "totalGoalKopecks": 1500000,
      "collectedKopecks": 600000,
      "deadline": "2026-05-28T23:59:00+03:00",
      "participantCount": 10,
      "paidCount": 4,
      "status": "active",
      "affectsReputation": true
    }
  ],
  "totalElements": 14,
  "totalPages": 1,
  "page": 0,
  "size": 20
}
```

### DTO Shape (sealed)

Discriminator: поле `type`. На backend — Kotlin `sealed class` где `type` —
обычное `abstract val` со строковым значением, переопределённым в каждом
наследнике. Jackson сериализует его как обычное поле — на проводе получается
ровно один `"type": "event" | "skladchina"`. На frontend — TS discriminated union.

> **Почему нет `@JsonTypeInfo`/`@JsonSubTypes`:** DTO — **response-only**, сервер
> его никогда не десериализует (фронтенд читает как TS-union). Полиморфная
> deserialization-машинерия Jackson'а добавляет сложность под фичу, которой нет,
> и комбинирование `EXISTING_PROPERTY` с явным `type` val было хрупким между
> версиями Jackson. Reviewer заблокировал annotation-вариант, оставили чистый
> sealed class с explicit `type` override.

```kotlin
sealed class ActivityItemDto {
    abstract val type: String
    abstract val id: UUID
    abstract val clubId: UUID
    abstract val title: String
    abstract val createdAt: OffsetDateTime
    abstract val isCompleted: Boolean

    data class EventActivity(
        override val id: UUID,
        override val clubId: UUID,
        override val title: String,
        override val createdAt: OffsetDateTime,
        override val isCompleted: Boolean,
        val eventDatetime: OffsetDateTime,
        val locationText: String,
        val participantLimit: Int,
        val goingCount: Int,
        val status: String,                  // EventStatus enum value
        val descriptionPreview: String?      // first 40 chars of description, trimmed; null if no description
    ) : ActivityItemDto() {
        override val type: String = "event"
    }

    data class SkladchinaActivity(
        override val id: UUID,
        override val clubId: UUID,
        override val title: String,
        override val createdAt: OffsetDateTime,
        override val isCompleted: Boolean,
        val paymentMode: String,             // SkladchinaMode enum
        val totalGoalKopecks: Long?,
        val collectedKopecks: Long,
        val deadline: OffsetDateTime,
        val participantCount: Int,
        val paidCount: Int,
        val status: String,                  // SkladchinaStatus enum
        val affectsReputation: Boolean
    ) : ActivityItemDto() {
        override val type: String = "skladchina"
    }
}
```

**TypeScript:**

```ts
type ActivityType = 'event' | 'skladchina';

interface ActivityBase {
  type: ActivityType;
  id: string;
  clubId: string;
  title: string;
  createdAt: string;        // ISO 8601
  isCompleted: boolean;
}

interface EventActivityDto extends ActivityBase {
  type: 'event';
  eventDatetime: string;
  locationText: string;
  participantLimit: number;
  goingCount: number;
  status: 'upcoming' | 'stage_1' | 'stage_2' | 'completed' | 'cancelled';
  descriptionPreview: string | null;  // first 40 chars of event description
}

interface SkladchinaActivityDto extends ActivityBase {
  type: 'skladchina';
  paymentMode: 'fixed_equal' | 'fixed_individual' | 'voluntary';
  totalGoalKopecks: number | null;
  collectedKopecks: number;
  deadline: string;
  participantCount: number;
  paidCount: number;
  status: 'active' | 'closed_success' | 'closed_failed' | 'cancelled';
  affectsReputation: boolean;
}

type ActivityItemDto = EventActivityDto | SkladchinaActivityDto;
```

### `isCompleted` computation (backend)

- **Event:** `status IN ('completed', 'cancelled')`
- **Skladchina:** `status IN ('closed_success', 'closed_failed', 'cancelled')`

Поле вычисляется маппером — UI не должен дублировать enum-логику.

### Errors

| HTTP | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `type` не из `event`/`skladchina`, `size > 50`, `page < 0` |
| 401 | `AUTH_REQUIRED` | Нет JWT |
| 403 | `FORBIDDEN` | User не active member клуба (через `@RequiresMembership` aspect — общий error code из `GlobalExceptionHandler.handleForbidden`, не специфичный `NOT_MEMBER`). См. [`docs/backlog/auth-error-code-granularity.md`](../backlog/auth-error-code-granularity.md) — потенциальная задача на детализацию error-кодов для OWASP A09. |
| 404 | `CLUB_NOT_FOUND` | Клуб не существует / soft-deleted |

### Связанные endpoint'ы (без изменений)

- `POST /api/clubs/{id}/events` — создание события (вызывается из `CreateEventPage`)
- `POST /api/clubs/{clubId}/skladchinas` — создание сбора (вызывается из `CreateSkladchinaPage`)
- `GET /api/clubs/{id}/events` — paged events, kept (используется в `/me/events` flow и legacy-вызовах)
- `GET /api/clubs/{clubId}/skladchinas/active` — active-only, kept (используется legacy SkladchinaManageTab до удаления; после удаления — можно тоже удалить в отдельной задаче, но не блокер этого PR)

---

## Frontend компоненты

### `CreateActivityPicker`

#### Picker — Modal vs ActionSheet (важно)

`@telegram-apps/telegram-ui` **v2 не экспортирует `ActionSheet`-компонент**
(`grep ActionSheet node_modules/@telegram-apps/telegram-ui` пусто). В качестве
замены используется `Modal` с двумя кнопками-опциями внутри. С точки зрения
UX это эквивалентно: bottom-sheet поверх контента, swipe-to-dismiss, кнопки
с emoji + subtitle. С точки зрения a11y `Modal` сам по себе даёт focus-trap.
Если в будущей версии UI-kit'а `ActionSheet` появится — миграция точечная,
поведение от пользователя не меняется.

`Modal` с двумя опциями-кнопками.

**Props:**
```ts
interface CreateActivityPickerProps {
  open: boolean;
  onClose: () => void;
  onSelectEvent: () => void;       // navigate(`/clubs/${clubId}/events/new`)
  onSelectSkladchina: () => void;  // navigate(`/clubs/${clubId}/skladchina/new`)
}
```

**Behavior:**
- Open: триггерится из родителя (`ActivitiesManageTab`) при tap на `+ Создать`,
  родитель эмитит `haptic.impact('light')` в момент открытия
- Tap опции: `haptic.impact('medium')` → close → выполнить callback
- Tap backdrop / системная отмена (`onOpenChange(false)`): `haptic.impact('light')` → close
- Опции:
  1. `🗓 Событие` — subtitle «Встреча с датой, временем, лимитом»
  2. `💰 Сбор` — subtitle «Сбор денег на бронь / инвентарь / подарок»

### `CreateEventPage`

Полноэкранная форма. Структурный миррор `CreateSkladchinaPage.tsx` —
тот же `brand-page`, `BrandBackdrop`, `mc-hero`, использует `useBackButton(true)`.

**URL:** `/clubs/:id/events/new`. Через React Router useParams.

**Поля (те же что в существующем inline-form):**
- `title` (string, required, max 255) — `Input`
- `description` (string, optional) — `Textarea`
- `locationText` (string, required, max 500) — `Input`
- `eventDatetime` (datetime-local, required, future) — `<input type="datetime-local">`
- `participantLimit` (int, required, > 0) — `<input type="number">`
- `votingOpensDaysBefore` — **НЕ показывается в UI вообще**. Backend всегда применяет default 14 (определён в `CreateEventRequest`). Решение: упрощение формы для organizer'а; кастомизация — за отдельной фичей, если когда-нибудь будет реальный запрос.

**Submit:**
1. Frontend-валидация (те же rules что в inline-form)
2. `haptic.impact('medium')`
3. `useCreateEventMutation.mutateAsync({ clubId, body })`
4. Success:
   - `haptic.notify('success')`
   - `navigate('/clubs/' + clubId + '/manage?tab=activities', { replace: true, state: { toast: 'Событие создано' } })`
5. Error:
   - `haptic.notify('error')`
   - Inline-сообщение под форм-блоком (как в `CreateSkladchinaPage`)

**Cancel button:** `navigate(-1)` — стандартный history.back.

**Toast pickup:** `OrganizerClubManage` читает `location.state.toast` и показывает
через `<Toast>` (паттерн уже использован при `onDeleted` в SettingsTab).

### `ActivityFilterChips`

```ts
type ActivityFilter = 'all' | 'event' | 'skladchina';

interface ActivityFilterChipsProps {
  value: ActivityFilter;
  onChange: (next: ActivityFilter) => void;
}
```

Три pill-chip, текущий — выделен (brand-accent). Тап — `haptic.select()` → onChange.

### `ActivityCard`

Полиморфная карточка. Внутри — switch по `activity.type`.

```ts
interface ActivityCardProps {
  activity: ActivityItemDto;
  onClick: () => void;
}
```

**Common visuals:**
- Border-radius, padding, brand-стили — единые для обоих типов
- Если `activity.isCompleted` → wrapper opacity `0.55`, badge `Завершено` в правом верхнем углу

**Event variant (type='event'):**
- Лейбл-иконка `🗓` в углу карточки
- Title (`activity.title`)
- Subtitle row 1: `formatDatetime(activity.eventDatetime)` · `activity.locationText`
- Subtitle row 2 (опц.): если `activity.descriptionPreview` не пуст — рендерится отдельной строкой более тусклым цветом (`var(--tgui--hint_color)`, font-size 13), ровно как пришло (бэк уже обрезал до 40 символов и добавил `…` если было длиннее). Если null — строка не рендерится.
- Right badge: `${goingCount}/${participantLimit}`

**Skladchina variant (type='skladchina'):**
- Лейбл-иконка `💰` в углу карточки
- Title (`activity.title`)
- Subtitle:
  - Если есть `totalGoalKopecks`: `${formatRub(collectedKopecks)} / ${formatRub(totalGoalKopecks)}` + progress bar (`width = clamp(0, collected/goal*100, 100)`)
  - Если goal null (voluntary): `${formatRub(collectedKopecks)} собрано`
- Right badge: `${paidCount}/${participantCount}`
- Если `affectsReputation` — мелкий warning-индикатор «⚠️ Репутация» (тот же что на SkladchinaPage)

**Tap:**
- `haptic.impact('light')`
- onClick → navigate:
  - event → `/events/${activity.id}`
  - skladchina → `/skladchina/${activity.id}`

### `ActivityFeedList`

Принимает плоский массив `ActivityItemDto[]`, группирует по дню `createdAt`
(browser-local timezone), рендерит секции с header'ами `Сегодня` / `Вчера` /
`4 мая`. Визуальный аплоэркейс делается через CSS (`text-transform: uppercase`
в `dayLabelStyle`) — `groupActivitiesByDay` возвращает строку в естественном
регистре. Trade-off зафиксирован: если кто-то снимет CSS — день-лейбл станет
mixed-case, что заметно глазами и быстро ловится в QA.

```ts
interface ActivityFeedListProps {
  activities: ActivityItemDto[];
  onActivityClick: (activity: ActivityItemDto) => void;
  loadMore?: () => void;          // если переданы — рендерит sentinel для infinite scroll
  hasMore?: boolean;
  isLoadingMore?: boolean;
}
```

Группировка — utility `groupActivitiesByDay(activities): Array<{ dayLabel: string; items: ActivityItemDto[] }>`
в `frontend/src/utils/activityGrouping.ts`. Внутри дня сортировка сохраняется
(уже отсортировано бэком `createdAt DESC`).

### `ActivitiesManageTab` (organizer)

```ts
interface ActivitiesManageTabProps {
  clubId: string;
}
```

**Composition:**
1. Sticky-top `Button + Создать` → открывает `CreateActivityPicker`
2. `ActivityFilterChips`
3. `ActivityFeedList`
4. Empty state при пустой выдаче (см. § Corner cases #1)

State:
- `filter: ActivityFilter` (default `'all'`)
- `pickerOpen: boolean`

Query:
- `useClubActivitiesQuery(clubId, { type: filter === 'all' ? undefined : filter })`

### `ClubActivitiesTab` (member, read-only)

```ts
interface ClubActivitiesTabProps {
  clubId: string;
}
```

**Composition:**
1. `ActivityFilterChips`
2. `ActivityFeedList`
3. Empty state

Отличия от `ActivitiesManageTab`:
- **Нет** sticky `+ Создать` кнопки
- **Нет** picker'а
- В остальном идентичен

> **Замечание:** если в `ClubPage.tsx` пользователь оказался organizer'ом —
> кнопка «Управление» в `cp-tab-row` ведёт его на `/clubs/:id/manage`, где есть
> creating. На `/clubs/:id` сами orgnizer'ы по-прежнему видят read-only ленту
> (это согласовано с текущим `ClubPage` поведением — `manage` отдельная роль).

### `OrganizerClubManage` — изменения

**TabKey изменение:**
```ts
// было
type TabKey = 'members' | 'applications' | 'events' | 'skladchina' | 'finances' | 'settings';
// становится
type TabKey = 'members' | 'applications' | 'activities' | 'finances' | 'settings';

const TAB_LABELS: Record<TabKey, string> = {
  members: 'Участники',
  applications: 'Заявки',
  activities: 'Активности',
  finances: 'Финансы',
  settings: 'Настройки',
};
```

`renderTab()`:
```tsx
case 'activities':
  return <ActivitiesManageTab clubId={clubId} />;
```

**Удалить (после ревью на side-effects):**
- `SkladchinaManageTab` компонент (~lines 72-130)
- `EventsTab` компонент (~lines 380-663)
- `EventDetailModal` (~lines 313-377) — удалить полностью (user-decision, подтверждено).
  На момент написания спеки используется только внутри `EventsTab`; убирается вместе.
  используется только внутри `EventsTab`. Можно удалить.
- `EventFormState`, `INITIAL_EVENT_FORM`, `UPCOMING_STATUSES`, `EVENT_STATUS_LABELS`
  — связанные константы

**Toast handling:**
В `OrganizerClubManage` добавить чтение `location.state.toast`:
```tsx
const location = useLocation();
const [toast, setToast] = useState<string | null>(location.state?.toast ?? null);
// Clear state на mount чтобы при повторном переходе toast не показался
useEffect(() => {
  if (location.state?.toast) {
    window.history.replaceState({}, document.title);
  }
}, []);
```

**Active tab from query param:**
Поддержать `?tab=activities` (для редиректа из CreateEventPage):
```tsx
const [searchParams] = useSearchParams();
const initialTab = (searchParams.get('tab') as TabKey) || 'members';
const [activeTab, setActiveTab] = useState<TabKey>(initialTab);
```

### `ClubPage` — изменения

```ts
// было
type TabId = 'events' | 'members' | 'profile';
// становится
type TabId = 'activities' | 'members' | 'profile';
```

`tabItems`:
```ts
{ key: 'activities', label: 'Активности', selected: activeTab === 'activities' },
{ key: 'members', label: 'Участники', selected: activeTab === 'members' },
{ key: 'profile', label: 'Мой профиль', selected: activeTab === 'profile' },
```

`activeTab === 'activities' && <ClubActivitiesTab clubId={id} />`

Lock-placeholder для не-членов остаётся (`<strong>События доступны участникам клуба</strong>`).
Текст обновить → «Активности клуба доступны участникам».

---

## Бизнес-логика (server-side)

### `ActivityService.getClubActivities(clubId, type, includeCompleted, page, size, callerUserId)`

1. `@RequiresMembership` aspect уже проверил доступ — `callerUserId` валиден
2. Если `type == "event"` → вызвать `eventRepository.findByClubId(clubId, status = null, page, size)`,
   опционально дофильтровать `isCompleted`-фильтром при `includeCompleted=false`,
   замаппить в `EventActivity`, вернуть как `PageResponse`
3. Если `type == "skladchina"` → вызвать `skladchinaRepository.findAllByClubWithAggregates(clubId, includeCompleted)`,
   замаппить через `ActivityMapper.toSkladchinaActivity`, применить in-memory slice по `page/size`, вернуть
4. Если `type == null` (= оба):
   - **Strategy MVP (in-memory merge):**
     - Получить **все** events клуба (без paging — `findByClubId(clubId, null, 0, MAX)`)
       или хотя бы `(page + 1) * size` items, отсортированных в репозитории по `createdAt DESC`
     - Получить **все** skladchinas клуба через новый метод `findAllByClubWithAggregates(clubId, includeCompleted)` (метод сразу отдаёт `List<SkladchinaWithAggregates>` без paging)
     - Если `includeCompleted=false` — отфильтровать обе коллекции по `!isCompleted`
     - Смержить в один `List<ActivityItemDto>`, отсортировать по `createdAt DESC`
     - Применить in-memory slice `[page*size, (page+1)*size)`
     - `totalElements = events.size + skladchinas.size`, `totalPages = ceil(total / size)`
   - **Risk:** O(N) загрузки всех активностей в память на каждый request. Для
     MVP клубов размером ≤ 200 активностей всего — приемлемо. Если клуб
     перевалит за 1000 активностей — мигрировать на UNION-query (см. § Open
     questions).

### Маппер `ActivityMapper`

```kotlin
@Component
class ActivityMapper {
    fun toEventActivity(e: Event, goingCount: Int): ActivityItemDto.EventActivity =
        ActivityItemDto.EventActivity(
            id = e.id,
            clubId = e.clubId,
            title = e.title,
            createdAt = e.createdAt!!,                  // events.created_at NOT NULL
            isCompleted = e.status in setOf(EventStatus.completed, EventStatus.cancelled),
            eventDatetime = e.eventDatetime,
            locationText = e.locationText,
            participantLimit = e.participantLimit,
            goingCount = goingCount,
            status = e.status.literal                    // jOOQ enum literal
        )

    fun toSkladchinaActivity(item: SkladchinaWithAggregates): ActivityItemDto.SkladchinaActivity {
        val s = item.skladchina
        return ActivityItemDto.SkladchinaActivity(
            id = s.id,
            clubId = s.clubId,
            title = s.title,
            createdAt = s.createdAt,
            isCompleted = s.status in setOf(
                SkladchinaStatus.closed_success,
                SkladchinaStatus.closed_failed,
                SkladchinaStatus.cancelled
            ),
            paymentMode = s.paymentMode.literal,
            totalGoalKopecks = s.totalGoalKopecks,
            collectedKopecks = item.collectedKopecks,
            deadline = s.deadline,
            participantCount = item.participantCount,
            paidCount = item.paidCount,
            status = s.status.literal,
            affectsReputation = s.affectsReputation
        )
    }
}
```

`SkladchinaWithAggregates` объявлен в пакете `com.clubs.skladchina` (рядом с
`SkladchinaRepository`), так как это persistence-уровень DTO с аггрегатами.
Маппер `com.clubs.activity.mapper.ActivityMapper` импортирует его.

### `SkladchinaRepository.findAllByClubWithAggregates`

```kotlin
fun findAllByClubWithAggregates(
    clubId: UUID,
    includeCompleted: Boolean
): List<SkladchinaWithAggregates>

data class SkladchinaWithAggregates(
    val skladchina: Skladchina,
    val collectedKopecks: Long,
    val participantCount: Int,
    val paidCount: Int
)
```

Возвращает полный список (без paging — пагинация выполняется в
`ActivityService` после merge'а с events). Это сознательно отличается от
`findMyFeed`, который пагинирует на стороне БД — здесь нужен полный набор для
корректного merge-and-paginate по `createdAt` через две таблицы.

Реализация — упрощённая версия `findMyFeed` без `userId`-логики:
- `WHERE skladchinas.club_id = :clubId`
- если `!includeCompleted` → `AND status = 'active'`
- `ORDER BY created_at DESC, id ASC`
- batch aggregates (paid, participants, collected) — как в `findMyFeed`
- **Новый data class `SkladchinaWithAggregates`** вместо переиспользования
  `MySkladchinaFeedItem`: тот тип содержит `myStatus` (caller-specific) — лента
  активностей не имеет «caller user-а» в скоупе одного запроса, и тащить туда
  `myStatus = null` загрязняло бы DTO. Чистое разделение caller-aware и
  caller-neutral агрегатов.

---

## Frontend бизнес-логика

### Picker flow (organizer)

1. Tap `+ Создать` → `setPickerOpen(true)` + `haptic.impact('light')`
2. ActionSheet рендерится поверх ленты
3. Tap «🗓 Событие» → `haptic.impact('medium')` → `setPickerOpen(false)` → `navigate('/clubs/${clubId}/events/new')`
4. Tap «💰 Сбор» → `haptic.impact('medium')` → `setPickerOpen(false)` → `navigate('/clubs/${clubId}/skladchina/new')`
5. Tap backdrop / system back → `haptic.impact('light')` → `setPickerOpen(false)`

### Create flow (CreateEventPage)

1. Mount → `useBackButton(true)` показывает back-button
2. User заполняет форму
3. Tap «Создать»:
   - Frontend-валидация (см. AC-12)
   - Mutation вызов
   - На success → `navigate('/clubs/' + clubId + '/manage?tab=activities', { replace: true, state: { toast: 'Событие создано' } })`
4. `OrganizerClubManage` mount → читает `?tab=activities` → активирует таб
5. `OrganizerClubManage` mount → читает `location.state.toast` → показывает toast «Событие создано»
6. `useCreateEventMutation` invalidate'ит `queryKeys.activities.byClub(clubId)` → лента обновляется автоматически

### Filter behavior

1. Default — `filter='all'`
2. Tap chip «События» → `setFilter('event')` → query key меняется → новый запрос
3. Query: `useClubActivitiesQuery(clubId, { type: filter === 'all' ? undefined : filter })`
4. Pagination сбрасывается на page=0 при смене фильтра (стандартное поведение
   `useInfiniteQuery` если queryKey меняется)

### Pagination

`useClubActivitiesQuery(clubId, filters)` — обёртка над `useInfiniteQuery`,
page-based, паттерн идентичен `events-feed.md`:

```ts
useInfiniteQuery({
  queryKey: queryKeys.activities.byClub(clubId, filters),
  queryFn: ({ pageParam }) => getClubActivities(clubId, { ...filters, page: pageParam, size: 20 }),
  initialPageParam: 0,
  getNextPageParam: (last) => last.page + 1 < last.totalPages ? last.page + 1 : undefined,
});
```

Consumer-pattern (`ActivitiesManageTab` / `ClubActivitiesTab`):

```ts
const activitiesQuery = useClubActivitiesQuery(clubId, filters);
const activities = useMemo(
  () => activitiesQuery.data?.pages.flatMap((p) => p.content) ?? [],
  [activitiesQuery.data],
);
// затем
<ActivityFeedList
  activities={activities}
  loadMore={() => activitiesQuery.fetchNextPage()}
  hasMore={activitiesQuery.hasNextPage}
  isLoadingMore={activitiesQuery.isFetchingNextPage}
  onActivityClick={...}
/>
```

`ActivityFeedList` принимает плоский `ActivityItemDto[]` (не `InfiniteData`) —
группировка/sentinel-логика внутри компонента, hook-агностично. Это сознательно:
компонент проще тестируется (`ActivityCard.test.tsx`) и переиспользуется в
любом контексте, где есть готовый список.

---

## Corner cases

### CC-1: Пустой клуб
**Backend:** `GET /api/clubs/{id}/activities?page=0&size=20` → `200` `{ content: [], totalElements: 0, totalPages: 0, page: 0, size: 20 }`
**Frontend:** `ActivityFeedList` рендерит `<Placeholder description="В клубе пока нет активностей. Создайте первую через '+ Создать'." />` (organizer) или «...» (member, без CTA).

### CC-2: Только один тип
**Backend:** events.size=5, skladchinas.size=0 → возвращает 5 events
**Frontend:** chips «Сборы» при тапе → пустой feed + empty state «В клубе пока нет сборов»

### CC-3: Все активности completed (всё dimmed)
**Backend:** 10 завершённых → возвращает все 10 с `isCompleted=true`
**Frontend:** все 10 карточек dimmed (`opacity: 0.55`); empty state НЕ рендерится

### CC-4: Pagination boundary — `page=0, size=5`, total=3
**Backend:** возвращает 3 элемента, `totalElements=3`, `totalPages=1`, `page=0`
**Frontend:** `getNextPageParam` возвращает `undefined` → infinite scroll sentinel ничего не делает

### CC-5: Skladchina без участников
**Backend:** `participantCount=0`, `paidCount=0` → badge `0/0`
**Frontend:** валидно отрендерить `0/0`, не падать. Тап → SkladchinaPage детальный

### CC-6: Event с `goingCount=0`
**Frontend:** badge `0/${limit}` — валидно

### CC-7: Тап по dimmed-карточке
**Frontend:** dim — только визуальный эффект; tap работает, навигация в detail page (event или skladchina) штатная

### CC-8: Member пытается зайти на `/clubs/:id/events/new`
**Frontend (best-effort):** в `CreateEventPage` mount → если не organizer → `navigate('/clubs/' + clubId, { replace: true })` + toast «Только организатор может создавать события».
**Backend (источник правды):** `POST /api/clubs/{id}/events` уже защищён `@RequiresOrganizer` → 403 даже если frontend пропустит.
> Проверка organizer-role на frontend — через `useMyClubsQuery` + `membership.role === 'organizer'` (паттерн уже есть в `ClubPage`).

### CC-9: Не-member пытается дёрнуть `GET /api/clubs/{id}/activities`
**Backend:** `@RequiresMembership` → `403 FORBIDDEN` (общий aspect-код)
**Frontend:** `ApiError`-ловится в query, рендерится `<Placeholder header="Ошибка" description="Нет доступа к этому клубу" />`

### CC-10: Navigation back из CreateEventPage с unsaved формой
**v1:** просто `navigate(-1)` — поля теряются. Подтверждение «Сохранить черновик?» **не делаем** (consistency с `CreateSkladchinaPage`, которая работает так же). Если фидбек попросит — отдельная задача.

### CC-11: Очень длинные title карточек
**Frontend:** CSS truncate `text-overflow: ellipsis; white-space: nowrap; overflow: hidden;` на title (один line). Описание скрыто.

### CC-12: `createdAt` ties (один и тот же момент)
**Backend:** secondary sort key — `id ASC` (UUID-сравнение стабильно и детерминированно).
**SQL:** `ORDER BY created_at DESC, id ASC`.

### CC-13: Клуб с большим количеством активностей (e.g. 500)
**MVP:** in-memory merge подгружает все 500. Risk но приемлемо для MVP-клубов.
**Если возникает:** мигрировать на UNION-SQL — backlog задача.

### CC-14: Skladchina со status `active` но прошла deadline (scheduler не успел)
**Backend:** возвращает с `status='active'`, `isCompleted=false` — пользователь видит как активный
**Frontend:** рендерится не-dimmed. Когда scheduler сработает (см. `SkladchinaScheduler`) — следующий refetch покажет dimmed
**Acceptable:** scheduler работает по cron — небольшая lag допустима

### CC-15: Создание события через CreateEventPage когда параллельно открыт `OrganizerClubManage` в другой вкладке
**TanStack Query invalidation работает в одном tab'е**, в другой вкладке — обновление по `staleTime` (30s) или при refocus
**Acceptable:** не оптимизируем под multi-tab сценарий

---

## Acceptance Criteria

### AC-1: Navigation — 5 табов вместо 6
**GIVEN** organizer открывает `/clubs/{id}/manage` (валидный clubId, есть organizer-роль)
**WHEN** видит TabsList
**THEN** в нём ровно 5 элементов: `Участники · Заявки · Активности · Финансы · Настройки`
**AND** табов `События` и `Сборы` в списке нет

### AC-2: Picker — открытие и опции
**GIVEN** organizer на табе «Активности» в `OrganizerClubManage`
**WHEN** тапает «+ Создать»
**THEN** появляется ActionSheet с двумя опциями: «🗓 Событие», «💰 Сбор»
**AND** light haptic при открытии

### AC-3: Picker → CreateEventPage
**GIVEN** ActionSheet открыт
**WHEN** organizer тапает «🗓 Событие»
**THEN** ActionSheet закрывается
**AND** medium haptic
**AND** происходит navigate на `/clubs/{id}/events/new`
**AND** видна полноэкранная форма с полями `Название`, `Место`, `Дата и время`, `Лимит участников`
**AND** кнопка «Назад» в Telegram navbar активна

### AC-4: Picker → CreateSkladchinaPage
**GIVEN** ActionSheet открыт
**WHEN** organizer тапает «💰 Сбор»
**THEN** ActionSheet закрывается
**AND** medium haptic
**AND** navigate на `/clubs/{id}/skladchina/new` (существующая страница, не ломается)

### AC-5: CreateEventPage submit success
**GIVEN** organizer на `/clubs/{id}/events/new` с валидно заполненной формой
**WHEN** тапает «Создать»
**THEN** POST `/api/clubs/{id}/events` отправляется
**AND** при success — navigate на `/clubs/{id}/manage?tab=activities`
**AND** активный таб = «Активности»
**AND** показывается toast «Событие создано»
**AND** созданное событие появляется в ленте (newest at top)

### AC-6: ActivityFeedList — sort by createdAt DESC
**GIVEN** в клубе 3 события (created 10:00, 11:00, 12:00) и 2 сбора (created 10:30, 11:30)
**WHEN** organizer открывает таб «Активности»
**THEN** карточки идут в порядке: event(12:00), sklад(11:30), event(11:00), sklад(10:30), event(10:00)

### AC-7: Day grouping
**GIVEN** в клубе 5 активностей: 2 созданы сегодня, 2 вчера, 1 неделю назад
**WHEN** organizer открывает таб «Активности»
**THEN** видит секции `СЕГОДНЯ`, `ВЧЕРА`, `<day month>` (например `15 МАЯ`) —
визуальный uppercase через CSS `text-transform: uppercase`; raw-строка из
`groupActivitiesByDay` — `Сегодня`/`Вчера`/`15 мая`
**AND** внутри секции — `createdAt DESC`

### AC-8: Completed activity dimmed inline
**GIVEN** в клубе 2 активные активности и 1 событие со status=`completed`
**WHEN** organizer открывает таб «Активности»
**THEN** completed-карточка показана в ленте на своём хронологическом месте (по `createdAt`)
**AND** её opacity ≈ 0.55
**AND** на ней badge `Завершено`

### AC-9: Tap on dimmed event opens detail
**GIVEN** organizer видит dimmed event-карточку
**WHEN** тапает на неё
**THEN** light haptic
**AND** navigate на `/events/{id}` — открывается обычный EventPage

### AC-10: Filter chip — «События»
**GIVEN** organizer на табе «Активности», есть 3 события и 2 сбора
**WHEN** тапает chip «События»
**THEN** select haptic
**AND** в ленте только 3 события (сборов нет)
**AND** GET `/api/clubs/{id}/activities?type=event&page=0&size=20`

### AC-11: Filter chip — «Сборы»
**GIVEN** organizer на табе «Активности», есть 3 события и 2 сбора
**WHEN** тапает chip «Сборы»
**THEN** в ленте только 2 сбора
**AND** GET `/api/clubs/{id}/activities?type=skladchina&page=0&size=20`

### AC-12: CreateEventPage validation
**GIVEN** organizer на `/clubs/{id}/events/new` с пустым полем `Название`
**WHEN** тапает «Создать»
**THEN** показывается inline-ошибка «Укажите название»
**AND** error haptic
**AND** POST НЕ отправляется

### AC-13: ClubPage member view — таб «Активности»
**GIVEN** member клуба открывает `/clubs/{id}` (он active member, не organizer)
**WHEN** видит таб-row
**THEN** там 3 таба: `Активности`, `Участники`, `Мой профиль`
**AND** дефолтно открыт «Активности»
**AND** видна та же лента (events + skladchinas, merged, sorted by createdAt DESC)
**AND** кнопки «+ Создать» НЕТ

### AC-14: ClubPage organizer view
**GIVEN** organizer клуба открывает `/clubs/{id}`
**WHEN** видит таб-row
**THEN** там 4 таба: `Активности`, `Участники`, `Мой профиль`, `Управление` (последний — navigation на manage)

### AC-15: ClubPage non-member view (visitor)
**GIVEN** visitor (не member, не organizer) открывает `/clubs/{id}` открытого клуба
**WHEN** прокручивает до tabs-секции
**THEN** видит lock-плейсхолдер «Активности клуба доступны участникам»
**AND** табов нет

### AC-16: API — non-member получает 403
**GIVEN** user НЕ member клуба X
**WHEN** делает `GET /api/clubs/X/activities`
**THEN** ответ `403 FORBIDDEN` (общий aspect-код, см. § Errors)

### AC-17: API — невалидный type → 400
**GIVEN** member клуба
**WHEN** делает `GET /api/clubs/{id}/activities?type=foo`
**THEN** ответ `400 VALIDATION_ERROR`

### AC-18: API — `size > 50` → 400
**GIVEN** member клуба
**WHEN** делает `GET /api/clubs/{id}/activities?size=100`
**THEN** ответ `400 VALIDATION_ERROR`

### AC-19: API — `includeCompleted=false` исключает completed
**GIVEN** клуб с 3 active events + 2 completed events + 1 active skladchina + 1 closed_success skladchina
**WHEN** member делает `GET /api/clubs/{id}/activities?includeCompleted=false`
**THEN** в content только 3 active events + 1 active skladchina (4 items)
**AND** все имеют `isCompleted=false`

### AC-20: Empty state — organizer
**GIVEN** organizer открывает таб «Активности» в свежесозданном клубе без активностей
**WHEN** видит content
**THEN** видит «+ Создать» кнопку sticky сверху
**AND** видит chips
**AND** видит placeholder «В клубе пока нет активностей. Создайте первую через '+ Создать'.»

### AC-21: Empty state — member
**GIVEN** member открывает таб «Активности» в клубе без активностей
**WHEN** видит content
**THEN** видит chips
**AND** видит placeholder «В клубе пока нет активностей.»
**AND** **НЕ** видит кнопку «+ Создать»

### AC-22: Deep-link compatibility — `?tab=events` редирект
**GIVEN** пользователь открывает legacy bookmark `/clubs/{id}/manage?tab=events`
**WHEN** OrganizerClubManage mount
**THEN** активный таб = `activities` (не `events` — старый ключ не существует)
**AND** показ работает без ошибок

### AC-23: Pagination
**GIVEN** клуб с 35 активностями, размер страницы 20
**WHEN** organizer скроллит до низа
**THEN** загружается page=1
**AND** добавляются 15 активностей (page 2 of 2)
**AND** дальше скролл не вызывает запросов

### AC-24: Invalidation при создании события
**GIVEN** organizer на `/clubs/{id}/manage?tab=activities` со списком активностей
**WHEN** создаёт новое событие через CreateEventPage и возвращается
**THEN** созданное событие появляется в ленте без manual refresh

---

## curl-команды для AC

```bash
# AC-16: 403 для non-member
curl -i -H "Authorization: Bearer $JWT_NON_MEMBER" \
  "$BASE/api/clubs/$CLUB_ID/activities"
# expect: 403

# AC-17: 400 для невалидного type
curl -i -H "Authorization: Bearer $JWT_MEMBER" \
  "$BASE/api/clubs/$CLUB_ID/activities?type=foo"
# expect: 400

# AC-18: 400 для size > 50
curl -i -H "Authorization: Bearer $JWT_MEMBER" \
  "$BASE/api/clubs/$CLUB_ID/activities?size=100"
# expect: 400

# AC-6: sort and merge
curl -s -H "Authorization: Bearer $JWT_MEMBER" \
  "$BASE/api/clubs/$CLUB_ID/activities?page=0&size=20" \
  | jq '.content[] | { type, title, createdAt }'

# AC-19: includeCompleted=false
curl -s -H "Authorization: Bearer $JWT_MEMBER" \
  "$BASE/api/clubs/$CLUB_ID/activities?includeCompleted=false&page=0&size=50" \
  | jq '.content[] | { type, status, isCompleted }'
# expect: все isCompleted == false
```

---

## Non-functional

### Производительность
- Endpoint < 500ms для клуба с 200 активностями (in-memory merge приемлем)
- Frontend: первый paint ленты < 1 sec на 4G в Telegram WebView
- Pagination не блокирует UI

### Безопасность
- `@RequiresMembership` aspect — единственный источник правды доступа
- В DTO для unified-feed нет sensitive полей (`paymentLink` skladchin'ы НЕ
  включён в `SkladchinaActivity` — раскрывается только в SkladchinaPage где
  есть полная авторизация). Это важно: лента доступна всем members, не только
  participants сбора
- Frontend: не доверять `isCompleted` для бизнес-решений — это UI-флаг,
  актуальные решения — на детальных страницах

### Логирование (backend)
- `ActivityService`: INFO log на вход — `userId`, `clubId`, `type`, `page`, `size`, итоговый `returned`-count
- WARN на rate-limit hit (если будет применён)

### Кеширование (frontend)
- TanStack Query `staleTime` 30s (default проекта)
- Invalidation после `useCreateEventMutation` / `useCreateSkladchinaMutation` —
  `queryKeys.activities.byClub(clubId)` (все variants — `byClub` без `filters`-параметра
  использовать prefix-match)

### Доступность (a11y)
- ActionSheet опции — focusable buttons
- Chips — `role="tablist"` + `aria-selected`
- Карточки — `<button>` (как в `ClubEventsTab`)
- Dimmed карточки — aria-label includes «Завершено»

---

## Frontend implementation plan (резюме)

1. **Backend first** — endpoint + DTO + service + repo extension + integration test
2. **Frontend types & API client** — `types/api.ts` + `api/activities.ts` + `queries/activities.ts` + queryKeys
3. **ActivityCard + ActivityFeedList + ActivityFilterChips** — изолированные UI-компоненты
4. **CreateEventPage** — миррор CreateSkladchinaPage
5. **CreateActivityPicker** — ActionSheet
6. **ActivitiesManageTab + ClubActivitiesTab** — композиции
7. **Wire up OrganizerClubManage + ClubPage** — удалить старое, подключить новое
8. **Router** — добавить `/clubs/:id/events/new`
9. **Invalidation** — расширить `useCreateEventMutation` и `useCreateSkladchinaMutation`
10. **Удалить `ClubEventsTab.tsx`** — после проверки grep, что больше не используется

---

## Backend implementation plan (резюме)

1. **Миграция:** **не нужна** (поля все есть)
2. **`SkladchinaRepository.findAllByClub`** — extension method + jOOQ impl
3. **`ActivityItemDto.kt`** sealed class в `com.clubs.activity.dto`
4. **`ActivityMapper.kt`** — два метода `toEventActivity`, `toSkladchinaActivity`
5. **`ActivityService.kt`** — orchestration: fetch events + skladchinas, map, merge, sort, paginate
6. **`ActivityController.kt`** — `@RequiresMembership(clubIdParam = "id")` + `GetMapping("/api/clubs/{id}/activities")`
7. **Integration test `ActivityControllerTest`:**
   - AC-6 (merge + sort), AC-10/11 (type filter), AC-19 (includeCompleted), AC-16 (403), AC-17/18 (400), AC-1 пустой клуб, AC-12 ties

---

## Migration / backwards compatibility

| Изменение | Compat strategy |
|---|---|
| Tab key `events` → `activities` в OrganizerClubManage | URL `/clubs/:id/manage?tab=events` после изменения вернёт дефолтный `members` (так как ключ не существует). **Решение MVP:** оставить так — это admin UI, bookmark-traffic пренебрежимо мал. Если попросят — поддержать `?tab=events` → `activities` редирект (одна строка в useSearchParams handler). |
| Tab key `events` → `activities` в ClubPage | URL `/clubs/{id}` — таб state локальный, query param не используется. Совместимости поддерживать не надо. |
| Удаление `ClubEventsTab.tsx` | Файл удаляется. Импортов извне нет — проверить grep перед удалением. |
| Endpoint `GET /api/clubs/{id}/events` | **Сохраняется** — backwards-compat для `EventsPage` (это `/me/events`-таб который JOIN'ит по всем клубам) и внешних потребителей если будут |
| Endpoint `GET /api/clubs/{clubId}/skladchinas/active` | **Сохраняется** для совместимости. После того как ActivityFeed устаканится — отдельной задачей можно удалить, если grep покажет 0 потребителей |
| Новый router `/clubs/:id/events/new` | New route, no conflict |
| Существующий router `/clubs/:id/skladchina/new` | Не меняется, продолжает работать |

---

## Decisions and open questions

### D-1: In-memory merge vs SQL UNION
**Decision (MVP):** in-memory merge.
**Why:** проще, явная логика в Kotlin, легче дебажить. Для клубов < 200
активностей приемлемо по производительности.
**When to revisit:** если жалобы на slow endpoint или клуб перевалит за 1000
активностей. Тогда — UNION ALL с server-side LIMIT/OFFSET и единым ORDER BY.
Это полностью внутренний рефакторинг — API не меняется.

### D-2: `votingOpensDaysBefore` в CreateEventPage — убрать из UI
**Decision (final, user 2026-05-23):** поле в UI **не показывать**, backend всегда применяет default 14.
**Why:** проще для пользователя (форма короче), проще для нас (меньше кода и edge-кейсов). Inline-форма в `OrganizerClubManage` этого поля не имела — мы сохраняем UX-консистентность. Кастомизация — за отдельной фичей, если будет реальный запрос.

### D-3: Toast после создания — через router state
**Decision:** `navigate(..., { state: { toast: 'Событие создано' }})`.
**Why:** паттерн уже использован в `OrganizerClubManage.SettingsTab.onDeleted`
(см. `navigate('/my-clubs', { state: { toast: ... }})`). Консистентно.

### D-4: Member на `/clubs/:id` видит ту же ленту что и organizer на manage
**Decision:** да, тот же `ActivityFeedList`, отличие только в наличии «+ Создать».
**Why:** UX согласованность, member видит контекст «что было/будет в клубе».

### D-5: Old ClubPage tab `events` → новый default `activities`
**Decision:** «События» переименовано в «Активности», логика расширена.
**Why:** альтернатива (оставить `События` + добавить отдельный `Сборы`) ровно
та проблема, которую решает эта фича.

### Resolved (user decisions, 2026-05-23)

> **Q-1 → RESOLVED:** Включить `descriptionPreview` (первые 40 символов
> event-description, обрезка + `…` на бэкенде) в `EventActivity`-DTO.
> Рендерить отдельной приглушённой строкой в карточке (см. § ActivityCard).

> **Q-2 → RESOLVED:** `votingOpensDaysBefore` **не показывать в UI вообще**.
> Backend всегда применяет default 14. Решение принято в пользу простоты UX
> формы — кастомизация останется на потом, если будет реальный запрос.

> **Q-3 → RESOLVED:** Удалить `EventDetailModal` полностью вместе с `EventsTab`.
> Подтверждено user'ом.

### Post-flight alignment (2026-05-24)

> **Q-4 → RESOLVED:** `ActionSheet` отсутствует в `@telegram-apps/telegram-ui`
> v2 → picker реализован на `Modal`. Спека и `CreateActivityPicker`
> приведены в соответствие. См. § «Picker — Modal vs ActionSheet».

> **Q-5 → RESOLVED:** Backend DTO `ActivityItemDto` написан без
> `@JsonTypeInfo`/`@JsonSubTypes` — explicit `type` val на каждом subclass.
> Wire-формат прежний. Reviewer заблокировал annotation-вариант как хрупкий
> между версиями Jackson. См. § «DTO Shape (sealed)» комментарий.

> **Q-6 → RESOLVED:** Skladchina-сторона репозитория отдаёт новый data class
> `SkladchinaWithAggregates` (не переиспользует `MySkladchinaFeedItem` чтобы
> не тащить caller-specific `myStatus`). Метод — `findAllByClubWithAggregates`,
> возвращает полный `List<>` (paging — в `ActivityService` после merge'a).
> См. § «`SkladchinaRepository.findAllByClubWithAggregates`».

> **Q-7 → RESOLVED:** Error-code из `@RequiresMembership` — общий `FORBIDDEN`,
> не специфичный `NOT_MEMBER`. Это **существующее** поведение
> `AuthorizationAspect` + `GlobalExceptionHandler.handleForbidden`, не
> вводилось этой фичей. Спека и AC-16 приведены к фактической реальности.
> Backlog-задача на детализацию error-кодов (OWASP A09) —
> `docs/backlog/auth-error-code-granularity.md`.

> **Q-8 → RESOLVED:** Activity-компоненты лежат в `frontend/src/components/manage/`
> (не в отдельном `components/activity/` как изначально). Это согласуется с тем,
> что они впервые потребовались в organizer-flow внутри `OrganizerClubManage`;
> при росте использования в других фичах можно вынести в `components/activity/`
> без изменения wire-API.

---

## Связанное

- `docs/modules/events.md` — backend event domain (CRUD, voting, attendance)
- `docs/modules/skladchina.md` — backend skladchina domain (CRUD, payment, scheduler)
- `docs/modules/club-page-unified.md` — `ClubPage` структура (этот feature меняет 1 таб)
- `docs/modules/events-feed.md` — `/me/events` aggregated feed (родственный паттерн, не источник правды для club-level)
- `docs/modules/haptic.md` — паттерны вибрации
- `.claude/rules/backend.md` — слои Controller/Service/Repository/Mapper
- `.claude/rules/frontend.md` — feature-based структура, маппинг, TanStack Query rules
- `.claude/rules/security.md` — `@RequiresMembership` контракт
- PRD-Clubs.md — product requirements (нет отдельной секции на unified-activities, эта фича — UI-уровневая агрегация существующих доменов)
