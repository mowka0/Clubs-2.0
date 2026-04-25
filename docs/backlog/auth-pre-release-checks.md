# Auth security — pre-release manual checks

Проверки которые отложены при мерже PR #21 (`bugfix/auth-security-hardening`,
коммит [a374a22](../../../../commit/a374a22)) — выполняем **перед production-релизом**,
до открытого запуска для пользователей.

Связанные файлы реализации:
- [`backend/.../auth/TelegramInitDataValidator.kt`](../../backend/src/main/kotlin/com/clubs/auth/TelegramInitDataValidator.kt) — `auth_date` check
- [`backend/.../resources/application.yml`](../../backend/src/main/resources/application.yml) — `JWT_EXPIRATION=3600000`
- [`docker-compose.prod.yml`](../../docker-compose.prod.yml) — prod-default 3600000

---

## Check 1 — JWT TTL = 1 час, прозрачная переавторизация

**Цель:** убедиться что после истечения 1ч `apiClient.authenticate()` фронта
автоматически делает re-login через fresh initData без видимого разлогина для юзера.

**Шаги:**
1. Открыть Mini App в Telegram, залогиниться.
2. Включить debug-режим webview:
   - **iOS:** Settings → 10 раз тапнуть на иконку → Allow Web View Inspection (Safari → Develop → [device])
   - **Android:** Settings → 2 раза тап на номер версии в самом низу → Enable WebView Debug (chrome://inspect/#devices)
   - Подробнее: см. [docs/design/telegram-constraints.md §29 / Debug](../design/telegram-constraints.md)
3. В DevTools → Network tab → отфильтровать по `/api/auth/`.
4. Оставить Mini App активным **55-65 минут** (фоном — не закрывать вкладку).
5. Пощёлкать любую кнопку (например, открыть клуб).

**Ожидание:**
- В Network должен появиться **второй** `POST /api/auth/telegram` от того же user'а
  (первый — на старте, второй — после истечения 1ч).
- Никаких 401-ошибок видимых юзеру, никакого «Не удалось авторизоваться» placeholder'а.
- В backend-логах Coolify: пара INFO `Auth success: userId=... telegramId=...` для одного и того же `userId`.

**Если не работает:**
- Если фронт показал «Не удалось авторизоваться» — проверить что `apiClient.authenticate()`
  не сломан (`authInFlight` deduplication).
- Если 401-цикл бесконечный — `auth_date` может слетать на dev-mock (см. Check 2).

---

## Check 2 — Replay-attack: старый initData отвергается

**Цель:** убедиться что `auth_date` блокирует повтор перехваченного initData
после 5 минут.

**Шаги:**
1. Открыть Mini App в Telegram.
2. В DevTools → Network → найти запрос `POST /api/auth/telegram` → скопировать
   payload — поле `initData` (URL-encoded строка вида
   `query_id=...&user=...&auth_date=...&hash=...`).
3. Подождать **>= 6 минут** (5 минут TTL + запас).
4. В терминале:
   ```bash
   curl -i -X POST https://77-42-23-177.sslip.io/api/auth/telegram \
     -H "Content-Type: application/json" \
     -d '{"initData":"<скопированный_initData>"}'
   ```

**Ожидание:**
- Ответ: **400 Bad Request** + `{"error":"Bad Request","message":"Invalid Telegram initData signature"}`
  (HMAC формально валиден, но `verifyHmac()` сначала проверит `auth_date` и вернёт `false`,
  затем `AuthService` бросит `ValidationException("Invalid Telegram initData signature")`).
- В backend-логах Coolify: WARN-запись:
  ```
  HMAC validation: auth_date too old. now=<...> auth_date=<...> age_sec=<...> max_age_sec=300
  ```
- В логах НЕ должно быть:
  ```
  HMAC validation failed — token_len=...
  ```
  (это сообщение для случая когда auth_date свежий, но hash не совпал —
  здесь не тот случай).

**Если не работает:**
- Если 200 + JWT вернулся — проверить что `auth_date` check в коде `TelegramInitDataValidator`
  выполняется **до** HMAC, а не после.
- Если получилось 200 без какого-либо WARN в логах — возможно бэк деплоится в dev-профиле
  (где вся проверка skipped) — проверить `SPRING_PROFILES_ACTIVE` в Coolify (должно быть `prod`).

---

## Когда выполнять

Перед открытым запуском (когда появятся реальные пользователи). Если в процессе
дальнейшей разработки auth-флоу затронется ещё раз — пере-проверить **обе**
проверки в той же сессии.

## Кто отмечает выполненным

После прохождения обеих проверок — пользователь либо удаляет этот файл, либо
помечает в статусе ниже:

```
[ ] Check 1 — JWT TTL & re-auth — passed on YYYY-MM-DD by <name>, build <git-sha>
[ ] Check 2 — Replay-attack rejection — passed on YYYY-MM-DD by <name>, build <git-sha>
```
