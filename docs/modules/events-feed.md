# Module: EventsFeed (вкладка «События»)

> **Status:** Spec для дизайн-итерации и последующей реализации.
> Текущее состояние: `frontend/src/pages/EventsPage.tsx` рендерит placeholder
> «Здесь скоро появится лента ваших ближайших событий из всех клубов где вы
> состоите» (PR #27 `feature/restructure-bottom-tabs`). Backend endpoint
> ещё не существует.

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
- Новый query hook `useMyEventsQuery()` в `frontend/src/queries/events.ts`
- Pull-to-refresh (если технически просто — Telegram WebApp expand эта возможность)

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

**Query parameters:**
| Параметр | Тип | Дефолт | Описание |
|---|---|---|---|
| `status` | string | `upcoming` | `upcoming` (все upcoming + stage_2), `past` (для будущей вкладки «Архив»). MVP — только `upcoming` |
| `limit` | int | 20 | pagination — сколько вернуть. Max 50 |
| `cursor` | string? | null | для pagination — eventDatetime + id последнего из предыдущей страницы. NULL = с начала |

**Response 200:**
```ts
{
  items: MyEventListItemDto[],
  nextCursor: string | null  // null = дальше ничего нет
}
```

`MyEventListItemDto`:
```ts
{
  id: string,
  title: string,
  eventDatetime: string,        // ISO 8601
  locationText: string,
  status: 'upcoming' | 'stage_2' | 'completed' | 'cancelled',

  clubId: string,
  clubName: string,
  clubAvatarUrl: string | null,

  myVote: 'going' | 'maybe' | 'not_going' | null,
  myParticipationStatus: 'confirmed' | 'waitlisted' | 'declined' | null,

  goingCount: number,
  confirmedCount: number,
  participantLimit: number,

  // Computed на бэке для удобства фронта (frontend не должен пересчитывать)
  actionRequired: boolean       // см. § «Action-required logic»
}
```

**Errors:**
- `401` — не авторизован
- `5xx` — внутренняя ошибка

**Notes:**
- Сортировка: `actionRequired DESC, eventDatetime ASC` — events требующие действия сверху, остальные хронологически
- Данные включают events из клубов где user `MembershipStatus.active` (member ИЛИ organizer). Не включают: applications/pending, soft-deleted клубы

### Action-required logic (backend computes)

`actionRequired = true` если выполняется ОДНО из:
1. `event.status == upcoming` AND voting открыт (`now ≥ event.eventDatetime - votingOpensDaysBefore days`) AND `myVote == null`
2. `event.status == stage_2` AND `myVote IN ('going', 'maybe')` AND `myParticipationStatus == null`

Иначе false.

---

## Анатомия event-card (для дизайнера — структура info, не визуал)

Каждая карточка события содержит:

### Top-section
- **Дата + время** события — крупно, например «Сб 4 мая, 18:00»
- **Title события** — основной текст
- **Place** — место проведения

### Club-section (subtle)
- **Avatar** клуба + **название** клуба — small/secondary, как footer-info («📚 Книжный клуб Москвы»)

### Status-badge
- Если `actionRequired` — выделенный badge: «Голосуй до X» / «Подтверди участие до Y» (где X, Y — даты)
- Иначе если `myVote` или `myParticipationStatus` — нейтральный badge: «Иду» / «Подтверждён» / «Лист ожидания» / etc
- Иначе — без badge

### Stats-section (для events с активным набором)
- `goingCount / participantLimit` (для stage_1 — голосование) ИЛИ `confirmedCount / participantLimit` (для stage_2 — подтверждение)
- Опционально: progress bar отображающий заполненность

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

## Empty states (важные для UX)

| Состояние | Текст | CTA |
|---|---|---|
| Нет membership ни в одном клубе | «Вы пока не состоите в клубах. Найдите интересный в Поиске.» | «Перейти в Поиск» → tab `/` |
| Membership есть, но нет upcoming events | «В ваших клубах пока нет предстоящих событий. Загляните позже или подпишитесь на новые клубы.» | «Найти ещё клубы» → `/` |
| Loading (initial) | Skeleton-карточки (3-5 placeholder) | — |
| Loading next page (pagination) | Spinner внизу | — |
| Network error | «Не удалось загрузить события. Проверьте соединение.» | «Повторить» → refetch |

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
- Pull-to-refresh release → `impact('soft')` (если pull-to-refresh реализован)

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
```ts
export function useMyEventsQuery(status: 'upcoming' | 'past' = 'upcoming') {
  return useInfiniteQuery({
    queryKey: queryKeys.events.myFeed(status),
    queryFn: ({ pageParam }) => getMyEvents({ status, cursor: pageParam, limit: 20 }),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
}
```

### Query keys (queries/queryKeys.ts)
Добавить:
```ts
events: {
  ...,
  myFeed: (status: string) => ['events', 'my-feed', status] as const,
}
```

### Invalidation
В существующих мутациях (`useCastVoteMutation`, `useConfirmParticipationMutation`, `useDeclineParticipationMutation`) — добавить `qc.invalidateQueries({ queryKey: queryKeys.events.all })` чтобы лента обновлялась после действия. Сейчас они invalidate'ят `events.detail(id)` и `events.myVote(id)` — расширить.

### Компоненты
```
frontend/src/components/events/
  EventsFeedList.tsx        — оркестрирует группировку и pagination
  EventsFeedSection.tsx     — одна секция (header + список карточек)
  EventCard.tsx             — карточка одного события
  EventsFeedEmpty.tsx       — empty states
```

### Страница
`frontend/src/pages/EventsPage.tsx` — становится thin container:
- `useMyEventsQuery()`
- Группирует data по логике (actionRequired / эта неделя / позже)
- Рендерит `<EventsFeedList groups={grouped} />`
- Пагинация через intersection observer

---

## Backend implementation план

### Endpoint
`GET /api/users/me/events` в новом или существующем `EventController` (или отдельный `UserEventsController` для консистентности с `/api/users/me/clubs` и `/api/users/me/applications`)

### Service
`UserEventsService.getMyEvents(userId, status, cursor, limit): MyEventsPageDto`

Логика:
1. SELECT user's active memberships → list of clubIds
2. SELECT events WHERE clubId IN (...) AND status IN (relevant statuses based on `status` param) AND clubs.deleted_at IS NULL
3. LEFT JOIN event_responses для текущего user → получить myVote, myParticipationStatus
4. JOIN clubs для clubName, avatarUrl
5. Compute actionRequired для каждой записи
6. ORDER BY actionRequired DESC, eventDatetime ASC
7. LIMIT + cursor logic

### DB queries / индексы
- Существующий index на `events.club_id` (V9) — должно работать
- Возможно понадобится составной индекс `(club_id, event_datetime, status)` если query slow на больших объёмах (≥10k events) — измерить, не упреждать

### Тестирование (backend)
- Integration test: user с 3 клубами получает agg-список только из этих клубов
- Negative: user не member ни одного клуба → empty list (не 4xx)
- Negative: club soft-deleted → events этого клуба отсутствуют
- Pagination: cursor работает корректно

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
