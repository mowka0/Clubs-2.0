# Module: Frontend Core

> **Status (2026-04-25):** этот документ описывает **первоначальное scaffolding** (TASK-023..025). Актуальное состояние файловой структуры, routing и компонентов — в [`docs/design/stack.md`](../design/stack.md). Расхождения относительно scaffolding-времени:
> - `OrganizerPage` удалён в PR `feature/restructure-bottom-tabs`; `/organizer` редиректится на `/my-clubs`. Введён 4-й tab «События» (`/events` → `EventsPage` placeholder).
> - `ClubInteriorPage` удалён в PR `feature/unified-club-page`; `/clubs/:id/interior` редиректится на `/clubs/:id` через `<InteriorRedirect>`. Контент member/organizer-tabs живёт в `frontend/src/components/club/{ClubEventsTab,ClubMembersTab,ClubProfileTab}.tsx`. Подробности — [`club-page-unified.md`](./club-page-unified.md).
> - Lazy-loaded sтраницы: ClubPage, EventPage, OrganizerClubManage, InvitePage. Tab-страницы (Discovery / MyClubs / Events / Profile) — eager.

---

## TASK-023 — Инициализация frontend-проекта

### Стек

- React 19 + TypeScript + Vite
- `@telegram-apps/sdk-react` v3
- `@telegram-apps/telegram-ui` v2
- Zustand v5
- react-router-dom v7

### Файловая структура (после инициализации)

```
frontend/
  package.json
  vite.config.ts
  tsconfig.json
  index.html
  .env.development        — VITE_API_URL, VITE_MOCK_INIT_DATA
  src/
    main.tsx              — SDK init + AppRoot + RouterProvider
    App.tsx               — RouterProvider с router
    router.tsx            — маршруты
    vite-env.d.ts         — /// <reference types="vite/client" />
    pages/
      DiscoveryPage.tsx   — заглушка
      MyClubsPage.tsx     — заглушка
      OrganizerPage.tsx   — заглушка (удалён в feature/restructure-bottom-tabs, 2026-04-25 — функционал слит в MyClubsPage; см. docs/modules/my-clubs-unified.md)
      ProfilePage.tsx     — заглушка
    api/
      apiClient.ts        — ApiClient class с retry при 401
    queries/              — TanStack Query / mutation хуки (server state)
      queryKeys.ts
      clubs.ts events.ts applications.ts members.ts finances.ts
    store/
      useAuthStore.ts     — Zustand auth store (только client state — token/user/isAuthenticated)
    telegram/
      sdk.ts              — getInitDataRaw()
    types/
      api.ts              — TypeScript типы DTO
```

### Важные решения

- `npm install` требует `--legacy-peer-deps`. Причина: `@telegram-apps/telegram-ui` объявляет peer `react@^18.2.0`, у нас `react@^19`. Lockfile, собранный с флагом, работает без проблем — но **любая новая установка** (добавление пакета, fresh clone без lockfile, CI с `npm ci`) требует флаг, иначе npm падает с `ERESOLVE`. В `frontend/Dockerfile` флаг тоже стоит. Снимется только когда tgui подымет peer-range до React 19
- AppRoot из `@telegram-apps/telegram-ui` — обёртка в `main.tsx` с `platform="base"` (единый визуал tgui независимо от ОС-клиента)
- SDK инициализируется до рендера: `init({ acceptCustomStyles: true })` в `main.tsx`
- Render-стек в `main.tsx` (от внешнего к внутреннему): `<ErrorBoundary FallbackComponent={RootErrorFallback}>` → `<QueryClientProvider>` (TanStack Query, см. дальше) → `<AppRoot platform="base">` → `<App />`. Это значит любой uncaught render-error ловится root-fallback'ом (`src/components/RootErrorFallback.tsx`), а server-state доступен через TanStack Query из любой страницы

---

## TASK-024 — Telegram SDK + apiClient с JWT

### Telegram SDK инициализация

**Файл: `src/telegram/sdk.ts`**

```typescript
import { init, retrieveLaunchParams, initData } from '@telegram-apps/sdk-react';

export function initTelegramSdk(): void {
  try {
    init({ acceptCustomStyles: true });
    initData.restore(); // Required in v3 to populate raw signal
  } catch (_e) { /* mock mode */ }
}

export function getInitDataRaw(): string {
  // v3: initData.raw is a Computed signal, call as function
  try {
    const raw = initData.raw();
    if (raw) return raw;
  } catch (_e) {}
  // Fallback: retrieveLaunchParams
  try {
    const raw = (retrieveLaunchParams().initDataRaw as string | undefined);
    if (raw) return raw;
  } catch (_e) {}
  // Fallback: native Telegram WebApp API
  const tgRaw = (window as any)?.Telegram?.WebApp?.initData;
  if (tgRaw) return tgRaw;
  const mock = import.meta.env.VITE_MOCK_INIT_DATA as string | undefined;
  if (mock) return mock;
  throw new Error('No initData available. Set VITE_MOCK_INIT_DATA in .env.development');
}
```

**Важно (v3):** `initData.restore()` обязателен при инициализации — без него `initData.raw()` возвращает `undefined`. `initData.raw` — это `Computed<string | undefined>`, вызывается как функция `initData.raw()`. Хук `useLaunchParams` удалён в v3.

### apiClient

**Файл: `src/api/apiClient.ts`**

```typescript
class ApiClient {
  private token: string | null = null;
  private isRetry = false;

  setToken(token: string | null) { this.token = token; }

  async request<T>(path: string, options?: RequestInit): Promise<T> {
    const res = await fetch(`${import.meta.env.VITE_API_URL}${path}`, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}),
        ...options?.headers,
      },
    });

    if (res.status === 401 && !this.isRetry) {
      this.isRetry = true;
      await this.authenticate();
      this.isRetry = false;
      return this.request<T>(path, options);
    }

    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
  }

  private async authenticate() {
    const initData = getInitDataRaw();
    const data = await this.request<{ token: string }>('/api/auth/telegram', {
      method: 'POST',
      body: JSON.stringify({ initData }),
    });
    this.token = data.token;
    useAuthStore.getState().setToken(data.token);
  }
}
```

**Исправленный баг:** условие `res.status === 401 && !this.isRetry` (без проверки `this.token`) — повторная аутентификация даже когда token = null.

### vite-env.d.ts

```typescript
/// <reference types="vite/client" />
interface ImportMetaEnv {
  readonly VITE_API_URL: string;
  readonly VITE_MOCK_INIT_DATA: string;
}
interface ImportMeta {
  readonly env: ImportMetaEnv;
}
```

### .env.development

```
VITE_API_URL=http://localhost:8080
VITE_MOCK_INIT_DATA=user=%7B%22id%22%3A123456789%2C%22first_name%22%3A%22Test%22%2C%22username%22%3A%22testuser%22%7D&auth_date=1234567890&hash=mockhash
```

### useAuthStore (Zustand)

```typescript
interface AuthState {
  user: UserDto | null;
  token: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
  login: () => Promise<void>;
  logout: () => void;
  setToken: (token: string) => void;
}
```

---

## TASK-025: Роутинг + навигация

**Приоритет:** critical
**Агент:** Frontend Dev
**Дата анализа:** 2026-03-21
**Зависимости:** TASK-023 (done)

> **Update 2026-04-25 (PR `feature/restructure-bottom-tabs`):** структура BottomTabBar изменилась — таб «Организатор» (`/organizer`) удалён, его функционал слит в «Мои клубы»; добавлен таб «События» (`/events`, placeholder). Текущий список табов и regex `isTabBarRoute` см. в [`docs/design/stack.md` §3 + §5](../design/stack.md) и [`docs/modules/my-clubs-unified.md`](./my-clubs-unified.md). Описание ниже сохранено для исторического контекста TASK-025 (initial routing setup), но **актуальная конфигурация табов и navigation flow** — в указанных документах.

---

### Описание

Задача реализует полную систему навигации Telegram Mini App: роутер со всеми маршрутами, нижнюю панель табов (BottomTabBar) для 4 главных разделов, поддержку кнопки "Назад" Telegram (BackButton) на вложенных страницах, обёртку layout в App.tsx и code splitting через React.lazy для тяжёлых страниц. Пользователь перемещается между разделами через BottomTabBar, а возврат с вложенных страниц происходит через нативную кнопку Telegram BackButton.

---

### Текущее состояние кода (после TASK-023)

**`frontend/src/main.tsx`** — SDK уже инициализирован через `init({ acceptCustomStyles: true })`, AppRoot обёртывает App.

**`frontend/src/App.tsx`** — уже использует `RouterProvider` с `router` из `./router`.

**`frontend/src/router.tsx`** — есть только 4 маршрута (`/`, `/my-clubs`, `/organizer`, `/profile`), без layout, без lazy loading, без вложенных страниц.

**`frontend/src/pages/`** — существуют 4 заглушки: `DiscoveryPage`, `MyClubsPage`, `OrganizerPage`, `ProfilePage` (каждая рендерит только `<Placeholder>`).

---

### Файлы для создания/изменения

| Файл | Действие | Описание |
|------|----------|----------|
| `src/router.tsx` | Изменить | Полный список маршрутов, Layout как parent route, React.lazy для всех страниц |
| `src/App.tsx` | Изменить | Убрать прямое использование RouterProvider — оставить только его (AppRoot уже в main.tsx) |
| `src/components/BottomTabBar.tsx` | Создать | 4 таба, видимость по pathname, активный таб |
| `src/hooks/useBackButton.ts` | Создать | Telegram BackButton show/hide/onClick |
| `src/pages/ClubPage.tsx` | Создать | Заглушка-placeholder (реализация в TASK-027) |
| `src/pages/ClubInteriorPage.tsx` | Создать | Заглушка-placeholder (реализация в TASK-028) |
| `src/pages/EventPage.tsx` | Создать | Заглушка-placeholder (реализация в TASK-029) |
| `src/pages/OrganizerClubManage.tsx` | Создать | Заглушка-placeholder (реализация в TASK-031) |
| `src/pages/InvitePage.tsx` | Создать | Заглушка-placeholder (реализация в TASK-034) |

> Заглушки нужны чтобы React.lazy мог их импортировать и роутер компилировался без ошибок. Реальная реализация страниц — в последующих задачах.

---

### Роутинг — полный список маршрутов

| Path | Page Component | BottomTabBar | BackButton | Примечание |
|------|---------------|:------------:|:----------:|------------|
| `/` | `DiscoveryPage` | Да (таб Discovery) | Нет | Корневой маршрут |
| `/my-clubs` | `MyClubsPage` | Да (таб My Clubs) | Нет | |
| `/organizer` | `OrganizerPage` | Да (таб Organizer) | Нет | |
| `/profile` | `ProfilePage` | Да (таб Profile) | Нет | |
| `/clubs/:id` | `ClubPage` | Да | Да | Детальная карточка клуба |
| `/clubs/:id/manage` | `OrganizerClubManage` | Да | Да | Только для организаторов |
| `/clubs/:id/interior` | `ClubInteriorPage` | Да | Да | Только для участников |
| `/events/:id` | `EventPage` | Да | Да | Только для участников |
| `/invite/:code` | `InvitePage` | Да | Да | Вступление по приглашению |

> **BottomTabBar** сейчас показывается на всех 9 страницах (`isTabBarRoute` в `BottomTabBar.tsx` разрешает также `^/clubs/[^/]+(/manage)?$`). Вопрос «скрывать ли на nested» — для дизайн-итерации (см. [docs/design/stack.md §3](../design/stack.md)).

**Архитектура роутера:** один родительский маршрут (`path: '/'`) с компонентом `<Layout />` содержит все дочерние маршруты через `children`. Это позволяет Layout рендерить BottomTabBar только там, где нужно.

---

### BottomTabBar — спецификация

**Файл:** `src/components/BottomTabBar.tsx`

**Интерфейс компонента:**
```tsx
// Нет пропсов. Компонент самостоятельно читает pathname через useLocation().
export const BottomTabBar: FC = () => { ... };
```

**Табы (4 штуки):**

| # | Label (рус.) | Path | Иконка |
|---|-------------|------|--------|
| 1 | Клубы | `/` | 🏠 (или telegram-ui icon) |
| 2 | Мои клубы | `/my-clubs` | 👥 |
| 3 | Организатор | `/organizer` | ⚙️ |
| 4 | Профиль | `/profile` | 👤 |

> Иконки: использовать эмодзи или SVG-иконки через telegram-ui если доступны. Главное — не custom HTML-теги для кнопок, использовать `<Cell>` или `<Tabbar>` из `@telegram-apps/telegram-ui`.

**Логика видимости:**

BottomTabBar рендерится в Layout. Виден на 4 tab-страницах + на страницах клубов (`/clubs/:id` и `/clubs/:id/manage`). Определяется через `useLocation()`:

```
TAB_PATHS = ['/', '/my-clubs', '/organizer', '/profile']
isTabBarRoute(pathname) =
  TAB_PATHS.includes(pathname)
  || /^\/clubs\/[^/]+(\/manage)?$/.test(pathname)
```

Скрыт на `/clubs/:id/interior`, `/events/:id`, `/invite/:code`. Если `isTabBarRoute(pathname) === false` — компонент возвращает `null`.

> Вопрос «скрывать ли на `/clubs/:id` и `/clubs/:id/manage` тоже» — для дизайн-итерации (nested-страницы обычно прячут bottom-bar, но тут решено оставить). См. `docs/design/stack.md §5`.

**Активный таб:** таб считается активным если `location.pathname === tab.path`. Для Discovery (`/`) — активен только при точном совпадении с `/`, не для `/clubs/...`.

**Навигация:** при клике на таб использовать `useNavigate()`, вызов `navigate(tab.path)`. Не использовать `<a href>` — только React Router.

**Позиционирование:** фиксирован внизу экрана. Стиль:
```css
position: fixed;
bottom: 0;
left: 0;
right: 0;
z-index: 100;
```

**Отступ контента:** Layout должен добавлять `padding-bottom` к основному контенту чтобы BottomTabBar не перекрывал последние элементы. Применять только на tab-страницах (когда `isTabPage === true`).

---

### BackButton — спецификация

**Файл:** `src/hooks/useBackButton.ts`

**Интерфейс хука:**
```tsx
// Без параметров. Хук подключается на detail-страницах.
// Автоматически показывает BackButton при mount, скрывает при unmount.
export const useBackButton = (): void => { ... };
```

**Поведение:**
1. При mount (useEffect): вызвать `backButton.show()` и `backButton.onClick(() => navigate(-1))`
2. При unmount (cleanup функция): вызвать результат `onClick` (для отписки) и `backButton.hide()`

**Импорт:**
```tsx
import { backButton } from '@telegram-apps/sdk-react';
import { useNavigate } from 'react-router-dom';
```

**Важно:** `backButton.onClick()` возвращает функцию-отписчик. Её нужно вызвать в cleanup.

**Полная реализация:**
```tsx
export const useBackButton = (): void => {
  const navigate = useNavigate();
  useEffect(() => {
    backButton.show();
    const off = backButton.onClick(() => navigate(-1));
    return () => {
      off();
      backButton.hide();
    };
  }, [navigate]);
};
```

**Использование в страницах:**

Хук подключается в начале каждой detail-страницы:
- `ClubPage` — вызывает `useBackButton()`
- `ClubInteriorPage` — вызывает `useBackButton()`
- `EventPage` — вызывает `useBackButton()`
- `OrganizerClubManage` — вызывает `useBackButton()`
- `InvitePage` — вызывает `useBackButton()`

**НЕ вызывать** на tab-страницах (Discovery, MyClubs, Organizer, Profile).

---

### Layout — спецификация

**Файл:** `src/router.tsx` (Layout компонент объявляется здесь же или выносится в `src/components/Layout.tsx`)

**Интерфейс:**
```tsx
// Нет пропсов. Читает pathname для определения типа страницы.
const Layout: FC = () => { ... };
```

**Структура рендеринга:**
```
<div style={{ paddingBottom: isTabPage ? '56px' : '0' }}>
  <Outlet />        ← сюда рендерится активный child route
</div>
<BottomTabBar />    ← внутри BottomTabBar сам решает показываться или нет
```

**Импорты в Layout:**
```tsx
import { Outlet, useLocation } from 'react-router-dom';
import { BottomTabBar } from './components/BottomTabBar';
```

**Структура `App.tsx`** (после изменения):

`App.tsx` остаётся минимальным — только `<RouterProvider router={router} />`. AppRoot уже в `main.tsx`, трогать его не нужно.

---

### Code Splitting — спецификация

**Какие страницы оборачивать в lazy:** все страницы (и тяжёлые, и заглушки) — для единообразия и подготовки к будущей реализации.

**Шаблон для named exports** (все текущие и будущие страницы используют named exports):
```tsx
const PageName = lazy(() =>
  import('./pages/PageName').then(m => ({ default: m.PageName }))
);
```

**Suspense fallback:** использовать `<Spinner />` из `@telegram-apps/telegram-ui` или простой текст "Загрузка...". Оборачивать каждый element в роутере:
```tsx
{ path: 'clubs/:id', element: <Suspense fallback={<Spinner />}><ClubPage /></Suspense> }
```

**Полный список lazy imports в router.tsx:**
```tsx
const DiscoveryPage = lazy(() => import('./pages/DiscoveryPage').then(m => ({ default: m.DiscoveryPage })));
const MyClubsPage = lazy(() => import('./pages/MyClubsPage').then(m => ({ default: m.MyClubsPage })));
const OrganizerPage = lazy(() => import('./pages/OrganizerPage').then(m => ({ default: m.OrganizerPage })));
const ProfilePage = lazy(() => import('./pages/ProfilePage').then(m => ({ default: m.ProfilePage })));
const ClubPage = lazy(() => import('./pages/ClubPage').then(m => ({ default: m.ClubPage })));
const ClubInteriorPage = lazy(() => import('./pages/ClubInteriorPage').then(m => ({ default: m.ClubInteriorPage })));
const EventPage = lazy(() => import('./pages/EventPage').then(m => ({ default: m.EventPage })));
const OrganizerClubManage = lazy(() => import('./pages/OrganizerClubManage').then(m => ({ default: m.OrganizerClubManage })));
const InvitePage = lazy(() => import('./pages/InvitePage').then(m => ({ default: m.InvitePage })));
```

---

### SDK init — спецификация

**Файл:** `src/main.tsx`

**Текущее состояние:** SDK уже инициализирован корректно:
```tsx
import { init } from '@telegram-apps/sdk-react';
init({ acceptCustomStyles: true });
```

**Изменять `main.tsx` в рамках TASK-025 не нужно.** AppRoot и init уже на месте.

**Для будущего TASK-024:** `backButton` из `@telegram-apps/sdk-react` доступен после вызова `init()`. Поскольку `init()` вызывается до рендера приложения в `main.tsx`, хук `useBackButton` может безопасно обращаться к `backButton` в любом компоненте.

---

### Итоговая структура router.tsx

```tsx
import { lazy, Suspense, FC } from 'react';
import { createBrowserRouter, Outlet, useLocation } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { BottomTabBar } from './components/BottomTabBar';

// Lazy imports для всех страниц
const DiscoveryPage = lazy(...);
// ... остальные (см. раздел Code Splitting)

const TAB_PATHS = ['/', '/my-clubs', '/organizer', '/profile'];

const Layout: FC = () => {
  const { pathname } = useLocation();
  const isTabPage = TAB_PATHS.includes(pathname);
  return (
    <>
      <div style={{ paddingBottom: isTabPage ? '56px' : '0' }}>
        <Outlet />
      </div>
      <BottomTabBar />
    </>
  );
};

export const router = createBrowserRouter([
  {
    path: '/',
    element: <Layout />,
    children: [
      { index: true, element: <Suspense fallback={<Spinner />}><DiscoveryPage /></Suspense> },
      { path: 'clubs/:id', element: <Suspense fallback={<Spinner />}><ClubPage /></Suspense> },
      { path: 'clubs/:id/interior', element: <Suspense fallback={<Spinner />}><ClubInteriorPage /></Suspense> },
      { path: 'events/:id', element: <Suspense fallback={<Spinner />}><EventPage /></Suspense> },
      { path: 'my-clubs', element: <Suspense fallback={<Spinner />}><MyClubsPage /></Suspense> },
      { path: 'organizer', element: <Suspense fallback={<Spinner />}><OrganizerPage /></Suspense> },
      { path: 'clubs/:id/manage', element: <Suspense fallback={<Spinner />}><OrganizerClubManage /></Suspense> },
      { path: 'profile', element: <Suspense fallback={<Spinner />}><ProfilePage /></Suspense> },
      { path: 'invite/:code', element: <Suspense fallback={<Spinner />}><InvitePage /></Suspense> },
    ],
  },
]);
```

---

### Corner Cases

| # | Ситуация | Ожидаемое поведение |
|---|----------|---------------------|
| 1 | Пользователь переходит напрямую по URL `/clubs/123` (без предыдущей истории) | BackButton показывается, при нажатии `navigate(-1)` — попадает на предыдущую запись в истории браузера. Если истории нет (прямой переход) — остаётся на той же странице или переходит на `/` (браузер обрабатывает `history.go(-1)` без эффекта). В Telegram Mini App прямые URL-переходы редки — приложение всегда стартует с `/`. |
| 2 | Пользователь нажимает BackButton на `/invite/:code` (пришёл через deep link) | `navigate(-1)` ведёт на предыдущую страницу в истории Router. Если стартовал прямо с инвайт-страницы — истории нет, `navigate(-1)` не сработает. Решение: в `InvitePage` использовать модифицированный хук с fallback: `navigate(canGoBack ? -1 : '/')`. Определять `canGoBack` через проверку `window.history.length > 1`. |
| 3 | Пользователь кликает на таб Discovery (`/`), находясь уже на `/` | Повторная навигация на тот же маршрут. React Router v7 не вызывает ошибку, но и не перезагружает страницу. Поведение корректно. |
| 4 | Пользователь кликает на таб Organizer (`/organizer`) находясь на `/clubs/123/manage` | Переход на `/organizer`, таб становится активным. BackButton скрывается (на `/organizer` нет `useBackButton`). BottomTabBar показывается. |
| 5 | BottomTabBar на `/clubs/123` | `isTabBarRoute('/clubs/123')` = `true` (regex match), BottomTabBar показывается. То же для `/clubs/123/manage`. |
| 6 | Spinner из telegram-ui не найден | Если `Spinner` не экспортируется из `@telegram-apps/telegram-ui`, использовать простой текстовый fallback: `<div style={{textAlign:'center', padding:'20px'}}>Загрузка...</div>`. Проверить наличие через импорт перед использованием. |
| 7 | `backButton` вызывается вне Telegram (в браузере при разработке) | `backButton.show()` может бросить исключение вне Telegram WebApp. Обернуть в `try/catch` или проверить `backButton.isSupported()` перед вызовом. Поведение в браузере: graceful degrade (кнопки нет, но страница работает). |
| 8 | Маршрут `/clubs/:id/interior` — вложенный path | `TAB_PATHS.includes('/clubs/abc/interior')` = `false`. Корректно: BottomTabBar скрыта, BackButton показывается. |
| 9 | Быстрое переключение табов (double-tap) | React Router обрабатывает последний navigate. Дублирования не происходит. |

---

### Критерии приёмки для тестирования

Проверять в браузере на `localhost:5173` (npm run dev):

**Роутинг:**
- [ ] Открыть `/` — страница DiscoveryPage рендерится (Placeholder "Клубы")
- [ ] Открыть `/my-clubs` — страница MyClubsPage рендерится
- [ ] Открыть `/organizer` — страница OrganizerPage рендерится
- [ ] Открыть `/profile` — страница ProfilePage рендерится
- [ ] Открыть `/clubs/123` — страница ClubPage рендерится (заглушка)
- [ ] Открыть `/clubs/123/interior` — страница ClubInteriorPage рендерится (заглушка)
- [ ] Открыть `/events/456` — страница EventPage рендерится (заглушка)
- [ ] Открыть `/clubs/789/manage` — страница OrganizerClubManage рендерится (заглушка)
- [ ] Открыть `/invite/abc` — страница InvitePage рендерится (заглушка)

**BottomTabBar:**
- [ ] На `/` — BottomTabBar видна, таб "Клубы" выделен активным состоянием
- [ ] На `/my-clubs` — BottomTabBar видна, таб "Мои клубы" выделен
- [ ] На `/organizer` — BottomTabBar видна, таб "Организатор" выделен
- [ ] На `/profile` — BottomTabBar видна, таб "Профиль" выделен
- [ ] На `/clubs/123` — BottomTabBar видна (regex match)
- [ ] На `/clubs/123/manage` — BottomTabBar видна (regex match)
- [ ] На `/clubs/123/interior` — BottomTabBar НЕ видна
- [ ] На `/events/456` — BottomTabBar НЕ видна
- [ ] На `/invite/abc` — BottomTabBar НЕ видна
- [ ] Клик по табу "Мои клубы" с `/` → URL меняется на `/my-clubs`
- [ ] Клик по табу "Клубы" с `/my-clubs` → URL меняется на `/`

**BackButton (в Telegram или через mock):**
- [ ] На `/clubs/123` — BackButton в Telegram показывается
- [ ] На `/` — BackButton в Telegram скрыта
- [ ] Нажать BackButton на `/clubs/123` → возврат на предыдущую страницу

**Code splitting:**
- [ ] В Network вкладке DevTools при первом открытии `/clubs/123` — загружается отдельный chunk для ClubPage
- [ ] При переходе между страницами — Suspense fallback (Spinner или "Загрузка...") кратковременно появляется

**Build:**
- [ ] `npm run build` проходит без TypeScript ошибок
- [ ] Console в браузере: 0 ошибок, 0 предупреждений
