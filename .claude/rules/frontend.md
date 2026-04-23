---
paths:
  - "frontend/**/*.ts"
  - "frontend/**/*.tsx"
---

# Frontend правила (React 19 / TypeScript / Telegram Mini Apps)

## Структура — feature-based

Группируй по фичам, не по типам.

```
frontend/src/
├── features/
│   ├── clubs/
│   │   ├── components/        # UI компоненты фичи
│   │   ├── hooks/             # useClubs, useClubById
│   │   ├── api/               # clubs.api.ts
│   │   ├── mappers/           # API response → UI model
│   │   └── types.ts           # типы фичи
│   ├── events/
│   │   └── ...
│   └── auth/
│       └── ...
├── shared/
│   ├── ui/                    # atoms, общие UI-компоненты
│   ├── lib/                   # utils (formatDate, generateSlug)
│   ├── api/                   # axios client, базовые типы
│   └── hooks/                 # общие хуки
├── pages/                     # страницы = маршруты, тонкие
└── app/                       # провайдеры, роутинг, глобальный setup
```

**Правила:**
- Фича не импортирует из другой фичи напрямую — только через `shared/`
- Если две фичи используют один код — вынеси в `shared/`
- Pages — тонкие, вся логика в feature

---

## State management — главное правило

**Server state ≠ Client state. Это два РАЗНЫХ инструмента.**

| Тип | Инструмент | Примеры |
|---|---|---|
| **Server state** | TanStack Query | Список клубов, профиль юзера, события |
| **Client state** | Zustand / useState | Выбранный таб, открыт ли модал, токен в памяти |

### ❌ Главный анти-паттерн
Фетчить с API → класть в Zustand → синхронизировать руками. Получаешь stale data, гонки, баги с кэшем.

### ✅ Правильно
```typescript
// Server state через TanStack Query
const { data: clubs, isLoading } = useQuery({
    queryKey: ['clubs'],
    queryFn: () => clubsApi.list(),
})

// Client state через Zustand — только реально клиентское
const useAuthStore = create<AuthStore>((set) => ({
    token: null,
    setToken: (token) => set({ token }),
}))

// Локальное — useState
const [isOpen, setIsOpen] = useState(false)
```

### Правила выбора
- Данные с API? → TanStack Query
- Глобальное клиентское (токен, тема)? → Zustand
- Нужно только в одном компоненте и его детях? → `useState` + prop drilling / Context
- Форма? → React Hook Form

### Prop drilling 2-3 уровня — норма
Не тащи всё в глобальный store "чтобы не передавать через пропсы".

---

## Маппинг данных

Вся логика маппинга (API response → UI model, форма → API request) — в **отдельных файлах** в `features/<name>/mappers/`.

**❌ Плохо** — маппинг в компоненте или хуке:
```tsx
function ClubCard({ club }: Props) {
    const name = club.name.toUpperCase()
    const date = new Date(club.createdAt).toLocaleDateString('ru')
    // ...
}
```

**✅ Хорошо** — вынесено в mapper:
```typescript
// features/clubs/mappers/club.mapper.ts
export function toClubViewModel(dto: ClubDto): ClubViewModel {
    return {
        id: dto.id,
        displayName: dto.name.toUpperCase(),
        formattedDate: formatDate(dto.createdAt),
    }
}

// features/clubs/hooks/useClubs.ts
export function useClubs() {
    return useQuery({
        queryKey: ['clubs'],
        queryFn: () => clubsApi.list(),
        select: (data) => data.map(toClubViewModel),
    })
}
```

---

## TypeScript — строго

### tsconfig
```json
{
  "strict": true,
  "noUncheckedIndexedAccess": true,
  "noImplicitReturns": true
}
```

### Запреты
- `any` — запрещён. Если тип неизвестен → `unknown` + type guard
- `as Type` касты — это ложь компилятору. Используй type guards, `satisfies`, discriminated unions

### Discriminated Unions для состояний
Вместо `isLoading`, `error`, `data` отдельно:
```typescript
type FetchState<T> =
    | { status: 'idle' }
    | { status: 'loading' }
    | { status: 'error'; error: Error }
    | { status: 'success'; data: T }

// компилятор заставит обработать все случаи в switch
switch (state.status) {
    case 'idle': return null
    case 'loading': return <Spinner />
    case 'error': return <ErrorView error={state.error} />
    case 'success': return <DataView data={state.data} />
}
```

### Branded types для ID
```typescript
type UserId = string & { readonly __brand: 'UserId' }
type ClubId = string & { readonly __brand: 'ClubId' }

function getUser(id: UserId) { /* ... */ }

const clubId: ClubId = '...' as ClubId
getUser(clubId) // ❌ TS error — типы несовместимы
```

### Props — explicit interface
```typescript
interface ClubCardProps {
    club: ClubViewModel
    onSelect?: (id: ClubId) => void
}

function ClubCard({ club, onSelect }: ClubCardProps) { /* ... */ }
```

---

## Компоненты

### Размер
- Компонент > 150-200 строк → разбить
- Если делает 3+ вещи → разбить по Single Responsibility

### Presentational + Container
Удобно когда UI переиспользуется:
- **Presentational** — только UI, принимает пропсы, рендерит, не знает про API
- **Container** — подключает данные (хуки, query), передаёт в presentational

```tsx
// Presentational — в features/clubs/components/ClubList.tsx
interface Props { clubs: ClubViewModel[] }
function ClubList({ clubs }: Props) {
    return <div>{clubs.map(c => <ClubCard key={c.id} club={c} />)}</div>
}

// Container — в features/clubs/components/ClubListContainer.tsx
function ClubListContainer() {
    const { data, isLoading } = useClubs()
    if (isLoading) return <Spinner />
    return <ClubList clubs={data ?? []} />
}
```

### ❌ НИКОГДА не создавай компоненты внутри рендера
```tsx
// ❌ React пересоздаёт Child на каждом рендере
function Parent() {
    const Child = () => <div>...</div>
    return <Child />
}
```

---

## Хуки

### "You Might Not Need an Effect"
`useEffect` — только для синхронизации с внешним миром (подписка, timer, DOM API).

| Задача | Решение |
|---|---|
| Вычислить из пропсов | делай в рендере |
| Кешировать дорогое вычисление | `useMemo` |
| Реагировать на действие юзера | обработчик события |
| Синхронизация с сервером | TanStack Query |
| Синхронизация с внешним (WebSocket, timer, DOM event) | `useEffect` с cleanup |

### Cleanup обязателен
```tsx
useEffect(() => {
    const timer = setInterval(...)
    return () => clearInterval(timer)  // всегда!
}, [])
```

### Custom hooks — одна ответственность
Один хук — одна задача. Не "useEverything".

---

## Re-renders

**Главный источник лишних ререндеров** — родитель рендерится → все дети ререндерятся, даже если пропсы не менялись.

### Что делать
- Мелкие компоненты — ререндерятся быстрее
- `React.memo` для тяжёлых компонентов в списках
- Стабильные ссылки для пропсов: `useCallback`, `useMemo`

### Что НЕ делать
- Не мемоизируй всё подряд — создаёт сложность без выгоды
- React 19 Compiler сам мемоизирует — ручная оптимизация только для измеренных проблем

### Правило
**Не готов замерять performance — не оптимизируй.** Сначала измерь (React DevTools Profiler), потом оптимизируй конкретное узкое место.

---

## Формы

React Hook Form — стандарт. `useState` для форм — анти-паттерн (ререндер на каждую букву).

```tsx
import { useForm } from 'react-hook-form'

function CreateClubForm() {
    const { register, handleSubmit, formState: { errors } } = useForm<CreateClubInput>()
    const onSubmit = (data: CreateClubInput) => { /* ... */ }
    return <form onSubmit={handleSubmit(onSubmit)}>...</form>
}
```

---

## Именование

- **Компоненты** — PascalCase: `ClubCard`, `EventList`
- **Хуки** — `use*`: `useClubs`, `useAuth`
- **Файлы компонентов** — PascalCase: `ClubCard.tsx`
- **Файлы не-компонентов** — kebab-case или camelCase: `clubs.api.ts`, `club.mapper.ts`
- **Типы/интерфейсы** — PascalCase: `ClubViewModel`, `CreateClubInput`
- **Константы** — SCREAMING_SNAKE_CASE: `MAX_CLUBS_PER_USER`

---

## Telegram Mini Apps SDK

Используется **SDK v3** (`@telegram-apps/sdk-react`). API отличается от v2.

### Правила
- `init()` вызывается **один раз** в `main.tsx` до использования любых SDK-функций
- `initData.restore()` — обязательно после `init()` в v3 (иначе `initData.raw()` вернёт undefined)
- `retrieveLaunchParams().initDataRaw` — единственный источник initData для авторизации
- **Mock initData в production запрещён** — только через `VITE_MOCK_INIT_DATA` в `.env.development`
- SDK подписки (BackButton, MainButton) — через `useEffect` с обязательным cleanup

### BackButton
- Показывать на detail-страницах, скрывать на tab-страницах
- Один источник правды для навигации — `useNavigate()` (не собственные реализации)

### Deep Links
- Формат: `t.me/<bot>?startapp=<param>`
- Парсить `startParam` из `retrieveLaunchParams()` при старте app
- Обработка в одном месте (DeepLinkHandler в корне), не в каждой странице

### HapticFeedback
- Использовать для подтверждения действий (`impactOccurred('medium')` на submit)
- `notificationOccurred('error')` на ошибках

---

## Общие утилиты

Универсальные функции — в `shared/lib/`:
- `formatDate`, `formatPrice`, `formatRelativeTime`
- `parseInitData`, `generateSlug`
- другие pure helpers

**Правило:** если функция используется в 3+ местах — вынеси в `shared/lib/`.

---

## Ошибки

- Глобальный `ErrorBoundary` на корне приложения
- 401 → сбросить токен, повторная авторизация через initData
- 5xx → показать юзер-френдли сообщение ("Не удалось загрузить, попробуйте позже")
- TanStack Query: `onError` для тостов, глобальный default через `QueryClient`

---

## Чего избегать

- **Серверные данные в Zustand** — использовать TanStack Query
- **Маппинг в компоненте** — выносить в mapper
- **`any` и `as Type`** — использовать `unknown` + type guards
- **Компонент > 200 строк** — разбивать
- **Логика в компоненте** — выносить в custom hooks или utils
- **`useEffect` для производных значений** — делать в рендере
- **Отсутствие cleanup в `useEffect`** — обязательно при подписках/таймерах
- **Универсальная логика в 3+ местах** — выносить в `shared/lib/`
