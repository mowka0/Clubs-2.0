# Module: EventsFeed (вкладка «Активности»)

> **Status:** Спека в реализации (`feature/events-feed-page`, начато 2026-05-18).
> Pre-flight Analyst закончен — pre-design решения зафиксированы в § «Decisions
> from pre-flight» ниже. Текущее состояние до этого PR:
> `frontend/src/pages/EventsPage.tsx` рендерит placeholder, backend endpoint
> ещё не существует.

## Decisions from pre-flight (2026-05-18)

Зафиксированные продуктовые и архитектурные решения после обсуждения с владельцем
продукта. Обновляют первоначальный draft спеки в местах противоречия.

1. **Таб переименовывается «События» → «Активности».** URL остаётся `/events`
   (переименование URL = breaking change без cause: складчина ещё не существует).
   Когда появится складчина — добавим segmented control «События / Сборы» внутри
   страницы, тогда же решим про URL.
2. **Карточка содержит stats-счётчик числом** (`12/30 идёт` для `upcoming`,
   `12/30 подтверждено` для `stage_2`). Без progress bar.
3. **Без inline quick-actions в карточке.** Тап → `/events/:id`, действия там.
4. **Pull-to-refresh — НЕ реализован в v1.** В ходе реализации оценили
   стоимость: TG WebView не имеет нативного pull-to-refresh, нужен custom
   touch-event handler ~50 строк отдельным хуком ради одной фичи. Заменено
   на **кнопку «Обновить»** в header — функционально эквивалентно, реализация
   тривиальна, hapticfeedback тот же. Если в проде попросят жест-обновление —
   отдельная задача `feature/pull-to-refresh-hook`.
5. **Empty state v1 = stub без recommendations.** Рекомендации клубов «по
   интересам с ближайшими событиями» отложены в
   `docs/backlog/onboarding-interests-and-recommendations.md` (зависит от модели
   интересов пользователя, которой ещё нет).
6. **Расширяемость для складчины** реализуется через generic naming:
   `frontend/src/components/feed/` (не `events/`). Generic-компоненты
   (FeedSection, FeedSkeleton, FeedEmpty, grouping-утилиты) переиспользуются.
   `EventCard.tsx` остаётся event-specific; когда появится `SkladchinaCard.tsx`
   — будет рядом. Endpoint остаётся `/api/users/me/events` (не generic
   `/me/feed`) — для складчины будет параллельный `/me/skladchinas`.
7. **Endpoint location:** `getMyEvents` добавляется в существующий
   `UserController` (рядом с `/me/clubs`, `/me/applications`), не отдельный
   `UserEventsController`. Сервис — `UserEventsService` в пакете `com.clubs.event`.
8. **Pagination — page-based** (консистентно с `findByClubId`), не cursor-based
   как в первоначальном draft. Cursor может быть введён позже, если page-based
   станет узким местом.
9. **Invalidation после mutations** — точечно: расширяем `useCastVoteMutation`,
   `useConfirmParticipationMutation`, `useDeclineParticipationMutation` на
   invalidate `events.myFeed` + `events.byClubAll(clubId)`, не `events.all`.
   Это эффективнее на больших нагрузках.
10. **EventStatus enum имеет 5 values** (`upcoming`, `stage_1`, `stage_2`,
    `completed`, `cancelled`). В реальном flow `stage_1` не транзитится
    (голосование происходит при `status=upcoming`). MVP feed показывает:
    `upcoming` + `stage_2`. `completed`/`cancelled` отфильтровываются на бэке.

---

## Цель

Сократить дистанцию пользователь ↔ событие до **одного клика от любого экрана**. Сейчас чтобы увидеть «куда я иду на этой неделе», пользователь должен сделать 3-4 клика: «Мои клубы» → выбрать клуб → «События» tab → пролистать. Если он состоит в 3+ клубах — это 3+ обходов. Тогда как **events — ядро ценности продукта** (PRD-Clubs.md): клубы — место, события — событие.

Цель — превратить «События» в **первую вкладку которую пользователь открывает каждый раз когда заходит в Mini App**. Если у него есть upcoming events — он сразу видит что и когда; если что-то требует ответа (vote/confirm) — он видит badge и может сразу действовать.

---

## Product rationale (почему именно так)

### Только member-events (privacy)

Лента показывает события **только из клубов где пользователь является active member** (включая organizer-роль). НЕ показывает:
- События из клубов где пользователь только подал application (pending)
- Публичные события (этого слоя в продукте нет, см. `project_clubs_over_events_rationale.md` — мы намеренно clubs-first, не event-discovery)
- События закрытых клубов где пользователь не состоит

Это защищает приватность closed-club meetups (organizer контролирует кто видит расписание) и согласуется с `EventController.getClubEvents` membership check (закрыт через `@RequiresMembership` aspect — см. `docs/backlog/club-events-membership-check.md` ✅ RESOLVED). Реализация `/api/users/me/events` должна аналогично фильтровать по active memberships на бэкенде, не доверяя клиенту.

### Action-first сортировка

Не хронология сверху, а **«что требует моего ответа» сверху**. Пользователь зашёл в app — должен сразу видеть что он должен сделать. Хронология — следом.

Группы (в порядке отображения):
1. **🔥 Требует действия** (если есть): голосование открыто и пользователь не голосовал, ИЛИ stage_2 и пользователь не подтвердил/не отказался при vote=going/maybe
2. **📅 Эта неделя** (≤ 7 дней)
3. **📆 Позже** (> 7 дней, ≤ 30 дней)
4. **(опционально) Прошедшие** (за последние 30 дней) — отдельный таб/секция, не основной flow

Группа «Требует действия» обнуляется когда пользователь действует — события переходят в обычную хронологическую группу.

---

## Scope

### Входит (MVP)

- Backend endpoint `GET /api/users/me/events` — агрегированный список upcoming events из clubs где user является active member
- Frontend `EventsPage.tsx` — заменяет текущий placeholder
  - Рендер группированного списка событий
  - Карточка события с действиями (см. § «Анатомия event-card»)
  - Empty state когда событий нет
  - Loading / error states
  - Тап по карточке → `/events/:id` (существующий EventPage с полным flow)
  - Кнопка «Обновить» в header (заменяет pull-to-refresh, см. Decision 4)
- Новый query hook `useMyEventsQuery()` в `frontend/src/queries/events.ts`

### НЕ входит (v2 / отдельные PR)

- Quick-action buttons в карточке (RSVP/confirm прямо из ленты, без перехода на EventPage). v1 — тап → детальный экран
- Календарный view (alternative визуализация)
- Push-уведомления о новых событиях / напоминания
- Фильтр по конкретному клубу (если у user 5+ клубов и список слишком длинный — этот фильтр оправдан, но не v1)
- Past events — отдельный таб «Архив» (сейчас рисуем только upcoming)
- Real-time обновления (WebSocket / SSE) — пока polling через invalidation после мутаций
- Интеграция с системным календарём («Добавить в Calendar.app»)

### Зависимости

- **Backend:** должен быть закрыт `docs/backlog/club-events-membership-check.md` (membership-check на event-endpoints) — иначе аггрегатный endpoint унаследует ту же утечку
- **Frontend:** все queries/мутации в `queries/events.ts` уже работают (PR #24 + #30) — нужен только новый hook + страница

---

## User Stories (приоритезированы)

### US-1 (Critical): Я вижу что мне нужно сделать

**Как** member клубов
**Я хочу** открыть вкладку «События» и сразу увидеть **action items** (события требующие моего ответа)
**Чтобы** не пропустить голосование или подтверждение участия и не подвести организатора

### US-2 (Critical): Я вижу когда моё ближайшее событие

**Как** member клубов
**Я хочу** видеть на одном экране ВСЕ предстоящие события (из всех моих клубов) хронологически
**Чтобы** планировать неделю без переключения между клубами

### US-3 (Important): Я понимаю в каком клубе событие

**Как** member нескольких клубов
**Я хочу** видеть на карточке события **название клуба + аватар**
**Чтобы** контекстно понимать «это событие из книжного клуба или йога-сообщества»

### US-4 (Important): Я понимаю свой статус относительно события

**Как** member клуба
**Я хочу** видеть на карточке мой текущий **статус по событию** (Голосовал «Иду» / Подтверждён / Лист ожидания / Не голосовал / etc)
**Чтобы** не открывать детали события только чтобы вспомнить «я подтвердил или нет»

### US-5 (Nice): Я понимаю набор участников

**Как** member клуба
**Я хочу** видеть на карточке счётчик «Сколько уже идёт» (`12/30 идёт` или progress bar)
**Чтобы** понимать набирается ли событие (если limit близок — спешу confirm)

### US-6 (Nice): Я могу легко открыть детальную страницу события

**Как** member клуба
**Я хочу** тапнуть карточку и попасть на existing `/events/:id` — со всеми деталями, voting/confirm flow
**Чтобы** действовать с полным контекстом

### US-7 (Edge): У меня нет клубов или они без событий

**Как** новый пользователь без членств / без событий
**Я хочу** видеть осмысленный empty state с подсказкой «Найдите клуб в Поиске»
**Чтобы** понимать что делать дальше, а не терять интерес

---

## API контракты

### `GET /api/users/me/events`

Аггрегированный список upcoming events из всех клубов где user — active member.
Добавляется в существующий `UserController` (рядом с `/me/clubs`,
`/me/applications`).

**Query parameters:**
| Параметр | Тип | Дефолт | Описание |
|---|---|---|---|
| `page` | int | 0 | 0-based номер страницы |
| `size` | int | 20 | размер страницы. Max 50 |

(`status` параметр НЕ добавляется в v1 — feed всегда показывает upcoming + stage_2. Past events — отдельный сценарий, будут отдельным endpoint или query-param когда понадобятся, см. R-2.)

**Response 200:** стандартный `PageResponse<MyEventListItemDto>` как у других list-endpoints:
```ts
{
  content: MyEventListItemDto[],
  totalElements: number,
  totalPages: number,
  page: number,
  size: number
}
```

`MyEventListItemDto`:
```ts
{
  id: string,
  title: string,
  eventDatetime: string,        // ISO 8601
  locationText: string,
  status: string,               // literal из EventStatus enum: upcoming | stage_2 (других в feed v1 не отдаём)

  clubId: string,
  clubName: string,
  clubAvatarUrl: string | null,

  myVote: 'going' | 'maybe' | 'not_going' | null,
  myParticipationStatus: 'confirmed' | 'waitlisted' | 'declined' | null,

  goingCount: number,
  confirmedCount: number,
  participantLimit: number,

  actionRequired: boolean       // computed на бэке, см. § «Action-required logic»
}
```

**Errors:**
- `401` — не авторизован
- `5xx` — внутренняя ошибка

**Notes:**
- Сортировка: `actionRequired DESC, eventDatetime ASC` — events требующие действия сверху, остальные хронологически
- Данные включают events из клубов где user `MembershipStatus.active` (member ИЛИ organizer). Не включают: applications/pending, soft-deleted клубы
- Фильтр на стороне БД: `status IN ('upcoming', 'stage_2') AND event_datetime > now() AND clubs.deleted_at IS NULL`
- `myParticipationStatus` мэппится из `EventResponse.finalStatus` (FinalStatus enum: confirmed/waitlisted/declined). Если запись `event_responses` отсутствует — null
- `myVote` мэппится из `EventResponse.stage1Vote` (Stage_1Vote enum). null если не голосовал

### Action-required logic (backend computes)

`actionRequired = true` если выполняется ОДНО из:
1. `event.status == upcoming` AND voting открыт (`now ≥ event.eventDatetime - votingOpensDaysBefore days`) AND `myVote == null`
2. `event.status == stage_2` AND `myVote IN ('going', 'maybe')` AND `myParticipationStatus == null`

Иначе false.

---

## Анатомия event-card (минимум информации, детали по тапу)

Карточка лаконичная: достаточно для решения «открывать или нет», полная инфа +
действия — на `/events/:id` (PR #28+ EventPage с voting/confirm flow).

### Содержимое (сверху вниз)
- **Дата + время** события — «Сб 4 мая, 18:00» (форматирование локализовано в `shared/lib/formatDate`)
- **Title** события
- **Place** — место проведения
- **Avatar клуба + название клуба** — subtle/secondary
- **Status-badge** — если применимо (см. ниже)
- **Stats-counter** — `12/30 идёт` (`upcoming`) или `12/30 подтверждено` (`stage_2`). Числом, без progress bar

### Status-badge логика
- Если `actionRequired` — выделенный (brass-acceпт) badge: «Голосуй» / «Подтверди участие»
- Иначе если `myParticipationStatus != null` — нейтральный badge: «Подтверждён» / «Лист ожидания» / «Отказался»
- Иначе если `myVote != null` (но не actionRequired) — нейтральный badge: «Иду» / «Возможно» / «Не иду»
- Иначе — без badge

### Без inline RSVP
В v1 на карточке **нет** кнопок «Иду / Возможно / Не иду». Это решение для
снижения визуальной плотности и одного очевидного primary action (тап → детали).
Quick-actions могут быть добавлены позже на основе использования (см. R-1).

### Tap behavior
- Light haptic + navigate `/events/:id`

---

## Группировка (для дизайнера — логика секций)

Список разделён на секции **в этом порядке**:

1. **🔥 Требует действия** — events где `actionRequired == true`. Sticky-header или явно выделенная секция (визуальный приоритет — это самая важная группа). Может быть пустой — тогда секция не рендерится.
2. **📅 Эта неделя** — events где `eventDatetime` в пределах +7 дней
3. **📆 Позже** — events где `eventDatetime` > 7 дней (макс 30 дней по дефолту load'а)
4. **(будущее, не v1)** «Архив» — отдельный tab/segmented-control сверху, грузит `?status=past`

Внутри каждой секции — сортировка по `eventDatetime ASC`.

---

## Empty states

v1 — stub-варианты без recommendations. Рекомендации клубов по интересам
отложены в `docs/backlog/onboarding-interests-and-recommendations.md` (нет
модели интересов user'а в БД).

| Состояние | Текст | CTA |
|---|---|---|
| Нет membership ни в одном клубе | «Вы пока не состоите в клубах. Найдите интересный в Поиске.» | «Перейти в Поиск» → tab `/` |
| Membership есть, но нет upcoming events | «В ваших клубах пока нет предстоящих событий. Загляните позже.» | — |
| Loading (initial) | Skeleton-карточки (3 placeholder) | — |
| Loading next page (pagination) | Spinner внизу | — |
| Network error | «Не удалось загрузить события. Проверьте соединение и попробуйте снова.» | «Повторить» → refetch |

**Имплементация (post-flight):** один компонент `<FeedEmpty>` рендерится во всех
non-data состояниях (no clubs / no events / error), различается title/description/CTA.
Различие "no clubs" vs "no events" не делается на UI — backend возвращает пустой
PageResponse в обоих случаях, отдельный запрос `useMyClubsQuery` ради разной
формулировки расценено как overkill для v1. Текст empty state универсален.

---

## Acceptance Criteria

### AC-1: Базовый рендер списка
**GIVEN** user member нескольких клубов с upcoming events
**WHEN** открывает таб «События»
**THEN** видит список карточек events отсортированный по `actionRequired DESC, eventDatetime ASC`
**AND** каждая карточка содержит: дату+время, title, место, аватар+название клуба, статус-badge (если применимо), счётчик участников

### AC-2: Action-required prioritization
**GIVEN** user имеет 5 events: 2 требуют действия (1 voting open, 1 stage_2 без confirm), 3 не требуют
**WHEN** открывает «События»
**THEN** видит секцию «🔥 Требует действия» сверху с 2-мя events
**AND** обе карточки имеют выделенный badge с deadline
**AND** ниже секция «Эта неделя» / «Позже» с остальными 3-мя events

### AC-3: Tap navigates to detail
**GIVEN** user на странице «События» с >= 1 event-карточкой
**WHEN** тап по карточке
**THEN** light haptic
**AND** navigate на `/events/:id`
**AND** на детальной странице полный flow voting/confirm/decline (PR #30 fix)

### AC-4: Pagination
**GIVEN** user member клубов с >20 upcoming events
**WHEN** scroll до низа списка
**THEN** загружается следующая страница (limit=20)
**AND** новые события рендерятся внутри их соответствующих секций (не в конец списка валом)

### AC-5: Empty state — нет клубов
**GIVEN** user не member ни одного клуба (новый аккаунт)
**WHEN** открывает «События»
**THEN** видит Placeholder «Вы пока не состоите в клубах. Найдите интересный в Поиске.»
**AND** кнопка/link «Перейти в Поиск» — навигация на `/`

### AC-6: Empty state — клубы без events
**GIVEN** user member клубов, но во всех нет upcoming events
**WHEN** открывает «События»
**THEN** видит Placeholder «В ваших клубах пока нет предстоящих событий...»

### AC-7: Privacy — events удалённого клуба не появляются
**GIVEN** user был member клуба X, клуб X soft-deleted
**WHEN** открывает «События»
**THEN** события клуба X отсутствуют в списке (даже если они в БД)

### AC-8: Refresh после действия в EventPage
**GIVEN** user на странице «События», видит карточку event Y в секции «🔥 Требует действия»
**WHEN** тапает на карточку → попадает на `/events/Y` → голосует → возвращается на `/events`
**THEN** карточка event Y больше НЕ в секции «Требует действия» (она «удовлетворила» условие — vote зарегистрирован)
**AND** карточка теперь в обычной секции с обновлённым status-badge

(Реализуется через invalidation `queryKeys.events.all` или specific my-events key из `useCastVoteMutation.onSuccess`.)

### AC-9: Haptic preserved
**GIVEN** user взаимодействует с EventsPage
**WHEN** scroll / tap / etc
**THEN** haptic срабатывает по правилам `haptic.md`:
- Тап по event-карточке → `impact('light')` (как другие nav-cells)
- Тап по «Перейти в Поиск» из empty state → `impact('light')`
- Тап по кнопке «Обновить» → `impact('light')` (заменяет pull-to-refresh)

---

## Non-functional

### Производительность
- Endpoint должен возвращать ответ < 300ms для user'а с 10 клубами и 50 events (через индекс по `events.club_id`, JOIN с memberships)
- Frontend: первый paint ленты < 1 sec на 4G в Telegram WebView
- Pagination не блокирует UI — infinite scroll плавный

### Безопасность
- Backend: только active members club'а могут видеть события клуба (membership-check внутри aggregation query)
- Soft-deleted clubs полностью исключены (filter `clubs.deleted_at IS NULL`)
- JWT auth (как все `/api/*` endpoints)

### Логирование
- INFO log на endpoint: `userId`, `count of events returned`
- WARN на rate-limit hit (если будет)

### Кеширование
- TanStack Query staleTime по дефолту 30s (project default), invalidation после vote/confirm/decline mutations
- Query key: `queryKeys.events.myFeed(status)`

### Доступность (a11y)
- Каждая карточка — focusable (button role или semantic Cell)
- Status-badge с понятным aria-label («Подтверждён, ваше место в списке»)

---

## Frontend implementation план

### Новый query hook (queries/events.ts)
Используем `useInfiniteQuery` с page-based pagination (консистентно с
существующим `findByClubId`):

```ts
export function useMyEventsQuery() {
  return useInfiniteQuery({
    queryKey: queryKeys.events.myFeed,
    queryFn: ({ pageParam }) => getMyEvents({ page: pageParam, size: 20 }),
    initialPageParam: 0,
    getNextPageParam: (last) => last.page + 1 < last.totalPages ? last.page + 1 : undefined,
  });
}
```

### Query keys (queries/queryKeys.ts)
Добавить:
```ts
events: {
  ...,
  myFeed: ['events', 'my-feed'] as const,
}
```
Один ключ без параметров (status фильтра пока нет, см. § «API контракты»).

### Invalidation
В существующих мутациях (`useCastVoteMutation`, `useConfirmParticipationMutation`, `useDeclineParticipationMutation`) — расширить onSuccess: добавить invalidate `events.myFeed` + `events.byClubAll(clubId)`. Точечно, **не** `events.all` (см. Decision 9).

```ts
// Пример для useCastVoteMutation.onSuccess
qc.invalidateQueries({ queryKey: queryKeys.events.detail(eventId) });
qc.invalidateQueries({ queryKey: queryKeys.events.myVote(eventId) });
qc.invalidateQueries({ queryKey: queryKeys.events.myFeed });    // лента
// byClubAll нужен только если знаем clubId — для confirm/decline он есть в response,
// для castVote проще вообще не invalidate'ить byClubAll (текущая страница event-detail
// сама подтянет fresh data на mount)
```

### Компоненты
```
frontend/src/components/feed/        ← generic namespace (расширение для skladchina)
  FeedSection.tsx           — одна секция (header + children)
  FeedSkeleton.tsx          — placeholder загрузки
  FeedEmpty.tsx             — empty state (принимает title/description/cta)
  EventCard.tsx             — карточка одного события (event-specific)
  (позже: SkladchinaCard.tsx)
```

Группировка / sorting / actionRequired-логика — generic helpers в `frontend/src/utils/feedGrouping.ts` (если простые) или inline в `EventsPage.tsx` (если ≤ 30 строк).

### Страница
`frontend/src/pages/EventsPage.tsx` — становится thin container:
- `useMyEventsQuery()`
- Группирует data по логике (actionRequired / эта неделя / позже)
- Рендерит `<EventsFeedList groups={grouped} />`
- Пагинация через intersection observer

---

## Backend implementation план

### Endpoint
`GET /api/users/me/events` добавляется в **существующий** `UserController` (рядом
с `/me/clubs`, `/me/applications`). DI: `UserEventsService` (новый сервис).

### Service
`UserEventsService.getMyEvents(userId: UUID, page: Int, size: Int): PageResponse<MyEventListItemDto>`

Пакет: `com.clubs.event` (логика event-related, рядом с EventService).

Логика:
1. Получить список clubIds где user — active member (`MembershipRepository`)
2. Если список пуст → вернуть пустой `PageResponse` (не 4xx)
3. Запрос в новом методе `EventRepository.findMyFeed(userId, clubIds, page, size)`:
   - SELECT events
     WHERE club_id IN (...)
       AND status IN ('upcoming', 'stage_2')
       AND event_datetime > now()
       AND clubs.deleted_at IS NULL  -- JOIN с clubs
   - LEFT JOIN event_responses ON event_id = events.id AND user_id = :userId → myVote, myParticipationStatus
   - JOIN clubs → clubName, avatarUrl
   - LEFT JOIN с aggregated counts (готовый `fetchGoingCounts` уже есть, нужен аналог для confirmedCount)
   - ORDER BY (actionRequired_expr) DESC, event_datetime ASC
   - LIMIT/OFFSET
4. Mapper в `MyEventListItemDto` (новый метод в EventMapper) — на каждой записи computes `actionRequired` через тот же helper, который SQL не может посчитать чисто (deadlines vs now)

### DB queries / индексы
- Существующий index на `events(club_id)` (V9) — должно работать
- Возможно понадобится составной индекс `(club_id, event_datetime, status)` если query slow на больших объёмах (≥10k events) — **не упреждаем**, измерим если будет медленно
- Миграция в этом PR — **не нужна** (все поля уже есть)

### Тестирование (backend)
Integration test `UserControllerMeEventsTest` (или дополнение существующего `UserControllerTest`):
- **AC-1**: user с 3 клубами получает agg-список только из этих клубов
- **AC-2**: actionRequired=true сортируется выше
- **Negative**: user не member ни одного клуба → пустой PageResponse (не 4xx)
- **Negative**: club soft-deleted → events этого клуба отсутствуют
- **Negative**: events со status=completed/cancelled отфильтрованы
- **Negative**: events с event_datetime < now() отфильтрованы
- **Privacy**: user A не видит events клуба B где он не member
- **Pagination**: page=1 возвращает корректный slice

---

## Risks / Open questions

### R-1 (Open): Quick actions vs detail navigation
В v1 решили — тап по карточке → переход на детальную страницу. **Open question**: после design iteration — стоит ли добавить inline quick-action buttons (RSVP в карточке без перехода)? UX-исследование показывает что 1-tap actions сокращают action latency, но добавляют визуальной плотности на карточке. Решение — после первой версии, на основе использования.

### R-2 (Open): Past events visibility
Нужен ли архив прошедших events? Use cases:
- «Сколько событий я посетил в этом клубе» — для reputation/badges
- «Найти участников прошлого события» — networking
- Просто «вспомнить когда было»

Если нужен — добавить `?status=past` в endpoint и опциональный tab в UI. v1 — не делаем, ждём фидбек.

### R-3 (Risk): Производительность при росте
Если будет 10k+ users каждый в 5+ клубах с 100+ events / клуб — aggregation query может стать медленным. Mitigations: индексы (см. выше), client-side cache на 30s (default), возможно cache-by-user на backend (Redis).

### R-4 (Risk): Privacy leak через aggregation
Если backend bug — пользователь может получить events из клубов где он НЕ member. Защита: explicit membership filter на каждом query, integration test проверяет negative case (user без членства не получает чужие events).

### R-5 (Open): Real-time updates
Если в клубе создали новое событие, должен ли пользователь увидеть его на «События» немедленно? Сейчас — только при следующем mount или после refresh. Real-time через WebSocket / SSE = большая инфраструктурная задача. v1 — polling через invalidation. v2 — подумать.

### R-6 (Open): Notifications integration
Не часть UI, но связанный вопрос: когда event переходит в `stage_2` (нужно подтверждать), должен прийти push-notification от @clubs_admin_bot. Сейчас не реализовано (см. отдельный backlog для bot/notifications). UX этой ленты предполагает что push приведёт пользователя сюда → он видит «🔥 Требует действия» → confirm.

---

## Связанное

- `docs/modules/events.md` — backend модуль events (legacy — описывает только club-scoped flow)
- `docs/modules/club-page-unified.md` — `ClubEventsTab` показывает events одного клуба, эта фича — agg-версия
- `docs/modules/haptic.md` — паттерны вибрации
- `docs/backlog/club-events-membership-check.md` — **обязательная** зависимость (security gap должен быть закрыт перед этой фичей)
- `docs/backlog/event-rsvp-buttons-broken.md` — RESOLVED в PR #30, основа для quick-actions если будем делать
- `project_clubs_over_events_rationale.md` — почему clubs-first и почему events НЕ публичны (privacy rationale для этой ленты)
- PRD-Clubs.md — product requirements
