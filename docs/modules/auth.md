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
3. Отсортировать остальные пары по ключу, объединить `\n`
4. `secret_key = HMAC-SHA256("WebAppData", bot_token)`
5. `computed = HMAC-SHA256(secret_key, data_check_string)`
6. Сравнить hex(computed) с hash
7. **Dev-профиль**: пропускать проверку (`spring.profiles.active=dev`)

### JWT claims
```json
{ "user_id": "uuid-string", "telegram_id": 123456789, "iat": 0, "exp": 86400 }
```
- Алгоритм: HS256
- TTL: 24 часа (86400000 мс)
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
