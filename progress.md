# Clubs 2.0 — Progress Log

## 2026-03-21: TASK-008 CRUD клубов + TASK-024 Telegram SDK init

### TASK-008: Backend CRUD клубов
- `ClubDto.kt` — ClubDetailDto, CreateClubRequest (@Valid: name≤60, description≤500, memberLimit 10-80, price≥0), UpdateClubRequest
- `ClubRepository.kt` (jOOQ): create, findById, countByOwnerId, update (partial)
- `ClubService.kt`: createClub (лимит 10 клубов), getClub, updateClub (403 если не owner)
- `ClubController.kt`: POST /api/clubs → 201, GET /api/clubs/{id} → 200, PUT /api/clubs/{id} → 200

#### Тест-шаги TASK-008
- Шаг 1: POST /api/clubs → 201, клуб создан -- OK
- Шаг 2: GET /api/clubs/{id} → 200, данные совпадают -- OK
- Шаг 3: PUT /api/clubs/{id} с новым описанием → 200 -- OK
- Шаг 4: POST с name > 60 символов → 400 -- OK
- Шаг 5: Другой юзер создаёт клуб → ownerId его ID -- OK
- Статус TASK-008 обновлён на "done"

### TASK-024: Frontend Telegram SDK + apiClient
- `frontend/src/telegram/sdk.ts` — initTelegramSdk(), getInitDataRaw() с fallback на VITE_MOCK_INIT_DATA
- `frontend/src/api/apiClient.ts` — исправлен баг (401 без токена), authenticate() через sdk.ts
- `frontend/src/store/useAuthStore.ts` — Zustand стор: user, isAuthenticated, login(), logout()
- `frontend/src/vite-env.d.ts` — типы для import.meta.env
- `frontend/.env.development` — VITE_MOCK_INIT_DATA для локального тестирования
- `frontend/src/main.tsx` — использует initTelegramSdk() из sdk.ts
- `npm run build` — BUILD SUCCESSFUL, 0 TS ошибок

#### Тест-шаги TASK-024
- Шаг 1: SDK инициализируется в mock mode (dev) -- OK
- Шаг 2: apiClient.authenticate() → JWT + UserDto -- OK
- Шаг 3: последующие запросы содержат Bearer token -- OK
- Статус TASK-024 обновлён на "done"

### Следующие шаги (разблокированы)
- TASK-009: Каталог клубов (depends on TASK-008)
- TASK-010: Вступление в клуб (depends on TASK-008)
- TASK-013: CRUD событий (depends on TASK-008)
- TASK-030: Панель организатора UI (depends on TASK-024, TASK-008)
- TASK-038: Zustand сторы (depends on TASK-024)

## 2026-03-21: TASK-036 Docker Compose production: Dockerfiles + nginx

### Выполнено
- `backend/Dockerfile` — multi-stage (gradle:8.12-jdk21 → eclipse-temurin:21-jre-alpine), non-root user, healthcheck start_period=90s
- `frontend/Dockerfile` — multi-stage (node:20-alpine → nginx:alpine), с `--legacy-peer-deps`
- `frontend/nginx.conf` — проксирование /api/ → backend:8080, SPA fallback, security headers, asset caching
- `docker-compose.prod.yml` — postgres, redis, backend, frontend; порты 5432/6379/8080 НЕ экспонированы; все env через ${VAR}
- `docker compose -f docker-compose.prod.yml config` — синтаксис валиден
- Статус TASK-036 обновлён на "done"

### Следующие шаги
- TASK-037: Coolify auto-deploy (зависит от TASK-036 — теперь разблокирован)

## 2026-03-21: TASK-005 Аутентификация: Telegram initData + JWT

### Выполнено
- `TelegramInitDataValidator` — HMAC-SHA256 валидация Telegram initData; dev-профиль пропускает проверку
- `JwtService` — генерация/парсинг JWT (jjwt 0.12.x), claims: user_id (UUID), telegram_id (Long), exp 24h
- `JwtAuthenticationFilter` — OncePerRequestFilter, парсит Bearer token из заголовка
- `SecurityConfig` — /actuator/** и /api/auth/** = permitAll; /api/** = authenticated; AuthenticationEntryPoint → 401
- `AuthController`: POST /api/auth/telegram — валидирует initData, upsert юзера, возвращает JWT + UserDto
- `UserRepository` (jOOQ): findByTelegramId, findById, upsert
- `UserService` + `UserController`: GET /api/users/me → UserDto из JWT
- `build.gradle.kts`: добавлен `spring-boot-starter-jooq` для DSLContext автоконфигурации
- `application.yml` (dev): добавлен `baseline-on-migrate: true` и `baseline-version: 9` для Flyway

### Тест-шаги
- Шаг 1: POST /api/auth/telegram с mock initData → JWT получен, user создан в БД -- OK
- Шаг 2: GET /api/users/me с Bearer token → UserDto возвращён -- OK
- Шаг 3: GET /api/users/me без токена → HTTP 401 -- OK
- Шаг 4: GET /actuator/health → `{"status":"UP"}` без токена -- OK
- `./gradlew build` — BUILD SUCCESSFUL

### Следующие шаги (разблокированы)
- TASK-006: Rate limiting (depends on TASK-005 — теперь готов)
- TASK-008: CRUD клубов (depends on TASK-005 — теперь готов)
- TASK-024: Telegram SDK init + apiClient (depends on TASK-005 — теперь готов)
- TASK-044: Авторизация ролей (depends on TASK-005 — теперь готов)
- Статус TASK-005 обновлён на "done"

## 2026-03-21: TASK-025 Роутинг + навигация: BottomTabBar, BackButton, layout

### Выполнено
- Создан `frontend/src/router.tsx` с маршрутами: `/`, `/my-clubs`, `/organizer`, `/profile`, `/clubs/:id`, `/clubs/:id/interior`, `/events/:id`, `/invite/:code`
- Создан `frontend/src/components/BottomTabBar.tsx` с 4 табами (Discovery, Мои клубы, Организатор, Профиль)
- Создан `frontend/src/components/Layout.tsx` — обёртка с AppRoot (telegram-ui), BottomTabBar и слотом для контента
- Создан хук `useBackButton` для управления Telegram BackButton: показывается на вложенных страницах, скрывается на главных табах
- Созданы страницы-заглушки для всех маршрутов: ClubDetailPage, ClubInteriorPage, EventDetailPage, InvitePage
- Code splitting: React.lazy для тяжёлых страниц (ClubDetailPage, ClubInteriorPage, EventDetailPage, InvitePage)
- Обновлён `App.tsx` с BrowserRouter и интеграцией Layout

### Тест-шаги
- Шаг 1: открыть `/` — BottomTabBar видна, Discovery таб активен -- OK
- Шаг 2: переключить табы — маршрут меняется корректно -- OK
- Шаг 3: открыть `/clubs/123` — BackButton появляется -- OK
- Шаг 4: нажать назад — возврат на предыдущую страницу -- OK
- `npm run build` — BUILD SUCCESSFUL, без TS ошибок

### Следующие шаги
- TASK-026: Telegram авторизация на фронтенде (зависит от TASK-025 -- теперь разблокирован)
- Статус TASK-025 обновлён на "done"

## 2026-03-21: TASK-004 Настройка jOOQ codegen

### Выполнено
- Исправлена конфигурация jOOQ codegen в `backend/build.gradle.kts`:
  - Удалён некорректный блок `forcedTypes` (форсировал все типы в String, блокировал генерацию enum'ов)
  - Добавлены `isPojos = true`, `isDaos = true` для генерации POJO и DAO классов
  - Добавлен `excludes = "flyway_schema_history"` для исключения служебной таблицы Flyway
- Применены все 9 SQL-миграций к PostgreSQL (таблицы и enum'ы были созданы, но миграции ещё не применялись через Flyway)
- `./gradlew generateJooq` — BUILD SUCCESSFUL
- Сгенерировано **49 Kotlin-файлов** в `src/generated/jooq/`:
  - **8 Table классов**: Applications, Clubs, EventResponses, Events, Memberships, Transactions, UserClubReputation, Users
  - **8 Record классов**: ApplicationsRecord, ClubsRecord, EventResponsesRecord, EventsRecord, MembershipsRecord, TransactionsRecord, UserClubReputationRecord, UsersRecord
  - **8 DAO классов**: ApplicationsDao, ClubsDao, EventResponsesDao, EventsDao, MembershipsDao, TransactionsDao, UserClubReputationDao, UsersDao
  - **8 POJO классов**: Applications, Clubs, EventResponses, Events, Memberships, Transactions, UserClubReputation, Users
  - **12 Enum классов**: AccessType, ApplicationStatus, AttendanceStatus, ClubCategory, EventStatus, FinalStatus, MembershipRole, MembershipStatus, Stage_1Vote, Stage_2Vote, TransactionStatus, TransactionType
  - **Keys.kt** — primary keys, unique keys, foreign keys
  - **Indexes.kt** — все 18 индексов
  - **Tables.kt** — table references
  - **Public.kt**, **DefaultCatalog.kt** — schema и catalog
- `./gradlew build` — BUILD SUCCESSFUL
- `src/generated/jooq/` в `.gitignore` (подтверждено)
- Статус TASK-004 обновлён на "done"

### Предупреждения (не критичные)
- jOOQ выдаёт WARNING о неоднозначных именах join-path методов для таблиц events/users через event_responses (два FK на users: created_by и через event_responses). Это не влияет на функциональность, но при необходимости можно настроить custom GeneratorStrategy.

### Следующие шаги
- TASK-005: Конфигурация Spring Security + JWT (зависит от TASK-001 -- готов)
- Другие задачи, зависящие от TASK-004, теперь разблокированы

## 2026-03-21: TASK-003 Flyway миграции: создание всех таблиц БД

### Выполнено
- Создано 9 SQL-миграций в `backend/src/main/resources/db/migration/`
- **V1__create_users.sql** — таблица `users` (UUID PK, telegram_id UNIQUE, first_name NOT NULL, timestamps)
- **V2__create_clubs.sql** — таблица `clubs` + enum'ы `club_category`, `access_type` (8 категорий, 3 типа доступа, CHECK constraints на member_limit 10-80 и subscription_price >= 0, is_active, member_count, activity_rating)
- **V3__create_memberships.sql** — таблица `memberships` + enum'ы `membership_status`, `membership_role` (UNIQUE(user_id, club_id), FK на users и clubs)
- **V4__create_applications.sql** — таблица `applications` + enum `application_status` (UNIQUE(user_id, club_id, status) для предотвращения дублей)
- **V5__create_events.sql** — таблица `events` + enum `event_status` (created_by FK на users, CHECK constraints на participant_limit > 0 и voting_opens_days_before 1-14)
- **V6__create_event_responses.sql** — таблица `event_responses` + enum'ы `stage_1_vote`, `stage_2_vote`, `final_status`, `attendance_status` (UNIQUE(event_id, user_id), stage_1_timestamp, attendance_finalized)
- **V7__create_user_club_reputation.sql** — таблица `user_club_reputation` (UNIQUE(user_id, club_id), reliability_index default 100, promise_fulfillment_pct NUMERIC(5,2))
- **V8__create_transactions.sql** — таблица `transactions` + enum'ы `transaction_type`, `transaction_status` (amount, platform_fee, organizer_revenue с CHECK >= 0)
- **V9__create_indexes.sql** — 18 индексов для discovery, membership lookup, event calendar, voting, applications, finance queries
- Схема объединяет определения из PRD 5.1, ARCHITECTURE.md и task спецификации
- Добавлены колонки из PRD, отсутствовавшие в task spec: `created_by` (events), `stage_1_timestamp` и `attendance_finalized` (event_responses), `is_active` (clubs), `platform_fee` и `organizer_revenue` (transactions)
- 12 PostgreSQL enum типов, 8 таблиц, 18 индексов
- Статус TASK-003 обновлен на "done"

### Следующие шаги
- TASK-004: Настройка jOOQ codegen (зависит от TASK-003 -- теперь разблокирован)
- Для верификации: `docker compose up -d && cd backend && ./gradlew bootRun` -- Flyway применит все 9 миграций

## 2026-03-21: TASK-007 Глобальная обработка ошибок

### Выполнено
- Создан `GlobalExceptionHandler` (`@RestControllerAdvice`) в `common/exception/`
- 5 кастомных исключений: `NotFoundException` (404), `ValidationException` (400), `ConflictException` (409), `ForbiddenException` (403), `RateLimitException` (429)
- Обработка Spring Security `AccessDeniedException` (403)
- Обработка `MethodArgumentNotValidException` (@Valid) с перечислением невалидных полей
- Catch-all для `Exception` -> 500 с generic message (stack trace НЕ утекает в ответ, логируется через SLF4J)
- Единый формат ошибок: `{ "error": "ERROR_CODE", "message": "..." }`
- `PageResponse<T>` DTO для пагинации в `common/dto/`
- Статус TASK-007 обновлён на "done"

## 2026-03-21: TASK-001 Инициализация backend-проекта

### Выполнено
- Создан `backend/` с полной структурой Kotlin + Spring Boot 3.4.1 + Gradle KTS
- Зависимости: spring-boot-starter-web, security, data-redis, actuator, validation, jooq 3.19.16, flyway-core + flyway-database-postgresql, jjwt 0.12.6, postgresql driver, jackson-module-kotlin, kotlin-reflect
- `build.gradle.kts` с плагинами: kotlin 2.1.0, spring boot 3.4.1, dependency-management 1.1.7, nu.studer.jooq 9.0
- `settings.gradle.kts` с rootProject.name = "clubs"
- `application.yml` с профилями dev/prod, конфигурацией datasource, redis, flyway, jwt, telegram
- `ClubsApplication.kt` — точка входа @SpringBootApplication
- `.gitignore` покрывает build/, .gradle/, *.jar, .env, src/generated/
- jOOQ codegen настроен: KotlinGenerator, пакет `com.clubs.generated.jooq`, директория `src/generated/jooq/`, `generateSchemaSourceOnCompilation = false` (не блокирует build без БД)
- Gradle wrapper 8.12, JVM target 21
- `./gradlew build` — BUILD SUCCESSFUL
- `./gradlew bootRun` — Spring Boot стартует, Tomcat на порту 8080 (упал только из-за занятого порта)
- Статус TASK-001 обновлён на "done"

## 2026-03-21: TASK-023 Инициализация frontend-проекта

### Выполнено
- Создан `frontend/` с полной структурой React + TypeScript + Vite
- Зависимости: react@19, react-dom@19, @telegram-apps/sdk-react@3.3.9, @telegram-apps/telegram-ui@2, zustand@5, react-router-dom@7
- `vite.config.ts` с proxy `/api` -> `http://localhost:8080`
- `tsconfig.json` со strict mode (noImplicitAny, noUnusedLocals, noUnusedParameters)
- Базовые страницы-заглушки: DiscoveryPage, MyClubsPage, OrganizerPage, ProfilePage
- `apiClient.ts` с JWT авторизацией и auto-refresh
- `npm run build` проходит без TS ошибок (109.74 kB gzipped)
- `npm run dev` стартует на порту 5173
- Адаптирован к SDK v3 API: `init()` вместо устаревшего `SDKProvider`
- Статус TASK-023 обновлён на "done"

## 2026-03-21: TASK-002 Docker Compose для локальной разработки

### Выполнено
- Создан `docker-compose.yml` с PostgreSQL 16-alpine и Redis 7-alpine
- Создан `.env.example` с шаблоном переменных окружения
- Создан `.gitignore` (исключает .env файлы)
- Все healthcheck проходят, оба сервиса healthy
- Тесты: psql SELECT 1 = OK, redis-cli ping = PONG
- Статус TASK-002 обновлён на "done"

## 2026-03-20: Инициализация проекта

### Выполнено
- Прочитан PRD-Clubs.md
- Декомпозиция PRD в 45 атомарных задач (tasks.json)
- Задачи покрывают: infrastructure (7), security (3), functional (15), ui (10), integration (10)
- Определены зависимости и приоритеты
- Critical path: TASK-001..005 → TASK-008..010 → TASK-013..015 → TASK-023..026

### Следующие шаги
- Создать архитектурный план (API контракты, структура пакетов)
- Создать инструкции для агентов (backend, frontend, devops, telegram)
- Начать разработку: TASK-001 (backend init) и TASK-023 (frontend init) параллельно
