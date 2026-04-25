# Module: Frontend State

> **Note:** файл переименован концептуально из `Frontend Stores` (Zustand-only) в `Frontend State`
> (Server state + Client state). Имя файла оставлено `frontend-stores.md` чтобы не ломать
> входящие ссылки из `haptic.md`, `frontend-core.md`, `docs/design/stack.md`.
>
> История изменения: см. раздел «Migration: feature/pre-design-stores» ниже —
> там зафиксирован legacy-API сторов (`useClubsStore` / `useEventsStore` /
> `useApplicationsStore`) и причина миграции.

---

## Главное правило

**Server state ≠ Client state.** Это два разных инструмента:

| Тип | Инструмент | Примеры в проекте |
|---|---|---|
| Server state | TanStack Query (`@tanstack/react-query`) | Список клубов, мои клубы, события клуба, мои заявки, members, applications |
| Client state | Zustand v5 | JWT-токен, `user`, `isAuthenticated` |
| Локальное UI | `useState` | Открыт ли модал, выбранный таб, форма wizard'а |
| Форма | React Hook Form (после PR #4) | CreateClubModal, OrganizerClubManage editors |

Источник правила: `.claude/rules/frontend.md` § «State management».

Главный анти-паттерн (явно запрещён правилами): **фетчить с API → класть в Zustand → синхронизировать руками.** Получается stale data, гонки, ручные `loading/error` флаги. До PR `feature/pre-design-stores` весь server state в проекте был именно так.

---

## Server state — TanStack Query

### QueryClient

Один экземпляр `QueryClient` создаётся в `frontend/src/main.tsx` и оборачивается `<QueryClientProvider>` (внутри `<ErrorBoundary>`, снаружи `<AppRoot>`). Дефолты — определены в TASK-PRE-002 (PR #22) и **не меняются** в этой миграции:

```ts
queries: {
  staleTime: 30_000,            // 30s — переключение табов не триггерит refetch
  gcTime: 5 * 60_000,           // 5 min — cache живёт между навигациями
  retry: 1,                     // 1 ретрай на network blip; 401 ловит apiClient
  refetchOnWindowFocus: false,  // Mini App не теряет фокус как браузер
}
```

Дефолты `mutations` не задаются — у каждой мутации свой `onSuccess` для invalidate.

> **Note:** `staleTime: 30s` означает что сразу после mount страница, открытая в кеше, рендерит cached data **без** refetch. Для invalidation после мутации это не помеха — `invalidateQueries` помечает кеш как stale и триггерит refetch следующего consumer'а.

### Расположение хуков

`frontend/src/queries/` — отдельная директория для query/mutation-хуков. По одному файлу на доменный API-модуль:

```
frontend/src/queries/
  clubs.ts          — useClubsQuery, useMyClubsQuery, useClubQuery, useClubByInviteQuery
                      + useCreateClubMutation, useUpdateClubMutation, useDeleteClubMutation
                      + useJoinClubMutation, useApplyToClubMutation
  events.ts         — useClubEventsQuery, useEventQuery
                      + useCastVoteMutation, useConfirmParticipationMutation,
                        useDeclineParticipationMutation, useCreateEventMutation,
                        useMarkAttendanceMutation
  applications.ts   — useMyApplicationsQuery
                      + useApproveApplicationMutation, useRejectApplicationMutation
  members.ts        — useClubMembersQuery, useMemberProfileQuery
  finances.ts       — useClubFinancesQuery
  queryKeys.ts      — централизованная фабрика query keys (см. ниже)
```

**Почему `queries/`, а не `hooks/`:** `hooks/` уже занят cross-cutting утилитами (`useHaptic`, `useBackButton`). Server-state-хуки — отдельный класс, и группировать их рядом с UI-хуками = смешивать ответственности. Когда в будущем фронт реорганизуется в `features/<name>/hooks/` (см. `docs/design/stack.md §21`), `queries/` мигрирует туда **по одной фиче**, а `hooks/` останется в `shared/hooks/`. Папка `queries/` — мост к features-структуре, а не самоцель.

### Query keys — конвенция

Все ключи строятся через фабрику в `queries/queryKeys.ts`:

```ts
export const queryKeys = {
  clubs: {
    all: ['clubs'] as const,
    list: (filters: ClubFilters) => ['clubs', 'list', filters] as const,
    my: () => ['clubs', 'my'] as const,
    detail: (id: string) => ['clubs', 'detail', id] as const,
    byInvite: (code: string) => ['clubs', 'invite', code] as const,
    members: (clubId: string) => ['clubs', 'detail', clubId, 'members'] as const,
    memberProfile: (clubId: string, userId: string) =>
      ['clubs', 'detail', clubId, 'members', userId] as const,
    applications: (clubId: string, status?: string) =>
      ['clubs', 'detail', clubId, 'applications', status ?? 'all'] as const,
    finances: (clubId: string) => ['clubs', 'detail', clubId, 'finances'] as const,
  },
  events: {
    all: ['events'] as const,
    byClub: (clubId: string, params?: EventListParams) =>
      ['events', 'by-club', clubId, params ?? {}] as const,
    byClubAll: (clubId: string) => ['events', 'by-club', clubId] as const,   // prefix для invalidate всех вариантов byClub
    detail: (id: string) => ['events', 'detail', id] as const,
    myVote: (id: string) => ['events', 'detail', id, 'my-vote'] as const,
  },
  applications: {
    mine: () => ['applications', 'mine'] as const,
  },
} as const;
```

**Правила:**
- Префикс — домен (`clubs` / `events` / `applications`)
- Подпрефикс — тип (`list` / `my` / `detail` / `by-club`)
- Хвост — параметры запроса (id, filters, params)
- Per-club вложенные данные сидят под `['clubs', 'detail', clubId, ...]` — это **намеренно**, чтобы один `invalidateQueries({ queryKey: ['clubs', 'detail', clubId] })` после `updateClub` сбрасывал и members, и applications, и finances одновременно. Если такая каскадная инвалидация не нужна — выноси на верхний уровень.
- Объекты-параметры передаются как есть (TanStack делает structural sharing, ссылочное равенство не требуется)

### Query-хуки — список и сигнатуры

| Хук | Query key | Источник данных | Используется в |
|---|---|---|---|
| `useClubsQuery(filters)` | `queryKeys.clubs.list(filters)` | `getClubs` | DiscoveryPage |
| `useMyClubsQuery()` | `queryKeys.clubs.my()` | `getMyClubs` | MyClubsPage, ProfilePage, ClubPage (после `feature/restructure-bottom-tabs` — OrganizerPage удалён, его консьюмеры слиты в MyClubsPage) |
| `useClubQuery(clubId, opts?)` | `queryKeys.clubs.detail(clubId)` | `getClub` | ClubPage (unified), OrganizerClubManage, MyClubsPage (для дозагрузки названий клубов и заявок), CreateClubModal post-create |
| `useClubByInviteQuery(code)` | `queryKeys.clubs.byInvite(code)` | `getClubByInvite` | InvitePage |
| `useClubEventsQuery(clubId, params?)` | `queryKeys.events.byClub(clubId, params)` | `getClubEvents` | components/club/ClubEventsTab, OrganizerClubManage |
| `useEventQuery(eventId)` | `queryKeys.events.detail(eventId)` | `getEvent` | EventPage, OrganizerClubManage (attendance modal) |
| `useMyVoteQuery(eventId, enabled?)` | `queryKeys.events.myVote(eventId)` | `getMyVote` | EventPage |
| `useMyApplicationsQuery()` | `queryKeys.applications.mine()` | `getMyApplications` | ClubPage, MyClubsPage, ProfilePage |
| `useClubMembersQuery(clubId)` | `queryKeys.clubs.members(clubId)` | `getClubMembers` | components/club/ClubMembersTab, OrganizerClubManage |
| `useMemberProfileQuery(clubId, userId)` | `queryKeys.clubs.memberProfile(clubId, userId)` | `getMemberProfile` | components/club/ClubProfileTab, OrganizerClubManage |
| `useClubApplicationsQuery(clubId, status?)` | `queryKeys.clubs.applications(clubId, status)` | `getClubApplications` | OrganizerClubManage |
| `useClubFinancesQuery(clubId)` | `queryKeys.clubs.finances(clubId)` | `getFinances` | OrganizerClubManage |

**Сигнатура (каноническая):**
```ts
export function useClubQuery(clubId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.clubs.detail(clubId!),
    queryFn: () => getClub(clubId!),
    enabled: Boolean(clubId),
  });
}
```

`enabled` обязателен везде где параметр — путь URL (может быть `undefined` до того как `useParams` зарезолвится).

#### Особый случай: `useClubsQuery` — `useInfiniteQuery`

DiscoveryPage реализует infinite scroll (`page === 0 ? content : [...prev, ...content]` в legacy-сторе). Под это используется `useInfiniteQuery`:

```ts
export function useClubsQuery(filters: Omit<ClubFilters, 'page'>) {
  return useInfiniteQuery({
    queryKey: queryKeys.clubs.list(filters),
    queryFn: ({ pageParam }) => getClubs({ ...filters, page: String(pageParam) }),
    initialPageParam: 0,
    getNextPageParam: (lastPage, _all, lastParam) =>
      lastParam + 1 < lastPage.totalPages ? lastParam + 1 : undefined,
  });
}
```

Consumer (`DiscoveryPage`) получает `data.pages` (массив `PageResponse<ClubListItemDto>`) и flatten'ит сам или через `select`:
```ts
const { data, fetchNextPage, hasNextPage, isFetching } = useClubsQuery(filters);
const clubs = data?.pages.flatMap(p => p.content) ?? [];
```

`fetchNextPage()` заменяет старый `fetchClubs({ page: '1' })`.

### Mutation-хуки — список и инвалидация

Каждая мутация определяет invalidation pattern в `onSuccess`. Если мутация трогает несколько кэшей — все перечислены.

| Мутация | API call | Invalidates |
|---|---|---|
| `useJoinClubMutation()` | `joinClub(clubId)` | `queryKeys.clubs.detail(clubId)`, `queryKeys.clubs.my()` |
| `useApplyToClubMutation()` | `applyToClub(clubId, answer)` | `queryKeys.applications.mine()`, `queryKeys.clubs.detail(clubId)` |
| `useJoinByInviteMutation()` | `joinByInviteCode(code)` | `queryKeys.clubs.my()` |
| `useCreateClubMutation()` | `createClub(body)` | `queryKeys.clubs.all`, `queryKeys.clubs.my()` |
| `useUpdateClubMutation()` | `updateClub(id, body)` | `queryKeys.clubs.detail(id)`, `queryKeys.clubs.all` (список устарел) |
| `useDeleteClubMutation()` | `deleteClub(id)` | `queryKeys.clubs.all`, `queryKeys.clubs.my()` |
| `useApproveApplicationMutation()` | `approveApplication(appId)` | `queryKeys.clubs.applications(clubId, 'all')` + `queryKeys.clubs.applications(clubId, 'pending')` (явная инвалидация конкретной табы organizer'а) + `queryKeys.applications.mine()` + `queryKeys.clubs.members(clubId)` (approve добавляет участника → members list устаревает). `clubId` передаётся mutate-аргументом, см. `ApproveApplicationArgs`. |
| `useRejectApplicationMutation()` | `rejectApplication(appId, reason?)` | `queryKeys.clubs.applications(clubId, 'all')` + `queryKeys.clubs.applications(clubId, 'pending')` + `queryKeys.applications.mine()` (members не трогается — заявка отклонена, новых участников нет) |
| `useCastVoteMutation()` | `castVote(eventId, vote)` | `queryKeys.events.detail(eventId)` + `queryKeys.events.myVote(eventId)` (явно — myVote ключ это prefix-extension от detail и формально покрыт первым invalidate, но оставлен явным на случай изменения формы ключа) |
| `useConfirmParticipationMutation()` | `confirmParticipation(eventId)` | то же что cast: `events.detail(eventId)` + `events.myVote(eventId)` |
| `useDeclineParticipationMutation()` | `declineParticipation(eventId)` | то же что cast: `events.detail(eventId)` + `events.myVote(eventId)` |
| `useCreateEventMutation()` | `createEvent(clubId, body)` | `queryKeys.events.byClubAll(clubId)` — prefix без params, сбрасывает все варианты фильтров и пагинации (`byClub(clubId, anyParams)` все совпадают по prefix-match) |
| `useMarkAttendanceMutation()` | `markAttendance(eventId, list)` | `queryKeys.events.detail(eventId)` |

**Сигнатура (каноническая):**
```ts
export function useJoinClubMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (clubId: string) => joinClub(clubId),
    onSuccess: (_data, clubId) => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.detail(clubId) });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.my() });
    },
  });
}
```

**Что мутация НЕ делает:**
- НЕ показывает toast / haptic — это решает consumer через `onSuccess` / `onError` callback при вызове `mutate()`. Это обязательное правило, потому что разные страницы хотят разный copy для toast'а ("Клуб создан" vs "Клуб обновлён"). Hook остаётся pure, побочные эффекты — на стороне UI.
- НЕ навигирует — то же самое.

**Pattern в consumer'е:**
```ts
const { mutate: join, isPending } = useJoinClubMutation();
const haptic = useHaptic();
const handleJoin = () => {
  join(clubId, {
    onSuccess: () => { haptic.notify('success'); /* show "Вы участник" */ },
    onError: (e) => { haptic.notify('error'); setError(e.message); },
  });
};
```

### Error handling

- Каждая страница рендерит `query.error` если `query.isError` — текущий шаблон в коде (Placeholder с текстом ошибки) сохраняется
- Для критичных ошибок (5xx, network down) глобальный `<ErrorBoundary>` уже на месте (PR #22) — он ловит throw в render, query-ошибки в этот catch не попадают сами по себе
- 401 retry уже сделан на уровне `apiClient.request` — TanStack Query видит уже успешный ответ и не делает дополнительных ретраев

### Loading states

- `query.isPending` — initial load (нет данных в кеше)
- `query.isFetching` — любой fetch (background refetch после invalidate тоже)
- В UI: `isPending` → `<Spinner />`, `isFetching && data` → опциональный subtle indicator (на этой итерации **не** добавляем — out of scope)

### Cache hit между страницами

Сценарий из AC: открыл клуб A (Discovery → ClubPage) → вернулся в Discovery → открыл клуб B → вернулся → снова клуб A. На последнем шаге `useClubQuery(A)` отдаст cached data **синхронно** (нет flicker `<Spinner />`), потому что `staleTime: 30_000` и `gcTime: 5min` ещё держат запись. После 30s — рендерит cached + кидает background refetch.

---

## Client state — Zustand

### `useAuthStore` (`frontend/src/store/useAuthStore.ts`)

**Не трогается миграцией.** Хранит то что реально клиентское и не приходит с одного API endpoint:

```ts
interface AuthState {
  user: UserDto | null;             // приходит из /api/auth/telegram при login()
  isAuthenticated: boolean;         // derived flag
  isLoading: boolean;               // login() in flight
  error: string | null;             // login() error
  login: () => Promise<void>;       // вызывает apiClient.authenticate()
  logout: () => void;               // clearToken + reset state
}
```

> **Спорный момент** (для будущего, не в этом PR): `user` сам по себе — server state (приходит от backend'а на /api/auth/telegram). Можно вынести в `useMeQuery()`. Не делаем сейчас, потому что:
> 1. У нас нет endpoint'а `GET /api/users/me` (только POST на /auth)
> 2. login flow императивный (init SDK → POST initData → setToken → setUser в одной транзакции), это плохо ложится на декларативный `useQuery`
> 3. JWT-токен (live в `apiClient`) и `user` логически связаны — держим вместе.
>
> Если в будущем появится `GET /api/users/me` — этот вопрос можно пересмотреть. Зафиксировано в `docs/backlog/frontend-prd-gaps.md` (создать если нет).

**Правила:**
- Токен в `apiClient.token` (private field), не в localStorage — XSS-устойчивость
- Все остальные сторы (`useClubsStore`, `useEventsStore`, `useApplicationsStore`) **удаляются** в этом PR

---

## Test infrastructure

### `renderWithProviders`

Файл: `frontend/src/test/utils/renderWithProviders.tsx` — новая утилита, обёртка над `@testing-library/react`'s `render`. Используется во всех тестах, которые рендерят компонент, использующий хуки из `queries/`.

**Сигнатура:**
```ts
interface RenderWithProvidersOptions {
  queryClient?: QueryClient;
  routerEntries?: string[];
}
export function renderWithProviders(
  ui: ReactElement,
  options?: RenderWithProvidersOptions
): RenderResult & { queryClient: QueryClient };
```

> **Spec correction (post-flight):** ранее в спеке упоминался `routes?: ReactNode` параметр. По факту он не реализован — все consumer'ы передают `<Routes>` / `<Route>` JSX прямо в `ui` параметре, что проще и не требует доп. абстракции. YAGNI.

**Дефолты:**
- Создаёт **новый** `QueryClient` на каждый рендер с `retry: false`, `gcTime: Infinity`, чтобы тесты были детерминированы и не подтягивали fresh data между assertions
- Оборачивает в `<QueryClientProvider>` → `<MemoryRouter initialEntries={routerEntries ?? ['/']}>` → `ui`
- Если consumer передал свой `queryClient` (для проверки cache state) — использует его

**Не делает:**
- Не мокает SDK / API — это остаётся на тесте (через `vi.mock` + MSW handlers)
- Не сетит auth state — тест сам делает `useAuthStore.setState(...)` если надо

### Что меняется в существующих тестах

| Файл | Действие |
|---|---|
| `test/stores/useClubsStore.test.ts` | **Удалить.** Замена — `test/queries/clubs.test.ts` (renderHook + QueryClientProvider, проверка `useClubsQuery` + invalidation после `useCreateClubMutation`). Минимальное покрытие — happy path + error на каждый query-хук. |
| `test/pages/ClubPage.test.tsx` | Заменить ручной `MemoryRouter` wrapper на `renderWithProviders`. Убрать `useClubsStore.setState({ ... })` reset-блок. Убрать `import { useClubsStore }`. Тесты сценариев (вступление, заявки) остаются — MSW мокает API, query-хуки работают как в проде. |
| `test/pages/CreateClubModal.test.tsx` | То же: обернуть в `renderWithProviders`, убрать любые ссылки на `useClubsStore`. |

Новый тест `test/queries/clubs.test.ts` (минимум):
- `useClubQuery` — happy path + 404 error
- `useCreateClubMutation` — happy path + проверка что `queryKeys.clubs.my()` инвалидируется (через `queryClient.getQueryState` или флаг через `onSuccess` callback)
- `useClubsQuery` (infinite) — initial fetch + `fetchNextPage` accumulates data

Аналогично `test/queries/events.test.ts` и `test/queries/applications.test.ts` — по 2-3 теста на файл, без stress-coverage.

---

## Migration: feature/pre-design-stores

### Скоуп миграции

**Удаляются файлы:**
- `frontend/src/store/useClubsStore.ts`
- `frontend/src/store/useApplicationsStore.ts`
- `frontend/src/store/useEventsStore.ts`
- `frontend/src/test/stores/useClubsStore.test.ts`

**Создаются файлы:**
- `frontend/src/queries/queryKeys.ts`
- `frontend/src/queries/clubs.ts`
- `frontend/src/queries/events.ts`
- `frontend/src/queries/applications.ts`
- `frontend/src/queries/members.ts`
- `frontend/src/queries/finances.ts` (то же)
- `frontend/src/test/utils/renderWithProviders.tsx`
- `frontend/src/test/queries/clubs.test.ts`, `events.test.ts`, `applications.test.ts`

**Меняются файлы (consumer'ы):**
- `frontend/src/pages/DiscoveryPage.tsx` — `useClubsStore` → `useClubsQuery` (infinite)
- `frontend/src/pages/MyClubsPage.tsx` — `useClubsStore.fetchMyClubs` + `useApplicationsStore.fetchMyApplications` → `useMyClubsQuery` + `useMyApplicationsQuery`. `getClub` в `useEffect` для дозагрузки названий → `useQueries({ queries: clubIds.map(id => ({ queryKey: queryKeys.clubs.detail(id), queryFn: () => getClub(id) })) })` — даёт shared cache с детальной страницей ClubPage
- ~~`frontend/src/pages/OrganizerPage.tsx`~~ — `useClubsStore.fetchMyClubs` → `useMyClubsQuery`. `createClub` в CreateClubModal → `useCreateClubMutation`. `getClub` для дозагрузки → как в MyClubsPage. **Файл удалён** в PR `feature/restructure-bottom-tabs` (2026-04-25); его потребители query-хуков (`useMyClubsQuery`, `useCreateClubMutation`, `useClubQuery` через `useQueries`) переехали в `MyClubsPage.tsx` и `components/CreateClubModal.tsx`.
- `frontend/src/pages/ProfilePage.tsx` — `useClubsStore` + `useApplicationsStore` → query-хуки.
- `frontend/src/pages/ClubPage.tsx` — `useClubsStore.fetchMyClubs` + `getClub` + `getMyApplications` в `Promise.all` → `useClubQuery` + `useMyClubsQuery` + `useMyApplicationsQuery` (3 параллельных запроса, TanStack их не блокирует). `joinClub` / `applyToClub` → `useJoinClubMutation` / `useApplyToClubMutation`.
- ~~`frontend/src/pages/ClubInteriorPage.tsx`~~ — файл удалён в `feature/unified-club-page` (2026-04-25); query-консьюмеры переехали в `frontend/src/components/club/ClubEventsTab.tsx` (`useClubEventsQuery`), `ClubMembersTab.tsx` (`useClubMembersQuery`), `ClubProfileTab.tsx` (`useMemberProfileQuery`). Унифицированный `ClubPage` дёргает `useClubQuery` сам.
- `frontend/src/pages/EventPage.tsx` — `getEvent` → `useEventQuery`. `castVote` / `confirmParticipation` / `declineParticipation` → mutations с invalidation `queryKeys.events.detail(eventId)`.
- `frontend/src/pages/OrganizerClubManage.tsx` — массовая миграция: `getClub`, `updateClub`, `deleteClub`, `getClubMembers`, `getClubApplications`, `approveApplication`, `rejectApplication`, `getClubEvents`, `createEvent`, `markAttendance`, `getEvent`, `getFinances` → соответствующие query/mutation-хуки.
- `frontend/src/pages/InvitePage.tsx` — `getClubByInvite` → `useClubByInviteQuery`. `joinByInviteCode` → `useJoinByInviteMutation`.

### Что НЕ входит (out of scope)

- Реорганизация в `features/<name>/` структуру (запланирована «Much later» в queue)
- Миграция CreateClubModal на React Hook Form (отдельный PR `feature/pre-design-rhf`, #4 из 4)
- Изменение `useHaptic` API. Hook остаётся как есть. Точки вызова `notify('success')` / `notify('error')` переезжают в `onSuccess` / `onError` callbacks при `mutate()`-вызовах (упоминание в `haptic.md` уже сделано)
- Branded types для ID
- Mappers (`toClubViewModel` etc) — `select` опция query-хука пока не используется
- Миграция `useAuthStore` (см. «спорный момент» выше)
- Optimistic updates — пока полагаемся на refetch после invalidate

### Решённые scope-decisions (зафиксированы пользователем 2026-04-25)

#### S1. Полная миграция (Variant A)

В этот PR входят **все** server-state точки фронта, не только 3 Zustand-стора:
- 3 Zustand-стора (`useClubsStore`, `useApplicationsStore`, `useEventsStore`) → удаляются
- 13 прямых API-вызовов в `useEffect`/`useState` по страницам — все мигрируются на `useQuery`/`useQueries`/`useMutation`:
  - `OrganizerClubManage.tsx` — 7 точек (members, applications, events, finances, club, memberProfile, eventDetail) разнесены по 5 табам
  - ~~`ClubInteriorPage.tsx` — 4 (club, events, members, memberProfile)~~ — файл удалён в `feature/unified-club-page`. Те же 4 query-вызова переехали в unified `ClubPage` + `components/club/{ClubEventsTab,ClubMembersTab,ClubProfileTab}`
  - `EventPage.tsx` — 2 (event, myVote)
  - `ClubPage.tsx`, `InvitePage.tsx`, `MyClubsPage.tsx` — `getClub` для деталей (`OrganizerPage.tsx` упразднён в `feature/restructure-bottom-tabs`)

Обоснование: цель PR — «ликвидировать анти-паттерн server-state-вне-Query». Половинчатая миграция оставит видимую рассинхронизацию (например, approve заявки в OrganizerClubManage не обновит соседний таб без ручного refetch).

#### S2. `useQueries` для динамических списков

MyClubsPage / ProfilePage берут массив `clubIds` из memberships/applications и для каждого нужен `getClub(id)`. Реализация — `useQueries({ queries: clubIds.map(id => ({ queryKey: queryKeys.clubs.detail(id), queryFn: () => getClub(id) })) })`. Каждый club становится отдельным cached query → открытие любого клуба = cache hit на ClubPage без повторного fetch. (Прежний consumer OrganizerPage слит в MyClubsPage в `feature/restructure-bottom-tabs`.)

#### S3. `react-query-devtools` в dev-режиме

Добавляем dependency `@tanstack/react-query-devtools` (с `--legacy-peer-deps` как и остальные React 19 зависимости). Подключение в `main.tsx` под `import.meta.env.DEV` — visual inspector кеша только в dev-bundle, в prod не попадает.

---

## Acceptance Criteria

### AC-1: Build & test зелёные
**GIVEN** ветка после миграции
**WHEN** `npm run build && npm test`
**THEN** оба прохождения без TS-ошибок, без падающих тестов

### AC-2: Нет легаси-сторов
**GIVEN** репо после миграции
**WHEN** `grep -rn "useClubsStore\|useApplicationsStore\|useEventsStore" frontend/src/`
**THEN** результат пустой
**AND** файлы `frontend/src/store/use{Clubs,Applications,Events}Store.ts` отсутствуют

### AC-3: useAuthStore не тронут
**GIVEN** репо после миграции
**WHEN** `git diff master -- frontend/src/store/useAuthStore.ts`
**THEN** diff пустой

### AC-4: Cross-page invalidation работает (join → MyClubs)
**GIVEN** пользователь не состоит в клубе X, MyClubsPage уже была открыта (cache содержит `queryKeys.clubs.my()`)
**WHEN** пользователь открывает ClubPage(X), нажимает «Вступить», вступление успешно (`status: active`)
**AND** возвращается на MyClubsPage
**THEN** клуб X отображается в списке без ручного refresh
(механика: `useJoinClubMutation.onSuccess` → `invalidateQueries(['clubs', 'my'])` → MyClubsPage refetch при следующем mount)

### AC-5: Cross-page invalidation для apply (closed club)
**GIVEN** пользователь подал заявку в closed-клуб X
**WHEN** возвращается на MyClubsPage / ProfilePage
**THEN** заявка появляется в секции «Мои заявки»
(механика: `useApplyToClubMutation.onSuccess` → `invalidateQueries(['applications', 'mine'])`)

### AC-6: Cache hit между страницами клубов
**GIVEN** Discovery → открыл ClubPage(A) (fetched), назад в Discovery, открыл ClubPage(B), назад, повторно открыл ClubPage(A) в течение 30 секунд
**WHEN** загружается ClubPage(A) второй раз
**THEN** контент клуба A отображается **синхронно** (без `<Spinner />`)
**AND** в Network tab нет повторного запроса `GET /api/clubs/A`
(механика: `staleTime: 30_000` → cached data считается fresh, fetcher не вызывается)

### AC-7: Cache hit после 30s — background refetch
**GIVEN** ClubPage(A) была открыта 31+ секунду назад, cache ещё живой (gcTime 5 min)
**WHEN** пользователь открывает ClubPage(A) снова
**THEN** контент рендерится из кеша **сразу** (cached data)
**AND** в Network tab появляется фоновый запрос `GET /api/clubs/A`
**AND** при разнице в данных — UI обновляется (TanStack пушит обновление)

### AC-8: Loading state показывается на initial fetch
**GIVEN** холодный старт MyClubsPage (cache пуст)
**WHEN** страница рендерится
**THEN** виден `<Spinner />` пока `isPending === true`
**AND** после fetch — спиннер исчезает, рендерится контент

### AC-9: Error state показывается
**GIVEN** API возвращает 500 для `GET /api/clubs/X`
**WHEN** ClubPage(X) рендерится
**THEN** виден placeholder с сообщением об ошибке (текст из `query.error.message`)
**AND** соседние queries на странице, которые успешны, не блокируются

### AC-10: Infinite scroll DiscoveryPage
**GIVEN** DiscoveryPage загружена, отображено 20 клубов (page 0)
**WHEN** пользователь триггерит подгрузку (scroll / "Загрузить ещё" — текущий механизм)
**THEN** вызывается `fetchNextPage()`
**AND** к списку добавляются клубы page 1
**AND** старые 20 не пропадают
**AND** при `hasNextPage === false` дальнейшие вызовы no-op

### AC-11: Mutation pending state
**GIVEN** ClubPage, кнопка «Вступить»
**WHEN** пользователь жмёт кнопку
**THEN** кнопка disabled пока `mutation.isPending === true`
**AND** после успеха — UI обновляется (cache invalidated → useClubsMy refetch → button text меняется)

### AC-12: Haptic переехал в callbacks
**GIVEN** успешный `joinClub`
**WHEN** мутация резолвится
**THEN** `haptic.notify('success')` вызывается **из** `mutate(..., { onSuccess: () => haptic.notify('success') })` consumer'а
**AND** не из `useJoinClubMutation` напрямую (хук остаётся pure)

### AC-13: QueryClient defaults не изменены
**GIVEN** `frontend/src/main.tsx`
**WHEN** инспектируется `new QueryClient({ defaultOptions: { queries: ... } })`
**THEN** значения совпадают с теми что в TASK-PRE-002 (staleTime 30s, gcTime 5min, retry 1, refetchOnWindowFocus false)

---

## Non-functional

- **Производительность:** N запросов на N memberships в MyClubsPage решается через `useQueries` (S2) — параллельные fetches, shared cache. Нет sequential `await` в `useEffect`.
- **Bundle size:** `@tanstack/react-query` уже в bundle (PR #22). Эта миграция добавляет `@tanstack/react-query-devtools` (S3), но он включается только в dev-bundle через `import.meta.env.DEV` — в prod-bundle не попадает (Vite tree-shake'ает мёртвую ветку).
- **Error boundaries:** глобальный `ErrorBoundary` уже на месте. Query-ошибки в него не falle через — они в `query.error`. Это намеренно: не каждая 404 — fatal.
- **Логирование:** не добавляется в этом PR. Если нужно — отдельная задача через `QueryCache.onError` глобально.

---

## Связи с другими модулями

| Модуль | Связь |
|---|---|
| `frontend-core.md` (TASK-024) | `useAuthStore` остаётся, см. там детали login flow |
| `haptic.md` | Точки вызова `notify('success'/'error')` переезжают в `onSuccess`/`onError` мутаций. Сам хук — без изменений. |
| `docs/design/stack.md §21` | После миграции пункт «Server state в Zustand» из таблицы arch-debt снимается. Аналитик / Developer обновляют `stack.md` post-flight. |
| `clubs.md`, `events.md`, `membership.md` | Backend API не меняется. Эти модули — источник истины для request/response shapes, query-хуки только их потребляют. |

---

## Риски

1. **Регрессия в edge-cases** — текущие сторы имеют ручную логику (например `page === 0 ? replace : append`). При миграции на `useInfiniteQuery` поведение должно сохраниться 1-в-1. Покрывается AC-10.
2. **Тесты MSW + React Query** — handlers могут срабатывать не на тот query, если queryKey случайно совпал. Митигация: уникальные ключи через фабрику + `queryClient.clear()` в `beforeEach`.
3. **OrganizerClubManage** — самый большой файл (1000+ строк), много мутаций. Полная миграция (S1) увеличивает шанс пропустить какой-то `useEffect` рефетч. Митигация: пройти файл сверху вниз по списку из § «Меняются файлы», грепнуть остатки `useState.*Dto\|useEffect.*get[A-Z]`.
4. **Race condition на mutate + refetch** — `invalidateQueries` помечает stale, но активный refetch не cancel'ит уже идущий старый. TanStack справляется через `lastFetchTime`, но если consumer полагается на «после mutate, useQuery сразу даст новые данные» — нужен `await` `refetch` или `mutateAsync` + manual refetch. Спецификация: для критичных flow (после `useJoinClubMutation` UI зависит от обновлённого `useMyClubsQuery`) использовать `await qc.invalidateQueries(...)` в `onSuccess` чтобы дождаться refetch.
5. **SettingsTab `dirty` после успешного save** (post-flight, найдено Reviewer'ом, не закрыто кодом) — `OrganizerClubManage` в SettingsTab сравнивает локальную форму с `clubQuery.data`, чтобы вычислить `dirty`. После `useUpdateClubMutation.onSuccess` происходит invalidate `clubs.detail(id)` → background refetch → новый `clubQuery.data`. Если backend нормализует строки (например trim trailing whitespace в `description`) — обновлённый `data` не равен локальной форме, и `dirty` остаётся `true` после save. Требует ручной проверки на staging (Tester scenario #6). При воспроизведении — патчить локальную форму из `onSuccess` либо нормализовать сравнение. Не блокирует мерж этой итерации.

---

## Migration drift (pre-flight findings)

Сверка кода / документации / PRD на 2026-04-25:

| Файл | Расхождение | Действие |
|---|---|---|
| `docs/modules/frontend-stores.md` (legacy) | Описывал Zustand-сторы как актуальную архитектуру, с пометкой arch-debt в конце | **Этот файл переписан** — старое описание сторов теперь только в git-истории. Здесь — целевая архитектура. |
| `docs/modules/haptic.md:233,247` | Уже упоминает `feature/pre-design-stores` и переезд `notify` в `onSuccess`/`onError` | Согласовано. После миграции — Analyst post-flight перепроверяет точечно. |
| `docs/modules/frontend-core.md:49` | Render-стек в `main.tsx` уже описан с `<QueryClientProvider>` | Согласовано, не меняем. |
| `docs/modules/frontend-core.md:12,37` | Стек упоминает Zustand v5 — корректно, остаётся для AuthStore | Не меняем. |
| `docs/design/stack.md §21` (line 1367) | Пункт «Server state в Zustand» в таблице arch-debt | После merge'а PR — Analyst post-flight снимает этот пункт (отдельной задачей в migrate-update). |
| `docs/design/stack.md` line 600 | Структура `frontend/src/store/` упоминается | Обновить post-flight: добавить `queries/`, оставить `store/` (для AuthStore). |
| `PRD-Clubs.md` | Прямых упоминаний state mgmt инструмента нет (только namespace `store/` в одном пути). Конфликта нет. | OK. |

**Молчаливых расхождений нет.** Все три источника (код / docs/modules / PRD) сейчас согласованы в том что Zustand используется для server state, и согласованы в том что это arch-debt. После миграции — обновляются согласованно.

---

## Post-flight alignment (2026-04-25, после Reviewer + Security + Tester)

Применённые правки спеки по результатам сверки кода с этим документом:

| # | Что изменилось | Причина |
|---|---|---|
| 1 | В таблицу query-хуков добавлены `useMyVoteQuery` и `useMemberProfileQuery` | Реально реализованы и используются (EventPage / OrganizerClubManage). В pre-flight спеке отсутствовали. |
| 2 | `queryKeys` factory обновлена: добавлены `events.myVote`, `events.byClubAll`, `clubs.memberProfile` | Соответствует реальной фабрике в `frontend/src/queries/queryKeys.ts`. `byClubAll` нужен для prefix-инвалидации, `myVote` для сценариев голосования, `memberProfile` для модалки профиля участника. |
| 3 | `useApproveApplicationMutation` invalidation: добавлен `members(clubId)` + явная инвалидация `applications(clubId, 'pending')` | Approve добавляет нового участника → `members` list устаревает (Developer прав, апрув = side effect на members). `'pending'` явно инвалидируется потому что префикс-match по `applications(clubId, 'all')` не покрывает соседнюю табу с другим status query-параметром. |
| 4 | `useRejectApplicationMutation` invalidation: добавлена явная `applications(clubId, 'pending')` | То же что approve по причине статусной табы. `members` НЕ трогается (отказ ≠ новый участник). |
| 5 | Event-мутации (cast/confirm/decline) — invalidation расширена явным `events.myVote(eventId)` рядом с `events.detail(eventId)` | Технически избыточно (myVote — prefix-extension от detail), но Developer оставил явно с комментарием на случай изменения формы ключа. Для документации — фиксируем фактическое поведение. |
| 6 | `useCreateEventMutation` invalidation: переименование `events.byClub(clubId)` → `events.byClubAll(clubId)` | Используется prefix-форма ключа, чтобы один invalidate сбрасывал все варианты фильтров/пагинации. |
| 7 | `renderWithProviders` сигнатура: убран `routes?: ReactNode` параметр | По факту не реализован — все consumer'ы передают route-JSX напрямую в `ui` параметре. YAGNI. |
| 8 | Добавлен Risk #5 — SettingsTab `dirty` после успешного save | Найдено Reviewer'ом, отложено в Tester scenario #6 для проверки на staging. Не блокирует мерж. |

**Backlog gap (вне scope этой итерации):**
- `docs/backlog/logout-cache-clear.md` — `useAuthStore.logout()` не вызывает `queryClient.clear()`. Не блокер: UI logout-кнопки сейчас нет. Доделать одновременно с появлением UI logout.

**Out-of-scope drift (намеренно не правится в этом PR):**
- `docs/design/stack.md §21` table row «Global ErrorBoundary на корне» всё ещё помечен 🟡 — на самом деле он уже добавлен в PR #22. Это drift от PR #22, не от этой миграции; оставлено как отдельное открытое улучшение в design-stack документе.
