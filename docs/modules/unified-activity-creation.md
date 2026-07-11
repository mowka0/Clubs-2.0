# Module: Unified Activity Creation (единая вкладка «Активности»)

> **Status:** ✅ Реализовано в `feature/unified-activity-creation` (post-flight
> docs alignment round 4 — 2026-05-24). Спецификация ниже описывает фактическую реализацию.
>
> ⚠️ **ВАЖНО (итерация 4 — entry point создания переехал):** создание активностей
> **больше НЕ в `OrganizerClubManage`**. Picker «+ Создать» (раньше — sticky-CTA
> внутри manage-таба «Активности») теперь живёт **на глобальной странице
> `/events` (`ActivitiesPage`)** как hero-кнопка «+ Создать», видимая только
> организаторам (≥1 клуб в роли organizer). Flow: тип (Событие/Сбор) → клуб
> (авто-выбор если 1 organizer-клуб, иначе `ClubPickerModal`) → существующие
> create-routes. **Таб «Активности» из `OrganizerClubManage` УДАЛЁН** — manage-панель
> теперь **3 таба (Участники/Финансы/Настройки)** после `feature/applications-inbox`
> (2026-05-30, таб «Заявки» удалён в пользу кросс-клубового inbox на
> `MyClubsPage`, см. [`applications-inbox.md`](./applications-inbox.md)).
> `ActivitiesManageTab.tsx` удалён ещё в `feature/unified-activity-creation`.
> Унифицированная лента активностей сохранилась только в **member-view**
> на `ClubPage` (`ClubActivitiesTab`, read-only). Детали — § «Итерация 4» ниже и
> Changelog 2026-05-24 (round 4). Секции 1-3 описывают предыдущие итерации, где
> picker был в manage — читать с учётом этого override.
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
«+ Создать», см. § Picker — Modal vs ActionSheet) и **единая лента активностей**
где события и сборы рендерятся одним списком, разрезанным на
`Предстоящие` (полные карточки, ближайшее сверху) и сворачиваемые
`Прошедшие (N)` (компактные приглушённые строки). Структурно — это
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
  событий+сборов из одного клуба, **без пагинации**, возвращает
  `ClubActivityFeedDto { upcoming, past }` (партиционирование по `isCompleted`,
  сортировка по `relevantDate`: upcoming ASC / past DESC, ties `id ASC`)
  > **Update 2026-06-12 — action-first пиннинг (per-user):** endpoint теперь
  > учитывает вызывающего юзера (`@AuthenticationPrincipal`). В группе `upcoming`
  > **первыми** идут события, требующие действия пользователя (открытый stage-1
  > голос без голоса ИЛИ stage_2 без подтверждения при голосе going/maybe),
  > затем — прежний порядок по `relevantDate ASC`. Признак считается запросом
  > `EventRepository.findActionRequiredEventIds(clubId, userId, now)` (тот же
  > предикат, что у глобальной `findMyFeed`) и прокидывается в DTO как
  > `EventActivity.actionRequired`. Зеркалит поведение глобальной ленты
  > (`events-feed.md` § «Action-first сортировка»), но в scope одного клуба.
- DTO `ActivityItemDto` — discriminated union (поле `type: 'event' | 'skladchina'`).
  `EventActivity` несёт `actionRequired: Boolean` (см. Update выше) — драйвер бейджа в карточке
- DTO `ClubActivityFeedDto` — обёртка с двумя пред-отсортированными группами
- Новый сервис `ActivityService` в пакете `com.clubs.activity` (новый пакет)
- Расширение `SkladchinaRepository` методом `findAllByClubWithAggregates(clubId, includeCompleted)`
  — текущий `findActiveByClub` отдаёт только active, для unified-feed нужны
  completed/failed тоже (попадают в группу `past`)
- Существующие endpoints (`/api/clubs/{id}/events`, `/api/clubs/{clubId}/skladchinas/active`)
  **сохраняются** — они используются в других контекстах (`/me/events`,
  legacy-вызовы) и не ломаются

**Frontend:**
- Новая страница `CreateEventPage` (`/clubs/:id/events/new`) — миррор
  `CreateSkladchinaPage`, полноэкранная, с back-кнопкой и sticky bottom submit.
  Итерация 4: получила поле загрузки фото (`AvatarUpload` → `photoUrl`)
- Новый picker-компонент `CreateActivityPicker` на базе `Modal` из
  `@telegram-apps/telegram-ui` (2 опции: 🗓 Событие, 💰 Сбор; см. § Picker — Modal vs ActionSheet).
  Итерация 4: рефакторен на единый `onPick(type)` (вместо двух
  `onSelectEvent`/`onSelectSkladchina`)
- ~~Новая вкладка `ActivitiesManageTab` в `OrganizerClubManage`~~ **УДАЛЕНА в
  итерации 4** (создание переехало на глобальную `ActivitiesPage`). Вместо неё
  итерация 4 ввела: `CreateActivityFlow` (контроллер flow тип→клуб→форма),
  `ClubPickerModal` (выбор клуба при ≥2 organizer-клубах), `useOrganizerClubs`
  (`queries/organizerClubs.ts`)
- Новый компонент `ActivityCard` — рендерит и event-карточку, и skladchina-карточку
  (внутри switch по `type`) — полная карточка для `upcoming`; прошедшие
  рендерятся компактным `ActivityCompactRow`
  > **Update 2026-06-12:** event-карточка показывает бейдж `rd-badge rd-warn`
  > при `actionRequired` — текст «Подтверди участие» в `stage_2`, иначе
  > «Проголосуй» (та же логика и тот же класс, что в глобальной `feed/EventCard`).
- Новый api/queries слой `api/activities.ts` + `queries/activities.ts`
- Доработка `ClubPage` / `ClubEventsTab` → новый `ClubActivitiesTab` (read-only,
  без `+ Создать`-кнопки); таб переименовывается «События» → «Активности»
- Router: добавить `/clubs/:id/events/new`

### НЕ входит (v2 / отдельные PR)

- Afisha / опросы / другие новые типы активностей — picker заранее экстенсибилен,
  но добавление типа = отдельная задача
- Inline quick-actions в карточке (RSVP / mark-paid прямо из ленты) — карточка
  по тапу ведёт на детальную страницу
- Пагинация (cursor / page-based) — лента не пагинируется, объём активностей
  одного клуба ограничен (см. D-1). Мигрировать на SQL UNION, когда перестанет
  держать
- Real-time обновления (WebSocket/SSE) — invalidation после mutations
- Drag-to-reorder / pin-to-top активностей
- Группировка по дню (`Сегодня`/`Вчера`/`<день месяц>`) — **отменена в
  итерации 3** в пользу разреза `Предстоящие` / `Прошедшие (N)` (см. § Feed UX
  + Changelog 2026-05-24)

---

## User Stories

### US-1 (Critical): Organizer — единая точка создания

**Как** организатор клуба
**Я хочу** одну кнопку `+ Создать` в табе «Активности», которая открывает picker
с типами (Событие / Сбор)
**Чтобы** не выбирать таб-«Сбор», таб-«Событие» в зависимости от типа активности

### US-2 (Critical): Organizer — единая лента наблюдения

**Как** организатор клуба
**Я хочу** видеть события и сборы одним списком, где предстоящие отсортированы
по дате активности (ближайшее сверху)
**Чтобы** «что скоро» работало одинаково и для событий, и для сборов,
без переключения табов

### US-3 (Important): Organizer — прошедшие свёрнуты, не отдельным табом

**Как** организатор клуба
**Я хочу** видеть завершённые активности под сворачиваемым блоком
`Прошедшие (N)` (компактные приглушённые строки), по тапу открывать детальный
экран как обычно
**Чтобы** не уходить «в архив» ради сводки «что было», но и не загромождать
основную ленту прошедшим

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
| `backend/src/main/kotlin/com/clubs/activity/dto/ClubActivityFeedDto.kt` | NEW (итерация 3) | Обёртка `{ upcoming, past }` — две пред-отсортированные группы (см. § «API контракт») |
| `backend/src/main/kotlin/com/clubs/skladchina/SkladchinaRepository.kt` | MODIFY | Добавить метод `findAllByClubWithAggregates(clubId, includeCompleted): List<SkladchinaWithAggregates>` + объявить новый data class `SkladchinaWithAggregates` (нейтральный к caller'у, без `myStatus`-поля из `MySkladchinaFeedItem`). Репозиторий отдаёт полный список; `ActivityService` партиционирует и сортирует in-memory (пагинации нет). |
| `backend/src/main/kotlin/com/clubs/skladchina/JooqSkladchinaRepository.kt` | MODIFY | Реализация нового метода с batch-aggregates (collected/participants/paid) по тому же паттерну что `findMyFeed`, но без `userId`-логики. |
| `backend/src/main/kotlin/com/clubs/event/EventRepository.kt` | MODIFY (опционально) | Если нужен метод `findAllByClub(clubId, page, size)` без `status`-фильтра. Текущий `findByClubId` принимает nullable `EventStatus?` — можно переиспользовать с `null` |
| `backend/src/main/kotlin/com/clubs/event/EventCompletionService.kt` | NEW (итерация 2) | `@Scheduled` (hourly) автозавершение прошедших событий `upcoming/stage_1/stage_2 → completed` (grace 6ч). Закрывает баг «прошедшие события не приглушаются в ленте». Полная спека — [`events.md`](./events.md) |
| `backend/src/main/kotlin/com/clubs/event/EventRepository.kt` + `JooqEventRepository.kt` | MODIFY (итерация 2) | Новый метод `markPastEventsCompleted(cutoff): Int` — `UPDATE events SET status=completed WHERE event_datetime < cutoff AND status IN (upcoming, stage_1, stage_2)` |
| `backend/src/main/resources/db/migration/V15__add_event_photo_url.sql` | NEW (итерация 4) | `ALTER TABLE events ADD COLUMN IF NOT EXISTS photo_url TEXT` (nullable). Зеркалит `skladchinas.photo_url`. Полная спека — [`events.md`](./events.md) § «Photo события» |
| `backend/.../event/{Event,EventDto,EventMapper,JooqEventRepository}.kt` | MODIFY (итерация 4) | `photoUrl: String?` в domain/`CreateEventRequest` (`@Size(max=1024)`)/`EventDetailDto`/`EventListItemDto`; маппер read/write `EVENTS.PHOTO_URL` |
| `backend/.../activity/dto/ActivityItemDto.kt` + `mapper/ActivityMapper.kt` | MODIFY (итерация 4) | `photoUrl: String?` в `EventActivity` (из `event.photoUrl`) и `SkladchinaActivity` (из `s.photoUrl` — складчина уже имела фото) |

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
| `frontend/src/pages/CreateEventPage.tsx` | NEW | Полноэкранная форма создания события — миррор `CreateSkladchinaPage`. Итерация 4: поле фото (`AvatarUpload` → `photoUrl`); на success navigate на `/events` (глобальная `ActivitiesPage`), не на `manage?tab=activities` |
| `frontend/src/components/manage/CreateActivityPicker.tsx` | NEW | `Modal`-picker с двумя опциями (Событие / Сбор). См. § «Picker — Modal vs ActionSheet». Итерация 4: единый колбэк `onPick(type: ActivityType)` вместо `onSelectEvent`/`onSelectSkladchina` (выбор клуба+навигацию делает `CreateActivityFlow`) |
| `frontend/src/components/manage/ActivitiesManageTab.tsx` | **REMOVED (итерация 4)** | Был organizer-таб создания внутри `OrganizerClubManage`. Удалён — создание переехало на глобальную `ActivitiesPage`. Файл удалён |
| `frontend/src/components/manage/CreateActivityFlow.tsx` | NEW (итерация 4) | Контроллер flow «тип → клуб → форма». Step 1: `CreateActivityPicker` (тип). Step 2: если задан `presetClubId` (FAB в контексте клуба) **или** 1 organizer-клуб — авто-навигация; если ≥2 — `ClubPickerModal`. Затем navigate на `/clubs/:id/events/new` или `/clubs/:id/skladchina/new`. **`presetClubId`** (Banco-редизайн): когда юзер на странице клуба, который организует, FAB пропускает выбор клуба — см. `useClubContextStore` ниже |
| `frontend/src/store/useClubContextStore.ts` | NEW (Banco-редизайн) | Zustand-стор `{ clubId, setClubId }` + хук `useSetClubContext(id)` (set на mount / null на unmount). Клубо-контекстные страницы (`ClubPage`/`OrganizerClubManage`/`EventPage`/`SkladchinaPage`) проставляют `clubId`; `AppDock` (`Layout`) вычисляет `presetClubId` (контекст + юзер организатор этого клуба) и передаёт в `CreateActivityFlow` |
| `frontend/src/components/manage/ClubPickerModal.tsx` | NEW (итерация 4) | `Modal`-список organizer-клубов (avatar/initials + name) для выбора клуба, когда у пользователя их ≥2. Экспортирует тип `ClubPickerOption` |
| `frontend/src/components/manage/ActivityThumb.tsx` | **REMOVED (Banco-редизайн)** | Был квадратный thumbnail слева в `ActivityCard` (фото / brass-плейсхолдер). Banco-редизайн (#49) убрал левый thumbnail → компонент осиротел и удалён при чистке мёртвого кода |
| `frontend/src/queries/organizerClubs.ts` | NEW (итерация 4) | Хук `useOrganizerClubs()` — клубы пользователя в роли organizer (`useMyClubsQuery` filter `role==='organizer'` + per-club `getClub` для name/avatar), возвращает `{ clubs: ClubPickerOption[], isLoading }` |
| `frontend/src/pages/ActivitiesPage.tsx` | MODIFY (итерация 4) | Глобальная страница `/events` `/skladchina`: hero-кнопка «+ Создать» (видна только если `useOrganizerClubs().clubs.length > 0`) → `CreateActivityFlow`. Сами сегменты (`EventsTab`/`SkladchinasTab` из `components/activities/`) — из events-feed модуля, не из unified-feed |
| `frontend/src/components/club/ClubActivitiesTab.tsx` | NEW | Read-only таб для member view (`ClubPage`) — заменяет `ClubEventsTab`. **Единственный** consumer unified-feed (`ActivityFeedList`/`ActivityCard`) после итерации 4 |
| `frontend/src/components/manage/ActivityCard.tsx` | NEW (фото — итерация 4) | Унифицированная полная карточка (внутри switch по `type`) — рендерит только `upcoming`. Итерация 4: фото-thumbnail слева (`ActivityThumb`, **убран в Banco-редизайне**), type-иконка (🗓/💰) — badge в правом верхнем углу; `photoUrl` берётся из DTO |
| `frontend/src/components/manage/ActivityCompactRow.tsx` | NEW (итерация 3) | Компактная приглушённая строка (иконка · title · дата) — рендерит элементы `past` под аккордеоном |
| `frontend/src/components/manage/ActivityFilterChips.tsx` | NEW | Chips `Все · События · Сборы` |
| `frontend/src/components/manage/ActivityFeedList.tsx` | NEW (переписан в итерации 3) | Секция `Предстоящие` (полные `ActivityCard`) + сворачиваемый блок `Прошедшие (N)` (компактные `ActivityCompactRow`). Принимает `ClubActivityFeed { upcoming, past }`. Группировки по дню и infinite-scroll sentinel больше нет |
| `frontend/src/utils/activityGrouping.ts` | REMOVED (итерация 3) | Группировка по дню отменена — файл и его тест удалены |
| `frontend/src/api/activities.ts` | NEW | `getClubActivities(clubId, params)` → `ClubActivityFeed { upcoming, past }`; params = только `{ type? }` |
| `frontend/src/queries/activities.ts` | NEW | `useClubActivitiesQuery` — обычный `useQuery` (не InfiniteQuery; лента не пагинируется) |
| `frontend/src/queries/queryKeys.ts` | MODIFY | Добавить `activities.byClub(clubId, filters)` |
| `frontend/src/types/api.ts` | MODIFY | Добавить TS-типы `ActivityItemDto`, `ActivityType`, `EventActivityDto`, `SkladchinaActivityDto` |
| `frontend/src/pages/OrganizerClubManage.tsx` | MODIFY | Удалить `EventsTab` + `SkladchinaManageTab` + `EventDetailModal` (dead code). **Итерация 4:** `TabKey` = `members \| applications \| finances \| settings` (БЕЗ `activities` — таб создания убран); legacy deep-links `?tab=activities\|events\|skladchina` (`LEGACY_TAB_KEYS`) → fallback `members`. `ActivitiesManageTab` НЕ подключается (удалён) |
| `frontend/src/pages/ClubPage.tsx` | MODIFY | `TabId` сменить `events` → `activities`; обновить лейбл «События» → «Активности»; импортировать `ClubActivitiesTab` вместо `ClubEventsTab` |
| `frontend/src/components/club/ClubEventsTab.tsx` | REMOVE | После миграции — удалить файл |
| `frontend/src/router.tsx` | MODIFY | Добавить route `/clubs/:id/events/new` → lazy `CreateEventPage` |
| `frontend/src/queries/events.ts` | MODIFY | Расширить invalidation в `useCreateEventMutation.onSuccess`: invalidate `activities.byClub(clubId)` |
| `frontend/src/queries/skladchina.ts` | MODIFY | Аналогично — invalidate `activities.byClub(clubId)` в `useCreateSkladchinaMutation.onSuccess` |
| `frontend/src/components/BrandStepper.tsx` | NEW (итерация 2), MODIFY (итерация 3) | Brand-степпер `[− N +]` для числового ввода. Используется в `CreateEventPage` для `participantLimit` вместо `<input type="number">`. Итерация 3: центральное значение — редактируемый numeric input (ручной ввод цифр, clamp к `[min, max]` на blur), не только +/− кнопки |
| `frontend/src/telegram/sdk.ts` | MODIFY (итерация 3) | SDK init дополнен `viewport.expand()` + `swipeBehavior.disableVertical()` — приложение больше не сворачивается при скролле контента (см. § Telegram viewport / swipe) |
| `frontend/src/components/manage/ManageHeader.tsx` | NEW (итерация 2) | Brand-hero карточка шапки `OrganizerClubManage` — заменяет плоский `Cell`-header |
| `frontend/src/components/manage/ManageTabs.tsx` | NEW (итерация 2) | Brass pill-tabs для `OrganizerClubManage` — заменяет telegram-ui `TabsList` (фиксит обрезание лейблов «Учас…») |
| `frontend/src/pages/CreateEventPage.tsx` | MODIFY (итерация 2) | `participantLimit` → `BrandStepper`; `datetime-local` обёрнут в `.brand-datetime` (стилизация + brass calendar icon) |
| `frontend/src/pages/CreateSkladchinaPage.tsx` | MODIFY (итерация 2) | `datetime-local` (`deadline`) обёрнут в `.brand-datetime` (стилизация + brass calendar icon). Степпер не применяется — у складчины нет поля participant-limit |
| `frontend/src/styles/brand-theme.css` | MODIFY (итерация 2) | Добавлены стили `.brand-stepper`, `.brand-datetime` + классы `ManageHeader` / `ManageTabs` |

### Routing reference

| Path | Page | Auth |
|---|---|---|
| `/events` / `/skladchina` | `ActivitiesPage` (hero «+ Создать» → `CreateActivityFlow`, видна organizer'ам) | любой авторизованный |
| `/clubs/:id` → tab `activities` | `ClubPage` → `ClubActivitiesTab` (read-only) | member или organizer |
| `/clubs/:id/manage` | `OrganizerClubManage` (4 таба, БЕЗ создания активностей с итерации 4) | organizer |
| `/clubs/:id/events/new` | `CreateEventPage` | organizer |
| `/clubs/:id/skladchina/new` | `CreateSkladchinaPage` (существует) | organizer |

---

## API контракт

### `GET /api/clubs/{id}/activities`

Унифицированная лента активностей одного клуба. Возвращает события + складчины
смерженные и **разрезанные на две пред-отсортированные группы** —
`upcoming` (ещё не завершённые) и `past` (завершённые). Без пагинации.

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

> **REMOVED (2026-05-24, итерация 3):** `includeCompleted`, `page`, `size` —
> убраны из контракта. Лента больше не пагинируется (объём активностей одного
> клуба ограничен — см. § Decisions D-1) и не фильтруется по completion-флагу:
> завершённые активности всегда отдаются в группе `past`, фронт сам решает
> сворачивать ли их. Бэкенд-разработчик явно зафиксировал удаление
> `includeCompleted`.

**Партиционирование (backend):**
- `past` = `isCompleted == true` (event.status ∈ `completed`/`cancelled`;
  skladchina.status ∈ `closed_success`/`closed_failed`/`cancelled`)
- `upcoming` = всё остальное (`isCompleted == false`)

**Сортировка по `relevantDate`** (own-date активности, не `createdAt`):
- `relevantDate` = `eventDatetime` для событий, `deadline` для складчин
- `upcoming` — `relevantDate ASC` (ближайшее сверху)
- `past` — `relevantDate DESC` (недавнее сверху)
- ties по `relevantDate` в обеих группах разрешаются `id ASC` (детерминизм)

> **`createdAt` всё ещё в DTO** (сериализуется), но **больше не используется**
> ни для сортировки, ни в UI. Оставлен как поле на проводе для обратной
> совместимости consumer'ов; при желании можно убрать отдельной задачей.

**Response 200:** `ClubActivityFeedDto { upcoming: ActivityItemDto[], past: ActivityItemDto[] }`

```json
{
  "upcoming": [
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
    },
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
      "confirmedCount": 0,
      "status": "upcoming",
      "descriptionPreview": "Утренняя практика на свежем воздухе…"
    }
  ],
  "past": []
}
```

> `confirmedCount` (stage-2 подтверждённый состав) добавлен в `EventActivity` (F5-21, 2026-06-25):
> карточка `ActivityCard` показывает `goingCount`/«идёт» при `upcoming` и `confirmedCount`/«подтв.»
> при `stage_2`/`completed` — фазовый показ как на `EventPage` (events.md § «Фазовый показ»).

> В примере `upcoming` отсортирован по `relevantDate ASC`: складчина с deadline
> `28 мая` идёт раньше события с `eventDatetime 30 мая`, хотя складчина была
> создана раньше — сортировка идёт по own-date, не по `createdAt`.

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
        val descriptionPreview: String?,     // first 40 chars of description, trimmed; null if no description
        val photoUrl: String?                // event cover (V15), null if none — итерация 4
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
        val affectsReputation: Boolean,
        val photoUrl: String?                // skladchina cover (existing), null if none — итерация 4
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
  photoUrl: string | null;            // event cover (V15) — итерация 4
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
  photoUrl: string | null;            // skladchina cover (existing) — итерация 4
}

type ActivityItemDto = EventActivityDto | SkladchinaActivityDto;
```

### `isCompleted` computation (backend)

- **Event:** `status IN ('completed', 'cancelled')`
- **Skladchina:** `status IN ('closed_success', 'closed_failed', 'cancelled')`

Поле вычисляется маппером — UI не должен дублировать enum-логику. `isCompleted`
определяет, в какую группу попадёт активность: `true` → `past`, `false` →
`upcoming` (см. § «API контракт» партиционирование).

> **Завершение событий реально работает (2026-05-24).** До итерации 2 события
> никогда не переходили в `completed` (Stage2Service двигал только `upcoming → stage_2`),
> поэтому прошедшие события всегда были `isCompleted=false` и не уходили в `past`.
> Это исправлено: `EventCompletionService` (hourly `@Scheduled`, grace 6ч) теперь
> переводит прошедшие `upcoming/stage_1/stage_2` в `completed` → они попадают в
> группу `past`. Складчины завершались и раньше — у них был `SkladchinaScheduler`.
> Полная спека автозавершения — в
> [`events.md`](./events.md) § «Автозавершение прошедших событий».

### Errors

| HTTP | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `type` не из `event`/`skladchina` |
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
- Место (optional) — **с фичи event-geo (2026-07-11) не текстовый input**: кнопка
  «Добавить место» → шит-пикер `LocationPickerSheet` (Яндекс.Карты, поиск адреса +
  уточнение пином; у выбранного места кнопки «Изменить»/«Убрать»). Даёт
  `locationLat`/`locationLon` + `locationText` (адрес из обратного геокодера,
  подрезается до 500). Ниже — поле «Уточнение к месту» → `locationHint` (≤ 200).
  **Правило (V58): точка ИЛИ непустое уточнение обязательны** — сабмит без обоих
  блокируется. Спека: [`event-geo.md`](./event-geo.md)
- `eventDatetime` (datetime-local, required, future) — `<input type="datetime-local">` обёрнут в `.brand-datetime` (стилизованный wrapper + brass calendar icon; нативный picker сохранён)
- `participantLimit` (int, required, > 0) — `BrandStepper` (`[− N +]`), не `<input type="number">` (итерация 2)
- `votingOpensDaysBefore` — **НЕ показывается в UI вообще**. Backend всегда применяет default 14 (определён в `CreateEventRequest`). Решение: упрощение формы для organizer'а; кастомизация — за отдельной фичей, если когда-нибудь будет реальный запрос.

**Submit:**
1. Frontend-валидация (те же rules что в inline-form)
2. `haptic.impact('medium')`
3. `useCreateEventMutation.mutateAsync({ clubId, body })` (`body.photoUrl` опц.)
4. Success:
   - `haptic.notify('success')`
   - **(итерация 4)** `navigate('/events', { replace: true, state: { toast: 'Событие создано' } })`
     — на глобальную `ActivitiesPage` (раньше — на `manage?tab=activities`,
     которого больше нет)
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
- `ActivityCard` рендерит **полную** карточку (используется в секции
  `Предстоящие`). Прошедшие активности рендерятся не этой карточкой, а
  компактным `ActivityCompactRow` (см. ниже).
- Карточка сохраняет defensive-обработку `isCompleted` (класс `completed` +
  badge `Завершено`) на случай completed-элемента в `upcoming`, но в штатном
  потоке `upcoming` содержит только незавершённые.

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

Принимает `ClubActivityFeed { upcoming, past }` (уже пред-отсортированный
бэкендом) и рендерит две секции без клиентской пересортировки:

1. **`Предстоящие`** — секция с лейблом, полные `ActivityCard` для каждого
   `upcoming`-элемента (ближайшее сверху). Рендерится только если
   `upcoming.length > 0`.
2. **`Прошедшие (N)`** — сворачиваемый аккордеон (кнопка-toggle с chevron
   `▸`/`▾` + счётчик `(N)`). По умолчанию **свёрнут**. Раскрытие → список
   компактных `ActivityCompactRow` (приглушённые). Тап на toggle →
   `haptic.impact('light')`. Рендерится только если `past.length > 0`.

```ts
interface ActivityFeedListProps {
  feed: ClubActivityFeed;
  onActivityClick: (activity: ActivityItemDto) => void;
}
```

Состояние раскрытия аккордеона — локальный `useState<boolean>` (default
`false`). Группировки по дню и infinite-scroll sentinel нет — лента не
пагинируется, разрез делает бэкенд по `isCompleted`. `utils/activityGrouping.ts`
удалён вместе со своим тестом.

### `ActivityCompactRow` (итерация 3)

Компактная одно-строчная карточка для прошедших активностей под аккордеоном.

```ts
interface ActivityCompactRowProps {
  activity: ActivityItemDto;
  onClick: () => void;
}
```

- Иконка (`🗓` event / `💰` skladchina) · `title` (truncate) · короткая дата
  (`day month`, ru-RU) — для event это `eventDatetime`, для skladchina `deadline`
- `aria-label` включает «Завершено»
- Тап → тот же `onActivityClick` → detail-страница

### ~~`ActivitiesManageTab` (organizer)~~ — УДАЛЁН в итерации 4

> ⚠️ **Компонент удалён** (`ActivitiesManageTab.tsx` больше нет). Создание
> переехало в `CreateActivityFlow` на глобальной `ActivitiesPage`; лента осталась
> только в member-view `ClubActivitiesTab`. Описание ниже — исторический контекст
> итераций 1-3.

```ts
interface ActivitiesManageTabProps {
  clubId: string;
}
```

**Composition (исторически):**
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

> ⚠️ **Итерация 4 override:** таб `activities` из manage **удалён**. Актуальный
> `TabKey = 'members' | 'applications' | 'finances' | 'settings'` (БЕЗ
> `activities`); `renderTab` не имеет `case 'activities'`; toast-handling и
> `?tab=` query-param обработка переехали на `ActivitiesPage` (создание теперь
> там). Снимки ниже — исторический план итерации 1.

**TabKey изменение (исторически, итерация 1):**
```ts
// было (6 табов)
type TabKey = 'members' | 'applications' | 'events' | 'skladchina' | 'finances' | 'settings';
// итерация 1 (5 табов)
type TabKey = 'members' | 'applications' | 'activities' | 'finances' | 'settings';
// итерация 4 (актуально, 4 таба — activities убран):
// type TabKey = 'members' | 'applications' | 'finances' | 'settings';

const TAB_LABELS: Record<TabKey, string> = {
  members: 'Участники',
  applications: 'Заявки',
  activities: 'Активности', // удалён в итерации 4
  finances: 'Финансы',
  settings: 'Настройки',
};
```

`renderTab()` (итерация 1, удалён в итерации 4):
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

**Active tab from query param (итерация 1, заменено в итерации 4):**
Исторически поддерживался `?tab=activities` для редиректа из CreateEventPage.
**Итерация 4:** create-редирект ведёт на `/events` (`ActivitiesPage`), а
`OrganizerClubManage` теперь резолвит legacy-ключи `activities|events|skladchina`
в `members` (`LEGACY_TAB_KEYS`, см. `resolveInitialTab`). Toast/`?tab=`-логика
создания переехала на `ActivitiesPage`.

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

### `ActivityService.getClubActivities(clubId, typeFilter)`

`@RequiresMembership` aspect уже проверил доступ на уровне контроллера.
Возвращает `ClubActivityFeedDto { upcoming, past }`.

1. Загрузить events: если `typeFilter == SKLADCHINA` → пусто, иначе
   `eventRepository.findAllByClubWithGoingCount(clubId)` → маппинг в
   `EventActivity`
2. Загрузить skladchinas: если `typeFilter == EVENT` → пусто, иначе
   `skladchinaRepository.findAllByClubWithAggregates(clubId, includeCompleted = true)`
   → маппинг в `SkladchinaActivity` (completed/closed тоже грузятся — они идут
   в `past`)
3. Смержить в один `List<ActivityItemDto>` (`events + skladchinas`)
4. **Партиционировать** по `isCompleted`: `(past, upcoming) = all.partition { it.isCompleted }`
5. Отсортировать:
   - `upcoming` → `relevantDate ASC, id ASC` (`UPCOMING_ORDER`)
   - `past` → `relevantDate DESC, id ASC` (`PAST_ORDER`)
   - `relevantDate(item)` = `eventDatetime` для event, `deadline` для skladchina
     (exhaustive `when` над sealed-подтипом)
6. Вернуть `ClubActivityFeedDto(upcoming = sortedUpcoming, past = sortedPast)`

**Без пагинации** (D-1): объём активностей одного клуба ограничен, in-memory
merge приемлем. Мигрировать на SQL UNION, когда перестанет держать. `@Transactional(readOnly = true)`.

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

Возвращает полный список (без paging). Это сознательно отличается от
`findMyFeed`, который пагинирует на стороне БД — здесь нужен полный набор для
корректного merge через две таблицы. Финальный порядок групп задаётся не
репозиторием, а `ActivityService` (пересортировка по `relevantDate` после
партиционирования); repo-level `ORDER BY` несущественен для контракта.

Параметр `includeCompleted` остаётся в сигнатуре репозитория, но
`ActivityService` всегда передаёт `true` — завершённые складчины нужны для
группы `past` (фильтрации по completion на уровне endpoint больше нет).

Реализация — упрощённая версия `findMyFeed` без `userId`-логики:
- `WHERE skladchinas.club_id = :clubId`
- если `!includeCompleted` → `AND status = 'active'` (caller `ActivityService`
  не использует эту ветку)
- `ORDER BY created_at DESC, id ASC` (пересортируется в сервисе)
- batch aggregates (paid, participants, collected) — как в `findMyFeed`
- **Новый data class `SkladchinaWithAggregates`** вместо переиспользования
  `MySkladchinaFeedItem`: тот тип содержит `myStatus` (caller-specific) — лента
  активностей не имеет «caller user-а» в скоупе одного запроса, и тащить туда
  `myStatus = null` загрязняло бы DTO. Чистое разделение caller-aware и
  caller-neutral агрегатов.

---

## Frontend бизнес-логика

### Picker flow (organizer) — итерация 4: на глобальной `ActivitiesPage`

> ⚠️ Заменяет прежний manage-флоу (picker был sticky-CTA внутри
> `ActivitiesManageTab`). Теперь — `CreateActivityFlow` на `ActivitiesPage`.

1. Tap hero `+ Создать` (виден если `useOrganizerClubs().clubs.length > 0`) →
   `setCreateOpen(true)` + `haptic.impact('light')`
2. `CreateActivityPicker` (`Modal`) рендерится поверх страницы
3. Tap «🗓 Событие» → `haptic.impact('medium')` → picker закрывается →
   `onPick('event')`:
   - 1 organizer-клуб → `navigate('/clubs/${theOnlyClub}/events/new')`
   - ≥2 → открыть `ClubPickerModal`; tap клуба → `navigate('/clubs/${clubId}/events/new')`
4. Tap «💰 Сбор» → аналогично → `navigate('/clubs/${clubId}/skladchina/new')`
5. Tap backdrop / system back на любом шаге → `haptic.impact('light')` → flow reset

### Create flow (CreateEventPage) — итерация 4

1. Mount → `useBackButton(true)` показывает back-button
2. User заполняет форму (включая опц. фото через `AvatarUpload`)
3. Tap «Создать»:
   - Frontend-валидация (см. AC-12)
   - Mutation вызов (`body.photoUrl` опц.)
   - На success → `navigate('/events', { replace: true, state: { toast: 'Событие создано' } })`
4. `ActivitiesPage` mount → читает `location.state.toast` → показывает toast «Событие создано»
5. `useCreateEventMutation` invalidate'ит `queryKeys.activities.byClub(clubId)` →
   лента клуба (`ClubActivitiesTab`) обновляется при следующем открытии

### Filter behavior

1. Default — `filter='all'`
2. Tap chip «События» → `setFilter('event')` → query key меняется → новый запрос
3. Query: `useClubActivitiesQuery(clubId, { type: filter === 'all' ? undefined : filter })`
4. При смене фильтра меняется queryKey → перезапрос (новый `ClubActivityFeed`)

### Data fetching (без пагинации)

`useClubActivitiesQuery(clubId, params)` — обычный `useQuery` (не InfiniteQuery;
лента не пагинируется, бэкенд отдаёт `{ upcoming, past }` целиком):

```ts
useQuery<ClubActivityFeed>({
  queryKey: queryKeys.activities.byClub(clubId, params),
  queryFn: () => getClubActivities(clubId, params),   // params = { type? }
  enabled: Boolean(clubId),
});
```

Consumer-pattern (`ActivitiesManageTab` / `ClubActivitiesTab`):

```ts
const activitiesQuery = useClubActivitiesQuery(clubId, params);
// затем, при наличии данных:
<ActivityFeedList
  feed={activitiesQuery.data}
  onActivityClick={...}
/>
```

`ActivityFeedList` принимает `ClubActivityFeed { upcoming, past }` напрямую —
бэкенд уже отсортировал обе группы, клиент рендерит в полученном порядке без
пересортировки. Аккордеон-состояние `Прошедшие` живёт локально в компоненте.

---

## Corner cases

### CC-1: Пустой клуб
**Backend:** `GET /api/clubs/{id}/activities` → `200` `{ upcoming: [], past: [] }`
**Frontend:** `ActivityFeedList` не рендерит ни одной секции; родитель показывает `<Placeholder description="В клубе пока нет активностей. Создайте первую через '+ Создать'." />` (organizer) или «...» (member, без CTA).

### CC-2: Только один тип
**Backend:** events=5, skladchinas=0 → `upcoming`/`past` содержат только events
**Frontend:** chips «Сборы» при тапе → пустой feed + empty state «В клубе пока нет сборов»

### CC-3: Все активности завершены
**Backend:** 10 завершённых → `{ upcoming: [], past: [10 items] }`, все `isCompleted=true`
**Frontend:** секция `Предстоящие` не рендерится; виден свёрнутый аккордеон `Прошедшие (10)`; empty state НЕ рендерится (`past` не пуст)

### CC-4: Только предстоящие, прошедших нет
**Backend:** 3 upcoming, 0 past → `{ upcoming: [3], past: [] }`
**Frontend:** секция `Предстоящие` с 3 карточками; блок `Прошедшие` не рендерится (`past.length === 0`)

### CC-5: Skladchina без участников
**Backend:** `participantCount=0`, `paidCount=0` → badge `0/0`
**Frontend:** валидно отрендерить `0/0`, не падать. Тап → SkladchinaPage детальный

### CC-6: Event с `goingCount=0`
**Frontend:** badge `0/${limit}` — валидно

### CC-7: Тап по прошедшей (compact-строке)
**Frontend:** компактная строка в аккордеоне `Прошедшие` кликабельна; tap → навигация в detail page (event или skladchina) штатная, как у полной карточки

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

### CC-12: `relevantDate` ties (одна и та же дата)
**Backend:** secondary sort key — `id ASC` (UUID-сравнение стабильно и детерминированно), в обеих группах (`UPCOMING_ORDER`, `PAST_ORDER`).

### CC-13: Клуб с большим количеством активностей (e.g. 500)
**MVP:** in-memory merge подгружает все 500 в одну выдачу (без пагинации). Risk но приемлемо для MVP-клубов.
**Если возникает:** мигрировать на UNION-SQL — backlog задача (D-1).

### CC-14: Skladchina со status `active` но прошла deadline (scheduler не успел)
**Backend:** возвращает с `status='active'`, `isCompleted=false` → попадает в группу `upcoming`
**Frontend:** видна полной карточкой в `Предстоящие`. Когда scheduler сработает (см. `SkladchinaScheduler`) — следующий refetch переместит в `Прошедшие`
**Acceptable:** scheduler работает по cron — небольшая lag допустима

### CC-15: Создание события через CreateEventPage когда параллельно открыт `OrganizerClubManage` в другой вкладке
**TanStack Query invalidation работает в одном tab'е**, в другой вкладке — обновление по `staleTime` (30s) или при refocus
**Acceptable:** не оптимизируем под multi-tab сценарий

### CC-16: Event с прошедшей датой, но в пределах grace-периода (< 6ч назад)
**Backend:** `EventCompletionService` ещё не перевёл его в `completed` (grace 6ч / hourly cron) → возвращается с прежним статусом `upcoming/stage_1/stage_2`, `isCompleted=false` → попадает в `upcoming`
**Frontend:** видно полной карточкой в `Предстоящие`. После следующего прогона scheduler'а (за пределами grace) refetch переместит в `Прошедшие`
**Acceptable:** симметрично CC-14 (skladchina lag) — небольшая задержка перемещения допустима. Полная логика — [`events.md`](./events.md) § «Автозавершение прошедших событий»

---

## Acceptance Criteria

### AC-1: Navigation — 4 таба в manage (итерация 4: «Активности» убран)
**GIVEN** organizer открывает `/clubs/{id}/manage` (валидный clubId, есть organizer-роль)
**WHEN** видит TabsList
**THEN** в нём ровно 4 элемента: `Участники · Заявки · Финансы · Настройки`
**AND** табов `События`, `Сборы` **и `Активности`** в списке нет
> До итерации 4 здесь был 5-й таб «Активности» с лентой+созданием. Создание
> переехало на глобальную `ActivitiesPage`, лента активностей осталась только в
> member-view `ClubPage`.

### AC-2: Picker — открытие и опции (итерация 4: на глобальной `ActivitiesPage`)
**GIVEN** organizer (≥1 клуб) на странице `/events` (`ActivitiesPage`)
**WHEN** тапает hero-кнопку «+ Создать»
**THEN** появляется `Modal`-picker с двумя опциями: «🗓 Событие», «💰 Сбор»
**AND** light haptic
> Не-organizer (0 клубов в роли organizer) кнопки «+ Создать» **не видит**. при открытии

### AC-3: Picker → выбор типа «Событие» (итерация 4: тип → клуб → форма)
**GIVEN** picker открыт на `ActivitiesPage`
**WHEN** organizer тапает «🗓 Событие»
**THEN** picker закрывается, medium haptic
**AND** если у organizer **ровно один** клуб → navigate на `/clubs/{theOnlyClub}/events/new`
**AND** если **≥2** клуба → открывается `ClubPickerModal`; после выбора клуба →
navigate на `/clubs/{chosen}/events/new`
**AND** видна полноэкранная форма с полями `Название`, `Место`, `Дата и время`,
`Лимит участников`, `Фото` (опц.)
**AND** кнопка «Назад» в Telegram navbar активна

### AC-4: Picker → выбор типа «Сбор» (итерация 4)
**GIVEN** picker открыт на `ActivitiesPage`
**WHEN** organizer тапает «💰 Сбор»
**THEN** picker закрывается, medium haptic
**AND** тот же club-resolve (авто / `ClubPickerModal`) →
navigate на `/clubs/{id}/skladchina/new` (существующая страница, не ломается)

### AC-5: CreateEventPage submit success (итерация 4: navigate на `/events`)
**GIVEN** organizer на `/clubs/{id}/events/new` с валидно заполненной формой
**WHEN** тапает «Создать»
**THEN** POST `/api/clubs/{id}/events` отправляется (с `photoUrl`, если фото задано)
**AND** при success — navigate на `/events` (глобальная `ActivitiesPage`)
**AND** показывается toast «Событие создано»
**AND** созданное событие появляется в ленте клуба (member-view `ClubActivitiesTab`)
после invalidation

### AC-6: Предстоящие — sort by relevantDate ASC, типы interleaved
**GIVEN** в клубе предстоящие: событие (eventDatetime +3d) и сбор (deadline +1d)
**WHEN** organizer открывает таб «Активности»
**THEN** в секции `Предстоящие` карточки идут по `relevantDate ASC`:
сбор(+1d) выше, событие(+3d) ниже — независимо от `createdAt`
**AND** ties по `relevantDate` разрешаются `id ASC`

### AC-7: Прошедшие — сворачиваемый аккордеон, sort DESC
**GIVEN** в клубе 2 завершённых активности (relevantDate −2d и −5d)
**WHEN** organizer открывает таб «Активности»
**THEN** виден блок-toggle `Прошедшие (2)`, по умолчанию **свёрнут** (chevron `▸`)
**WHEN** тапает на toggle
**THEN** light haptic
**AND** chevron → `▾`, раскрывается список компактных строк по `relevantDate DESC`:
−2d выше, −5d ниже

### AC-8: Завершённая активность — в Прошедшие, не в основной ленте
**GIVEN** в клубе 2 предстоящие активности и 1 событие со status=`completed`
**WHEN** organizer открывает таб «Активности»
**THEN** completed-событие **не** показано в секции `Предстоящие`
**AND** счётчик блока `Прошедшие` = `(1)`
**AND** после раскрытия — completed показано компактной приглушённой строкой

### AC-9: Tap on past row opens detail
**GIVEN** organizer раскрыл `Прошедшие` и видит компактную строку прошедшего события
**WHEN** тапает на неё
**THEN** navigate на `/events/{id}` — открывается обычный EventPage

### AC-10: Filter chip — «События»
**GIVEN** organizer на табе «Активности», есть 3 события и 2 сбора
**WHEN** тапает chip «События»
**THEN** select haptic
**AND** в `upcoming`+`past` только события (сборов нет)
**AND** GET `/api/clubs/{id}/activities?type=event`

### AC-11: Filter chip — «Сборы»
**GIVEN** organizer на табе «Активности», есть 3 события и 2 сбора
**WHEN** тапает chip «Сборы»
**THEN** в `upcoming`+`past` только сборы
**AND** GET `/api/clubs/{id}/activities?type=skladchina`

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
**AND** видна та же лента (events + skladchinas, `Предстоящие` по `relevantDate ASC` + сворачиваемый `Прошедшие`)
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

### AC-18: API — `page`/`size`/`includeCompleted` игнорируются (REMOVED)
**GIVEN** member клуба
**WHEN** делает `GET /api/clubs/{id}/activities?size=100&page=2&includeCompleted=false`
**THEN** ответ `200` с полным `{ upcoming, past }` — неизвестные query-параметры
игнорируются Spring'ом, контракт их не объявляет (см. § «API контракт» REMOVED)
**AND** завершённые активности **присутствуют** в `past` (фильтрации по
completion больше нет)

### AC-19: API — завершённые всегда в `past`, незавершённые в `upcoming`
**GIVEN** клуб с 3 предстоящими events + 2 completed events + 1 active skladchina + 1 closed_success skladchina
**WHEN** member делает `GET /api/clubs/{id}/activities`
**THEN** `upcoming` содержит 3 events + 1 skladchina (все `isCompleted=false`)
**AND** `past` содержит 2 events + 1 skladchina (все `isCompleted=true`)

### AC-20: Empty state — organizer (итерация 4: лента в member-view, создание глобально)
**GIVEN** organizer открывает таб «Активности» в `ClubPage` (`/clubs/:id`) свежесозданного клуба
**WHEN** видит content
**THEN** видит chips
**AND** видит placeholder «В клубе пока нет активностей.»
> Кнопка «+ Создать» теперь не в табе клуба, а hero на глобальной
> `ActivitiesPage`. Member-view `ClubActivitiesTab` read-only для всех (включая
> organizer'а, открывшего `/clubs/:id`).

### AC-21: Empty state — member
**GIVEN** member открывает таб «Активности» в клубе без активностей
**WHEN** видит content
**THEN** видит chips
**AND** видит placeholder «В клубе пока нет активностей.»
**AND** **НЕ** видит кнопку «+ Создать»

### AC-22: Deep-link compatibility — legacy `?tab=` fallback (итерация 4)
**GIVEN** пользователь открывает legacy bookmark `/clubs/{id}/manage?tab=events`
(или `?tab=activities` / `?tab=skladchina`)
**WHEN** OrganizerClubManage mount
**THEN** активный таб = `members` (`LEGACY_TAB_KEYS` fallback — этих табов больше нет)
**AND** показ работает без ошибок
> До итерации 4 fallback вёл на `activities`. После удаления таба «Активности»
> все три legacy-ключа резолвятся в дефолтный `members`.

### AC-23: Без пагинации — вся лента в одной выдаче
**GIVEN** клуб с 35 активностями
**WHEN** organizer открывает таб «Активности»
**THEN** один запрос `GET /api/clubs/{id}/activities` возвращает все 35 в
`{ upcoming, past }`
**AND** скролл не вызывает дополнительных запросов (пагинации нет)

### AC-24: Invalidation при создании события (итерация 4)
**GIVEN** organizer создал событие через `CreateEventPage`
**WHEN** `useCreateEventMutation.onSuccess` отрабатывает и organizer открывает
ленту клуба (`/clubs/{id}` таб «Активности»)
**THEN** созданное событие присутствует в ленте без manual refresh
(invalidation `queryKeys.activities.byClub(clubId)`)

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

# AC-6: upcoming sorted by relevantDate ASC, типы interleaved
curl -s -H "Authorization: Bearer $JWT_MEMBER" \
  "$BASE/api/clubs/$CLUB_ID/activities" \
  | jq '.upcoming[] | { type, title }'

# AC-7 / AC-19: past содержит завершённые, upcoming — нет
curl -s -H "Authorization: Bearer $JWT_MEMBER" \
  "$BASE/api/clubs/$CLUB_ID/activities" \
  | jq '{ upcoming: [.upcoming[].isCompleted], past: [.past[].isCompleted] }'
# expect: upcoming все false, past все true
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
- `ActivityService`: INFO log на вход — `clubId`, `type`; на выход — `clubId`, `upcoming`-count, `past`-count
- WARN на rate-limit hit (если будет применён)

### Кеширование (frontend)
- TanStack Query `staleTime` 30s (default проекта)
- Invalidation после `useCreateEventMutation` / `useCreateSkladchinaMutation` —
  `queryKeys.activities.byClub(clubId)` (все variants — `byClub` без `filters`-параметра
  использовать prefix-match)

### Доступность (a11y)
- Picker (`Modal`) опции — focusable buttons
- Chips — `role="tablist"` + `aria-selected`
- Карточки и компактные строки — `<button>`
- `Прошедшие`-toggle — `aria-expanded` отражает состояние аккордеона
- Прошедшие строки (`ActivityCompactRow`) — `aria-label` includes «Завершено»

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
   - AC-6 (upcoming sort + interleave), AC-7/AC-19 (past partition), AC-10/11 (type filter), AC-16 (403), AC-17 (400 invalid type), AC-1 пустой клуб, AC-12 ties

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

### Post-flight alignment — итерация 2 (2026-05-24)

> **Q-9 → RESOLVED (staging-feedback fix):** прошедшие события не приглушались в
> ленте активностей — баг был в том, что события **никогда не переходили в
> `completed`** (статус выставлять было нечем; Stage2Service двигал только
> `upcoming → stage_2`). Исправлено `EventCompletionService` (hourly `@Scheduled`,
> grace 6ч). `isCompleted = status IN (completed, cancelled)` теперь реально
> срабатывает для прошедших событий → dimming работает. Складчины приглушались и
> до этого (`SkladchinaScheduler`). Полная спека автозавершения —
> [`events.md`](./events.md) § «Автозавершение прошедших событий». См. CC-16.

> **Q-10 → RESOLVED (Manage brand-редизайн):** `OrganizerClubManage` обёрнут в
> `brand-page` + `BrandBackdrop`; плоский `Cell`-header заменён на brand-карточку
> `ManageHeader`; telegram-ui `TabsList` заменён на brass pill-tabs `ManageTabs`
> (фиксит обрезание лейблов вида «Учас…»). Внутренности табов (Участники / Заявки /
> Финансы / Настройки) и `ActivitiesManageTab` — без изменений. Новые компоненты:
> `ManageHeader`, `ManageTabs` (оба в `components/manage/`). См. также
> [`ui-pages.md`](./ui-pages.md) § OrganizerClubManage и
> [`club-page-unified.md`](./club-page-unified.md) (заметка про `TabsList`).

> **Q-11 → RESOLVED (create-form polish):** в `CreateEventPage` лимит участников →
> `BrandStepper` (`[− N +]`); `datetime-local` в обеих формах (`CreateEventPage`,
> `CreateSkladchinaPage`) обёрнут в стилизованный `.brand-datetime` с brass
> calendar-иконкой (нативный picker сохранён). `BrandStepper` — в
> `components/BrandStepper.tsx`. В `CreateSkladchinaPage` степпер **не** применяется
> (нет поля participant-limit; суммы вводятся в рублях).

### Post-flight alignment — итерация 3 (2026-05-24)

> **Q-12 → RESOLVED (контракт ленты переработан):** endpoint
> `GET /api/clubs/{id}/activities` больше **не пагинируется**. Вместо
> `PageResponse<ActivityItemDto>` возвращает `ClubActivityFeedDto { upcoming, past }`
> (новый DTO `ClubActivityFeedDto.kt`). Партиционирование — по `isCompleted`;
> сортировка — по `relevantDate` (event=`eventDatetime`, skladchina=`deadline`):
> `upcoming` ASC, `past` DESC, ties `id ASC`. Параметры `page`, `size`,
> `includeCompleted` **удалены** из контракта (backend-разработчик явно отметил
> удаление `includeCompleted`). `createdAt` остаётся в DTO, но больше не
> используется для сортировки/UI. Frontend `useClubActivitiesQuery` переведён с
> `useInfiniteQuery` на обычный `useQuery`. См. § «API контракт», § «Бизнес-логика».

> **Q-13 → RESOLVED (Feed UX переработан, staging-feedback):** ранее
> задокументированное поведение «лента sorted by `createdAt DESC`, группировка по
> дню (`Сегодня`/`Вчера`/`<день>`), completed dimmed inline (`opacity 0.55`)»
> **отменено**. Новый дизайн: секция `Предстоящие` (полные `ActivityCard`,
> ближайшее сверху) + сворачиваемый аккордеон `Прошедшие (N)` с компактными
> приглушёнными строками (`ActivityCompactRow.tsx`). `createdAt` в карточках не
> показывается (события — дата/место, складчины — deadline+прогресс). Выравнивание
> иконок поправлено. Utility `utils/activityGrouping.ts` и её тест **удалены**.
> Решение «completed dimmed inline» из итерации 1 (US-3, AC-8) переформулировано
> под split-feed. См. § «Feed UX» (`ActivityFeedList`, `ActivityCompactRow`).

> **Q-14 → RESOLVED (Telegram viewport / swipe):** `initTelegramSdk` (`sdk.ts`)
> дополнен `viewport.expand()` (full-height) + `swipeBehavior.disableVertical()` —
> приложение больше не сворачивается/минимизируется при вертикальном скролле
> контента (сворачивание остаётся только перетаскиванием хедера Telegram). Каждый
> вызов guarded `.isAvailable()` + изолированный catch, чтобы не ломать non-Telegram
> (local dev) fallback. См. § «Telegram viewport / swipe».

> **Q-15 → RESOLVED (BrandStepper ручной ввод):** центральное значение степпера
> теперь редактируемый numeric input (ввод цифр с клавиатуры, clamp к `[min, max]`
> на blur), а не только +/− кнопки. Haptic `selection` по-прежнему только на
> +/− тапах, не на каждое нажатие клавиши. См. файловую таблицу `BrandStepper.tsx`.

### Post-flight alignment — итерация 4 (2026-05-24, round 4)

> **Q-16 → RESOLVED (entry point создания переехал из manage на глобальную
> `ActivitiesPage`):** picker «+ Создать» больше **не** sticky-CTA внутри
> manage-таба «Активности». Теперь — hero-кнопка «+ Создать» на странице `/events`
> (`ActivitiesPage`), видимая только организаторам (`useOrganizerClubs().clubs.length > 0`).
> Flow: тип (`CreateActivityPicker.onPick(type)`) → клуб (авто-выбор если 1
> organizer-клуб, иначе `ClubPickerModal`) → существующие create-routes
> (`/clubs/:id/events/new` · `/clubs/:id/skladchina/new`). Контроллер flow —
> `CreateActivityFlow`. Новые: `CreateActivityFlow`, `ClubPickerModal`,
> `useOrganizerClubs` (`queries/organizerClubs.ts`). `CreateActivityPicker`
> рефакторен с двух колбэков на единый `onPick`. `CreateEventPage` на success
> навигирует на `/events` (не на `manage?tab=activities`).
>
> ⚠️ **Supersedes** memory-note `project_unified_creation_in_manage` («создание
> событий + сборов = одна вкладка управления через picker»): создание вынесено
> ИЗ управления НА глобальный таб. См. отчёт «→ User».

> **Q-17 → RESOLVED (таб «Активности» удалён из `OrganizerClubManage`):**
> `ActivitiesManageTab.tsx` **удалён**. Manage-панель теперь 4 таба:
> Участники / Заявки / Финансы / Настройки (`TabKey` без `activities`). Legacy
> deep-links `?tab=activities|events|skladchina` (`LEGACY_TAB_KEYS`) → fallback
> `members`. Unified-лента активностей (`ActivityFeedList`/`ActivityCard`)
> сохранилась только в member-view `ClubActivitiesTab` (`ClubPage`).

> **Q-18 → RESOLVED (карточка активности — фото-редизайн + event photo):**
> миграция `V15__add_event_photo_url.sql` (`events.photo_url TEXT` nullable)
> добавила событиям обложку (параллель `skladchinas.photo_url`). `photoUrl`
> протянут в `CreateEventRequest` (`@Size(max=1024)`), `EventDetailDto`,
> `EventListItemDto`, `ActivityItemDto.EventActivity`/`SkladchinaActivity`
> (маппер: event — `event.photoUrl`, skladchina — `s.photoUrl`). Карточка
> `ActivityCard` редизайнена: фото/placeholder thumbnail **слева** (`ActivityThumb`
> — позже убран в Banco-редизайне), type-иконка (🗓/💰) — badge **в правом верхнем
> углу** (раньше — иконка в углу без фото). `CreateEventPage` получил поле загрузки фото (`AvatarUpload` →
> `CreateEventBody.photoUrl`). Backend photo-спека — [`events.md`](./events.md).

> **Q-19 → RESOLVED (аккордеон «Прошедшие» теперь анимируется):** раскрытие/
> сворачивание блока `Прошедшие (N)` в `ActivityFeedList` использует
> grid-rows-transition (вместо резкого pop); учитывает `prefers-reduced-motion`.

---

## Итерация 4 — глобальный entry point создания (2026-05-24)

Создание активностей переехало из `OrganizerClubManage` на глобальную
`ActivitiesPage` (`/events` / `/skladchina`).

### Flow «тип → клуб → форма»

1. На `ActivitiesPage` хук `useOrganizerClubs()` (`queries/organizerClubs.ts`)
   собирает клубы пользователя в роли organizer (`useMyClubsQuery` filter
   `role === 'organizer'` + per-club `getClub` для name/avatar/category).
2. Кнопка hero «+ Создать» рендерится **только если** `clubs.length > 0`.
3. Tap → `haptic.impact('light')` → открывается `CreateActivityFlow`.
4. **Step 1** — `CreateActivityPicker` (`Modal`, опции 🗓 Событие / 💰 Сбор).
   `onPick(type)`:
   - если organizer-клуб **ровно один** → авто-навигация на
     `/clubs/{theOnlyClub}/{events|skladchina}/new`;
   - если **≥2** → переход к Step 2.
5. **Step 2** — `ClubPickerModal` (`Modal`-список клубов: avatar/initials + name).
   `onPick(clubId)` → navigate на `/clubs/{clubId}/{events|skladchina}/new`.
6. Create-страницы (`CreateEventPage` / `CreateSkladchinaPage`) читают `:id` из
   URL — без изменений в их сигнатуре. На success — navigate на `/events`
   (глобальная `ActivitiesPage`), toast «Событие создано» через router state.

### Карточка `ActivityCard` (фото-редизайн)

> ⚠️ Историческое (итерация 4). Banco-редизайн (#49) позже убрал левый thumbnail —
> `ActivityThumb` удалён. Актуальная карточка — [`redesign-banco-style.md`](./redesign-banco-style.md).

- **Слева** — `ActivityThumb`: квадрат с фото (`photoUrl`, cover) либо
  brass-плейсхолдер с type-emoji (🗓/💰), если фото нет.
- **Справа сверху** — `type-badge` с иконкой типа.
- Контент (title / дата+место или прогресс / badge счётчика) — без изменений по
  данным, перекомпонован вокруг thumbnail.

### Где живёт unified-лента после итерации 4

`ActivityFeedList` + `ActivityCard` + `ActivityCompactRow` используются только в
**member-view** `ClubActivitiesTab` (`ClubPage`, read-only). Organizer-таб
`ActivitiesManageTab` удалён. Глобальная `ActivitiesPage` рендерит свои
сегменты (`EventsTab`/`SkladchinasTab` из events-feed модуля), а не unified-feed.

---

## Telegram viewport / swipe (итерация 3)

`initTelegramSdk()` (`frontend/src/telegram/sdk.ts`) при инициализации SDK:

- **`viewport.expand()`** — разворачивает Mini App на всю доступную высоту
  (после `viewport.mount()`, который async). `requestFullscreen()` намеренно
  **не** используется — нужна только полная высота, не перекрытие хедера.
- **`swipeBehavior.disableVertical()`** — отключает сворачивание приложения при
  вертикальном swipe по контенту. Раньше скролл ленты активностей сворачивал
  Mini App. Свернуть всё ещё можно перетаскиванием хедера Telegram.

Обе операции guarded через `.isAvailable()` и обёрнуты в изолированные
`try/catch`, чтобы сбой не прерывал init и не ломал local-dev (non-Telegram)
режим. Поведение глобальное (не специфично для ленты активностей), но
зафиксировано здесь, так как баг «сворачивание при скролле» проявлялся именно
на длинной ленте.

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
