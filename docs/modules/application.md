# Module: Application

Заявки на вступление в закрытые клубы. Соответствует PRD §4.2.2. Источник истины для бизнес-правил — PRD.

## Цель
Дать пользователю возможность подать заявку (с ответом на `applicationQuestion`) на вступление в клуб с `access_type = closed`. Организатор клуба принимает решение (approve / reject) вручную в течение 48 часов, иначе scheduler авто-отклоняет заявку.

## Scope
### Входит
- `POST /api/clubs/{id}/apply` — подать заявку
- `GET /api/clubs/{id}/applications` — список заявок клуба (organizer-only)
- `POST /api/applications/{id}/approve` — одобрить (organizer-only)
- `POST /api/applications/{id}/reject` — отклонить (organizer-only)
- `GET /api/users/me/applications` — свои заявки
- `ApplicationScheduler` — раз в час: auto-reject заявок старше 48 часов

### НЕ входит (из этой итерации)
- Resubmit после `rejected` (отдельная фича)
- Bulk approve/reject
- DM-уведомления **заявителю** об approve/reject **не реализованы** (GAP-007 в `docs/backlog/telegram-bot-prd-gaps.md` — частично закрыт). DM **организатору** при создании заявки реализован в `feature/applications-inbox` (2026-05-30, см. `docs/modules/applications-inbox.md`).
- Двухкнопочный UX «запрос + вступить» (см. GAP-6 в `docs/backlog/payment-prd-gaps.md`) — текущий approve платного клуба автоматически вызывает `paymentService.createInvoice`
- Resubmit после `auto_rejected` — статус есть в БД, но flow повторной подачи не описан

## Архитектура

```
ApplicationController
    │
    ▼
ApplicationService ─── ClubRepository,
    │                  MembershipRepository,
    │                  PaymentService,
    │                  NotificationService,        ◀── async DM organizer'у на submit
    │                  UserRepository,             ◀── batch applicants для inbox, organizer telegram_id для DM
    │                  ReputationRepository        ◀── aggregateByUserIds для peer-signal
    │
    └── @Transactional submitApplication / approveApplication / rejectApplication
    └── @Transactional(readOnly=true) getMyPendingApplications / getMyPendingApplicationsCount

@Scheduled(fixedDelay = 3_600_000) ApplicationScheduler
    │
    ▼
ApplicationRepository (markAutoRejected)
    │
    └──▶ ClubRepository.decreaseActivityRatingSafely
```

> Endpoints `GET /api/users/me/applications-pending` и `/applications-pending-count` живут в `ApplicationController`, питают кросс-клубовый organizer-inbox на `MyClubsPage`. Полная спека — [`applications-inbox.md`](./applications-inbox.md).

### Файлы (целевая структура)
| Файл | Роль |
|---|---|
| `application/Application.kt` | Domain data class |
| `application/ApplicationDto.kt` | DTO + `SubmitApplicationRequest`, `RejectApplicationRequest` |
| `application/ApplicationController.kt` | HTTP endpoints |
| `application/ApplicationService.kt` | Оркестрация submit/approve/reject/list |
| `application/ApplicationRepository.kt` | Интерфейс CRUD заявок + counts |
| `application/JooqApplicationRepository.kt` | Реализация с jOOQ |
| `application/ApplicationMapper.kt` | `ApplicationsRecord` ↔ `Application` ↔ `ApplicationDto` |
| `application/ApplicationScheduler.kt` | Cron-задача auto-reject |

### Хранение
Таблица `applications` (V3 + последующие):
- `id` UUID PK
- `user_id`, `club_id` UUID — FK
- `answer_text` TEXT, nullable
- `status` enum `application_status` (`pending`, `approved`, `rejected`, `auto_rejected`)
- `rejected_reason` TEXT, nullable
- `created_at`, `resolved_at` TIMESTAMPTZ
- Индексы: `(club_id, status)`, `(user_id)` — см. ARCHITECTURE.md §5

## User Stories

### US-1: подача заявки
**Как** пользователь Mini App
**Я хочу** подать заявку с ответом на вопрос организатора в закрытый клуб
**Чтобы** дождаться решения и получить доступ к клубу

### US-2: разбор заявок организатором
**Как** организатор закрытого клуба
**Я хочу** видеть pending-заявки с ответами заявителей и одобрять/отклонять их
**Чтобы** контролировать состав сообщества

### US-3: auto-reject устаревших заявок
**Как** система
**Я хочу** автоматически отклонять заявки старше 48 часов
**Чтобы** не зависала очередь заявителей

## API контракты

### POST /api/clubs/{id}/apply
Подать заявку на вступление в закрытый клуб.

Request:
```json
{ "answerText": "string?" }
```

Response 201:
```json
{
  "id": "uuid",
  "userId": "uuid",
  "clubId": "uuid",
  "status": "pending",
  "answerText": "string|null",
  "rejectedReason": null,
  "createdAt": "ISO-8601",
  "resolvedAt": null
}
```

Errors:
- `400 VALIDATION_ERROR` — клуб не закрытый, либо `answerText` пуст при заданном `application_question`
- `404 NOT_FOUND` — клуб не найден
- `409 CONFLICT` — уже member; либо есть активная заявка (pending / approved-pending-payment)
- `429 RATE_LIMIT_EXCEEDED` — превышен лимит 5 заявок в сутки

### GET /api/clubs/{id}/applications
Список заявок клуба для организатора.

Query: `?status=pending|approved|rejected|auto_rejected` (необязательный фильтр)

Response 200: `ApplicationDto[]`

Errors:
- `400 VALIDATION_ERROR` — невалидное значение `status`
- `403 FORBIDDEN` — caller не владелец клуба
- `404 NOT_FOUND` — клуб не найден

### POST /api/applications/{id}/approve
Одобрить заявку (organizer-only). Для платного клуба запускает `paymentService.createInvoice` (DM с invoice заявителю); membership создаётся в `handleSuccessfulPayment`. Для бесплатного — membership создаётся сразу.

Response 200: `ApplicationDto` (status = `approved`, `resolvedAt` заполнен)

Errors:
- `400 VALIDATION_ERROR` — заявка не в статусе `pending` (`Application is not pending`); либо клуб заполнен (`Club is full`)
- `403 FORBIDDEN` — caller не организатор клуба
- `404 NOT_FOUND` — заявка или клуб не найдены

### POST /api/applications/{id}/reject
Отклонить заявку (organizer-only).

Request:
```json
{ "reason": "string" }
```
`reason` — **обязателен**, `@NotBlank` + `@Size(min=5, max=500)`. После `trim()` сервис дополнительно проверяет ≥5 символов (defense-in-depth: `"  ab "` проходит `@Size(min=5)` по сырой длине, но обрезается до 2 символов — Service бракует `400 VALIDATION_ERROR`).

> Nullable-сигнатура параметра `reason: String?` в `ApplicationService.rejectApplication` намеренно сохранена для возможных будущих system-driven reject (например, scheduler). Контракт UI / `RejectApplicationRequest` — non-null с ≥5 символов после trim.

Response 200: `ApplicationDto` (status = `rejected`, `rejectedReason`, `resolvedAt` заполнены)

Errors:
- `400 VALIDATION_ERROR` — `reason` отсутствует / пустой / `<5` символов после trim / `>500` символов; либо заявка не в статусе `pending`
- `403 FORBIDDEN` — caller не организатор клуба
- `404 NOT_FOUND` — заявка или клуб не найдены

### GET /api/users/me/applications
Свои заявки (любые статусы).

Response 200: `ApplicationDto[]`

## DTO

### ApplicationDto
```json
{
  "id": "uuid",
  "userId": "uuid",
  "clubId": "uuid",
  "status": "pending|approved|rejected|auto_rejected",
  "answerText": "string|null",
  "rejectedReason": "string|null",
  "createdAt": "ISO-8601",
  "resolvedAt": "ISO-8601|null"
}
```

### SubmitApplicationRequest
```json
{ "answerText": "string|null" }
```
**Валидация:** в текущей реализации `answerText` не имеет `@Size` (см. «Известные ограничения»). Бизнес-правило обязательности проверяется в Service на основании `club.application_question`.

### RejectApplicationRequest
```json
{ "reason": "string" }
```
**Валидация:** `@NotBlank` + `@Size(min=5, max=500)` на `reason`. Bean Validation возвращает `400 VALIDATION_ERROR` при отсутствии / пустом / `<5` / `>500`. Дополнительно `ApplicationService.rejectApplication` проверяет `reason.trim().length >= 5` (defense in depth — см. `applications-inbox.md` § «rejectApplication — изменения»).

## Бизнес-правила

### submitApplication
1. Клуб должен существовать → `404 Club not found`
2. `club.access_type = closed` → иначе `400 Club does not accept applications`
3. Если `club.application_question != null` — `answerText` обязателен (непустой, после trim) → иначе `400 Answer is required for this club`
4. Нет active membership пользователя в этом клубе → иначе `409 Already a member`
5. Нет **активной** заявки пользователя в этом клубе — где «активная» = `pending` ИЛИ `approved`:
   - `pending` → `409 Application already exists`
   - `approved` → `409 Application already approved — waiting for payment`
   (статус `approved` означает, что approve уже произведён, но оплата ещё не прошла; иначе membership был бы создан и сработала бы проверка п.4)
6. Anti-spam: не более **5 заявок в сутки** на пользователя (`countTodayByUser`, окно — календарные сутки UTC) → иначе `429 Too many applications today`
7. Создаётся запись со `status = pending`, `created_at = now()`, `answer_text = trim(request.answerText)` если непустой иначе null
8. Логируется INFO с id заявки, clubId, userId
9. **Async DM организатору** через `NotificationService.sendApplicationCreatedDM` (`@Async`, fail-isolated — ошибка Telegram API не откатывает транзакцию). Текст: `📥 Новая заявка от {applicantDisplayName} в клуб «{clubName}»`, inline web_app button «Открыть заявки» → `${webAppBaseUrl}/my-clubs?focus=inbox`. Лукапы (organizer / applicant / clubName) обёрнуты try-catch — при любом NPE / БД-промахе DM пропускается с WARN-логом, INSERT заявки уже произошёл. См. `docs/modules/applications-inbox.md` § «submitApplication — изменения».

### approveApplication
1. Заявка существует → `404 Application not found`
2. Клуб заявки существует → `404 Club not found`
3. `club.owner_id = caller` → иначе `403 Forbidden`
4. `application.status = pending` → иначе `400 Application is not pending`
5. `count(active memberships) < club.member_limit` → иначе `400 Club is full`
6. **Платный клуб** (`subscription_price > 0`):
   - вызвать `paymentService.createInvoice(application.userId, club.id)` — Stars-инвойс уйдёт в DM
   - **не создавать** membership и **не инкрементировать** `member_count` — это произойдёт в `handleSuccessfulPayment` после оплаты
7. **Бесплатный клуб** (`subscription_price = 0` или `null`):
   - `membershipRepository.create(userId, clubId)` — membership active
   - `clubs.member_count += 1`
8. `applications.status = approved`, `resolved_at = now()`
9. Логируется INFO

### rejectApplication
1. Заявка существует → `404 Application not found`
2. Клуб заявки существует → `404 Club not found`
3. `club.owner_id = caller` → иначе `403 Forbidden`
4. `application.status = pending` → иначе `400 Application is not pending`
5. **`reason` обязателен ≥5 символов после `trim()`.** DTO-валидация (`@NotBlank @Size(min=5, max=500)`) отсекает большинство случаев до Service; Service делает повторную проверку post-trim (`"  ab "` проходит `@Size(min=5)`, но trim даёт 2 символа → `400 VALIDATION_ERROR "Reason must be at least 5 characters after trim"`). Параметр `reason: String?` остаётся nullable в сигнатуре Service для возможных future system-driven reject — если `null`, проверка trim пропускается (в `rejected_reason` пишется `null`). Из UI всегда приходит non-null строка ≥5 символов.
6. `applications.status = rejected`, `rejected_reason = reason?.trim()?.ifEmpty { null }`, `resolved_at = now()`
7. Логируется INFO **без значения `reason`** (PII-класс — см. § «Non-functional / Логирование»)

### getClubApplications
1. Клуб существует → `404 Club not found`
2. `club.owner_id = caller` → иначе `403 Forbidden`
3. Если `status` передан — должен быть валидным literal'ом `ApplicationStatus`, иначе `400 Invalid status: <value>`
4. Возвращается список, сортировка `created_at DESC` (новые заявки сверху)

### getMyApplications
Без авторизационных проверок над данными — caller получает только свои заявки (фильтр `user_id = caller`). Сортировка `created_at DESC`.

### ApplicationScheduler
- `@Scheduled(fixedDelay = 3_600_000)` — каждые 60 минут.
- `@Transactional` обёртывает чтение и мутации.
- Алгоритм:
  1. `cutoff = now - 48h`
  2. `expired = findPendingOlderThan(cutoff)` — read snapshot
  3. Если пусто — выход.
  4. `markAutoRejected(cutoff)` — bulk UPDATE status → `auto_rejected`, `resolved_at = now()` для всех `pending` с `created_at < cutoff`
  5. Для каждого уникального `clubId` среди expired — `clubRepository.decreaseActivityRatingSafely(clubId, 5)` (penalty = 5, `GREATEST(rating - 5, 0)`)
- Логирование INFO: количество и id заявок, по каждому клубу — факт штрафа.

## Acceptance Criteria

**AC-1: успешная подача**
```
GIVEN клуб X — closed, application_question = "Расскажи о себе"
AND caller не member и не имеет активных заявок в X
WHEN POST /api/clubs/X/apply { answerText: "..." }
THEN 201 Created
AND applications: новая запись status=pending, answer_text сохранён
```

**AC-2: пустой answerText при обязательном вопросе**
```
GIVEN клуб X с application_question != null
WHEN POST /api/clubs/X/apply { answerText: "" }  // или { }
THEN 400 "Answer is required for this club"
AND applications: запись не создана
```

**AC-3: подача в открытый клуб**
```
GIVEN клуб X с access_type = open
WHEN POST /api/clubs/X/apply { answerText: "hi" }
THEN 400 "Club does not accept applications"
```

**AC-4: повторная подача (pending уже есть)**
```
GIVEN у caller pending-заявка в X
WHEN POST /api/clubs/X/apply { answerText: "..." }
THEN 409 "Application already exists"
```

**AC-5: повторная подача между approve и оплатой**
```
GIVEN approve уже произведён, membership ещё нет (платный клуб, invoice в DM)
WHEN POST /api/clubs/X/apply { answerText: "..." }
THEN 409 "Application already approved — waiting for payment"
```

**AC-6: rate limit 5/day**
```
GIVEN caller подал 5 заявок в текущих сутках
WHEN POST /api/clubs/X/apply (6-я)
THEN 429 "Too many applications today"
AND applications: запись не создана
```

**AC-7: approve бесплатного клуба**
```
GIVEN заявка pending, клуб subscription_price = 0
WHEN organizer POST /api/applications/{id}/approve
THEN 200 OK, application.status = approved
AND memberships: новая запись status=active, role=member
AND clubs.member_count += 1
```

**AC-8: approve платного клуба**
```
GIVEN заявка pending, клуб subscription_price = 500
WHEN organizer POST /api/applications/{id}/approve
THEN 200 OK, application.status = approved
AND paymentService.createInvoice вызван с (application.userId, clubId)
AND memberships: запись НЕ создана
AND clubs.member_count НЕ изменён
```

**AC-9: approve неpending-заявки**
```
GIVEN заявка status = rejected (или approved / auto_rejected)
WHEN organizer POST /api/applications/{id}/approve
THEN 400 "Application is not pending"
```

**AC-10: approve чужой заявки**
```
GIVEN caller не владелец клуба заявки
WHEN POST /api/applications/{id}/approve
THEN 403 "Forbidden"
```

**AC-11: approve при заполненном клубе**
```
GIVEN заявка pending, count(active memberships) = club.member_limit
WHEN organizer POST /api/applications/{id}/approve
THEN 400 "Club is full"
AND application.status НЕ меняется
```

**AC-12: reject с reason**
```
GIVEN заявка pending
WHEN organizer POST /api/applications/{id}/reject { reason: "не подходит" }
THEN 200 OK, status=rejected, rejected_reason="не подходит", resolved_at заполнен
```

**AC-13: reject без reason / с пустым / коротким reason**
```
GIVEN заявка pending
WHEN organizer POST /api/applications/{id}/reject (body отсутствует / { } / { reason: "" } / { reason: "    " } / { reason: "abcd" })
THEN 400 VALIDATION_ERROR (@NotBlank / @Size(min=5))
AND application.status НЕ меняется

WHEN organizer POST /api/applications/{id}/reject { reason: "  ab " }  // 5 raw chars, 2 после trim
THEN 400 VALIDATION_ERROR "Reason must be at least 5 characters after trim" (Service-level guard)
AND application.status НЕ меняется
```

**AC-14: reject reason — граница 500 символов**
```
WHEN organizer POST /api/applications/{id}/reject { reason: "x" * 500 }
THEN 200 OK, status=rejected, rejected_reason="x" * 500

WHEN organizer POST /api/applications/{id}/reject { reason: "x" * 501 }
THEN 400 VALIDATION_ERROR (Bean Validation @Size(max=500))
AND application.status НЕ меняется
```

**AC-15: список — только организатор**
```
GIVEN caller — НЕ владелец клуба X
WHEN GET /api/clubs/X/applications
THEN 403 "Forbidden"
```

**AC-16: фильтр по status**
```
GIVEN в клубе X 3 pending, 1 rejected, 2 approved заявки
WHEN organizer GET /api/clubs/X/applications?status=pending
THEN 200 OK, 3 элемента
```

**AC-17: невалидный status**
```
WHEN GET /api/clubs/X/applications?status=garbage
THEN 400 "Invalid status: garbage"
```

**AC-18: свои заявки**
```
GIVEN caller подал 4 заявки разных статусов
WHEN GET /api/users/me/applications
THEN 200 OK, 4 элемента, все принадлежат caller
```

**AC-19: scheduler auto-reject**
```
GIVEN 2 pending-заявки в клубе X старше 48 часов
WHEN ApplicationScheduler.autoRejectExpiredApplications() прогон
THEN обе заявки: status = auto_rejected, resolved_at = now()
AND логи INFO с id заявок
```

**AC-20: scheduler — нет expired**
```
GIVEN все pending моложе 48h
WHEN scheduler прогон
THEN ни UPDATE, ни decreaseActivityRatingSafely не вызваны
```

## Corner Cases

| Ситуация | Код | Сообщение |
|----------|-----|-----------|
| Клуб не найден | 404 | "Club not found" |
| Клуб открытый | 400 | "Club does not accept applications" |
| Ответ не указан при обязательном вопросе | 400 | "Answer is required for this club" |
| Уже active member | 409 | "Already a member" |
| Pending заявка уже есть | 409 | "Application already exists" |
| Approved заявка ждёт оплату | 409 | "Application already approved — waiting for payment" |
| Лимит 5 в сутки | 429 | "Too many applications today" |
| Approve не организатором | 403 | "Forbidden" |
| Approve не-pending заявки | 400 | "Application is not pending" |
| Approve при заполненном клубе | 400 | "Club is full" |
| Approve платного: заявитель без валидного telegram_id (не может получить invoice) | 200 | application.status = approved, invoice не отправлен (WARN-лог внутри `PaymentService`). Заявитель не получит DM — эскалация после первого инцидента. |
| Reject не организатором | 403 | "Forbidden" |
| Reject не-pending заявки | 400 | "Application is not pending" |
| Reject без reason / `<5` после trim | 400 | VALIDATION_ERROR (`@NotBlank` / `@Size(min=5)` / Service post-trim guard) |
| Reject с reason > 500 символов | 400 | VALIDATION_ERROR (Bean Validation `@Size(max=500)`) |
| GET /applications не организатором | 403 | "Forbidden" |
| GET /applications?status=invalid | 400 | "Invalid status: <value>" |
| Запрос без JWT | 401 | стандартный Spring Security |

## Non-functional

- **Транзакции**: `submitApplication`, `approveApplication`, `rejectApplication`, `ApplicationScheduler.autoRejectExpiredApplications` — все обёрнуты `@Transactional`. На submit это критично для атомарности rate-limit check + insert (две параллельные заявки одного пользователя на 5-й и 6-й позиции корректно сериализуются под REPEATABLE READ/SERIALIZABLE; на READ COMMITTED обе могут пройти, но в МVP допустимо).
- **Идемпотентность**: на уровне БД отсутствует уникальный constraint `(user_id, club_id, status)`. Защита от дублей `pending` — `findActiveByUserAndClub` в Service. Race window существует: два одновременных submit от одного пользователя теоретически могут создать две `pending`-записи. Acceptable для MVP, fix — partial UNIQUE индекс по `(user_id, club_id) WHERE status IN ('pending', 'approved')`.
- **Логирование**:
  - INFO: submit (+ строка «DM dispatched for application-created: clubId=…, organizerTelegramId=…» при успешной диспетчеризации, либо WARN при пропуске), approve, reject (с id + clubId + userId), invoice request, membership-create на approve бесплатного клуба, scheduler — количество auto-rejected и факт штрафа
  - PII (answerText, reason) **в логи не пишутся** — контроллер `reject` явно не логирует `request.reason`, Service-лог reject содержит только ids
- **Безопасность**:
  - `@Valid` на DTO: `@NotBlank @Size(min=5, max=500)` на `RejectApplicationRequest.reason`
  - Все endpoint'ы JWT-защищены (`AuthenticatedUser` через Spring Security)
  - Owner-check у approve/reject/list — через `club.owner_id == caller.userId` в Service
  - Rate limit 5/day per user — защита от спама заявок
  - `answerText` не санитизируется на бекенде; вывод в Mini App рендерится React'ом (HTML-экранирование автоматически) — XSS не возможна
- **Производительность**:
  - `findActiveByUserAndClub`, `findById`, `findByClubId` используют индексы `(club_id, status)` и `(user_id)` (см. ARCHITECTURE.md §5)
  - `countTodayByUser` — count over `user_id + created_at >= start_of_day` (плановое: index по `created_at`, текущая нагрузка низкая)
  - Scheduler — раз в час, на текущих объёмах массовый UPDATE не превышает миллисекунд
- **Доступность**: при падении `PaymentService.createInvoice` `approveApplication` пробросит исключение → `@Transactional` откатит обновление статуса. Заявка остаётся pending. Acceptable (organizer повторит).

## Интеграции

- **`club` модуль** — `ClubRepository.findById` (для access_type / owner_id / member_limit / subscription_price), `incrementMemberCount` (free approve), `decreaseActivityRatingSafely` (scheduler штраф)
- **`membership` модуль** — `findActiveByUserAndClub` (проверка дубля), `countActiveByClubId` (проверка лимита), `create` (free approve)
- **`payment` модуль** — `PaymentService.createInvoice` при approve платного клуба. Membership создаётся в `PaymentService.handleSuccessfulPayment`, не в `ApplicationService`.
- **`telegram-bot` модуль** — DM **организатору** на submit реализован (`NotificationService.sendApplicationCreatedDM`, см. `docs/modules/telegram-bot.md` § «sendApplicationCreatedDM» и `docs/modules/applications-inbox.md`). DM **заявителю** об approve/reject **остаётся не реализованным** (GAP-007 в `docs/backlog/telegram-bot-prd-gaps.md` — частично закрыт).
- **БД** — таблицы `applications`, `memberships`, `clubs`. Enum `application_status` (`pending` / `approved` / `rejected` / `auto_rejected`).

## Известные ограничения

- **`SubmitApplicationRequest.answerText` без `@Size`** — нет ограничения сверху. Риск DoS через мегабайтные строки (раздувание БД, медленные `SELECT`). См. `docs/backlog/application-answertext-validation.md`. Pre-existing, не регрессия рефактора.
- **`paymentService.createInvoice` внутри `@Transactional`** — сетевой вызов в Telegram держит БД-транзакцию открытой. Pre-existing concern из reviewer-ревью (см. payment.md паттерн post-commit event для решения). Не блокирует, backlog.
- **DM-нотификации заявителю** отсутствуют (GAP-007). Пользователь видит решение только при следующем заходе в Mini App.
- **Двухкнопочный UX закрытого клуба** (GAP-6) не реализован — approve платного клуба автоматически создаёт invoice, а не разблокирует кнопку «Вступить».
- **Race-condition на pending-дубликат**: см. блок Non-functional → «Идемпотентность». Backlog: partial UNIQUE на `(user_id, club_id)` для активных статусов.
- **Resubmit после `rejected` / `auto_rejected`** не описан и не запрещён явно — в текущем коде проверка идёт только по активным статусам (`pending` / `approved`). Это допускает повторную подачу после отказа. Намеренное поведение или баг — открытый вопрос.
