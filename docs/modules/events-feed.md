# Module: EventsFeed (вкладка «Активности»)

> **Status:** ✅ Реализовано (PR `feature/events-feed-page`, merge 2026-05-18).
> Endpoint `GET /api/users/me/events` живой, `EventsPage` переписан с
> placeholder'а, таб в нижней навигации переименован «События» → «Активности»
> (URL остаётся `/events`). Все Decisions из § ниже отражены в коде.
> Post-flight расхождения уже подтянуты в спеку (см. Decision 4 о замене
> pull-to-refresh на кнопку «Обновить»).
>
> **Update (итерация 4 `feature/unified-activity-creation`, 2026-05-24):** в шапку
> `ActivitiesPage` добавлена hero-кнопка «+ Создать» (видна менеджерам — владелец / активный со-организатор,
> `useOrganizerClubs().clubs.length > 0`) → flow создания события/сбора
> (`CreateActivityFlow`). Это chrome поверх ленты, на сам feed-контракт
> (`GET /api/users/me/events`) не влияет. Полная спека создания —
> [`unified-activity-creation.md`](./unified-activity-creation.md) § «Итерация 4».
>
> **Update (итерация 5 `feature/events-history`, 2026-07-20):** в ленту добавлена секция
> **«История»** — прошедшие события с личной отметкой явки (`attended`). Тот же endpoint,
> новое поле `isHistory` в DTO, 5-ключевой `ORDER BY`. Основное тело ниже описывает
> предстоящую половину ленты; всё про историю — § «Итерация 5» в конце файла
> (источник истины для этой части).

## Decisions from pre-flight (2026-05-18)

Зафиксированные продуктовые и архитектурные решения после обсуждения с владельцем
продукта. Обновляют первоначальный draft спеки в местах противоречия.

1. **Таб переименовывается «События» → «Активности».** URL остаётся `/events`
   (переименование URL = breaking change без cause: складчина ещё не существует).
   Когда появится складчина — добавим segmented control «События / Сборы» внутри
   страницы, тогда же решим про URL.
2. **Карточка содержит stats-счётчик числом** (`12/30` — confirmedCount для
   `stage_2`, иначе goingCount, над participantLimit; без слова «идёт»/«подтверждено»,
   чтобы не выходить за рамки карточки) **+ progress bar** под счётчиком
   (доля участников, brass-заливка). _(round 5, 2026-05-24: убрали слово, добавили bar.)_
   У открытой встречи (V62, `participantLimit = null`) — счёт без знаменателя.
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
   (FeedSection, FeedSkeleton, FeedEmpty — последний удалён 2026-07-20 в пользу
   FoxEmpty, — grouping-утилиты) переиспользуются.
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
    (голосование происходит при `status=upcoming`). Предстоящая половина ленты
    показывает: `upcoming` + `stage_2`. `cancelled` не отдаётся никогда;
    `completed` (и прошедшие `stage_2`) с итерации 5 попадают в ленту — но только
    как «История», при личной отметке явки (см. § «Итерация 5»).

---

## Цель

Сократить дистанцию пользователь ↔ событие до **одного клика от любого экрана**. Сейчас чтобы увидеть «куда я иду на этой неделе», пользователь должен сделать 3-4 клика: «Мои клубы» → выбрать клуб → «События» tab → пролистать. Если он состоит в 3+ клубах — это 3+ обходов. Тогда как **events — ядро ценности продукта** (PRD-Clubs.md): клубы — место, события — событие.

Цель — превратить «События» в **первую вкладку которую пользователь открывает каждый раз когда заходит в Mini App**. Если у него есть upcoming events — он сразу видит что и когда; если что-то требует ответа (vote/confirm) — он видит badge и может сразу действовать.

---

## Product rationale (почему именно так)

### Только member-events (privacy)

Лента показывает события **только из клубов где у пользователя есть доступ** (предикат `MembershipAccess`: `active` ИЛИ `cancelled` с неистёкшей подпиской; включая organizer-роль). НЕ показывает:
- События из клубов где пользователь только подал application (pending)
- Публичные события (этого слоя в продукте нет, см. `project_clubs_over_events_rationale.md` — мы намеренно clubs-first, не event-discovery)
- События закрытых клубов где пользователь не состоит

Это защищает приватность closed-club meetups (organizer контролирует кто видит расписание) и согласуется с `EventController.getClubEvents` membership check (закрыт через `@RequiresMembership` aspect — см. `docs/backlog/club-events-membership-check.md` ✅ RESOLVED). Реализация `/api/users/me/events` фильтрует по общему предикату доступа `MembershipAccess` на бэкенде (тот же, что у `isMember` / `@RequiresMembership` и event-created DM), не доверяя клиенту.

### Action-first сортировка

Не хронология сверху, а **«что требует моего ответа» сверху**. Пользователь зашёл в app — должен сразу видеть что он должен сделать. Хронология — следом.

Группы (в порядке отображения):
1. **🔥 Требует действия** (если есть): голосование открыто и пользователь не голосовал, ИЛИ stage_2 и пользователь не подтвердил/не отказался при vote=going/maybe
2. **📅 Эта неделя** (≤ 7 дней)
3. **📆 Позже** (> 7 дней, ≤ 30 дней)
4. **История** (итерация 5) — посещённые прошедшие события, последняя секция, недавние первыми (см. § «Итерация 5»)

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
- Past events — отдельный таб «Архив» _(итерация 5 закрыла сценарий иначе: секция «История» в той же ленте, таб «Архив» отвергнут PO — см. § «Итерация 5»)_
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

(`status` параметр так и не добавлен — прошедшие посещённые события с итерации 5 приезжают в той же ленте как «История», без новых параметров и endpoint'ов. R-2 закрыт.)

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
  participantLimit: number | null,   // null = открытая встреча (V62)

  actionRequired: boolean,      // computed на бэке, см. § «Action-required logic»

  isHistory: boolean            // итерация 5: прошедшее посещённое событие → секция «История»
}                               // (семантика и запреты клиенту — § «Итерация 5»)
```

**Errors:**
- `401` — не авторизован
- `5xx` — внутренняя ошибка

**Notes:**
- Сортировка (5 ключей, итерация 5): `historyBucket ASC → actionRequired DESC → upcomingSort ASC NULLS LAST → eventDatetime DESC → id ASC`. Читается так: предстоящие (требующие действия сверху, дальше хронологически) → история (недавние первыми). Точные выражения — § «Итерация 5» → «Сортировка»
- Предстоящая половина включает events из клубов где у user есть доступ по `MembershipAccess` (`active` или `cancelled` с неистёкшей подпиской; member ИЛИ organizer). Не включает: applications/pending, soft-deleted клубы
- Фильтр предстоящих на стороне БД: `status IN ('upcoming', 'stage_2') AND event_datetime > now() AND clubs.is_active = true`. Вторая половина ленты — «История» (личная отметка `attended`, `event_datetime <= now()`): состав, границы и обоснования — § «Итерация 5»
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
- **Stats-counter** — `12/30` числом (confirmedCount в `stage_2`, иначе goingCount / participantLimit; у открытой встречи V62 — без знаменателя), без слова. Под ним **progress bar** (доля участников, brass-заливка) — round 5

### Status-badge логика
Вычисление (`pickBadge`, по приоритету):
- Если `isHistory` — **без badge** (уточнение PO 2026-07-20: в «Историю» попадают только
  посещённые, бейдж дублировал бы заголовок секции; первая ветка, до всех прочих —
  иначе строка истории показала бы «Подтверждён», см. Решение 3)
- Иначе если `actionRequired` — акцентный badge: «Проголосуй» / «Подтверди участие»
- Иначе если `myParticipationStatus != null` — нейтральный: «Подтверждён» / «Лист ожидания» / «Отказался» / «Не подтвердил»
- Иначе если `myVote != null` — нейтральный: «Иду» / «Возможно» / «Не иду»
- Иначе — без badge

**Рендер на карточке** (факт, итерация 5): показываются только акцентные бейджи
(`badge.accent`). Нейтральные статусы вычисляются, но НЕ рендерятся — решение
о снижении визуальной плотности, итерацией 5 не пересматривалось.

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
4. **История** (итерация 5) — events где `isHistory == true`; ветка проверяется в `groupMyEvents` первой. Пустая — не рендерится.

Внутри секций 1–3 — сортировка по `eventDatetime ASC`; внутри «Истории» — `DESC` (недавние первыми). Порядок задаёт бэкенд, клиент раскладывает по секциям не пересортировывая.

---

## Empty states

> **[ОБНОВЛЕНО 2026-07-20, empty-states волны 1–3]** Таблица и «Имплементация» ниже —
> историческая v1. Актуальные пустые состояния таба «События» — лис-маскот `FoxEmpty`
> с роль-развилкой организатор/участник и отдельной error-сценой «Повторить»
> (PR #111, тексты на «ты») — см. `empty-states.md`. Компонент `FeedEmpty` **удалён**
> в волне 3 (последний импортёр SkladchinasTab мигрирован на FoxEmpty); цитаты старых
> текстов в AC-5/AC-6/AC-9 ниже — тоже исторические.

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
- TanStack Query staleTime по дефолту 30s (project default), invalidation после vote/confirm/decline mutations + markAttendance/resolveDispute (итерация 5, лента организатора — см. § «Итерация 5» → Решение 4)
- Query key: `queryKeys.events.myFeed`

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
В существующих мутациях (`useCastVoteMutation`, `useConfirmParticipationMutation`, `useDeclineParticipationMutation`) — расширить onSuccess: добавить invalidate `events.myFeed` + `events.byClubAll(clubId)`. Точечно, **не** `events.all` (см. Decision 9). Итерация 5 добавила invalidate `events.myFeed` также в `useMarkAttendanceMutation` / `useResolveDisputeMutation` (обновляется лента организатора — см. § «Итерация 5» → Решение 4).

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
  FeedEmpty.tsx             — УДАЛЁН 2026-07-20 (empty-states волна 3): заменён FoxEmpty.tsx
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

### R-2 (✅ CLOSED 2026-07-20): Past events visibility
Закрыт решением PO: секция «История» в той же ленте, состав — только события, где
пользователя **отметили пришедшим**. Отдельный таб «Архив» и `?status=past` отвергнуты.
Полная спецификация — § «Итерация 5: секция „История“» ниже.

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
- `docs/modules/club-page-unified.md` + `docs/modules/unified-activity-creation.md` — `ClubActivitiesTab` (бывший `ClubEventsTab`) показывает unified-feed одного клуба, эта фича — agg-версия только-events across clubs
- `docs/modules/haptic.md` — паттерны вибрации
- `docs/backlog/club-events-membership-check.md` — **обязательная** зависимость (security gap должен быть закрыт перед этой фичей)
- `docs/backlog/event-rsvp-buttons-broken.md` — RESOLVED в PR #30, основа для quick-actions если будем делать
- `project_clubs_over_events_rationale.md` — почему clubs-first и почему events НЕ публичны (privacy rationale для этой ленты)
- PRD-Clubs.md — product requirements

---

# Итерация 5: секция «История» в «Активности → События»

> **Status:** ✅ Реализовано (ветка `feature/events-history`, 2026-07-20; текст выровнен
> с фактическим кодом post-flight'ом той же даты). Закрывает R-2.
> Pre-flight сверка PRD ↔ код ↔ docs проведена, расхождения — в § «Pre-flight findings».

## Цель

Прошедшие встречи сейчас исчезают из продукта бесследно: `findMyFeed` жёстко режет
`status IN (upcoming, stage_2) AND event_datetime > now`
(`JooqEventRepository.kt:139-140`), а `GET /api/clubs/{id}/activities` отдаёт `past`
только внутри одного клуба и без пагинации (`ActivityService.kt:36,81-94`). Кросс-клубного
ответа на вопрос «куда я ходил» в продукте нет.

Даём его на том же экране, где человек и так живёт — во вкладке «События», по образцу
уже работающей «Истории» во вкладке «Сборы».

## Продуктовое решение PO (зафиксировано, не переоткрывается)

**В «Историю» попадают только события, где организатор отметил пользователя пришедшим** —
`event_responses.attendance = 'attended'` для этого пользователя.

Отвергнуто:
- «где голосовал» — голос ≠ факт посещения, лента засорилась бы событиями, куда человек не пошёл;
- «все прошедшие события моих клубов» — это расписание клуба, а не личная история.

**Осознанное следствие, принятое PO:** если организатор не отметил явку, событие в историю
**не попадёт никогда**. История — производная от дисциплины организатора, не от факта
физического присутствия. Это тот же контракт, на котором стоит вся репутация
(`JooqReputationRepository.kt:68`), — история и Trust будут рассказывать одну и ту же историю,
а не две разных.

## Scope

### Входит
- Расширение `GET /api/users/me/events`: к предстоящим добавляется история посещённых событий
- Новое поле `isHistory: boolean` в `MyEventListItemDto`
- Секция «История» в `EventsTab` — последняя, недавние первыми
- Правка `EventCard` под прошедшее событие (бейдж и мета)
- Правка триггера пустого состояния (сцена лиса не должна прятаться историей) — зеркало W3-03
- Одна миграция: частичный индекс под новый предикат

### НЕ входит
- Счётчик «сколько человек пришло» на карточке истории — нужен новый агрегат `attendedCount`,
  а честного числа сейчас нет (см. § «Отображение», решение 2). В бэклог.
- Фильтр «история по конкретному клубу»
- «Найти участников прошлого события» / networking-сценарий из старого R-2
- Отдельный таб «Архив», `?status=past`, cursor-пагинация
- Изменение доступа к `GET /api/events/{id}` (см. Риск RH-3)

---

## Решение 1: API-контракт — единая лента, не отдельный эндпоинт

**Выбрано: расширить `GET /api/users/me/events`.** Новых эндпоинтов нет, новых query-параметров нет.

### Разбор альтернатив

| | A. Единая лента (выбрано) | B. Отдельный `GET /api/users/me/events/history` |
|---|---|---|
| Frontend | один `useInfiniteQuery`, один `IntersectionObserver`, одна инвалидация | два запроса, два observer'а, координация «предстоящие исчерпаны → начинать историю» |
| Соответствие «как в Сборах» | точное — `/me/skladchinas` именно так и устроен | расходится с эталоном, который назвал PO |
| Конфликт сортировок | решается одним `ORDER BY` (см. ниже) | нет конфликта |
| Дублирование | нет | membership-предикат и маппинг в двух местах |
| Независимая пагинация истории | нет | есть |

**Ключевой аргумент.** Конфликт сортировок (предстоящие — по возрастанию, история — по
убыванию) выглядит как аргумент за B, но он **уже решён в проекте** — ровно в той ленте,
которую PO назвал эталоном. `JooqSkladchinaRepository.findMyFeed` (строки 173-206) держит
в одном `ORDER BY` три ключа: `statusBucket` (active=0 / closed=1) → `actionRequired` →
условный `activeSort` (deadline для active, NULL для closed) → `closed_at DESC` для истории.
Паттерн проверен в проде, ложится на jOOQ, требует `selectDistinct` с выносом выражений в
select list. Раз конфликт снимается пятью строками SQL, платить за него вторым эндпоинтом
и второй пагинацией на фронте — нарушение YAGNI.

**«История потенциально бесконечна»** — не подтверждается для выбранного состава. В историю
попадают только события с личной отметкой `attended`; активный участник посещает единицы
встреч в месяц, то есть десятки строк в год, а не тысячи. Плюс история сортируется **после**
всех предстоящих: пользователь доходит до неё только пролистав всё будущее, то есть хвост
подгружается лениво самой пагинацией.

**Принятая цена:** если у пользователя 25 предстоящих событий, на странице 0 (size=20)
истории не будет вообще — нужно доскроллить. Поведение идентично вкладке «Сборы», принимаем.

### Итоговый контракт

`GET /api/users/me/events` — сигнатура не меняется.

| Параметр | Тип | Дефолт | Ограничения |
|---|---|---|---|
| `page` | int | 0 | `coerceAtLeast(0)` |
| `size` | int | 20 | `coerceIn(1, MAX_PAGE_SIZE)` |

Ответ — прежний `PageResponse<MyEventListItemDto>`. `totalElements` теперь считает
объединение предстоящих и истории.

**`MyEventListItemDto` — единственное изменение:**
```ts
{
  ...,                    // все существующие поля без изменений
  isHistory: boolean      // НОВОЕ: true = прошедшее посещённое событие
}
```

`isHistory` вычисляет **бэкенд** — по тому же принципу, что и существующий `actionRequired`:
бакет уже посчитан для `ORDER BY`, клиент его не переводдит. Клиенту **запрещено**
выводить принадлежность к истории из `status === 'completed'` или из `eventDatetime < now`:
`EventCompletionService` переводит событие в `completed` не сразу, а по крону раз в час с
запасом 6 часов (`COMPLETION_GRACE_HOURS`), так что окно до ~7 часов, где явка уже
отмечена, а статус ещё `stage_2`, реально существует.

### Состав ленты (два непересекающихся набора)

**(A) Предстоящие — без изменений:**
```
events.status IN ('upcoming','stage_2')
AND events.event_datetime > now()
AND clubs.is_active = true
AND memberships.user_id = :callerId
AND MembershipAccess.hasAccess()            -- memberships.status = 'active'
```

**(B) История — новое:**
```
event_responses.user_id  = :callerId
AND event_responses.attendance = 'attended'
AND events.event_datetime <= now()          -- дизъюнктность с (A) по построению, см. ниже
AND clubs.is_active = true
AND events.status <> 'cancelled'
-- JOIN с memberships НЕТ (обоснование — Решение 2в)
```

**Дизъюнктность наборов — по построению**, через `event_datetime` (A: `> now`, B: `<= now`),
а не через инвариант AttendanceService «явку нельзя отметить до старта события»: если бы бакеты
делились только явкой, появление переноса события (отметили → перенесли в будущее) отдало бы
экс-участнику **будущее** событие клуба, из которого он исключён. Явное `<= now` закрывает это
классом условий, а не поведением соседнего сервиса.

**Как реализовано:** один запрос — `clubs.is_active AND (A OR B)`, `memberships` и
`event_responses` подключены LEFT JOIN'ами с `user_id` в ON-условии (в WHERE он деградировал бы
LEFT до INNER и убил историю без активного членства). Переписывание на буквальный UNION ALL
отложено до роста данных — `docs/backlog/refactoring-candidates.md` **RF-7**.

### Сортировка

```
ORDER BY
  history_bucket ASC,        -- 0 = предстоящие, 1 = история
  action_required DESC,      -- внутри предстоящих (существующее выражение)
  upcoming_sort  ASC NULLS LAST,   -- CASE WHEN bucket=0 THEN event_datetime END
  event_datetime DESC,             -- история: недавние первыми
  events.id ASC                    -- тай-брейк: ни один ключ выше не уникален; без него
                                   -- offset-пагинация могла бы менять местами события
                                   -- с одинаковым datetime между страницами
```

Порядок массива в ответе — контракт. Клиент раскладывает по секциям, сохраняя порядок,
и **не пересортировывает**.

---

## Решение 2: краевые случаи

### 2а. `disputed` — в историю НЕ попадает

**Рекомендация: исключать. Отдельного правила не требуется — фильтр `attendance = 'attended'`
уже даёт нужное поведение.**

Обоснование по коду:
- `disputed` достижим **только** из `absent`: `disputeAbsentAttendance` требует
  `attendance = 'absent'` (`JooqEventResponseRepository.kt:277`), `canDispute` в
  `AttendanceService.kt:106-108` — то же. То есть `disputed` = «организатор сказал „не был“,
  участник возражает», а не признанное посещение.
- Если организатор разрешает спор в пользу участника — `resolveDisputedAttendance` пишет
  `attended` (`JooqEventResponseRepository.kt:291`), и событие появляется в истории при
  следующем запросе. Самоисцеляется.
- Если спор дожил до финализации нерешённым — ATT-2 возвращает строку в `absent`
  (`EventResponseRepository.kt:97`), и событие не появится. Fail-closed.

Показывать спорную отметку как «ты был» означало бы отдать участнику право самому себе
записать посещение обходом организатора. Ноль строк кода, ноль спорных состояний в UI.

### 2б. Событие отменено после того, как явка была отмечена — в историю НЕ попадает

**Рекомендация: исключать через `events.status <> 'cancelled'`.**

Случай реален, не гипотетичен: `cancelActiveEventsByClub` (`JooqEventRepository.kt:329-335`)
переводит в `cancelled` события в статусе `stage_2`, и в самом коде рядом стоит комментарий,
что у `stage_2` явка **может быть уже отмечена**. Путь — удаление клуба.

Обоснование:
1. Отменённое событие не состоялось. «Ты был» на нём — фактическая неправда в лицо пользователю.
2. Консистентность с репутацией: `JooqReputationRepository.kt:68`, `countPastEvents`
   (`JooqEventRepository.kt:304`) и весь club-quality (`JooqClubQualityRepository.kt:64`,
   `JooqClubRankRepository.kt:124`) уже исключают `cancelled`. Если ledger это посещение не
   засчитал, история не должна его показывать — иначе пользователь увидит в истории встречу,
   которой нет в его Trust, и это выглядит как баг системы.

### 2в. Выход / исключение из клуба — история СОХРАНЯЕТСЯ

**Рекомендация: история НЕ гейтится `MembershipAccess`. Это осознанное отклонение от
предиката доступа, и его надо зафиксировать явно.**

Четыре независимых аргумента:

1. **Эталон, названный PO, уже так работает.** `JooqSkladchinaRepository.findMyFeed:153-156`
   строит `baseCondition` из `clubs.is_active` + вовлечённость (`participant.user_id = me OR
   creator_id = me`) — **membership-предиката там нет**. Закрытые сборы клубов, из которых
   человек вышел, остаются в его «Истории». «Как в Сборах» = так же.
2. **Продукт это уже пообещал.** `MyClubsPage` имеет секцию «История» покинутых клубов
   (`MyReputationDto.historyClubs`, PRD:190), а empty-states W3-02 прямым текстом пишет
   пользователю: «Твоя история и репутация сохранились» (`empty-states.md:120`). Исчезновение
   посещённых встреч при выходе сделало бы это обещание ложным.
3. **Прецедент на уровне эндпоинта уже есть.** `getMyAttendance` намеренно **без**
   `@RequiresMembership` с комментарием в коде: «участнику, вышедшему из клуба, всё ещё нужен
   доступ к UI спора» (`EventController.kt:113-116`).
4. **Приватность не нарушается.** `MembershipAccess` защищает **будущее расписание** клуба —
   кто вправе видеть, что клуб планирует. К встрече, на которой человек лично присутствовал и
   которую ему лично отметили, это обоснование неприменимо. Строка выдаётся строго по
   `event_responses.user_id = :callerId` — это собственный факт пользователя, а не контент клуба.

**Граница, которая остаётся:** `clubs.is_active = false` (soft-deleted клуб) исключает
события и из истории тоже — как в Сборах. Половина (A), предстоящие, `MembershipAccess`
сохраняет без изменений.

**Побочный эффект, который надо признать:** `frozen` / `expired` участник теряет предстоящую
ленту, но продолжает видеть свою историю. Это правильно: заморозка — отсечение от контента
вперёд, а не стирание прошлого.

---

## Решение 3: отображение

### Секция
- Заголовок **«История»**, счётчик — существующий `FeedSection` с `count`, `accent={false}`.
  Ровно как `groupSkladchinas` (`SkladchinasTab.tsx:34`).
- Позиция — **последняя**: «Требует действия» → «Эта неделя» → «Позже» → «История».
- Внутри — недавние первыми (порядок задан бэкендом, клиент сохраняет).
- Пустая секция не рендерится.

`groupMyEvents` (`utils/feedGrouping.ts`) получает четвёртый ключ `history`; ветка
`isHistory` проверяется **первой**, до `actionRequired` и до недельного горизонта.

### Карточка — компактная строка `HistoryCard` (решение PO 2026-07-21, «вариант B»)

История рендерится НЕ полноразмерным `EventCard`, а компактной строкой — отдельный
презентационный компонент `components/feed/HistoryCard.tsx` (props: `dateISO`, `title`,
`subtitle`, `onClick`; CSS-секция `.rd-hist-*` в redesign.css). Раскладка: слева
дата-плитка (число 16px/800 + месяц в родительном падеже 10px uppercase, вертикальный
hairline), затем название (14px/600, ellipsis) и подстрока 11.5px (ellipsis). Высота
~56px против ~170px полной карточки. Мокап: `docs/design/events-history/mockups/
history-visual-variants.html`, вариант B (вариант A «рамка» и C «рамка+компакт»
отвергнуты: рамка дублирует сигнал компактной формы, штриховая обводка чужеродна
Banco-Plata, «закрытая» рамка врёт при постраничной подгрузке).

Мотив компактности — не только эстетика: прошедшее не должно конкурировать за внимание
с предстоящим, а обложка/бейджи/счётчики на прошедшем либо врут («N идут», «Подтверждён» —
у каждой строки истории `myParticipationStatus === 'confirmed'` по конструкции
`setAttendance`), либо пусты. У компактной строки их нет по построению; честный
`attendedCount` — в бэклоге (см. Scope).

- **События**: subtitle = `клуб · locationText` (при null-локации — только клуб).
- **Сборы** (`SkladchinasTab`, история): тот же `HistoryCard`; dateISO = deadline,
  subtitle = `клуб · <финальный статус>` («Завершён» / «Не собран» / «Отменён» —
  тексты из `pickBadge` SkladchinaCard). Единый визуальный язык истории в обоих табах.
- `EventCard` историю больше не рендерит — его `isHistory`-ветки удалены как мёртвые;
  инвариант «история → только HistoryCard» закреплён комментарием в EventsTab.

Тап по строке истории ведёт на `/events/:id` (событие) / `/skladchina/:id` (сбор) —
как у активных, отдельного вида страницы нет.

### Пустые состояния — зеркало W3-03

Сейчас `EventsTab.tsx:66` считает пустоту как `events.length === 0`, и одно старое посещённое
событие навсегда спрячет и лиса, и CTA «Создать событие». Это ровно тот дефект, который PO уже
исправил для Сборов 2026-07-20 (`empty-states.md:137-142`).

- Триггер сцены: **`events.some(e => !e.isHistory) === false`** — нет предстоящих.
- «История» рендерится **под сценой**, не вместо неё.
- `soonIcon="📅"` — только в **организаторской** сцене и только при пустой истории (при
  непустой тизер «скоро здесь» дублирует смысл). Участническая сцена — исторически без
  тизера, так и осталась.
- Заголовки обеих сцен: **«Событий пока нет»** (история пуста) / **«Предстоящих событий
  нет»** (история есть) — заменили роль-специфичные заголовки PR #111 («Пора запланировать
  встречу» / «Пока нет событий»); описания остались ролевыми.
- Роль-развилка (организатор / участник), описания, error-сцена, гейт `isEmptySceneResolving`
  на время загрузки роли — **без изменений**.

Обновить `empty-states.md`: добавить пункт W3-03a для `EventsTab` со ссылкой на этот раздел.

---

## Решение 4: пагинация на фронте

**Без изменений.** Один `useInfiniteQuery` (`queries/events.ts:72`), один `IntersectionObserver`
с `rootMargin: '200px'` (`EventsTab.tsx:33-47`), одна кнопка-сентинел. История приезжает теми
же страницами, что и предстоящие, — прямое следствие Решения 1. Ни «Показать ещё», ни второй
подгрузки не появляется.

Инвалидация `queryKeys.events.myFeed` — прежняя. Дополнительно `myFeed` инвалидируется в
`useMarkAttendanceMutation` / `useResolveDisputeMutation` — единственных мутациях, добавляющих
строку в историю. Обе зовёт **организатор**, поэтому инвалидация срабатывает в его клиенте и
обновляет **его** ленту (организатор тоже участник событий — если явку отметили и ему, строка
появится в его «Истории»). До устройства отмеченного участника инвалидация не долетает (пуша
нет) — свою «Историю» он увидит при следующем маунте ленты / истечении staleTime (30s).

---

## Acceptance Criteria

### AC-H1: История отображается
**GIVEN** пользователь имеет 2 предстоящих события и 3 события, где его отметили `attended`
**WHEN** открывает «Активности → События»
**THEN** видит секции «Эта неделя»/«Позже» с 2 событиями, а под ними секцию «История» со счётчиком 3
**AND** внутри «Истории» события идут от самого недавнего к самому давнему

### AC-H2: Только отмеченная явка
**GIVEN** пользователь голосовал `going` на прошедшем событии X, но организатор явку не отмечал
(`attendance IS NULL`), и был отмечен `attended` на событии Y
**WHEN** открывает «События»
**THEN** в «Истории» есть Y и **нет** X

### AC-H3 (негатив): отмечен отсутствующим
**GIVEN** пользователя отметили `absent` на прошедшем событии
**THEN** события нет в «Истории»

### AC-H4 (негатив): спорная отметка
**GIVEN** пользователя отметили `absent`, он оспорил (`attendance = 'disputed'`), спор не решён
**THEN** события нет в «Истории»
**WHEN** организатор разрешает спор в пользу участника (`attendance → 'attended'`) и лента обновляется
**THEN** событие появляется в «Истории»

### AC-H5 (негатив): отменённое событие
**GIVEN** пользователя отметили `attended`, после чего событие перешло в `cancelled`
(например, клуб был удалён)
**THEN** события нет в «Истории»

### AC-H6: история переживает выход из клуба
**GIVEN** пользователь посетил 2 события клуба K (`attended`), затем вышел из K
(или был исключён — `membership` удалён / не `active`)
**WHEN** открывает «События»
**THEN** оба события клуба K остаются в «Истории»
**AND** предстоящие события клуба K в ленте отсутствуют

### AC-H7 (негатив): soft-deleted клуб
**GIVEN** пользователь посетил событие клуба K, клуб K soft-deleted (`is_active = false`)
**THEN** события нет ни в «Истории», ни в предстоящих

### AC-H8 (негатив, приватность): чужая история
**GIVEN** пользователь A отмечен `attended` на событии E, пользователь B — нет
**WHEN** B открывает «События»
**THEN** E отсутствует в ленте B
**AND** ответ `GET /api/users/me/events` для B не содержит `id` события E

### AC-H9: сортировка бакетов
**GIVEN** есть предстоящее событие завтра и посещённое вчера
**WHEN** запрашивается страница 0
**THEN** в `content` предстоящее идёт **раньше** посещённого, независимо от абсолютных дат

### AC-H10: пагинация
**GIVEN** у пользователя 5 предстоящих и 30 посещённых событий, `size=20`
**WHEN** он скроллит до низа дважды
**THEN** `totalElements = 35`, подгружается страница 1, новые события попадают в «Историю»,
а не сваливаются в конец списка отдельным блоком

### AC-H11: пустое состояние при непустой истории
**GIVEN** предстоящих событий нет, в «Истории» 3 события
**WHEN** пользователь открывает «События»
**THEN** видит сцену лиса «Предстоящих событий нет» (с CTA по роли) **И** секцию «История» под ней
**AND** тизер `soonIcon` не рендерится

### AC-H12: пустое состояние при пустой истории
**GIVEN** ни предстоящих, ни истории
**THEN** сцена «Событий пока нет» (в организаторской ветке — с `soonIcon="📅"`; участническая
без тизера, как раньше), секции «История» нет

### AC-H13: карточка прошедшего события
**GIVEN** событие в «Истории»
**THEN** рендерится компактной строкой `HistoryCard` (дата-плитка · название · клуб · место),
не полноразмерной карточкой с обложкой (решение PO 2026-07-21, вариант B)
**AND** бейджей нет вовсе («Подтверждён» в т.ч.; уточнение PO 2026-07-20: секция и так
означает «ты был», первоначальный нейтральный бейдж отменён)
**AND** счётчика «N идут» нет
**WHEN** тап по строке
**THEN** light haptic и переход на `/events/:id`

### AC-H14: свежая отметка явки
**GIVEN** пользователь на вкладке «События», организатор только что отметил его `attended`
на событии, прошедшем 2 часа назад (статус ещё `stage_2`, крон завершения не отработал)
**WHEN** лента перезапрашивается
**THEN** событие в секции «История», а не в «Эта неделя»

---

## Non-functional

### Индексы и производительность
- Существующий `idx_event_responses_user_id` (`V9:22`) под новый предикат **недостаточно
  селективен**: он вытянет все отклики пользователя (включая всё, где явка не отмечена) и
  отфильтрует `attendance` уже после выборки.
- **Нужна одна миграция** — частичный индекс ровно под предикат:
  ```sql
  CREATE INDEX IF NOT EXISTS idx_event_responses_user_attended
      ON event_responses (user_id)
      WHERE attendance = 'attended';
  COMMENT ON INDEX idx_event_responses_user_attended IS
      'Частичный индекс под секцию «История» ленты /me/events: отклики с подтверждённой явкой.';
  ```
  Уточнение к постановке задачи: новых **таблиц и колонок** фича действительно не требует —
  всё нужное есть с V6/V24. Но индексная миграция нужна. Факт:
  `V61__index_event_responses_user_attended.sql`.
- Цель: `< 300ms` p95 для пользователя с 10 клубами, 50 предстоящими и 200 посещёнными событиями.
- `countDistinct` для `totalElements` считается по объединению — проверить, что не появляется
  второй полный скан на каждый запрос страницы.

### Безопасность
- История скоупится **только** по `event_responses.user_id = :callerId`. Никогда по событию,
  никогда по клубу — иначе утечёт чужой ростер.
- Предстоящая половина ленты сохраняет `MembershipAccess.hasAccess()` без изменений.
- `clubs.is_active = true` применяется к обеим половинам.
- Интеграционный тест на AC-H8 обязателен: это единственная защита от утечки списка посещений.

### Логирование
- Расширить существующий INFO в `UserEventsService.getMyEvents`: добавить `historyCount`
  (сколько строк в странице — история). Уровень прежний, PII не логируется.

### Кеширование
- `queryKeys.events.myFeed` — без изменений, staleTime по дефолту 30s.

---

## Интеграции

| С чем | Направление | Что важно |
|---|---|---|
| `attendance.md` / `AttendanceService` | читаем | `attendance = 'attended'` — единственный источник истории; переходы `disputed → attended/absent` меняют её состав |
| `EventCompletionService` | читаем | статус `completed` выставляется кроном с лагом до ~7ч — на `isHistory` не влияет (бакет считается по явке, не по статусу) |
| `empty-states.md` W3-03 | зеркалим | тот же триггер «нет активных», та же логика `soonIcon` |
| `reputation-v2.md` | согласованность | тот же набор фактов (`attended` + не `cancelled`), что питает Trust — история и репутация не должны расходиться |
| `club-page-unified.md` (`/api/clubs/{id}/activities`) | не трогаем | клубный `past` остаётся как есть: он про расписание клуба, а не про личную явку |

---

## Риски

### RH-1 (Medium): история пуста у большинства пользователей
Если организаторы не отмечают явку, секция «История» не появится ни у кого, и фича будет
выглядеть нерабочей. Это принятое PO следствие состава, но его стоит измерить: доля событий
с `attendance_marked = true` — метрика успеха этой фичи. При низкой доле лечится не сменой
состава, а давлением на организатора (напоминание об отметке уже есть —
`attendance_reminder_sent`, V21).

### RH-2 (Low): смещение пагинации при параллельной отметке явки
Если организатор отмечает явку между запросами страниц, состав ленты меняется и строка может
продублироваться или пропасть на границе страниц. Классическая проблема offset-пагинации, уже
существующая в этой ленте (и в Сборах) для предстоящих событий. Не лечим — cursor-пагинация
отложена Decision 8.

### RH-3 (Medium, pre-existing, НЕ вводится этой фичей): `GET /api/events/{id}` без гейта
`EventController.kt:55` — `getEvent` не имеет `@RequiresMembership`. Поэтому тап по карточке
истории после выхода из клуба сработает. Это удобно для нашего сценария, но сам факт открытого
эндпоинта — существующий пробел доступа, не созданный этой итерацией. Разбирать отдельно;
здесь фиксируем, что фича на него **опирается**, и при закрытии пробела понадобится явное
исключение для «я посещал это событие».

### RH-5 (Low, pre-existing, найден post-flight'ом 2026-07-20): выход из клуба в окне «явка отмечена, событие ещё stage_2»
Каскад выхода (`deleteByUserAndClubAndActiveEvents`, `JooqEventResponseRepository.kt:350`)
удаляет отклики на событиях `status IN (upcoming, stage_1, stage_2)` с
`attendance_finalized = false`. Прошедшее событие держит `stage_2` до крона завершения
(≤ ~7 ч), а финализация явки происходит ещё позже — поэтому участник, вышедший из клуба в
этом окне, теряет свою свежую `attended`-строку: посещение не попадёт **ни** в Trust
(репутация читает только `attendance_finalized = true`), **ни** в «Историю». Инвариант
«история и Trust рассказывают одну историю» сохраняется (теряют обе), но AC-H6 «история
переживает выход» в этом узком окне не выполняется — по построению каскада, существовавшему
до фичи. Окно узкое (часы), сценарий маргинальный (выйти из клуба сразу после посещённой
встречи); лечение — исключить из каскада строки с `attendance IS NOT NULL` — трогает
контракт репутационного пайплайна, отдельное решение, не в этой итерации.

### RH-4 (Low): рост `totalElements` ломает интуицию счётчика
`totalElements` теперь включает историю, и число может выглядеть неожиданно большим. На UI
`totalElements` не показывается (счётчики берутся из длины секций), так что влияния нет —
но если появится «бейдж с числом событий», он должен считать предстоящие, а не `totalElements`.

---

## Pre-flight findings (сверка PRD ↔ код ↔ docs, 2026-07-20)

### F-1 [→ User, требует решения]: PRD обещает «был / не был», PO решил «только был»
`PRD-Clubs.md:201` (§4.3.2 Профиль участника): «История посещений (список событий с отметками:
**был / не был**)». Решение PO для этой фичи — **только `attended`**.

Формально это не прямое противоречие (PRD описывает историю в **профиле участника**, фича — в
глобальной ленте «События»), но два разных «списка посещений» с разным составом в одном
продукте — заготовка для расхождения. PRD-строка не реализована нигде.

Рекомендация: оставить PRD как есть, а расхождение зафиксировать в бэклоге как открытый вопрос
«единый источник истории посещений». **Правку PRD не делаю — нужно согласие PO.**

### F-2 [spec updated]: R-2 в `events-feed.md` висел открытым
Старый R-2 предлагал `?status=past` + отдельный таб «Архив». Решение PO это отвергает.
R-2 помечен CLOSED со ссылкой на этот раздел.

### F-3 [✅ закрыт post-flight'ом 2026-07-20]: `events-feed.md` Decision 10 устарел частично
Decision 10 гласил: «`completed`/`cancelled` отфильтровываются на бэке». После этой итерации
`completed` частично проходит (когда есть личная `attended`), `cancelled` — по-прежнему нет.
Формулировка Decision 10 обновлена в post-flight alignment (см. Decision 10 выше).

### F-4 [ОК, расхождений нет]
- `attendance_status ('attended','absent','disputed')` — V6, комментарий V44:131. Совпадает
  с постановкой задачи.
- `disputed` достижим только из `absent` — подтверждено в трёх местах кода.
- Новых таблиц/колонок не требуется — подтверждено. **Кроме индексной миграции** (см. Non-functional).
- Лента Сборов не гейтится membership — подтверждено (`JooqSkladchinaRepository.kt:153-156`).
- Индекса `event_responses(user_id, attendance)` нет — подтверждено (V9, V20 — единственные
  индексы на таблице).

---

## Файлы для изменения

| Файл | Что |
|---|---|
| `backend/src/main/resources/db/migration/V61__index_event_responses_user_attended.sql` | новый частичный индекс |
| `backend/src/main/kotlin/com/clubs/event/JooqEventRepository.kt` | `findMyFeed` — объединение предстоящих и истории (OR-предикаты, LEFT JOIN), новый `ORDER BY` с `historyBucket` и тай-брейком `id` |
| `backend/src/main/kotlin/com/clubs/event/MyFeedItem.kt` | `MyFeedItem` — поле `isHistory` |
| `backend/src/main/kotlin/com/clubs/event/EventMapper.kt` | `toMyFeedItemDto` — проброс `isHistory` |
| `backend/src/main/kotlin/com/clubs/event/EventDto.kt` (`MyEventListItemDto`) | поле `isHistory` |
| `backend/src/main/kotlin/com/clubs/event/UserEventsService.kt` | лог `historyCount` |
| `frontend/src/types/api.ts` | `MyEventListItemDto.isHistory` |
| `frontend/src/utils/feedGrouping.ts` | секция `history`, ветка `isHistory` первой |
| `frontend/src/components/feed/EventCard.tsx` | бейдж «Ты был», мета без счётчика |
| `frontend/src/components/activities/EventsTab.tsx` | триггер пустого состояния по `!isHistory`, тексты, `soonIcon` |
| `frontend/src/queries/events.ts` | инвалидация `myFeed` после `markAttendance` / `resolveDispute` |
| `docs/modules/empty-states.md` | пункт W3-03a про `EventsTab` |
