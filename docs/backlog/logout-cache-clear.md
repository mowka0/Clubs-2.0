# Logout: clear TanStack Query cache

Surfaced 2026-04-25 by Security review on `feature/pre-design-stores`
(severity: Low). Не блокирует мерж — UI logout-флоу сейчас отсутствует
(`useAuthStore.logout()` определён, но ни одна страница его не вызывает).
Зафиксировать на момент когда logout появится в UI.

## Что не так

`useAuthStore.logout()` сегодня (`frontend/src/store/useAuthStore.ts`):
- сбрасывает `apiClient.token`
- зануляет `user`, `isAuthenticated`, `error`

Но **не трогает кеш TanStack Query**. После миграции server state
(`feature/pre-design-stores`) в `QueryClient` живут чувствительные данные
прошлого пользователя:
- `queryKeys.applications.mine()` — заявки текущего юзера в закрытые клубы
- `queryKeys.clubs.my()` — список клубов где состоит юзер
- `queryKeys.clubs.detail(id, ...)` ветка — для админских клубов: members,
  applications, finances

Если в одном Telegram-аккаунте сделают logout → login другим аккаунтом
(теоретический сценарий мульти-юзера; для Mini App пока не реализован),
старые данные останутся видимы в UI **до** первого invalidate / refetch.

`gcTime: 5 min` ограничивает окно, но это не нулевой риск.

## Severity

**Low.** Прямой эксплуатации нет:
- Logout-кнопки в UI пока **нет** (только программный `logout()` в store)
- Telegram Mini App = один аккаунт = одна сессия
- Через 5 минут `gcTime` GC всё равно очистит

Категория OWASP: A02 Cryptographic Failures (sensitive data lifecycle),
boundary-case.

## Fix (когда появится UI logout)

В `useAuthStore.logout()` (или в обёртке-хуке `useLogout()`):

```ts
const qc = useQueryClient();
const logout = useAuthStore((s) => s.logout);
return () => {
  logout();
  qc.clear();   // wipes both active queries и cached data
};
```

`qc.clear()` атомарен и проще `removeQueries({ queryKey: [...] })` для каждого
домена. Минус — следующий же mount страницы триггерит re-fetch всего, но это
ожидаемое поведение после logout.

## Acceptance

- При вызове `logout()` `queryClient.getQueryCache().getAll()` возвращает `[]`
- Тест в `test/queries/` или `test/pages/` (когда UI logout появится):
  setup → fetch any query → logout → assert cache empty

## Когда выполнять

Одновременно с PR который добавит logout-кнопку в UI (Profile / settings).
До того как logout появится в UI — фикс не нужен (нечем триггернуть).
