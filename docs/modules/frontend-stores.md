# Module: Frontend Stores

## TASK-038 — Zustand сторы: clubs, events, membership

### Файловая структура
```
src/
  api/
    clubs.ts       — getClubs, getClub, createClub, getMyClubs
    events.ts      — getClubEvents, getEvent, createEvent
    membership.ts  — joinClub, applyToClub, getMyApplications
  store/
    useClubsStore.ts   — clubs, myClubs, loading, error, fetchClubs, fetchMyClubs
    useEventsStore.ts  — events, loading, fetchClubEvents, vote, confirm
```

### useClubsStore
```ts
interface ClubsState {
  clubs: ClubListItemDto[]
  myClubs: MembershipDto[]
  loading: boolean
  error: string | null
  fetchClubs: (params?: Record<string, string>) => Promise<void>
  fetchMyClubs: () => Promise<void>
}
```

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

**events.ts** — `getClubEvents(clubId, params?)`, `getEvent(id)`, `createEvent(clubId, body)`

**membership.ts** — `joinClub(clubId)`, `applyToClub(clubId, answer)`, `getMyApplications()`

### Правила
- JWT хранится только в `useAuthStore` через `apiClient` — никогда в localStorage
- Все типы из `src/types/api.ts` — не дублировать интерфейсы
- Загрузка данных только через store actions, не напрямую из компонентов
