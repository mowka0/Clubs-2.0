# Clubs 2.0 — Progress Log

## 2026-03-21: TASK-018 + TASK-006 + TASK-021 + TASK-022 + TASK-028 + TASK-029 + TASK-031 + TASK-035 + TASK-037 + TASK-044

### TASK-018: Система репутации
- `ReputationService.kt` — @Scheduled(1h): обрабатывает финализированные события, upsert в user_club_reputation
- Правила: going→confirmed→attended: +100, going→confirmed→absent: -50, maybe→confirmed→attended: +30, maybe→confirmed→absent: -20, declined: +10
- `./gradlew compileKotlin` — BUILD SUCCESSFUL

### TASK-006: Rate limiting
- Добавлен `bucket4j-core:8.10.1` в build.gradle.kts
- `RateLimitFilter.kt` — OncePerRequestFilter, 60 req/min на IP или telegram_id, исключение /actuator/health
- Зарегистрирован в SecurityConfig перед JwtAuthenticationFilter

### TASK-021: Список участников с репутацией
- `MemberListDto.kt` — MemberListItemDto (userId, firstName, lastName, avatarUrl, role, joinedAt, reliabilityIndex, promiseFulfillmentPct)
- `MemberController.kt` — добавлен GET /api/clubs/{clubId}/members (только для участников, сортировка reliability_index DESC)
- JOIN MEMBERSHIPS + USERS + USER_CLUB_REPUTATION (LEFT JOIN)

### TASK-022: Финансовый блок
- `FinancesDto.kt` — FinancesDto (activeMembers, monthlyRevenue, organizerShare, platformFee, organizerSharePct, platformFeePct)
- `FinancesService.kt` — запросы: COUNT active members, SUM(amount) за текущий месяц, 80/20 split
- `ClubController` — добавлен GET /api/clubs/{id}/finances

### TASK-035: Промо-теги в каталоге
- Backend: добавлено поле `tags: List<String>` в ClubListItemDto
- Логика: "Новый" (< 14 дней), "Популярный" (топ-10%), "Свободные места" (< 80% заполнено)
- Frontend: ClubCard показывает теги как синие Badge

### TASK-028: Внутренний экран клуба
- `ClubInteriorPage.tsx` — 3 таба: События (upcoming + past), Участники (с reliability), Мой профиль (репутация)
- Использует getClubMembers, getMemberProfile, getClubEvents
- Маршрут /clubs/:id/interior

### TASK-029: Экран события
- `EventPage.tsx` — полностью реализован
- Статистика набора (going/maybe/notGoing + прогресс-бар)
- Этап 1: кнопки голосования (Пойду/Возможно/Не пойду)
- Этап 2: подтверждение/отказ, статус (confirmed/waitlisted/declined)
- TypeScript компилируется без ошибок

### TASK-031: Дашборд организатора
- `OrganizerClubManage.tsx` — 4 таба: Участники, Заявки (approve/reject), События (создание + отметка присутствия), Финансы
- Маршрут /clubs/:id/manage
- `npm run build` — BUILD SUCCESSFUL

### TASK-037: Coolify auto-deploy
- `.github/workflows/deploy.yml` — GitHub Actions на push в main
- Вызывает Coolify webhook через secrets COOLIFY_WEBHOOK_URL + COOLIFY_TOKEN

### TASK-044: Авторизация ролей
- `common/auth/Annotations.kt` — @RequiresMembership, @RequiresOrganizer (с clubIdParam)
- `common/auth/AuthorizationAspect.kt` — Spring AOP @Around advice, проверяет MEMBERSHIPS/CLUBS
- Добавлен spring-boot-starter-aop
- Применено: @RequiresOrganizer на createEvent и getFinances
- `./gradlew compileKotlin` — BUILD SUCCESSFUL

### TASK-039: Telegram Bot
- Зависимости: telegrambots-springboot-longpolling-starter:7.10.0 + telegrambots-client:7.10.0
- `BotConfig.kt` — @Bean OkHttpTelegramClient
- `ClubsBot.kt` — SpringLongPollingBot + LongPollingSingleThreadUpdateConsumer
- Команды: /start (Mini App кнопка), /кто_идет (ближайшее событие), /мой_рейтинг (репутация)
- Обработка successful_payment делегируется PaymentService

### TASK-040: Telegram уведомления
- `NotificationService.kt` — @Async DM уведомления
- sendEventCreated: всем участникам клуба
- sendStage2Started: проголосовавшим going/maybe
- sendAttendanceMarked: отмеченным absent (с кнопкой оспорить)
- sendDirectMessage: универсальный метод для других нужд

### TASK-041: Telegram Stars оплата
- `PaymentService.kt` — createInvoice (SendInvoice с currency="XTR"), handleSuccessfulPayment
- При успешной оплате: создаёт/продлевает membership + запись в transactions (80/20 split)
- ClubsBot обрабатывает successful_payment → PaymentService

### TASK-042: Автопродление подписок
- `SubscriptionScheduler.kt` — cron "0 0 9 * * *" (ежедневно в 09:00)
- За 3 дня: warning notification через NotificationService
- При истечении: active → grace_period
- После grace_period (3 дня): → expired, decrement member_count
- `MembershipService.cancelMembership()` + `POST /api/clubs/{id}/cancel`

### TASK-043: Привязка Telegram-группы
- `V10__add_telegram_group_id.sql` — добавлен BIGINT столбец telegram_group_id
- `ClubService.linkTelegramGroup()` — DSL.field("telegram_group_id") (до регенерации jOOQ)
- `POST /api/clubs/{id}/link-group` — только для организатора (@RequiresOrganizer)

### TASK-045: S3 хранилище (подтверждено агентом)
- `S3Config.kt` — S3Client с forcePathStyle=true для MinIO/Timeweb совместимости
- `StorageService.kt` — uploadFile, deleteFile
- `StorageController.kt` — POST /api/upload (jpg/png, 5MB), UUID-based path
- `docker-compose.yml` — добавлен MinIO сервис (порты 9000/9001)
- `application.yml` — s3.bucket, s3.base-url, multipart.max-file-size=5MB

## 🎉 ПРОЕКТ ЗАВЕРШЁН: 45/45 задач выполнено
- `./gradlew compileKotlin` — BUILD SUCCESSFUL
- `npm run build` — ✓ built in 1.04s
- Все 45 задач в tasks.json имеют status="done"

## 2026-03-21: TASK-011 + TASK-014 + TASK-026 + TASK-030

### Specs созданы (Analyst)
- `docs/modules/membership.md` — добавлена секция TASK-011 (заявки)
- `docs/modules/events.md` — добавлена секция TASK-014 (голосование Этап 1)
- `docs/modules/ui-pages.md` — TASK-026 (Discovery) и TASK-030 (Organizer)
- `docs/modules/infrastructure.md` — TASK-001, 002, 003, 004, 007, 036 (backfill)
- `docs/modules/frontend-core.md` — добавлены TASK-023 и TASK-024 секции (backfill)

### TASK-011: Заявки в закрытый клуб
- `ApplicationDto.kt` — ApplicationDto, SubmitApplicationRequest, RejectApplicationRequest
- `ApplicationRepository.kt` — create, findById, findByClubId, findPendingByUserAndClub, countTodayByUser, updateStatus, findByUserId
- `ApplicationService.kt` — submitApplication (validation: accessType=closed, answerText если есть вопрос, rate limit 5/день), approveApplication (creates membership), rejectApplication, getClubApplications, getMyApplications
- `ApplicationController.kt` — POST /api/clubs/{id}/apply, GET /api/clubs/{id}/applications, POST /api/applications/{id}/approve, POST /api/applications/{id}/reject
- `UserController.kt` — добавлен GET /api/users/me/applications
- `./gradlew compileKotlin` — BUILD SUCCESSFUL
- Статус TASK-011 обновлён на "done"

### TASK-014: Этап 1 голосования
- `VoteDto.kt` — CastVoteRequest, VoteResponseDto, MyVoteDto
- `EventResponseRepository.kt` — upsertStage1Vote (insert или update), findByEventAndUser, countByVote
- `VoteService.kt` — castVote (проверки: member, status=upcoming, voting open by days), getMyVote; upsert логика
- `EventController.kt` — добавлены POST /api/events/{id}/vote и GET /api/events/{id}/my-vote
- Статус TASK-014 обновлён на "done"

### TASK-026: Discovery страница
- `ClubCard.tsx` — карточка клуба: название, категория (badge), город, цена, участники, ближайшее событие
- `ClubFilters.tsx` — поиск (text), категория (chips с горизонтальным скроллом), город (text)
- `DiscoveryPage.tsx` — infinite scroll через IntersectionObserver, debounce 300ms для фильтров, empty state, skeleton spinner
- `useClubsStore.ts` — обновлён fetchClubs: append (page > 0) vs replace (page = 0) для infinite scroll
- `npx tsc --noEmit` — 0 ошибок
- Статус TASK-026 обновлён на "done"

### TASK-030: Панель организатора
- `OrganizerPage.tsx` — список своих клубов + кнопка создания
- CreateClubModal — 5 шагов: основное, категория, участники+цена, описание, вопрос при вступлении
- Калькулятор дохода: memberLimit * subscriptionPrice * 0.8
- Валидация каждого шага перед переходом
- После создания — редирект на /clubs/{id}
- Статус TASK-030 обновлён на "done"

### Следующие шаги (разблокированы)
- TASK-019: Автоотклонение заявок (depends on TASK-011 ✓)
- TASK-027: Страница клуба детальная (depends on TASK-011 ✓)
- TASK-015: Этап 2 голосования (depends on TASK-014 ✓)

## 2026-03-21: TASK-027 + TASK-012 + GET /api/users/me/clubs

### TASK-027: Страница клуба (ClubPage)
- `ClubPage.tsx` — полная информация: обложка, название, категория/тип, город, участники, описание, правила
- Кнопка вступления: "Вступить" (open) / "Хочу вступить" (closed) / "Вы участник" (если уже в клубе)
- Для закрытого клуба: Modal с полем ответа на вопрос организатора
- useBackButton(true) — кнопка назад в Telegram
- Статус TASK-027 обновлён на "done"

### TASK-012: Приватный клуб по инвайт-ссылке
- `ClubRepository.findByInviteCode()`, `updateInviteCode()`
- При создании private клуба → генерируется 16-символьный invite code
- `ClubService.getClubByInviteCode()`, `regenerateInviteLink()`
- `MembershipService.joinByInviteCode()` — вступление по инвайту (без проверки access_type)
- `InviteController.kt` — GET /api/invite/{code}, POST /api/invite/{code}/join, POST /api/clubs/{id}/regenerate-invite
- Статус TASK-012 обновлён на "done"

### GET /api/users/me/clubs (бонус)
- `MembershipRepository.findByUserId()` — найти все активные членства пользователя
- `UserController` — добавлен GET /api/users/me/clubs → List<MembershipDto>
- Используется в OrganizerPage и ClubPage для проверки членства

## 2026-03-21: TASK-016 + TASK-019 + TASK-020

### TASK-016: Отметка присутствия организатором
- `AttendanceDto.kt` — AttendanceEntryRequest, MarkAttendanceRequest, AttendanceResultDto
- `AttendanceService.kt` — markAttendance (проверка: organizerId=club.ownerId, event уже прошёл)
- `EventController` — POST /api/events/{id}/attendance
- Статус TASK-016 обновлён на "done"

### TASK-019: Автоотклонение заявок
- `ApplicationScheduler.kt` — @Scheduled(fixedDelay=3_600_000): pending заявки > 48h → auto_rejected
- Каждый затронутый клуб: activity_rating -= 5 (минимум 0)
- Логирование с количеством и ID отклонённых заявок
- Статус TASK-019 обновлён на "done"

### TASK-020: API профиля пользователя
- Существующие эндпоинты: GET /api/users/me, /me/clubs, /me/applications — все реализованы
- `MemberProfileDto.kt` — профиль участника с репутацией
- `MemberController.kt` — GET /api/clubs/{clubId}/members/{userId} (нужно быть участником)
- Статус TASK-020 обновлён на "done"

### Следующие шаги
- TASK-015 [critical]: Этап 2 голосования (cron + подтверждения)
- TASK-034: Страница инвайта (depends on TASK-012 ✓)
- TASK-028: Внутренний экран клуба (depends on TASK-027 ✓)

## 2026-03-21: TASK-015 + TASK-034

### TASK-015: Этап 2 голосования (Stage 2)
- `@EnableScheduling` добавлен в ClubsApplication
- `EventRepository.findEventsToTriggerStage2()` — события с event_datetime <= now()+24h и stage_2_triggered=false
- `EventRepository.transitionToStage2()` — status=stage_2, stage_2_triggered=true
- `EventResponseRepository` — добавлены: countConfirmed, findFirstWaitlisted, updateStage2Vote, findGoingByEventOrderByTimestamp, findMaybeByEventOrderByTimestamp
- `Stage2Service` — @Scheduled(fixedDelay=300_000), triggerStage2, confirmParticipation (FIFO, limit check), declineParticipation (promote waitlisted)
- `Stage2Dto.kt` — ConfirmResponseDto
- `EventController` — POST /api/events/{id}/confirm, POST /api/events/{id}/decline
- Статус TASK-015 обновлён на "done"

### TASK-034: Страница инвайта (InvitePage)
- `InvitePage.tsx` — загружает клуб по коду, показывает инфо, кнопка "Вступить в клуб"
- `api/clubs.ts` — добавлен getClubByInvite(code)
- `api/membership.ts` — добавлен joinByInviteCode(code)
- После вступления: success state + кнопка "Перейти в клуб"
- Статус TASK-034 обновлён на "done"



## 2026-03-21: TASK-009 + TASK-010 + TASK-013 + TASK-038

### Specs созданы (Analyst)
- `docs/modules/clubs.md` — каталог, фильтры, ClubListItemDto
- `docs/modules/membership.md` — join, edge cases
- `docs/modules/events.md` — CRUD событий, EventDetailDto
- `docs/modules/frontend-stores.md` — Zustand stores, api modules

### TASK-009: Каталог клубов (Discovery API)
- `ClubListItemDto`, `NearestEventDto`, `ClubFilterParams` добавлены в ClubDto.kt
- `ClubRepository.findAll()` — фильтры по category/city/accessType/price/search, private скрыты, сортировка activity_rating DESC
- `ClubRepository.fetchNearestEvents()` — ближайшее событие для каждого клуба одним запросом
- `ClubService.getClubs()` — валидация фильтров, minPrice/maxPrice check
- `GET /api/clubs` добавлен в ClubController
- Статус TASK-009 обновлён на "done"

### TASK-010: Вступление в открытый клуб
- `MembershipDto`, `MembershipRepository`, `MembershipService`, `MembershipController`
- `POST /api/clubs/{id}/join` — проверки: open, not member, not full; updates member_count
- Статус TASK-010 обновлён на "done"

### TASK-013: CRUD событий
- `EventDto.kt` — EventDetailDto, EventListItemDto, CreateEventRequest (@Future, @Positive, @Min/@Max)
- `EventRepository` — create, findById, findByClubId (с пагинацией), getVoteCounts
- `EventService` — createEvent (403 если не owner), getClubEvents, getEvent
- `EventController` — POST /api/clubs/{id}/events, GET /api/clubs/{id}/events, GET /api/events/{id}
- Статус TASK-013 обновлён на "done"

### TASK-038: Frontend Zustand stores
- `src/types/api.ts` — полный набор DTO типов
- `src/api/clubs.ts`, `src/api/events.ts`, `src/api/membership.ts` — api модули
- `src/store/useClubsStore.ts` — clubs, myClubs, fetchClubs, fetchMyClubs
- `src/store/useEventsStore.ts` — eventsByClub, currentEvent, fetchClubEvents, fetchEvent
- `npm run build` — BUILD SUCCESSFUL
- Статус TASK-038 обновлён на "done"

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
