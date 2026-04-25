# Auth: миграция JWT в HttpOnly cookie

Запрошено пользователем 2026-04-25 при тесте PR #24 (`feature/pre-design-stores`) на staging.

## Что не так

`apiClient.token` и `useAuthStore.user` живут только в JS-памяти. На refresh страницы внутри Mini App теряются → `useAuthStore.login()` → `apiClient.authenticate()` → `POST /api/auth/telegram` с `initData`.

Backend (PR #21, `TelegramInitDataValidator.kt:100`) отвергает `initData` старше 5 минут. Получаем:
- Пользователь открыл Mini App в 12:00 → auth ok, JWT выдан
- Через ≥5 минут пользователь нажал refresh
- App пытается re-auth с тем же `initData` (Telegram отдаёт оригинальный, не свежий) → backend rejects → UI: «Не удалось авторизоваться. Откройте приложение через Telegram.»

Refresh ломается через 5 минут после открытия. Это блокирует базовый use case.

## Что **не** делать

**`localStorage`** — security.md явно запрещает (XSS-уязвимо, токен живёт долго).

**`sessionStorage`** — рассмотрено и отклонено пользователем. С точки зрения XSS marginally лучше localStorage (tab-scoped, очищается при закрытии Mini App), но всё ещё доступно из любого JS на странице. Не radix-grade security.

## Правильное решение: HttpOnly cookie

Backend → `POST /api/auth/telegram` ставит:
```
Set-Cookie: session=<JWT>;
            HttpOnly; Secure; SameSite=Strict;
            Max-Age=3600; Path=/api
```

JS вообще не видит токен — XSS бессилен. Cookie автоматически отправляется браузером на запросы к `/api/*`.

### Изменения backend (~1.5ч)

1. `TelegramAuthController.authenticate()` — вместо `{ token, user }` в body отдавать `{ user }` + `Set-Cookie` header
2. Spring Security: настроить чтение JWT из `Cookie` header (а не из `Authorization: Bearer`)
3. `Set-Cookie` flags:
   - `HttpOnly` — JS недоступен
   - `Secure` — только HTTPS (проверить что staging прокси не ломает)
   - `SameSite=Strict` — защита от CSRF (Mini App single-origin, должно работать)
   - `Max-Age=3600` — 1 час, как текущий JWT TTL
   - `Path=/api` — узкий scope

### Изменения frontend (~1.5ч)

1. `apiClient.request()` — добавить `credentials: 'include'` ко всем `fetch()` вызовам
2. Удалить логику с `this.token` / `setToken` / `clearToken` — больше не нужна
3. `useAuthStore` — `user` всё ещё нужен в state (для UI: ProfilePage, ClubPage используют `user.id` / `user.firstName`). Получать через `GET /api/users/me` после успешного auth (новый endpoint, либо include в response от `/api/auth/telegram`)
4. `Layout` auth init: 
   - Попробовать `GET /api/users/me` с включённым cookie
   - 200 → set user, isAuthenticated=true
   - 401 → запустить `/api/auth/telegram` (для первой сессии или после expiry)
   - 401 → ошибка → показать «Откройте через Telegram»

### CSRF (важно!)

`SameSite=Strict` блокирует CSRF в большинстве сценариев. Но если есть mutating endpoints (POST/PUT/DELETE) и потенциальные cross-site embedding — добавить CSRF-token в response, валидировать на mutating запросах.

Для Mini App single-origin — `SameSite=Strict` достаточно. Если будет deploy в нескольких origin (production + staging) — нужно `SameSite=Lax` + CSRF-token.

### Тесты

- Backend: integration test что auth ставит правильный Set-Cookie
- Frontend: e2e что после refresh user остаётся залогинен (нужен Playwright/Cypress, у нас пока нет)
- Manual: refresh через 10 минут — должно работать

## Связанные

- `docs/backlog/auth-pre-release-checks.md` — JWT 1ч re-auth manual check, become irrelevant после этой миграции (cookie expiry handles it)
- `.claude/rules/security.md` § «Authentication / JWT» — обновить рекомендацию: HttpOnly cookie вместо «токен в памяти»
- PR #21 (`afbb4fe`) — auth_date 5min check всё ещё актуален как защита от replay, не нужно ослаблять

## Когда фиксить

Желательно **до** публичного релиза. Refresh внутри session — базовый ожидаемый сценарий, текущее поведение неприемлемо для пользователей.

Кандидат в ветку `feature/auth-httponly-cookie`. Оценка ~3-4 часа полная (backend + frontend + integration test).
