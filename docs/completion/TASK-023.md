## Выполнено: TASK-023

### Изменённые файлы
- `frontend/package.json`
- `frontend/package-lock.json`
- `frontend/vite.config.ts`
- `frontend/tsconfig.json`
- `frontend/tsconfig.node.json`
- `frontend/index.html`
- `frontend/.gitignore`
- `frontend/src/main.tsx`
- `frontend/src/App.tsx`
- `frontend/src/router.tsx`
- `frontend/src/pages/DiscoveryPage.tsx`
- `frontend/src/pages/MyClubsPage.tsx`
- `frontend/src/pages/OrganizerPage.tsx`
- `frontend/src/pages/ProfilePage.tsx`
- `frontend/src/api/apiClient.ts`
- `frontend/src/types/api.ts`
- `frontend/src/components/.gitkeep`
- `frontend/src/store/.gitkeep`
- `frontend/src/hooks/.gitkeep`

### Acceptance Criteria
- [x] `frontend/` создан с Vite + React + TypeScript
- [x] Зависимости установлены: react@19, react-dom@19, @telegram-apps/sdk-react@3, @telegram-apps/telegram-ui@2, zustand@5, react-router-dom@7
- [x] `npm install --legacy-peer-deps` (конфликт telegram-ui и React 19)
- [x] `vite.config.ts` с proxy `/api` -> `http://localhost:8080`
- [x] `tsconfig.json` со strict mode (noImplicitAny: true)
- [x] Базовая структура папок: `src/api/`, `src/components/`, `src/pages/`, `src/store/`, `src/hooks/`, `src/types/`
- [x] `src/main.tsx` -- точка входа с init() от @telegram-apps/sdk-react
- [x] `src/App.tsx` -- RouterProvider
- [x] `src/router.tsx` -- базовый роутер с заглушками страниц
- [x] `npm run build` проходит без TypeScript ошибок

### Test Steps
1. `cd frontend && npm install --legacy-peer-deps` -- 120 packages installed
2. `npm run build` -- 0 TypeScript ошибок, dist/ создан (343 kB JS, 46 kB CSS, 109.7 kB gzipped)
3. `npm run dev` -- стартует на порту 5173, HTTP 200

### Build
- npm run build: pass

### Коммит
`chore(frontend): initialize React + TypeScript + Vite project`

### Решения и заметки
- `@telegram-apps/sdk-react` v3.3.9 больше НЕ экспортирует `SDKProvider`. В v3 инициализация SDK выполняется через вызов `init()` из `@telegram-apps/sdk-react` (функция, не компонент). Код `main.tsx` адаптирован: `init({ acceptCustomStyles: true })` вместо `<SDKProvider>`.
- `tsconfig.node.json` требует `"composite": true` при использовании project references, и удалён `"noEmit": true` (несовместим с composite).
- Bundle size: 109.74 kB gzipped -- в пределах целевого показателя 200 kB.
- `retrieveLaunchParams` доступен через реэкспорт из `@telegram-apps/bridge` в `@telegram-apps/sdk` -> `@telegram-apps/sdk-react`.
