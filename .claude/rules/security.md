# Правила Security

Правила безопасности для всего проекта. Проверяются Security-агентом и должны соблюдаться всеми Developer-агентами.

## OWASP Top 10 (2025/2026) — что проверять

### A01. Broken Access Control
Самая распространённая уязвимость (3.73% тестируемых приложений).
- Авторизация проверяется на **каждом endpoint**, не только на "чувствительных"
- Роль юзера проверяется **до** выполнения операции
- Нет "angular-style" проверок на клиенте — авторизация серверная
- **IDOR** (Insecure Direct Object Reference): `/api/clubs/{id}` — проверить что юзер имеет доступ именно к этому клубу
- **SSRF** (включён в A01): не делать HTTP-запросы по user-provided URL без whitelist

### A02. Cryptographic Failures
- Пароли — bcrypt/argon2, никогда plain или MD5/SHA1
- HTTPS везде (HTTP редиректится)
- Чувствительные данные (tokens, PII) не в URL (они попадают в логи)
- См. § "Authentication / JWT" ниже для требований к секретам и JWT

### A03. Software Supply Chain Failures (новое)
- Dependencies с фиксированными версиями (lockfile в git)
- Periodic `npm audit` / `gradle dependencyUpdates`
- Не использовать пакеты без активной поддержки
- GitHub Actions pinned к SHA, не к tag

### A04. Insecure Design
- Threat modeling на этапе спеки (Analyst прорабатывает что может пойти не так)
- Rate limiting заложен в архитектуру, не прикручен потом
- Защита от automated attacks — captcha/OTP где уместно

### A05. Security Misconfiguration
- Дефолтные creds изменены (postgres, redis, admin-панели)
- Debug эндпоинты (/actuator) закрыты в prod или за auth
- Error messages не светят stacktrace пользователю
- CORS настроен явно (whitelist origins), не `*`
- HTTP security headers: CSP, X-Frame-Options, X-Content-Type-Options

### A06. Vulnerable and Outdated Components
- Base images: pinned версии, regular updates
- Dependencies: следить за CVE, обновлять
- Не использовать deprecated библиотеки

### A07. Identification and Authentication Failures
- Rate limit на login/auth — агрессивно (см. раздел Rate Limiting)
- Session timeout — короткий
- MFA где реально нужно (админ-панели, финансы)
- Credential stuffing защита

### A08. Software and Data Integrity Failures
- CI/CD защищён от supply chain (OIDC, signed commits опционально)
- Не исполнять код из ненадёжных источников (eval, deserialization)
- Signed images (cosign) — для зрелых проектов

### A09. Security Logging and Monitoring Failures
- Лог событий безопасности: логины, отказы в авторизации, rate limit hits
- Алерты на подозрительные паттерны (много 401/403 подряд от одного IP)
- Логи доступны для аудита, ротируются, не подделываются

### A10. Mishandling of Exceptional Conditions (новое)
- Ошибки не проглатываются
- При ошибке система в безопасном состоянии (fail-close, не fail-open)
- Error messages не содержат sensitive info

---

## Authentication / JWT

### Правила
- JWT_SECRET **минимум 256 бит**, сгенерирован `openssl rand -base64 32`
- Access token TTL **15-60 минут** (у нас сейчас 7 дней — рассмотреть сокращение)
- Refresh token (если будет) — отдельный endpoint, revocable
- **Verify signature** перед любым использованием claims
- **Validate claims**: `exp` (не истёк), `iss` / `aud` если используется
- **Reject `none` algorithm** — классическая дыра
- Алгоритм — HS256 для внутренних, RS256/PS256 если публикуется JWKS

### Для нашего Telegram initData
- HMAC-SHA256 **только на сервере**
- `auth_date` в пределах **5 минут** (защита от replay)
- Значения **URL-decode** перед построением data-check-string (у нас была бага)
- Секрет = HMAC-SHA256(bot_token, key="WebAppData")
- Сравнение hash через constant-time compare (защита от timing attack)

---

## Authorization

### Правила
- **Каждый endpoint явно объявляет** кто может его вызывать
- Проверка role происходит **до** логики, через аннотации/middleware
- **Ownership checks**: юзер может редактировать только свои ресурсы
- **RBAC**: роли определены централизованно, не разбросаны по коду
- Публичные endpoints **явно** помечены как публичные

### Запреты
- "Security by obscurity" — секретные URL не заменяют авторизацию
- Проверка прав на фронте (без серверной) — никогда

---

## Input Validation

### На границе
- HTTP request → `@Valid` DTO с Bean Validation
- URL parameters → проверка формата (regex, length)
- Headers → whitelist значений
- File uploads → размер, MIME type, extension, **содержимое**
- JSON → schema validation

### Типы проверок
- **Type** — строка, число, UUID
- **Length** — min/max
- **Range** — диапазон значений
- **Format** — email, url, regex
- **Enum** — ограниченный набор значений

### Sanitization
- HTML — экранирование при выводе (React делает по умолчанию)
- SQL — jOOQ защищает от инъекций, но **raw SQL** должен быть параметризован
- Shell commands — не использовать user input в shell

---

## Rate Limiting

### Обязательно
- Auth endpoints: **агрессивно** (5 попыток в минуту на IP/user)
- Public API: по IP
- Authenticated API: по user_id
- Expensive operations (генерация отчётов, массовые рассылки): строже

### Strategy
- **Token bucket** (у нас bucket4j) — хорошо для burst + sustained
- При hit: HTTP 429 с `Retry-After` header
- **Логирование** каждого hit — может быть атака
- **Account lockout** после N неудачных логинов

---

## Secrets

### Правила
- Секреты **только в env vars**
- Никогда в коде, коммитах, логах, ответах API
- Git hooks / secret scanners для предотвращения commit секретов
- Ротация при утечке или периодически
- `JWT_SECRET`, API keys, passwords, tokens, webhooks — всё секреты

### Маскирование в логах
```kotlin
// ❌ плохо
log.info("Received token: $token")

// ✅ хорошо
log.info("Received token: ${token.take(6)}...${token.takeLast(4)}")
// или вообще не логировать
```

---

## Sensitive Data Handling

### Классификация
- **Publicly visible**: имя клуба, аватар
- **User-visible**: свой профиль, свои клубы
- **Admin-visible**: user records, аналитика
- **Never-visible**: пароли, токены, raw API keys

### Правила
- Не логировать: пароли, токены, номера карт, полные email в DEBUG
- Response объекты **явно** выбирают поля (не `user.toDto()` который отдаст всё)
- БД: чувствительные поля зашифрованы где уместно
- PII удаляется при удалении пользователя (GDPR если RU не волнует, то EU users)

---

## Payment Security (для будущего)

У нас Telegram Stars — большая часть безопасности на стороне Telegram, но:
- Проверка подписи payload от Telegram
- Idempotency для платёжных операций
- Логирование всех транзакций (audit trail)
- Не доверять цене от клиента — считать на сервере
- Refund flow протестирован

---

## HTTPS / Transport

- Весь трафик через HTTPS (staging + prod)
- HTTP → HTTPS редирект
- HSTS header включён
- TLS 1.2+
- Секреты не в URL (query parameters)

---

## CORS

- `Access-Control-Allow-Origin` — whitelist доменов, **не `*`** для authenticated запросов
- Credentials: `Allow-Credentials: true` только если реально нужно
- Preflight requests обрабатываются корректно

---

## Security Headers

```
Content-Security-Policy: default-src 'self'; script-src 'self'
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
Strict-Transport-Security: max-age=31536000
Referrer-Policy: strict-origin-when-cross-origin
```

---

## Специфика нашего проекта

### Telegram Mini App
- `initData` валидация — **обязательна** на каждом auth запросе
- User может подделать client-side данные, **сервер верит только подписанному initData**
- Bot token — секрет, не в коде
- Webhook от Telegram — проверять секретный путь или токен

### Backend
- Spring Security настроен: `/actuator/**` и `/api/auth/**` = permitAll, остальное = JWT
- `@PreAuthorize` на методах Service для ownership checks
- JWT в headers (Authorization: Bearer), не в cookies (у нас SPA)

### Frontend
- Токен **в памяти / Zustand**, не в localStorage (XSS-устойчивее)
- Не доверять данным от API без schema-валидации (optional)
- Dangerously SetInnerHTML — избегать, React защищает по умолчанию

### Infrastructure
- VPS: SSH только по ключу, не паролем
- Порты: только 80/443 наружу, всё остальное — внутри Docker network
- Coolify под HTTPS (sslip.io работает с Let's Encrypt)

---

## Чего избегать

- Собственная криптография — использовать проверенные библиотеки
- "Security through obscurity" — скрытые URL не заменяют авторизацию
- Trust user input — никогда
- Rate limit "потом добавим" — добавлять сразу
- Секреты в build args (попадают в image layers)
- `eval`, `exec`, deserialization непроверенных данных
- Stacktrace в response — только request ID и generic сообщение
- Устаревшие dependencies без причины

---

## Response при находке уязвимости

1. **Severity**: Critical / High / Medium / Low
2. **Impact**: что может произойти (auth bypass, data leak, DoS)
3. **Reproduce**: как воспроизвести
4. **Fix**: что сделать
5. **Ref**: OWASP категория, CWE ID если известен
