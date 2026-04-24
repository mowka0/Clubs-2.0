# Module: Auth

## TASK-005 — Аутентификация: Telegram initData + JWT

### Endpoints
```
POST /api/auth/telegram
  Body: { initData: string }
  Response 200: { token: string, user: UserDto }

GET /api/users/me
  Headers: Authorization: Bearer <JWT>
  Response 200: UserDto
```

### Файловая структура
```
auth/
  TelegramInitDataValidator.kt  — HMAC-SHA256 валидация initData
  JwtService.kt                 — генерация/парсинг JWT (jjwt 0.12.x)
  JwtAuthenticationFilter.kt   — OncePerRequestFilter: Bearer → SecurityContext
  AuthDto.kt                    — AuthRequest, AuthResponse
  AuthService.kt                — оркестрация: validate → upsert user → JWT
  AuthController.kt             — POST /api/auth/telegram
common/security/
  SecurityConfig.kt             — Spring Security filter chain
  AuthenticatedUser.kt          — data class для @AuthenticationPrincipal
user/
  UserRepository.kt             — jOOQ: findByTelegramId, findById, upsert
  UserService.kt                — getUserById
  UserController.kt             — GET /api/users/me
  UserDto.kt                    — UserDto data class
```

### HMAC-SHA256 алгоритм (TelegramInitDataValidator)
1. Разбить `initData` по `&`, получить пары `key=value`
2. Извлечь `hash`, удалить его из набора
3. **URL-декодировать значения** (`URLDecoder.decode(value, UTF_8)`) — Telegram передаёт значения URL-encoded, а `data_check_string` должен содержать декодированные значения (так же как `new URLSearchParams()` в JS)
4. **Проверить `auth_date`**: отвергнуть если старше 5 минут (`AUTH_DATA_MAX_AGE_SECONDS = 300`) либо в будущем более чем на 60с (`CLOCK_SKEW_TOLERANCE_SECONDS`). Защита от replay-атак с перехваченным initData
5. Отсортировать остальные пары по ключу, объединить `\n`
6. `secret_key = HMAC-SHA256("WebAppData", bot_token)`
7. `computed = HMAC-SHA256(secret_key, data_check_string)`
8. Сравнить hex(computed) с hash
9. **Dev-профиль**: пропускать всю проверку (HMAC + auth_date) — `spring.profiles.active=dev`

> **Важно:** без URL-декодирования HMAC всегда будет неверным, так как `user` поле содержит `%7B%22id%22...` вместо `{"id":...}`.
> **auth_date** проверяется **до** HMAC — чтобы не тратить CPU на expired initData. Без этой проверки перехваченный валидный initData работает бесконечно.

### JWT claims
```json
{ "user_id": "uuid-string", "telegram_id": 123456789, "iat": 0, "exp": 3600 }
```
- Алгоритм: HS256
- TTL: **1 час** (`JWT_EXPIRATION=3600000` мс) — по security.md 15-60 мин. Frontend переавторизуется прозрачно через `apiClient.authenticate()` на 401-ответ с fresh initData (`authInFlight` deduplication, см. [frontend-core.md TASK-024](./frontend-core.md))
- Секрет: `${JWT_SECRET}` из env (минимум 32 символа)
- Кастинг `telegram_id`: `(claims["telegram_id"] as? Number)?.toLong()`

### SecurityConfig правила
| Путь | Доступ |
|------|--------|
| `/actuator/**` | permitAll |
| `/api/auth/**` | permitAll |
| `/api/**` | authenticated (JWT) |
| Остальные | permitAll |

- Без токена → 401 (AuthenticationEntryPoint.sendError(401))
- Чужой/просроченный токен → 401

### Rate limiting

Управляется `RateLimitFilter` (bucket4j). Два раздельных bucket pool'а по разным лимитам:

| Путь | Лимит | Причина |
|------|-------|---------|
| `/api/auth/*` | **5/мин** на IP | Агрессивная защита от brute-force HMAC (security.md § Authentication) |
| Остальные `/api/**` | **60/мин** на user (или IP если не авторизован) | Штатная защита от flood |
| `/actuator/health` | без лимита | Healthcheck для Docker / Traefik |

При превышении — `429 Too Many Requests` с JSON `{"error":"Too many requests. Limit: N per minute."}`. Значение N подставляется динамически.

Ключ bucket'а: `user:<userId>` для авторизованных, `ip:<clientIp>` для анонимных (парсится из `X-Forwarded-For` если есть, иначе `remoteAddr`).

### User upsert (UserRepository)
- Ищем по `telegram_id`
- Если нет → INSERT с новым UUID
- Если есть → UPDATE (username, first_name, last_name, avatar_url, updated_at)

### Corner Cases
| Ситуация | Код | Сообщение |
|----------|-----|-----------|
| `initData` пустая | 400 | "initData must not be blank" |
| Неверная HMAC подпись (prod) | 400 | "Invalid Telegram initData signature" |
| Нет поля `user` в initData | 400 | "Missing user data in initData" |
| Нет `id` в user JSON | 400 | "Missing user id in initData" |
| GET /api/** без токена | 401 | "Unauthorized" |
| GET /api/** с протухшим токеном | 401 | "Unauthorized" |
| GET /actuator/health без токена | 200 | `{"status":"UP"}` |
