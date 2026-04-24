# Module: Frontend Stores

## TASK-038 — Zustand сторы: clubs, events, membership, applications

### Файловая структура
```
src/
  api/
    clubs.ts        — getClubs, getClub, createClub, updateClub, deleteClub, getMyClubs, getClubByInvite
    events.ts       — getClubEvents, getEvent, createEvent, castVote, getMyVote, confirmParticipation, declineParticipation, markAttendance, getFinances
    membership.ts   — joinClub, applyToClub, approveApplication, rejectApplication, getMyApplications, getClubApplications, getClubMembers, getMemberProfile, joinByInviteCode
  store/
    useAuthStore.ts          — см. frontend-core.md TASK-024 (client-state: токен, user, isAuthenticated)
    useClubsStore.ts         — clubs, myClubs, pagination, loading, error, fetchClubs, fetchMyClubs
    useApplicationsStore.ts  — applications, loading, error, fetchMyApplications
    useEventsStore.ts        — events, currentEvent, loading, fetchClubEvents, fetchEvent
```

### useAuthStore
**Client-state**, не server-state. Хранит токен в памяти и статус авторизации. Детали — в [frontend-core.md TASK-024](./frontend-core.md).

### useClubsStore
```ts
interface ClubsState {
  clubs: ClubListItemDto[]
  myClubs: MembershipDto[]
  totalPages: number        // из PageResponse<T>
  totalElements: number
  loading: boolean
  error: string | null
  fetchClubs: (filters?: ClubFilters) => Promise<void>   // поддерживает пагинацию через filters.page
  fetchMyClubs: () => Promise<void>
}
```

Пагинация: при `filters.page === 0` — заменяет `clubs`, при `page > 0` — дописывает (`[...state.clubs, ...res.content]`) для infinite scroll на DiscoveryPage.

### useApplicationsStore
```ts
interface ApplicationsState {
  applications: ApplicationDto[]
  loading: boolean
  error: string | null
  fetchMyApplications: () => Promise<void>
}
```

Используется на MyClubsPage и ProfilePage для секции «Мои заявки».

### useEventsStore
```ts
interface EventsState {
  eventsByClub: Record<string, EventListItemDto[]>
  currentEvent: EventDetailDto | null
  loading: boolean
  error: string | null
  fetchClubEvents: (clubId: string, params?: Record<string, string>) => Promise<void>
  fetchEvent: (eventId: string) => Promise<void>
}
```

### API модули

**clubs.ts** — все вызовы через `apiClient`, типизированы через типы из `types/api.ts`

**events.ts** — события, голосование (`castVote`, `getMyVote`), подтверждение участия (`confirmParticipation`, `declineParticipation`), отметка явки (`markAttendance`), финансы клуба (`getFinances`)

**membership.ts** — вступление (`joinClub`, `applyToClub`, `joinByInviteCode`), заявки организатора (`approveApplication`, `rejectApplication`, `getClubApplications`), участники (`getClubMembers`, `getMemberProfile`), мои заявки (`getMyApplications`)

### Правила
- JWT хранится только в `useAuthStore` через `apiClient` — никогда в localStorage
- Все типы из `src/types/api.ts` — не дублировать интерфейсы
- Загрузка данных только через store actions, не напрямую из компонентов

> **Arch-debt:** использование Zustand для server-state (clubs, events, applications) противоречит `.claude/rules/frontend.md` («server state ≠ client state, TanStack Query для server state»). Зафиксировано в [docs/design/stack.md §21](../design/stack.md). Миграция — `feature/pre-design-prep` перед дизайн-итерацией.
