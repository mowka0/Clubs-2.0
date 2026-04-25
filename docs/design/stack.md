# Фронтенд-стек Clubs 2.0 — полная инвентаризация

Живой снапшот `frontend/src/` на старт дизайн-итерации. Источник истины для
**Claude Design** (генерить макеты в терминах наших реальных компонентов и
данных) и для разработчика при внедрении handoff'а обратно в код.

Тех-стек в целом описан в [frontend-core.md](../modules/frontend-core.md).
Правила платформы — [telegram-constraints.md](./telegram-constraints.md).

---

## Содержание

1. File tree
2. Dev environment (Vite, MSW, env, scripts)
3. Routing & page map
4. Auth flow & guards
5. Conditional navigation (BottomTabBar / BackButton)
6. Data models (TypeScript shapes)
7. Page anatomy (все 9 страниц top→bottom)
8. OrganizerPage wizard — 5 шагов детально
9. Custom-компоненты
10. tgui v2 — используемые компоненты
11. Telegram SDK — что используем, что не
12. Hooks
13. UI mechanisms: Toast / Modal / Alert
14. Naming vocabulary (exact strings)
15. Current visual state — честный аудит
16. Viewport & tgui platform setting
17. Design tokens — «нулевое состояние»
18. Иконки — emoji-only
19. State & API layer
20. Known pains
21. Architectural debt vs `.claude/rules/frontend.md`
22. Что ждём от дизайн-итерации

---

## 1. File tree

```
frontend/
├── package.json
├── vite.config.ts
├── tsconfig.json
├── index.html
├── .env.development          — VITE_MOCK_INIT_DATA, VITE_API_URL
└── src/
    ├── main.tsx              — SDK init + <AppRoot> + <RouterProvider>
    ├── App.tsx               — обёртка с <RouterProvider router={router}>
    ├── router.tsx            — декларация всех 9 маршрутов
    ├── vite-env.d.ts
    │
    ├── pages/                — 9 страниц, 4 eager + 5 lazy
    │   ├── DiscoveryPage.tsx
    │   ├── MyClubsPage.tsx
    │   ├── OrganizerPage.tsx
    │   ├── ProfilePage.tsx
    │   ├── ClubPage.tsx
    │   ├── ClubInteriorPage.tsx
    │   ├── EventPage.tsx
    │   ├── OrganizerClubManage.tsx
    │   └── InvitePage.tsx
    │
    ├── components/           — 7 custom-компонентов
    │   ├── Layout.tsx
    │   ├── BottomTabBar.tsx
    │   ├── ClubCard.tsx
    │   ├── ClubFilters.tsx
    │   ├── AvatarUpload.tsx
    │   ├── Toast.tsx
    │   └── RootErrorFallback.tsx
    │
    ├── hooks/
    │   └── useBackButton.ts
    │
    ├── store/                — Zustand v5 (только client state)
    │   └── useAuthStore.ts   — JWT token / user / isAuthenticated
    │
    ├── queries/              — TanStack Query / mutation хуки (server state)
    │   ├── queryKeys.ts
    │   ├── clubs.ts
    │   ├── events.ts
    │   ├── applications.ts
    │   ├── members.ts
    │   └── finances.ts
    │
    ├── api/                  — REST-клиент + эндпоинты
    │   ├── apiClient.ts      — axios-like класс с 401-retry и Bearer-token
    │   ├── clubs.ts
    │   ├── membership.ts
    │   └── events.ts
    │
    ├── types/
    │   └── api.ts            — все DTO (источник истины для форм данных)
    │
    ├── telegram/
    │   └── sdk.ts            — init({ acceptCustomStyles: true }) + getInitDataRaw()
    │
    ├── utils/
    │   ├── formatters.ts     — formatPrice(), formatDatetime()
    │   └── validators.ts     — validateStep() для CreateClubModal
    │
    └── test/
        ├── setup.ts
        └── mocks/            — MSW handlers + server + mocked tgui
```

Всего: **9 страниц**, **7 компонентов**, **1 хук**, **4 стора**, **3 API-модуля**,
**1 файл типов**, **2 util-модуля**.

---

## 2. Dev environment

### Версии ключевых зависимостей

```json
{
  "react": "^19.0.0",
  "react-dom": "^19.0.0",
  "react-router-dom": "^7.0.0",
  "zustand": "^5.0.0",
  "@telegram-apps/sdk-react": "^3.0.0",
  "@telegram-apps/telegram-ui": "^2.0.0",
  "vite": "^6.0.0",
  "typescript": "^5.0.0",
  "vitest": "^1.6.1",
  "@vitest/ui": "^1.6.1",
  "msw": "^2.12.14",
  "happy-dom": "^14.12.3",
  "@testing-library/react": "^16.3.2",
  "@testing-library/user-event": "^14.6.1"
}
```

### Скрипты
- `npm run dev` — Vite dev server (по умолчанию `localhost:5173`)
- `npm run build` — `tsc && vite build`
- `npm run preview` — превью прод-сборки
- `npm run test` — `vitest run` (один прогон)
- `npm run test:watch` — watch-режим

### Vite config
- Плагин: `@vitejs/plugin-react`
- Proxy: `/api` → `http://localhost:8080` (локальный backend)
- Тест-env: `happy-dom` + `./src/test/setup.ts`

### Env vars
- `VITE_MOCK_INIT_DATA` — mock `initDataRaw` для локальной разработки **вне**
  Telegram (браузерная вкладка). Значение — URL-encoded querystring.
- `VITE_API_URL` — если задан, переопределяет дефолтный `/api` (prod).

### MSW
Мокинг **только в тестах** (`src/test/mocks/`):
- `handlers.ts` — handlers для `/api/*` эндпоинтов
- `server.ts` — MSW server для vitest
- `telegramUi.tsx` — mock tgui-компонентов для изоляции тестов

**Не используется** в dev/prod.

### Peer deps
`--legacy-peer-deps` **не требуется** в текущем состоянии зависимостей. Если
при апгрейде tgui/React появится конфликт — см. CLAUDE.md «Важные решения».

---

## 3. Routing & page map

### Таблица маршрутов

| Path | Компонент | Load | BackButton | BottomTabBar | Уровень |
|---|---|---|---|---|---|
| `/` | DiscoveryPage | eager | ✗ | ✓ | tab |
| `/my-clubs` | MyClubsPage | eager | ✗ | ✓ | tab |
| `/organizer` | OrganizerPage | eager | ✗ | ✓ | tab |
| `/profile` | ProfilePage | eager | ✗ | ✓ | tab |
| `/clubs/:id` | ClubPage | lazy | ✓ | ✓ | nested |
| `/clubs/:id/interior` | ClubInteriorPage | lazy | ✓ | ✓ | nested |
| `/clubs/:id/manage` | OrganizerClubManage | lazy | ✓ | ✓ | nested |
| `/events/:id` | EventPage | lazy | ✓ | ✓ | nested |
| `/invite/:code` | InvitePage | lazy | ✓ | ✓ | nested |

> **Примечание.** В текущей реализации BottomTabBar показан **на всех
> страницах**, включая nested. Это отклонение от tg-style native UX (обычно на
> nested-страницах tab-bar скрывается). На дизайн-итерации решить — оставить
> так или скрывать на nested.

### Lazy-load паттерн
Все `lazy()`-страницы обёрнуты в `<Suspense fallback={<PageFallback />}>`
(спиннер tgui).

---

## 4. Auth flow & guards

### Как определяется «залогинен»

- Стор: `useAuthStore()` с полями `isAuthenticated: boolean`, `isLoading`,
  `error`, `user: UserDto | null`.
- JWT-токен хранится **только в памяти** `apiClient` (не в localStorage — см.
  `telegram-constraints.md §24`).
- На `mount` Layout:
  ```tsx
  useEffect(() => {
    if (!isAuthenticated && !isLoading && !error) login();
  }, [isAuthenticated, isLoading, error, login]);
  ```

### Login-процедура

`login()` делает:
1. `getInitDataRaw()` из `telegram/sdk.ts` (source chain: SDK signal → launch
   params → `window.Telegram.WebApp.initData` → env `VITE_MOCK_INIT_DATA`).
2. `POST /api/auth/telegram { initData: initDataRaw }`.
3. Ответ: `{ token, user: UserDto }`. Token кэшируется в `apiClient`.
4. `isAuthenticated = true`, `user` в сторе.

### Unauthed-рендер (в Layout)

| State | Что рендерится |
|---|---|
| `isLoading` | `<PageFallback />` (спиннер на всю страницу) |
| `error` | `<Placeholder>` «Не удалось авторизоваться. Откройте приложение через Telegram.» |
| `!isAuthenticated && !loading && !error` | ничего (триггерится `login()`) |
| `isAuthenticated` | `<Outlet />` — рендер маршрута |

**Нет redirect'ов** — всё управляется условным рендером в `Layout`.

### 401 retry в apiClient
При 401 от backend — `apiClient` делает один повтор с fresh `initData`. Если
снова 401 → полный logout (`isAuthenticated = false`), UI покажет placeholder
«Не удалось авторизоваться…».

---

## 5. Conditional navigation

### BottomTabBar — где показан

Правило в `BottomTabBar.tsx`:

```ts
export function isTabBarRoute(pathname: string): boolean {
  if (TAB_PATHS.has(pathname)) return true;
  // TAB_PATHS = ['/', '/my-clubs', '/organizer', '/profile']
  return /^\/clubs\/[^/]+(\/manage)?$/.test(pathname);
}
```

Итого: **все 9 страниц** показывают BottomTabBar. На `/events/:id` и
`/invite/:code` тоже (хотя дизайн-решение может это изменить).

`Layout` добавляет `paddingBottom: 80` корневому контейнеру, чтобы tab-bar не
перекрывал контент.

### BackButton — где активна

В `Layout`:
```ts
const isMainTab = TAB_PATHS.has(pathname);
useBackButton(!isMainTab);
```

Т.е.: на **tab-страницах скрыт**, на **nested страницах показан** и ведёт
`navigate(-1)`.

---

## 6. Data models

Все DTO живут в `src/types/api.ts`. Это **источник истины** для форм данных,
отображаемых в UI.

### UserDto
```ts
interface UserDto {
  id: string;
  telegramId: number;
  telegramUsername: string | null;
  firstName: string;
  lastName: string | null;
  avatarUrl: string | null;
  city: string | null;
}
```

### ClubListItemDto (для карточек в лентах)
```ts
interface ClubListItemDto {
  id: string;
  name: string;
  category: string;           // см. enum Category ниже
  accessType: string;         // 'open' | 'closed' | 'private'
  city: string;
  subscriptionPrice: number;  // Stars/месяц
  memberCount: number;
  memberLimit: number;
  avatarUrl: string | null;
  nearestEvent: NearestEventDto | null;
  tags: string[];
}

interface NearestEventDto {
  id: string;
  title: string;
  eventDatetime: string;      // ISO-8601
  goingCount: number;
}
```

### ClubDetailDto (для ClubPage / OrganizerClubManage)
```ts
interface ClubDetailDto {
  id: string;
  ownerId: string;
  name: string;
  description: string;
  category: string;
  accessType: string;
  city: string;
  district: string | null;
  memberLimit: number;
  subscriptionPrice: number;
  avatarUrl: string | null;
  rules: string | null;
  applicationQuestion: string | null;   // только для closed
  inviteLink: string | null;
  memberCount: number;
  activityRating: number;
  isActive: boolean;
}
```

### EventDetailDto
```ts
interface EventDetailDto {
  id: string;
  clubId: string;
  title: string;
  description: string | null;
  locationText: string;
  eventDatetime: string;              // ISO-8601
  participantLimit: number;
  votingOpensDaysBefore: number;
  status: string;                     // см. EventStatus enum
  goingCount: number;
  maybeCount: number;
  notGoingCount: number;
  confirmedCount: number;
  attendanceMarked: boolean;
  attendanceFinalized: boolean;
  createdAt: string | null;
}
```

### MembershipDto
```ts
interface MembershipDto {
  id: string;
  userId: string;
  clubId: string;
  status: string;         // 'active' | 'inactive' | 'banned'
  role: string;           // 'organizer' | 'member' | 'moderator'
  joinedAt: string | null;
  subscriptionExpiresAt: string | null;
}
```

### ClubApplicationDto
```ts
interface ClubApplicationDto {
  id: string;
  userId: string;
  clubId: string;
  status: string;                   // см. ApplicationStatus enum
  answerText: string | null;        // ответ на applicationQuestion
  rejectedReason: string | null;
  createdAt: string | null;
  resolvedAt: string | null;
}
```

### MemberListItemDto (список участников клуба)
```ts
interface MemberListItemDto {
  userId: string;
  firstName: string;
  lastName: string | null;
  avatarUrl: string | null;
  role: string;                     // 'organizer' | 'member'
  joinedAt: string | null;
  reliabilityIndex: number;         // рейтинг надёжности
  promiseFulfillmentPct: number;    // % выполненных обещаний
}
```

### MemberProfileDto (юзер в контексте клуба)
```ts
interface MemberProfileDto {
  userId: string;
  clubId: string;
  firstName: string;
  username: string | null;
  avatarUrl: string | null;
  reliabilityIndex: number;
  promiseFulfillmentPct: number;
  totalConfirmations: number;
  totalAttendances: number;
}
```

### FinancesDto (финансовая панель клуба)
```ts
interface FinancesDto {
  activeMembers: number;
  monthlyRevenue: number;           // Stars/месяц всего
  organizerShare: number;           // доля организатора (80%)
  platformFee: number;              // доля платформы (20%)
  organizerSharePct: number;        // 80
  platformFeePct: number;           // 20
}
```

### PendingPaymentDto (ответ при вступлении требующем оплаты)
```ts
interface PendingPaymentDto {
  status: 'pending_payment';
  clubId: string;
  priceStars: number;
  message: string;
}
```

### PageResponse<T> (пагинация)
```ts
interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}
```

### Enums (string-константы)

```ts
type Category =
  | 'sport' | 'creative' | 'food' | 'board_games'
  | 'cinema' | 'education' | 'travel' | 'other';

type AccessType = 'open' | 'closed' | 'private';

type MembershipStatus = 'active' | 'inactive' | 'banned';
type Role = 'organizer' | 'member' | 'moderator';

type ApplicationStatus = 'pending' | 'approved' | 'rejected' | 'auto_rejected';

type EventStatus = 'upcoming' | 'stage_1' | 'stage_2' | 'completed' | 'cancelled';

type UserVote = 'going' | 'maybe' | 'not_going' | 'confirmed' | 'waitlisted' | 'declined';
```

---

## 7. Page anatomy

Для каждой страницы — структура **сверху вниз**, состояния и зависимости.

### 7.1 DiscoveryPage `/` ⚡ tab

**Header:** — (BackButton скрыт)
**Body top→bottom:**
1. `<ClubFilters>` — sticky сверху: search input, category chips, city input
2. Inline error (красный текст) — если API упал
3. `<Placeholder>` «Клубы не найдены. Попробуйте изменить фильтры» — если пусто
4. Список `<ClubCard>` → click `navigate('/clubs/:id')`
5. Центральный `<Spinner>` — при loading
6. Sentinel `<div>` для IntersectionObserver (infinite scroll)

**Footer:** BottomTabBar
**Состояния:** loading · empty · error · success · infinite-scroll-loading
**API:** `getClubs(filters)` с пагинацией `page`/`size=20`

---

### 7.2 MyClubsPage `/my-clubs` ⚡ tab

**Header:** —
**Body:**
1. `<Section header="Мои клубы">`
   - Spinner при loading
   - Placeholder «Вы пока не состоите ни в одном клубе» — если пусто
   - `<Cell>` каждого членства → `navigate('/clubs/{id}')`
     - subtitle: «Организатор» или «Участник»
2. `<Section header="Мои заявки">`
   - Placeholder «Нет поданных заявок» — если пусто
   - `<Cell>` заявки
     - subtitle: дата подачи (`formatDatetime`)
     - after: badge-статус («На рассмотрении» / «Принята» / «Отклонена»)
3. `<Toast>` — если в `location.state.toast` есть сообщение (после удаления
   клуба)

**Footer:** BottomTabBar
**API:** `getMyClubs()`, `getMyApplications()`, плюс `getClub(id)` для подгрузки
названий клубов в заявках

---

### 7.3 OrganizerPage `/organizer` ⚡ tab

**Header:** —
**Body:**
1. `<Section header="Мои клубы">` — только где `role === 'organizer'`
   - Spinner / Placeholder / список `<Cell>` → `navigate('/clubs/{id}/manage')`
2. CTA-секция: `<Button>` **«+ Создать новый клуб»** → открывает `<Modal>` с
   `CreateClubModal` (5-шаговый wizard, см. §8)

**Footer:** BottomTabBar
**API:** `getMyClubs()`, `createClub(body)`

---

### 7.4 ProfilePage `/profile` ⚡ tab

**Header:** —
**Body:**
1. Профиль-header
   - `<Avatar>` 48×48 (из `user.avatarUrl` или fallback emoji 👤)
   - `firstName` + `lastName`
   - `@telegramUsername` (если есть)
2. `<Section header="Статистика">`
   - `<Cell>` «Клубов» → `myClubs.length`
   - `<Cell>` «Ожидают ответа» → `pendingApps.length` (красная подсветка, если > 0)
3. `<Section header="Мои клубы">` — превью первых 5
   - `<Cell>` → `/clubs/{id}`
   - Кнопка «Показать все (X)» — если `> 5`
4. `<Section header="Активные заявки">` — если `pendingApps.length > 0`
5. `<Placeholder>` «Вы пока не состоите…» + `<Button>` «Найти клуб» — если
   `myClubs.length === 0`

**Footer:** BottomTabBar
**API:** `getMyClubs()`, `getMyApplications()` + `login()` из auth-стора

---

### 7.5 ClubPage `/clubs/:id` ⚡ nested

**Header:** BackButton
**Body:**
1. Header клуба
   - `<Avatar>` 80×80
   - Название (`<Text weight="1">`)
   - Badges: категория + тип доступа
2. Info-cells
   - «Город» (+ «Район» если есть)
   - «Участники» — `X / Y`
   - «Подписка» — `formatPrice(subscriptionPrice)`
3. `<Section header="О клубе">` — `description`
4. `<Section header="Правила">` — если `rules !== null`
5. Join-error (красный текст) — если есть
6. CTA-кнопка (динамическая, см. ниже)
7. `<Modal>` «Заявка в клуб» — для closed-клубов

**CTA-логика** (приоритет сверху вниз):
- `isOrganizer` → «⚙️ Управление клубом» (disabled, outline)
- `isMember` → «Вы участник ✓» (disabled, outline)
- `pendingPayment` → «💳 Ожидаем оплату — {priceStars} Stars» (disabled) +
  hint про Telegram-счёт
- `app.status === 'pending'` → «⏳ Заявка на рассмотрении» (disabled)
- `app.status === 'approved'` → «💳 Ожидаем оплату…» (disabled)
- `joinSuccess` → «Заявка отправлена ✓» (disabled)
- `accessType === 'open'` → «Вступить» (onClick = `handleJoin`)
- `accessType === 'closed'` → «Хочу вступить» (onClick = `setShowApplyModal`)

**ApplyModal:**
- Заголовок «Заявка в клуб»
- Если `applicationQuestion` — показать + `<Textarea>` для ответа
- Join-error
- Кнопки: «Отмена» / «Отправить»

**Footer:** BottomTabBar
**API:** `getClub(id)`, `fetchMyClubs()`, `getMyApplications()`, `joinClub(id)`,
`applyToClub(id, answer)`

---

### 7.6 ClubInteriorPage `/clubs/:id/interior` ⚡ nested

**Header:** BackButton
**Body:**
1. Header клуба — название + «X / Y участников»
2. `<TabsList>` горизонтальная (3 таба): **«События» / «Участники» / «Мой
   профиль»**

**Tab 1: События**
- `<Section header="Ближайшие">` (статусы `upcoming` / `stage_1` / `stage_2`)
  - `<Cell>` события (subtitle: дата+время · место, after: `X/Y`)
  - click → `navigate('/events/:id')`
- `<Section header="Прошедшие">` (`completed`, max 5) — аналогично

**Tab 2: Участники**
- `<Section header="Участники (N)">`
  - `<Cell>` каждого — before: Avatar, subtitle: «Надёжность: {index}»,
    after: badge «Организатор» (если role)

**Tab 3: Мой профиль**
- `<Placeholder>` «Профиль недоступен» — если `memberProfile === null`
- Иначе: Avatar + имя + `<Section header="Репутация">` с метриками

**Footer:** BottomTabBar
**API:** `getClub()`, `getClubEvents()`, `getClubMembers()`,
`getMemberProfile(clubId, userId)`

---

### 7.7 EventPage `/events/:id` ⚡ nested

**Header:** BackButton
**Body:**
1. Header события — title, `formatEventDate()`, место, description
2. `<Section header="Статистика набора">`
   - «Хочу пойти» / «Может быть» / «Не пойду» — счётчики
   - Progress bar (зелёный gradient `#34C759 → #30D158`): `goingCount /
     participantLimit`
3. `<Section header="Голосование">` — если `status ∈ { upcoming, stage_1 }`
   - Badge «Ваш голос: {VOTE_LABELS[myVote]}» (если проголосован)
   - 3 кнопки: «Пойду» / «Возможно» / «Не пойду» (mode зависит от `myVote`)
4. `<Section header="Подтверждение участия">` — если `status === 'stage_2'`
   - Статус-badge
   - Кнопки «Подтвердить участие» / «Отказаться» (если `myVote ∈ { going,
     maybe }`)
5. `<Section header="Участники">` — если `status ∈ { stage_2, completed }` и
   `confirmedCount > 0`

**Footer:** BottomTabBar
**API:** `getEvent(id)`, `getMyVote(id)`, `castVote(id, vote)`,
`confirmParticipation(id)`, `declineParticipation(id)`

---

### 7.8 OrganizerClubManage `/clubs/:id/manage` ⚡ nested

**Header:** BackButton
**Body:**
1. Header — название + «X / Y участников | город»
2. `<TabsList>` (5 табов): **«Участники» / «Заявки» / «События» / «Финансы» /
   «Настройки»**

**Tab 1: Участники**
- `<Cell>` каждого — before: Avatar, subtitle: «Надёжность: {i} · Обещания:
  {pct}%», after: badge «Орг» для organizer
- Click → `<MemberProfileModal>` (Avatar + @username + «Статус в клубе» +
  «Репутация»)

**Tab 2: Заявки**
- Список заявок со `status === 'pending'`
  - Заголовок: «Пользователь {userId[:8]}…»
  - Текст: сколько времени до автоотклонения (красное если ≤ 6 часов)
  - «Ответ на вопрос» (если есть)
  - Кнопки: «Принять» / «Отклонить»

**Tab 3: События**
- `<Button>` «+ Создать событие» → разворачивает inline-форму:
  - Название * / Место * / DateTimeLocal * / Лимит участников *
- `<Section header="Предстоящие">` → клик по событию открывает
  `<EventDetailModal>`
- `<Section header="Завершённые">` → after-кнопка «Присутствие» → открывает
  `<AttendanceModal>` (чекбоксы по каждому подтверждённому участнику)

**Tab 4: Финансы**
- `<Section header="Финансовая сводка">` — Cell'ы:
  - «Активных участников»
  - «Выручка за месяц» (Stars)
  - «Доля организатора» (%)
  - «Комиссия платформы» (%)

**Tab 5: Настройки**
- `<Section header="Аватар">` → `<AvatarUpload>`
- `<Section header="Основное">` → Input'ы: Название / Город / Район / Лимит
  участников / Цена подписки
- `<Section header="Описание и правила">` → `<Textarea>` Описание / Правила /
  Input «Вопрос для заявки» (только если `accessType === 'closed'`)
- `<Section header="Нельзя изменить">` — read-only: Категория, Тип доступа
- `<Button>` «Сохранить» (disabled если не dirty)
- `<Section header="Опасная зона">` → `<Button>` «🗑 Удалить клуб» →
  `<DeleteModal>` с подтверждением

**Footer:** BottomTabBar
**API:** `getClub()`, `getClubMembers()`, `getMemberProfile()`,
`getClubApplications()`, `approveApplication()`, `rejectApplication()`,
`getClubEvents()`, `createEvent()`, `getEvent()`, `markAttendance()`,
`getFinances()`, `updateClub()`, `deleteClub()`

---

### 7.9 InvitePage `/invite/:code` ⚡ nested

**Header:** BackButton
**Body:**
1. Spinner при loading
2. `<Placeholder>` «Ссылка недействительна» — если ошибка и нет клуба
3. Если `joined === true`:
   - `<Placeholder>` «Добро пожаловать!» + description «Вы вступили в клуб
     "{name}"»
   - `<Button>` «Перейти в клуб» → `/clubs/{id}`
4. Иначе:
   - `<Section header="Приглашение в клуб">` — Avatar 64×64 + название +
     category-badge
   - Info-секция: Город · Участники X/Y · Подписка (если > 0)
   - `<Section header="О клубе">` (если description)
   - Join-error
   - `<Button>` «Вступить в клуб»

**Footer:** BottomTabBar
**API:** `getClubByInvite(code)`, `joinByInviteCode(code)`

---

## 8. OrganizerPage wizard — 5 шагов создания клуба

Открывается в `<Modal>` из OrganizerPage. State хранится локально в компоненте
(не Zustand), валидация — `validateStep(step, form)` из `utils/validators.ts`.

Заголовок модала: «Шаг {X} из 5: {STEP_TITLES[X]}». Кнопка-крестик ✕ в углу.

### Step 0 — «Основное»

| Поле | Тип | Обязательно | Валидация | Placeholder |
|---|---|---|---|---|
| Название клуба | text | ✓ | 3–60 симв. | «Например: Книжный клуб Москвы» |
| Город | text | ✓ | non-empty | «Москва» |
| Район (необязательно) | text | — | — | «Центральный» |

**CTA:** «Далее»

### Step 1 — «Категория»

- `<Select>` «Категория *»: options —
  Спорт / Творчество / Еда / Настолки / Кино / Образование / Путешествия /
  Другое. Default: `other`.
- Radio-группа:
  - «Открытый клуб» (default) — подпись «Любой желающий может вступить»
  - «Закрытый клуб» — подпись «Вступление по заявке (организатор одобряет)»

**CTA:** «Далее»

### Step 2 — «Участники»

| Поле | Тип | Обяз. | Валидация | Placeholder |
|---|---|---|---|---|
| Лимит участников | number | ✓ | 1–200 | «30» |
| Цена подписки (Stars/мес) | number | — | integer ≥ 0 | «0 — бесплатно» |

Живой расчёт: если `subscriptionPrice > 0 && memberLimit > 0` — показывается
Cell «Доход организатора»:
> «При {memberLimit} участниках вы будете зарабатывать {Math.round(limit ×
> price × 0.8)} Stars в месяц (80% от дохода)»

**CTA:** «Далее»

### Step 3 — «Описание»

- `<AvatarUpload>` — лейбл «Аватар (необязательно)»
- `<Textarea>` «Описание клуба *» — 10–500 симв., placeholder «Расскажите о
  своём клубе (10–500 символов)»
- `<Textarea>` «Правила (необязательно)» — placeholder «Правила сообщества»

**CTA:** «Далее»

### Step 4 — «Заявка»

Зависит от `accessType`:
- Если `closed`: `<Input>` «Вопрос для вступления (необязательно)» —
  placeholder «Почему вы хотите вступить?»
- Если `open`: `<Placeholder>` «Для открытого клуба вопрос при вступлении не
  нужен»

**CTA:** «Создать клуб» (submit) — `createClub(body)`, при сабмите кнопка
показывает `<Spinner>`.

### Навигация wizard'а

- `step > 0` — кнопка «Назад» (outline)
- `step < 4` — primary «Далее»
- `step === 4` — primary «Создать клуб» (submit)

Валидация на каждый «Далее» — `validateStep(step, form)` возвращает
`{ valid: boolean, errors: Record<string, string> }`. Ошибки рендерятся под
полями.

---

## 9. Custom-компоненты

Все лежат в `frontend/src/components/`.

### `<Layout>`
Root-обёртка:
- `<Suspense fallback={<PageFallback />}>` для lazy-страниц
- Auth-check + вызов `login()` при unauth
- Управление `useBackButton(isMainTab ? false : true)`
- Рендер `<Outlet />` + `<BottomTabBar>`
- Добавляет `paddingBottom: 80` под tab-bar

### `<BottomTabBar>`
- 4 таба поверх tgui `<Tabbar>`
- Логика `isTabBarRoute(pathname)` определяет видимость
- Каждый таб = `<Tabbar.Item>` с emoji-иконкой + label
- Haptic на клик: `select()` через `useHaptic` (см. [`docs/modules/haptic.md`](../modules/haptic.md))

### `<ClubCard>` `clubs: ClubListItemDto[]`
Строка ленты клубов:
- `<Avatar>` (из `avatarUrl`)
- Title: `name`
- Badges: `category` + `accessType`
- Subtitle: `memberCount / memberLimit`, город, `formatPrice(subscriptionPrice)`
- Если `nearestEvent`: «Ближайшее: {title} — {formatDatetime(datetime)}»

**Гэп:** нет skeleton-состояния, нет hover/press эффекта.

### `<ClubFilters>` `value: FilterState, onChange`
Блок фильтров:
- `<Input>` поиск по названию
- `<Section>` с `category`-chips (emoji-иконки)
- `<Input>` город

### `<AvatarUpload>` `value, onChange, onError`
Загрузка аватара клуба:
- Preview 96×96 (круглый)
- Кнопки «Загрузить» / «Заменить» / «Убрать»
- Валидация: только JPEG/PNG, ≤ 5 МБ
- Ошибки: «Только JPEG и PNG» / «Файл больше 5 МБ»

### `<Toast>` `message, durationMs=3000, onClose`
Уведомление:
- `position: fixed; bottom: 80` (поверх BottomTabBar)
- Тёмный фон, белый текст, border-radius, box-shadow
- Auto-dismiss через `setTimeout`

Паттерн вызова (императивный, без глобального стора):
```tsx
const [toastMsg, setToastMsg] = useState<string | null>(null);
// ...
setToastMsg('Клуб создан!');
// в JSX
{toastMsg && <Toast message={toastMsg} onClose={() => setToastMsg(null)} />}
```

Либо через `navigate(..., { state: { toast: '...' } })` и чтение из
`location.state` на целевой странице (используется для delete-клуба → MyClubs).

### `<RootErrorFallback>` `error, resetErrorBoundary` (Added in `feature/pre-design-prep` — this PR)
Last-resort fallback для top-level `<ErrorBoundary>` (`react-error-boundary`) в `main.tsx`:
- Centered placeholder на весь экран с `var(--tgui--bg_color)`
- Заголовок «Что-то пошло не так» + сообщение из `error.message` (если есть) или дефолт «Попробуйте перезапустить приложение.»
- `<Button>` «Попробовать снова» → `resetErrorBoundary()` сбрасывает boundary, рендер-дерево перерендерится
- Виден только при uncaught render-error'е (защита от blank-screen). Per-page ошибки обрабатываются страницами сами через loading/error состояния

---

## 10. tgui v2 — используемые компоненты

17 штук (deduplicated).

### Структура и layout
- `AppRoot` — root-wrapper (`main.tsx`)
- `List` — вертикальный scroll-контейнер для `<Cell>`-ов
- `Section` — сгруппированный блок с опц. header'ом
- `Tabbar` — основа нашего BottomTabBar

### Интерактив
- `Button` — primary / secondary CTA (modes: filled, outline, plain, gray)
- `Cell` — строка списка со слотами `before` / `after`, поддержка click
- `Input` — текстовое поле
- `Select` — dropdown
- `Textarea` — многострочный ввод
- `TabsList` — горизонтальная вкладка внутри страницы (используется в
  ClubInteriorPage и OrganizerClubManage)

### Обратная связь и состояния
- `Spinner` — индикатор, размеры `s` / `m` / `l`
- `Placeholder` — пустое состояние: header + description + опц. action
- `Badge` — метка, type=`number` | `dot`
- `Text` — семантический текст, props `weight` 1–4
- `Avatar` — аватар, size `20/24/40/48/96`

### Оверлей
- `Modal` — используется в OrganizerPage (CreateClubModal), ClubPage
  (ApplyModal), OrganizerClubManage (MemberProfileModal / EventDetailModal /
  AttendanceModal / DeleteModal)

**Всего custom-композиций на базе tgui:** 5 модалок + 1 toast + 6 базовых
компонентов = 12 composable-блоков.

---

## 11. Telegram SDK — использование

Централизовано в `src/telegram/sdk.ts` и `src/hooks/useBackButton.ts`.

### Используется ⚡

| API | Где |
|---|---|
| `init({ acceptCustomStyles: true })` | `main.tsx`, один раз |
| `initData.restore()` + `initData.raw()` | получение `initDataRaw` |
| `retrieveLaunchParams().initDataRaw` | fallback-источник |
| `mountBackButton` / `showBackButton` / `hideBackButton` / `unmountBackButton` | `useBackButton` |
| `onBackButtonClick(handler)` | `useBackButton` |

### Не используется, но доступно 📦 (гэпы)

- ~~**`hapticFeedback`** — во всех interactive-моментах.~~ Реализован через
  `src/hooks/useHaptic.ts` — см. [`docs/modules/haptic.md`](../modules/haptic.md).
- **`viewport`** (stableHeight, safe-area-insets, события) — сейчас полагаемся
  на `100%` и tgui-дефолты. Гэп, §5–6 в constraints.
- **`mainButton` / `secondaryButton`** — на nested-страницах надо использовать
  вместо собственных `<Button>` (native CTA). Гэп, §10.
- **`settingsButton`** — 📦 low priority.
- **`closingConfirmation`** — для wizard'а OrganizerPage (unsaved changes).
  Гэп, §23.
- **`cloudStorage` / `deviceStorage` / `secureStorage`** — для persistent
  фильтров Discovery. 📦.
- **`setHeaderColor` / `setBottomBarColor`** — для бесшовного перехода
  header → content. Гэп, §2 в constraints.
- **`showPopup` / `showAlert` / `showConfirm`** — сейчас все confirm-диалоги
  кастомные через tgui `<Modal>`. Переход на native был бы consistently. Гэп,
  §13 в constraints.

---

## 12. Hooks

### `useBackButton(visible: boolean): void`

Управляет Telegram BackButton: mount/unmount + show/hide + обработчик клика.

```ts
export function useBackButton(visible: boolean): void {
  const navigate = useNavigate();

  // 1. Mount/unmount BackButton
  useEffect(() => {
    if (mountBackButton.isAvailable()) mountBackButton();
    return () => {
      if (hideBackButton.isAvailable()) hideBackButton();
      unmountBackButton();
    };
  }, []);

  // 2. Toggle видимости
  useEffect(() => {
    if (visible) showBackButton.isAvailable() && showBackButton();
    else hideBackButton.isAvailable() && hideBackButton();
  }, [visible]);

  // 3. Обработчик клика → navigate(-1)
  useEffect(() => {
    if (!visible) return;
    if (!onBackButtonClick.isAvailable()) return;
    const off = onBackButtonClick(() => navigate(-1));
    return off;
  }, [visible, navigate]);
}
```

Используется в `Layout` (вызывается один раз с `!isMainTab`).

**Других custom-хуков в проекте нет.**

---

## 13. UI mechanisms: Toast / Modal / Alert

### Toast

**Паттерн:** imperative, через локальный `useState` страницы. Нет глобального
стора для тостов.

```tsx
// MyClubsPage (приём toast из navigate state)
useEffect(() => {
  const incoming = location.state?.toast;
  if (incoming) {
    setToastMessage(incoming);
    navigate(location.pathname, { replace: true, state: {} });
  }
}, [location]);

// Показ (локально на странице)
{toastMessage && <Toast message={toastMessage} onClose={() => setToastMessage(null)} />}
```

**Использование в коде:**
- После `deleteClub()` в OrganizerClubManage → `navigate('/my-clubs', {
  state: { toast: 'Клуб «X» удалён' } })` → MyClubsPage читает из `location.state`.

### Modal

**Библиотека:** tgui `<Modal>` (а не native `<dialog>`).

```tsx
<Modal open={showModal} onOpenChange={(o) => !o && setShowModal(false)}>
  {/* Контент: Section, Input, Button, и т.д. */}
</Modal>
```

**Где используется:**
1. OrganizerPage → `<CreateClubModal>` (5-шаговый wizard)
2. ClubPage → ApplyModal (заявка в закрытый клуб)
3. OrganizerClubManage → `<MemberProfileModal>`, `<EventDetailModal>`,
   `<AttendanceModal>`, `<DeleteModal>`

Закрытие по клику на backdrop — через `onOpenChange(false)`.

### Alert / Popup

**Native Telegram `WebApp.showAlert` / `showConfirm` / `showPopup` — НЕ
используются.**

Все confirm/alert сейчас:
- Error messages — inline красным текстом (`color: var(--tgui--destructive_text_color)`)
- Confirm удаления — custom `<Modal>` (`<DeleteModal>` в OrganizerClubManage)

**Это гэп**: по гайдам Telegram надо использовать native popup'ы. См.
[telegram-constraints.md §13](./telegram-constraints.md#13).

---

## 14. Naming vocabulary

Exact-strings, собранные из реального JSX. **Дизайн обязан использовать именно
эти термины**, чтобы не расходиться с кодом.

### Сущности (единогласно)

| Концепт | Термин | Встречается как |
|---|---|---|
| Сообщество | **Клуб** | «Клуб», «Мои клубы», «Создать клуб», «Управление клубом», «клубов», «клубе» |
| Мероприятие | **Событие** | «Событие», «События», «Создать событие», «Завершённые» |
| Apply-запрос | **Заявка** | «Заявка», «Мои заявки», «Заявки», «Заявка в клуб», «Заявка на рассмотрении», «Заявка одобрена», «Заявка отправлена» |
| Member | **Участник** | «Участники», «Участник», «участников», «Активные участники», «подтверждённых участников» |
| Владелец клуба | **Организатор** | «Организатор», «Мои клубы (организатор)», «Управление клубом», «Доля организатора» |
| Role-badge | **Орг** (сокр.) | в Badge-after |

**Никаких расхождений (community / member / admin / etc.) в коде нет** — словарь
последовательный.

### Действия (кнопки)

| Намерение | Точная формулировка |
|---|---|
| Вступить в клуб | «Вступить», «Вступить в клуб», «Хочу вступить» (для closed) |
| Отправить заявку | «Отправить» (CTA в ApplyModal), «Подать заявку» (implicit) |
| Записаться на событие | «Пойду», «Возможно», «Не пойду» (голос) / «Подтвердить участие», «Отказаться» (stage_2) |
| Отмена | «Отмена», «Отменить» |
| Сохранить | «Сохранить» |
| Создать | «Создать клуб», «Создать событие», «Создать» |
| Удалить | «Удалить клуб», «Удалить», «🗑 Удалить клуб» |
| Управлять | «⚙️ Управление клубом» |
| Аватар-действия | «Заменить», «Загрузить», «Убрать» |
| Принять заявку | «Принять» |
| Отклонить заявку | «Отклонить» |
| Искать клуб | «Найти клуб» |

### Статусы (bade / pill)

**Заявка (ClubApplicationDto.status):**
- `pending` → «На рассмотрении» / «Ожидают ответа»
- `approved` → «Принята» / «Заявка одобрена»
- `rejected` / `auto_rejected` → «Отклонена»

**Событие (EventDetailDto.status):**
- `upcoming` → «Запланировано»
- `stage_1` → «Голосование»
- `stage_2` → «Подтверждение»
- `completed` → «Завершено»
- `cancelled` → «Отменено»

**Голос участника (UserVote):**
- `going` → «Пойду»
- `maybe` → «Возможно»
- `not_going` → «Не пойду»
- `confirmed` → «Подтверждён»
- `waitlisted` → «Лист ожидания»
- `declined` → «Отказался»

**Присутствие:**
- `attendanceMarked: true` → «Явка отмечена»
- `attendanceFinalized: true` → «Явка финализирована»

### Навигация — 4 таба BottomTabBar

1. «Discovery» (🔍) — поиск клубов
2. «Мои клубы» (👥) — список своих клубов + заявок
3. «Организатор» (⚙️) — управление своими клубами
4. «Профиль» (👤) — профиль юзера

> «Discovery» — единственный английский термин в UI. Вероятно стоит локализовать
> в «Поиск» или «Обзор» на дизайн-итерации.

### Формы (labels полей)

```
«Название клуба *»
«Город *»
«Район (необязательно)»
«Категория *»
«Лимит участников *»
«Цена подписки (Stars/мес)»
«Описание клуба *»
«Правила (необязательно)»
«Вопрос для вступления (необязательно)»
«Аватар (необязательно)»
«Открытый клуб» / «Закрытый клуб»
«Место *»
«Дата и время *»
```

### Ошибки и пустые состояния

```
«Клубы не найдены. Попробуйте изменить фильтры»
«Вы пока не состоите ни в одном клубе»
«Нет поданных заявок»
«У вас пока нет клубов. Создайте первый!»
«Профиль недоступен. Не удалось загрузить ваш профиль в этом клубе»
«Нет предстоящих событий»
«Список участников пуст»
«Нет активных заявок»
«Событий пока нет. Создайте первое!»
«Не удалось авторизоваться. Откройте приложение через Telegram.»
«Ссылка недействительна. Эта ссылка-приглашение устарела или не существует»
«Только JPEG и PNG»
«Файл больше 5 МБ»
```

---

## 15. Current visual state — честный аудит

**Палитра.** Приложение полностью живёт на tgui CSS-переменных. Используется:
- `var(--tgui--bg_color)` — фон страницы (iOS light: серо-белый, dark: чёрный)
- `var(--tgui--secondary_bg_color)` — фон секций
- `var(--tgui--text_color)` — основной текст
- `var(--tgui--hint_color)` — вторичный текст (hints, labels)
- `var(--tgui--button_color)` — accent на primary-кнопках
- `var(--tgui--destructive_text_color)` — ошибки, delete
- Один хардкод: `linear-gradient(90deg, #34C759, #30D158)` — зелёный gradient
  progress-bar в EventPage

**Никакого бренд-акцента Clubs 2.0 не существует.** Ни логотипа, ни
собственного accent-цвета. Приложение визуально **неотличимо от любого
tgui-шаблона**.

**Типографика.** `<Text weight="1">` (bold) для заголовков, `weight="2"` для
особо крупных. Размеры: 20–22px для page titles, 14–15px для body, 12–13px для
captions. Line-height ≈ 1.5. Без custom-шрифтов — системный tgui-дефолт.

**Плотность.** Компактная мобильная — `padding: 16` стандарт, gap 8/12/16px.
`<Cell>` и `<Section>` из tgui дают правильный ритм. Плотность комфортная, но
монотонная (однородные блоки без визуальной иерархии).

**Иконки.** Emoji в JSX (15 мест): 🏠 👤 ⚙️ 🔍 👥 📸 🗑 💳 ⏳ ⭐. Каждая
рендерится своим шрифтом на iOS / Android / Desktop → **разный визуал на
разных платформах**. Иконок-SVG / иконочной библиотеки нет.

**Сводка.** Чистый tgui-boilerplate без визуального характера. Стабильно,
функционально, нативно, но **безлико** — проект не запоминается, не
выделяется, бренда нет. Это ровно то, что дизайн-итерация должна изменить.

---

## 16. Viewport & tgui platform setting

### `<AppRoot>`-конфиг (main.tsx)

```tsx
<AppRoot>
  <App />
</AppRoot>
```

**`platform` prop не указан** — tgui применяет default (авто-детект из UA
либо `base`). Стилистика компонентов будет меняться в зависимости от клиента
(iOS vs Android Telegram).

**Дизайн-вывод:** решить — жёстко фиксируем `platform="base"` для единого
визуала, или оставляем авто-детект для native-feel. Обычный выбор — `base`
для единообразия.

### Viewport meta (index.html)

```html
<meta name="viewport" content="width=device-width, initial-scale=1.0">
```

Стандартно, без ограничений масштаба.

### Media queries

**Нет ни одной `@media`-запроса в коде**, `window.matchMedia` тоже не
используется. Layout полностью полагается на flex + tgui-responsive-поведение.

### Container constraints

Нет `min-width` / `max-width` на корневом контейнере. Верстка реагирует только
на viewport Telegram.

**Target widths:** базируясь на покрытии устройств Telegram,
проектируем под **320 → 430 px**:
- 320 — min (iPhone SE 1-го поколения, малые Android)
- 375 — baseline (iPhone 11-15 обычные, средние Android)
- 430 — max (iPhone 15/16 Pro Max, большие Android)

Desktop-клиент Telegram открывает Mini App в фиксированном окне ≈ 400 px —
падает в тот же диапазон.

---

## 17. Design tokens — «нулевое состояние»

### Что есть

Только `--tgui--*` CSS-переменные, используются **inline через
`style={{ color: 'var(--tgui--...)' }}`** в JSX. Нет CSS-файлов, нет
`theme/tokens/design-system/styles/` директорий.

### Частотность (grep по `frontend/src/`)

| Переменная | Вхождений | Контекст |
|---|---|---|
| `--tgui--hint_color` | 30 | вторичный текст, метаданные |
| `--tgui--secondary_bg_color` | 8 | фон карточек, fallback |
| `--tgui--destructive_text_color` | 7 | ошибки, delete-действия |
| `--tgui--text_color` | 6 | основной текст |
| `--tgui--button_color` | 3 | активный chip, подсветка |
| `--tgui--button_text_color` | 1 | override в custom-кнопке |
| `--tgui--divider` | 1 | разделитель |

### Магические числа (кандидаты в токены)

**Spacing** (padding / margin / gap):
```
16px — 28 вхождений   ← основная единица
 8px — 16
12px — 13
20px —  7
 4px —  2
10px —  2
 6px —  1
```
Также встречаются крупные: 24, 32, 40.

**Border-radius:**
```
16px — аватары 80–96
12px — картинки 48–64
20px — chips
 8px — alert-боксы
 4px — inline-разделители
50%  — круглые аватары
```

### Предложение: `tokens.ts`

```ts
export const spacing = { xs: 4, sm: 8, md: 12, base: 16, lg: 20, xl: 24, xxl: 32, xxxl: 40 };
export const radius  = { xs: 4, sm: 8, md: 12, base: 16, lg: 20, pill: 999, circle: '50%' };
```

Принимаем на дизайн-итерации, после чего рефакторим inline-стили.

---

## 18. Иконки — emoji-only

- **Нет** `lucide-react`, `react-icons`, `@tabler/icons`, `phosphor-react`.
- **Нет** SVG-спрайта или своего `<Icon>`-компонента.
- Иконки — Unicode-emoji **прямо в JSX** (≈15 мест): 🔍 👥 ⚙️ 👤 🏠 📷 🎬 💳
  ⭐ 🗑 ⏳ 📸 ✓ и прочие.

**Боль:** emoji рендерятся разным шрифтом между iOS / Android / Desktop; не
стилизуются (`color`, `size` игнорируются); разный визуальный вес.
**Первый кандидат на замену** после дизайн-итерации.

Опции:
1. Lucide Icons (MIT, 1500+, React-wrapper) — fast adoption.
2. Свой SVG-набор (~40 иконок под бренд Clubs) — уникально, дороже.

См. [telegram-constraints.md §30](./telegram-constraints.md#30) — требования
к иконкам.

---

## 19. State & API layer

### Client state — Zustand (`src/store/`)

- `useAuthStore` — `isAuthenticated`, `isLoading`, `error`, `user`, `login()`.

State-only, без persist middleware → **после refresh всё теряется**, JWT
заново получается через `initData`.

### Server state — TanStack Query (`src/queries/`)

С PR `feature/pre-design-stores` (2026-04-25) весь server state живёт в
TanStack Query. Раньше списки клубов / события / заявки лежали в Zustand-сторах
(`useClubsStore`, `useEventsStore`, `useApplicationsStore`) — это было основным
arch-debt'ом (см. §21). После миграции:

- `queries/queryKeys.ts` — фабрика query keys
- `queries/clubs.ts` — `useClubsQuery` (infinite), `useMyClubsQuery`,
  `useClubQuery`, `useClubByInviteQuery` + create/update/delete/join/apply
  мутации
- `queries/events.ts` — `useClubEventsQuery`, `useEventQuery`, `useMyVoteQuery`
  + cast/confirm/decline/create/markAttendance мутации
- `queries/applications.ts` — `useMyApplicationsQuery`,
  `useClubApplicationsQuery` + approve/reject мутации
- `queries/members.ts` — `useClubMembersQuery`, `useMemberProfileQuery`
- `queries/finances.ts` — `useClubFinancesQuery`

Полная спека и invalidation-таблица — [`docs/modules/frontend-stores.md`](../modules/frontend-stores.md).
QueryClient defaults: `staleTime: 30s`, `gcTime: 5min`, `retry: 1`,
`refetchOnWindowFocus: false`.

### API layer (`src/api/`)

- `apiClient.ts` — класс с `fetch`-обёрткой:
  - Bearer-token в header
  - 401-retry с fresh initData
  - `baseURL = import.meta.env.VITE_API_URL ?? '/api'`
- `clubs.ts` — `getClubs`, `getClub`, `createClub`, `updateClub`, `deleteClub`,
  `getClubByInvite`
- `membership.ts` — `getMyClubs`, `getMyApplications`, `joinClub`,
  `applyToClub`, `approveApplication`, `rejectApplication`, `getClubMembers`,
  `getMemberProfile`, `joinByInviteCode`
- `events.ts` — `getClubEvents`, `getEvent`, `createEvent`, `castVote`,
  `getMyVote`, `confirmParticipation`, `declineParticipation`,
  `markAttendance`, `getFinances`

---

## 20. Known pains — что дизайн должен помочь решить

1. **Inline-стили повсюду.** 50+ мест с `padding: 16`, `gap: 8`,
   `borderRadius: 12` — нет единого источника правды. После рестайла в
   `tokens.ts`.
2. **Emoji как иконки** (§18) — разные на платформах, не стилизуются.
3. **Inconsistent fallbacks у CSS-переменных** — иногда
   `var(--tgui--button_color, #default)`, иногда без default. Стандартизировать.
4. **Нет hover/press-состояний** у custom-компонентов (`<ClubCard>`,
   inline-кнопки). tgui-компоненты это делают сами, custom — нет.
5. **Hardcoded Cyrillic labels** — i18n отложен, но при рестайле фиксируем
   тексты в словаре.
6. **Нет loading skeleton'ов** — везде `<Spinner>`, выглядит бедно на длинных
   списках. Кандидат — `<Placeholder>` с shimmer-эффектом.
7. ~~**Нет haptic feedback**~~ — реализован в `feature/pre-design-haptic`
   через `src/hooks/useHaptic.ts`. Спека: [`docs/modules/haptic.md`](../modules/haptic.md).
8. **Нет viewport safe-area handling** — risk на devices с notch/dynamic-island.
9. **Нет native MainButton** на nested-страницах — собственный `<Button>` в
   Section внизу вместо Telegram MainButton (который автоматически живёт над
   клавиатурой и с safe-area).
10. **Нет closing-confirmation** в wizard'е OrganizerPage — юзер может потерять
    5 шагов данных.
11. **Нет брендинга** — ни логотипа, ни accent-цвета, ни визуального
    характера.
12. **«Discovery» по-английски** в tab-label посреди русского UI — разорвать.
13. **Модалка внутри модалки** в OrganizerClubManage (EventDetailModal →
    AttendanceModal) — Telegram не поддерживает nesting, надо пересмотреть UX.
14. **`<Modal>` как confirm** — лучше перейти на native `WebApp.showConfirm`
    для consistency (constraints §13).
15. **Arch-debt vs frontend.md** — server-state в Zustand, нет TanStack
    Query / RHF / mappers / features-structure / branded types / global
    ErrorBoundary. Детально — §21. (Частично закрыто: TanStack Query +
    ErrorBoundary — done в PR #22 / `feature/pre-design-stores`.)

---

## 21. Architectural debt vs `.claude/rules/frontend.md`

**Важно для Claude Design и разработчика handoff'а.** В проекте есть формальные
frontend-правила (`.claude/rules/frontend.md`), от которых **фактический код
заметно отклоняется**. Дизайн-рестайл — хороший момент провести рефакторинг
одновременно, потому что границы компонентов всё равно будут меняться.

| Правило (frontend.md) | Факт в коде | Gap-оценка |
|---|---|---|
| **Feature-based structure** (`features/clubs/`, `features/events/`, `shared/`) | Плоская: `pages/`, `components/`, `store/`, `api/` — по типам, не по фичам | 🔴 major |
| **Server state через TanStack Query** | ✅ Закрыто в `feature/pre-design-stores` (2026-04-25). Все списки/детали через `useQuery`/`useInfiniteQuery`/`useMutation` в `src/queries/`. Zustand остался только для `useAuthStore`. | ✅ соблюдается |
| **TanStack Query в deps** | ✅ Установлен в PR #22 (`@tanstack/react-query`), `react-query-devtools` — в `devDependencies`, подключается только в `import.meta.env.DEV` | ✅ соблюдается |
| **React Hook Form для форм** | `useState` во всех формах (OrganizerPage wizard, OrganizerClubManage settings, ApplyModal) | 🔴 major — rerender на каждую букву |
| **RHF в deps** | Не установлен | 🔴 major |
| **Mappers** (`features/*/mappers/` для API→UI трансформаций) | Нет mapper'ов, форматирование inline в JSX или в `utils/formatters.ts` | 🟡 moderate |
| **Discriminated Unions** для состояний (`{status: 'loading' \| 'error' \| 'success'}`) | Отдельные `isLoading`, `error`, `data` в сторах | 🟡 moderate |
| **Branded types** для ID (`type ClubId = string & { __brand: 'ClubId' }`) | Обычные `string` везде | 🟡 moderate — легко перепутать `userId` / `clubId` |
| **Global ErrorBoundary на корне** | Не найден в `main.tsx` / `App.tsx` | 🟡 moderate |
| **Deep link handler в одном месте** | Отсутствует — `start_param` нигде не парсится | 🟡 moderate (фича deep-link ещё не нужна, но закладывать правильно) |
| **TSConfig strict** | ✓ strict + noImplicitAny + noUnusedLocals + noUnusedParameters | ✅ соблюдается |
| **`any` / `as Type` запрещены** | Нужен отдельный аудит (grep'ом видно единичные `as`) | 🟢 minor |
| **Haptic в interactive-моментах** | Реализован через `useHaptic` (см. [`docs/modules/haptic.md`](../modules/haptic.md)) | ✅ соблюдается |

### Что это значит для дизайн-итерации

Дизайн-рестайл **неизбежно** заденет структуру компонентов и хуков. Разумно
параллельно (или сразу после) провести **arch-миграцию**:

1. Установить `@tanstack/react-query` + обернуть стор-функции в query-хуки.
   Zustand-сторы для server-state снести, оставить только `useAuthStore`
   (client state).
2. Установить `react-hook-form` + переписать wizard OrganizerPage и
   OrganizerClubManage settings.
3. Реорганизовать в `features/*` — `clubs/`, `events/`, `applications/`,
   `auth/`, плюс `shared/`.
4. Вынести маппинг `DTO → ViewModel` в `features/*/mappers/`. Это даст
   Claude Design и ребёнку handoff'а стабильную промежуточную модель
   (имя поля = в макете = в коде).
5. Ввести branded types для `ClubId`, `UserId`, `EventId`, `ApplicationId`.
6. Глобальный `ErrorBoundary` в `app/`.

### Порядок работ (предложение)

**Вариант A — «сначала дизайн, потом arch»:**
1. Дизайн-итерация → handoff в текущую структуру → визуальный рестайл
2. Отдельный PR arch-миграции по правилам

**Вариант B — «параллельно»:**
1. Сначала arch-миграция (TanStack Query, features/, RHF, ErrorBoundary)
2. Потом handoff из Claude Design — ложится на чистую архитектуру

**Вариант B визуально более дорогой старт, но handoff получается чище** —
Claude Design предлагает макеты, под которые сразу есть нужная инфраструктура
(Query-хуки для loading state'ов, discriminated union'ы для error-states, и
т.д.). Рекомендую B.

**Вариант C — «гибрид»:** arch-миграция только там, где трогает дизайн
(OrganizerPage wizard → RHF, страницы со списками → TanStack Query).
ErrorBoundary, branded types, features-reorg — отдельно.

Решение — за человеком. Этот файл только маркирует gap.

---

## 22. Что ждём от дизайн-итерации

### Tokens (должны родиться)
- Типографическая шкала (11–12 стилей, см. constraints §4)
- Spacing-scale: `4, 8, 12, 16, 20, 24, 32, 40`
- Radius-scale: `4, 8, 12, 16, 20, pill, circle`
- Motion tokens (durations + easings, constraints §16)
- Z-index layers (constraints §17)

### Colors
- Маппинг семантических ролей на `--tg-theme-*` переменные (constraints §3)
- Проверка контраста в обеих темах
- Решение: нужен ли бренд-accent поверх `button_color`

### Assets (обязательные)
- Иконка приложения (SVG + PNG 512, две темы) — для loading screen + home
- HEX цвета loading screen (light + dark) — для BotFather
- Иконочный набор (24px grid, currentColor fill) — замена emoji
- Avatar-placeholder (инициалы на цветном фоне)

### Макеты (9 страниц × 2 темы)
- DiscoveryPage, MyClubsPage, OrganizerPage, ProfilePage (tab)
- ClubPage, ClubInteriorPage, OrganizerClubManage (5 табов), EventPage,
  InvitePage (nested)
- Каждая — empty state + loading skeleton + error state
- Nested — с учётом места под Telegram MainButton

### Spec-детализации
- OrganizerPage wizard — 5 шагов с иллюстрациями перехода
- OrganizerClubManage — 5 табов + все модалки (Member / Event / Attendance /
  Delete)
- ClubPage — CTA-логика (8 вариантов)
- EventPage — таблица: status → что показываем (voting vs confirmation)

### Паттерны-guidelines
- Toast (shape, position над BottomTabBar, timing)
- Native popup vs custom Modal (решение: что куда)
- Confirm с `destructive` button
- MainButton + showProgress (flow при submit)
- Pull-to-refresh (если решим делать на DiscoveryPage)

### UX-флоу
- Permission-запросы (constraints §26): когда и как просим
- Deep-link entry (с `/clubs/:id`, `/invite/:code`, `/events/:id` сразу)
- Onboarding первого запуска + `addToHomeScreen` prompt
- Wizard-rescue (unsaved changes → closingConfirmation)

---

## Приложение: быстрые справки

**Базовый Cell:**
```tsx
<Cell
  before={<Avatar size={48} src={user.avatarUrl} />}
  after={<Badge type="number">12</Badge>}
  subtitle="Организатор"
  onClick={() => navigate(`/clubs/${club.id}`)}
>
  {club.name}
</Cell>
```

**Примерный wizard-step:**
```tsx
<Section header={`Шаг ${step + 1} из 5: ${STEP_TITLES[step]}`}>
  <Input
    header="Название клуба *"
    placeholder="Например: Книжный клуб Москвы"
    value={form.name}
    onChange={(e) => update('name', e.target.value)}
    status={errors.name ? 'error' : 'default'}
  />
  {errors.name && <Text style={{ color: 'var(--tgui--destructive_text_color)' }}>{errors.name}</Text>}
</Section>
```

**Toast-вызов:**
```tsx
setToastMessage('Клуб создан!');
// в JSX
{toastMessage && <Toast message={toastMessage} onClose={() => setToastMessage(null)} />}
```
