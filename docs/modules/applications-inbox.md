# Applications Inbox — кросс-клубовый организаторский инбокс + DM-триггер

> Core backend поведение submit / approve / reject / scheduler / DTO базового
> `ApplicationDto` живёт в `docs/modules/application.md`. Этот документ
> описывает **дополнительную** поверхность: кросс-клубовый организаторский
> инбокс на `MyClubsPage`, точку-индикатор на `BottomTabBar`, обязательную
> причину отказа, DM при создании заявки и показ причины отказа заявителю.
> Не дублируем сюда правила базового submit/approve/reject — только дельта.

## Цель

Сейчас «Заявки» спрятаны во вкладке per-club в `OrganizerClubManage` и
требуют от организатора зайти в каждый клуб отдельно. Скрипт автоотклонения
через 48 часов «съедает» заявки молча: ни органзатор, ни заявитель не получают
сигнала. Организаторы пропускают заявки → заявители не попадают в клуб и
очередь зависает.

Фича решает:
1. **Сборка всех pending-заявок в одном месте** на `MyClubsPage` (где
   организатор уже бывает каждый день, в отличие от per-club manage).
2. **DM-триггер при создании заявки** — организатор узнаёт сразу, а не
   через 47 часов 59 минут.
3. **Снимок личности и репутации заявителя в карточке** — peer-signal
   агрегирован по всем клубам пользователя, без необходимости открывать
   профиль вручную.
4. **Обязательная причина отказа** — заявитель получает фидбек, может
   корректировать поведение в других клубах, не остаётся в неведении.

## Scope

### Входит
- **Backend**
  - `GET /api/users/me/applications-pending` — все pending-заявки по всем
    клубам, где caller — owner, обогащённые applicant + peer-stats + club.
  - `GET /api/users/me/applications-pending-count` — счётчик для tab-dot.
  - `RejectApplicationRequest.reason` → mandatory, `@NotBlank` +
    `@Size(min=5, max=500)`. Существующий контракт `application.md` сейчас
    допускает `null` и до 500 символов — это поведение **меняется**, см.
    «Изменения существующих контрактов».
  - DM-уведомление организатору в `ApplicationService.submitApplication`
    после INSERT (через `NotificationService` + `@Async`, fail-isolated от
    основной транзакции).
  - **DM-уведомление ЗАЯВИТЕЛЮ при одобрении** (de-Stars, 2026-06-28) в
    `ApplicationService.approveApplication` → `NotificationService.sendApplicationApprovedDM`
    (best-effort, fail-isolated). Платный клуб (`subscriptionPrice>0`, участник стал
    `frozen`): «✅ Вашу заявку … одобрили — оплатите вступление» + кнопка «Оплатить взнос»
    (deep-link `/clubs/{id}` → frozen-экран). Бесплатный (`active` сразу): «… одобрили.
    Добро пожаловать!». Закрывает разрыв: раньше участник не знал, что одобрен, и не
    возвращался платить. Только закрытые клубы (заявки есть лишь у них); платный+открытый
    платит сразу при вступлении — без заявки/одобрения.
- **Frontend**
  - Новая секция «Заявки на рассмотрении» на `MyClubsPage` (organizer-side).
  - `ApplicationReviewModal` — полный профиль заявителя + ответ + клуб +
    кнопки «Принять» / «Отклонить» с textarea для reason.
  - Точка-индикатор на табе «Мои клубы» в `BottomTabBar` когда
    `pending count > 0`.
  - Показ `rejectedReason` заявителю в карточке pending-заявки на
    `ProfilePage` («Активные заявки») и на `MyClubsPage` (AppCard applicant-side).
- **Удаление**
  - Tab «Заявки» из `OrganizerClubManage` удаляется полностью.
    `TabKey` сокращается до `'members' | 'finances' | 'settings'`.
    `ApplicationsTab` компонент — удалить. Deep-link `?tab=applications`
    добавить в `LEGACY_TAB_KEYS` (fallback на `members`).
  - `ApplicationsTab` УДАЛЯЕТСЯ; функциональность переезжает на
    `MyClubsPage`. См. изменения в `docs/modules/clubs.md`.

### НЕ входит (YAGNI / следующая итерация)
- Архив approved / rejected (история решений). На фронте сейчас нет;
  бэкенд `GET /api/clubs/{id}/applications?status=*` остаётся, но не
  используется UI.
- Stage 1 «Пойду» агрегат в peer-signal (отдельная колонка не существует).
- Throttling / batching DM при одновременной серии заявок (если 5 заявок
  за минуту — будет 5 DM, осознанно).
- Шаблоны / quick-presets для reject reason.
- Resubmit после rejected — статусы есть, flow не описан (см. известные
  ограничения в `application.md`).

## User Stories

### US-1: организатор видит все заявки сразу
**Как** организатор нескольких клубов
**Я хочу** видеть pending-заявки по всем своим клубам в одном месте на «Мои клубы»
**Чтобы** не пропускать заявки из-за необходимости заходить в каждый клуб

### US-2: DM как сигнал
**Как** организатор
**Я хочу** получить Telegram-уведомление сразу при подаче новой заявки
**Чтобы** среагировать раньше 48-часового авто-отклонения

### US-3: peer-signal до открытия профиля
**Как** организатор
**Я хочу** видеть в карточке заявителя сводный сигнал «N клубов · посетил X из Y»
**Чтобы** принимать решение, не открывая полный профиль каждого

### US-4: обязательная причина отказа
**Как** заявитель отклонённой заявки
**Я хочу** видеть причину отказа в моём профиле и на «Мои клубы»
**Чтобы** понять что улучшить или скорректировать выбор клуба

### US-5: индикатор в навигации
**Как** организатор
**Я хочу** видеть точку на табе «Мои клубы» когда есть необработанные заявки
**Чтобы** не открывать вкладку зря и не пропускать новые pending

### US-6: deep-link из DM
**Как** организатор, кликнувший на DM-уведомление
**Я хочу** оказаться сразу на секции «Заявки на рассмотрении» с автоскроллом
**Чтобы** не искать инбокс среди клубов

## API контракты

### `GET /api/users/me/applications-pending` (NEW)

Список pending-заявок по всем клубам, где caller — `owner_id`.
Обогащён applicant + peer-stats + club для рендера без N+1 на фронте.

Request: без параметров. JWT-юзер.

Response 200:
```json
[
  {
    "applicationId": "uuid",
    "answerText": "string|null",
    "createdAt": "ISO-8601",
    "hoursUntilAutoReject": 41,
    "applicant": {
      "userId": "uuid",
      "firstName": "string",
      "lastName": "string|null",
      "telegramUsername": "string|null",
      "avatarUrl": "string|null"
    },
    "peerStats": {
      "memberClubCount": 5,
      "totalConfirmations": 4,
      "totalAttendances": 3
    },
    "club": {
      "id": "uuid",
      "name": "string",
      "avatarUrl": "string|null"
    }
  }
]
```

Сортировка: `createdAt ASC` (старые сверху — больше риска авто-reject).

Errors:
- `401` — нет JWT.
- (никакого `403` — caller получает только свои данные, если он не организатор
  ни одного клуба → пустой массив `[]`).

### `GET /api/users/me/applications-pending-count` (CHANGED, breaking)

Combined counter feeding the `/my-clubs` nav-dot. Оба числа сигнализируют
«есть действие организатора на этой вкладке». Один endpoint = один cache slot
на фронте. **UPDATED (de-Stars 2026-06-28):** Stars-эпоховые `awaitingPaymentCount` /
`organizerAwaitingPaymentCount` удалены (инвойсов больше нет); вместо них
`awaitingDuesCount` (paid-and-waiting members).

Response 200:
```json
{ "inboxCount": 7, "awaitingDuesCount": 2 }
```

Поля:
- `inboxCount` — pending-заявки по клубам, где caller — owner (organizer-action).
- `awaitingDuesCount` — frozen-участники, заявившие оплату (claim) по клубам
  caller'а (owner): оплатили off-platform и ждут решения «Взнос получен» /
  «Отказать». De-Stars: `membershipRepository.countClaimedFrozenByOwner`.

Оба зажигают точку на «Мои клубы» (sum > 0).

Errors: `401` — нет JWT.

> **Breaking-change** vs. предыдущая итерация: было `{ "count": N }`. Frontend
> обновляется одновременно с backend. Добавление `organizerAwaitingPaymentCount`
> в 2026-05-31 — additive, существующие fields сохраняются.

### `GET /api/users/me/applications-awaiting-payment` (NEW)

Список approved-заявок caller'а, по которым нет active membership (т.е.
Stars-инвойс был отправлен, но оплата не дошла). Сортировка `resolvedAt DESC`.

Response 200:
```json
[
  {
    "applicationId": "uuid",
    "approvedAt": "ISO-8601",
    "club": { "id": "uuid", "name": "string", "avatarUrl": "string|null" },
    "subscriptionPrice": 250
  }
]
```

`subscriptionPrice` — в Stars (XTR), для отображения «Оплатить N⭐».
Errors: `401` — нет JWT. Caller получает только свои данные (фильтр по
`user_id = caller.userId`), IDOR не применим.

### `POST /api/applications/{id}/resend-invoice` (NEW)

Re-trigger Stars-инвойса для approved-but-unpaid заявки. Кнопка живёт рядом
с карточкой в «Ожидают оплаты».

Request: пустой body.

Auth: JWT, caller обязан быть applicant'ом (`application.user_id == caller.userId`).

Response: `204 No Content`.

Errors:
- `401` — нет JWT.
- `403 FORBIDDEN` — caller не applicant (НЕ owner-проверка, разница с
  approve/reject).
- `404 NOT_FOUND` — application не существует.
- `400 VALIDATION_ERROR` `"No payment pending"` — статус не `approved` ИЛИ
  у пользователя уже есть active/grace-period membership в этом клубе.
- `429 RATE_LIMIT_EXCEEDED` `"Please wait before resending the invoice"`
  — повторный вызов раньше 60 секунд (in-memory per-application cooldown).
- `5xx` — Telegram API ошибка при отправке инвойса (пробрасывается из
  `PaymentService.createInvoice` через `GlobalExceptionHandler`).

Логирование: INFO `"Invoice resent: applicationId=... userId=... clubId=..."`.
Сумма инвойса и детали платежа **не логируются**.

### `GET /api/users/me/organizer/awaiting-payment-applicants` (NEW)

Cross-club mirror of `/api/clubs/{clubId}/awaiting-payment-applicants`:
approved-but-unpaid applicants across **all** clubs where caller is owner.
Surfaces on `MyClubsPage` so an organizer with multiple clubs sees pending
payments in one place without entering each club. Сортировка `resolvedAt DESC`.

Auth: JWT. Скоупится через `clubs.owner_id IN (caller_owned_club_ids)` —
non-organizers получают пустой массив, 403 не нужен.

Response 200:
```json
[
  {
    "applicationId": "uuid",
    "approvedAt": "ISO-8601",
    "userId": "uuid",
    "firstName": "string",
    "lastName": "string|null",
    "telegramUsername": "string|null",
    "avatarUrl": "string|null",
    "club": { "id": "uuid", "name": "string", "avatarUrl": "string|null" },
    "subscriptionPrice": 250
  }
]
```

Errors: `401` — нет JWT.

Свободные клубы (`subscription_price = 0`) **никогда** не попадают в выдачу —
membership создаётся синхронно при approve.

### `GET /api/clubs/{clubId}/awaiting-payment-applicants` (NEW)

Organizer-only mirror of `/api/users/me/applications-awaiting-payment`:
applicants for [clubId] whose application is approved but the Stars invoice
hasn't been paid (no active/grace_period membership). Surfaces on
`ClubMembersTab` (organizer view) so the full applicant → member lifecycle
is visible in one place.

Auth: JWT, caller must be `clubs.owner_id` of [clubId].

Response 200:
```json
[
  {
    "applicationId": "uuid",
    "userId": "uuid",
    "firstName": "string",
    "lastName": "string|null",
    "telegramUsername": "string|null",
    "avatarUrl": "string|null",
    "approvedAt": "ISO-8601"
  }
]
```

Сортировка: `resolvedAt DESC` (свежие сверху).

Errors:
- `401` — нет JWT.
- `403 FORBIDDEN` — caller не owner клуба.
- `404 NOT_FOUND` — клуб не существует.

Свободные клубы (`subscription_price = 0`) **никогда** не попадают в выдачу —
membership создаётся синхронно при approve, состояние «approved-without-membership»
для них не возникает.

### `POST /api/applications/{id}/complete-free-membership` (NEW)

Завершает вступление в **бесплатный** клуб (`subscription_price <= 0`) для
approved-заявки, которая по легаси / прошлому багу осталась без membership-записи.
Авто-вызов с MyClubsPage при обнаружении stuck-заявок (silent self-heal); как
fallback — кнопка «Завершить вступление» на `ClubPage` в visitor-CTA-блоке.

Делегирует в `FreeMembershipActivator.activate` (см. `docs/modules/membership.md`
§ «Free-club reactivate-or-create»):
- если для `(user_id, club_id)` строки нет → INSERT membership(active);
- если строка существует со статусом `cancelled` / `expired` → **реактивация**
  (status=active, joined_at=now, subscription_expires_at=null, updated_at=now);
- если строка существует со статусом `active` / `grace_period` → Service
  отдаёт 400 «Already a member» ДО вызова активатора.

Счётчик участников отдельно не пишется: колонка `clubs.member_count` дропнута в
V33, значение считается на лету из `memberships`. Новая/реактивированная active-строка
автоматически попадает в live-счёт.

Всё в одной транзакции на уровне Service.

Request: пустой body.

Auth: JWT, caller обязан быть applicant'ом (`application.user_id == caller.userId`).

Response: `200 OK` с `MembershipDto` (см. `docs/modules/membership.md`).

Errors:
- `401` — нет JWT.
- `403 FORBIDDEN` — caller не applicant.
- `404 NOT_FOUND` — application не существует / клуб не существует.
- `400 VALIDATION_ERROR` `"Application is not approved"` — статус заявки не `approved`.
- `400 VALIDATION_ERROR` `"Club is not free — pay the invoice instead"` —
  у клуба `subscription_price > 0` (paid-клубы используют Stars-инвойс).
- `400 VALIDATION_ERROR` `"Already a member"` — у пользователя уже есть
  active / grace_period membership в этом клубе.

Логирование: INFO `"Free membership completed for stuck application:
applicationId=... userId=... clubId=..."` (Service) + INFO активатора
`"Free membership created"` или `"Free membership reactivated"` с
`previousStatus`.

#### Frontend self-heal (MyClubsPage)

На монтировании страницы, после загрузки `myClubs` + `myApplications` +
деталей клубов: фильтруется набор заявок где `status === 'approved'` И
`subscriptionPrice === 0` И user НЕ в active membership этого клуба. Для
каждой — silent `POST /complete-free-membership` (без haptic, без toast).
`useRef` флаг гарантирует один запуск на mount. После success — invalidate
`queryKeys.clubs.my()` → КПСС появляется в «Активные клубы». Платные клубы
из этой логики исключены — у них корректный путь через «Ожидают оплаты».

### `POST /api/applications/{id}/reject` (CHANGED)

Изменения к контракту из `application.md` §API:

Request:
```json
{ "reason": "string" }
```

`reason` теперь **обязателен**:
- `@NotBlank` (после `trim()` не пуст)
- `@Size(min=5, max=500)`

Errors:
- `400 VALIDATION_ERROR` — `reason` отсутствует / пустой / `< 5` символов
  после trim / `> 500` символов. (Раньше пустой / отсутствующий проходил
  с записью `null` в БД.)

Поведение в Service неизменно: INSERT идёт независимо от того, кто и как
форматирует строку; пробельные символы по краям обрезаются перед сохранением.

> Backward-compat: существующие записи в `applications.rejected_reason` со
> значением `NULL` или текстом `<5` символов остаются как есть. Новые
> reject-операции через UI обязаны соблюдать новое правило.
> Старый организатор-агент `ApplicationsTab` удалён, других вызывающих
> `/reject` нет; миграция данных не нужна.

## DTO (backend)

### `PendingApplicationDto`

Файл: `application/PendingApplicationDto.kt` (новый).

```kotlin
data class PendingApplicationDto(
    val applicationId: UUID,
    val answerText: String?,
    val createdAt: OffsetDateTime,
    val hoursUntilAutoReject: Int,    // floor((createdAt + 48h - now) / 1h), min 0
    val applicant: ApplicantInfoDto,
    val peerStats: PeerStatsDto,
    val club: ClubBriefDto
)

data class ApplicantInfoDto(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val telegramUsername: String?,
    val avatarUrl: String?,
    val country: String?,
    val city: String?,
    val bio: String?,
    val interests: List<String>      // batch-loaded via InterestRepository.findUserInterestNamesByUserIds
)

data class PeerStatsDto(
    val memberClubCount: Int,
    val totalConfirmations: Int,
    val totalAttendances: Int,
    // P1b cross-club reputation (реализовано 2026-06-14, on-read из ledger через ApplicantSignalService):
    val reliableClubs: Int,       // N — клубы с Trust ≥ 70 среди клубов с track-record
    val trackRecordClubs: Int,    // M — клубы с ≥3 исходами (донат «надёжен в N из M клубов»)
    val level: Int,               // глобальный уровень геймификации (1..10)
    val levelName: String,
    val levelTier: String         // "base" | "mid" | "top" — цвет пилла (gold у top)
)

data class ClubBriefDto(
    val id: UUID,
    val name: String,
    val avatarUrl: String?
)
```

### `PendingApplicationsCountDto`

```kotlin
data class PendingApplicationsCountDto(
    val inboxCount: Int,
    // De-Stars: frozen members who declared a dues payment, across the caller's clubs (awaiting decision).
    val awaitingDuesCount: Int = 0
)
```

### `AwaitingPaymentApplicationDto`

```kotlin
data class AwaitingPaymentApplicationDto(
    val applicationId: UUID,
    val approvedAt: OffsetDateTime,
    val club: ClubBriefDto,
    val subscriptionPrice: Int    // в Stars (XTR)
)
```

### `AwaitingPaymentApplicantDto`

Organizer-side mirror — описывает applicant'а, а не club'а:

```kotlin
data class AwaitingPaymentApplicantDto(
    val applicationId: UUID,
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val telegramUsername: String?,
    val avatarUrl: String?,
    val approvedAt: OffsetDateTime
)
```

### `OrganizerAwaitingPaymentApplicantDto`

Cross-club вариант для MyClubsPage. К applicant-полям выше добавлены
`club` и `subscriptionPrice` — карточка рендерится в общем списке без
контекста какого-либо одного клуба.

```kotlin
data class OrganizerAwaitingPaymentApplicantDto(
    val applicationId: UUID,
    val approvedAt: OffsetDateTime,
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val telegramUsername: String?,
    val avatarUrl: String?,
    val club: ClubBriefDto,
    val subscriptionPrice: Int
)
```

### `RejectApplicationRequest` (изменён)

```kotlin
data class RejectApplicationRequest(
    @field:NotBlank
    @field:Size(min = 5, max = 500)
    val reason: String
)
```

## Бизнес-правила

### `getMyPendingApplications(organizerId)`

1. `clubIds = clubRepository.findIdsByOwnerId(organizerId)` (новый метод
   репозитория клубов; используйте существующий способ если уже есть).
   Если пусто → return `emptyList()`.
2. `applications = applicationRepository.findPendingByClubIds(clubIds)`
   (новый метод репозитория заявок).
3. Для каждой заявки:
   - Загрузить applicant (`UserRepository.findById` или batch).
   - Загрузить peer-stats (см. ниже «Peer-signal»).
   - Загрузить club brief из уже выбранных clubs.
   - Вычислить `hoursUntilAutoReject = max(0, floor(((createdAt + 48h) - now) / 1h))`.
4. Вернуть отсортировано по `createdAt ASC`.

**Производительность**: batch-load users по `userId IN (...)` и peer-stats
batch-load по `userId IN (...)` — один запрос для всех applicants.
N+1 не допускается.

### `getMyPendingApplicationsCount(organizerId)`

`SELECT COUNT(*) FROM applications a JOIN clubs c ON a.club_id = c.id WHERE c.owner_id = :organizerId AND a.status = 'pending'`.
Один запрос. Используется в правиле производительности ниже (см. AC-12).

### `submitApplication` — изменения

После строки `log.info("Application submitted: ...")` (`ApplicationService.kt:59`),
**до** `return`, добавить:

```kotlin
notificationService.sendApplicationCreatedDM(
    organizerTelegramId = ...,
    applicantDisplayName = ...,
    clubName = ...
)
```

Правила:
- Вызов **внутри `@Transactional` метода допустим**, потому что
  `NotificationService.sendApplicationCreatedDM` помечен `@Async` — Spring
  откладывает выполнение до commit'а транзакции (поведение
  `@Async`-методов: они выполняются в отдельном thread'е, и любая ошибка
  в DM **не откатывает** транзакцию заявки).
- Аргументы: `organizerTelegramId` (Long из `users.telegram_id` владельца
  клуба), `applicantDisplayName` (`firstName` + опц. `lastName`),
  `clubName` (`clubs.name`).
- Загрузить `organizerTelegramId` и `clubName` через существующий
  `clubRepository.findById(clubId)` + `userRepository.findTelegramIdById(...)`.
  Если уже есть в текущей выборке клуба — переиспользовать.
- Если applicant без `firstName` (теоретически не бывает, в БД NOT NULL) —
  fallback на «Новый пользователь».

### `rejectApplication` — изменения

Контракт остаётся как в `application.md` §rejectApplication, но:
- Валидация reason переехала на DTO-уровень (`@NotBlank @Size(min=5, max=500)`) → 400 уходит до Service для очевидных случаев (пустой / `<5` / `>500`).
- Service по-прежнему получает `reason: String?` параметром (для обратной
  совместимости с тестами и потенциального future system-driven reject от
  scheduler'а), но из UI всегда придёт непустая строка ≥5 символов.
- **Defense-in-depth post-trim guard в Service:** `"  ab "` проходит `@Size(min=5)` (raw длина 5), но `trim()` даёт 2 символа. Service делает повторную проверку: если `reason != null` И (после trim длина <5 ИЛИ trim даёт пустую строку) → `ValidationException "Reason must be at least 5 characters after trim"` → 400. Это закрывает дыру в `@Size(min=5)` против пограничных whitespace-payload'ов и не блокирует возможный future system-driven reject с `reason = null`.
- В `applications.rejected_reason` пишется `reason?.trim()?.ifEmpty { null }`. `null` reason записывается как есть.

## Peer-signal — формула и edge cases

**Источник**: таблица `user_club_reputation` (одна строка per `(user_id, club_id)`) — теперь
производный кэш над `reputation_ledger` (модель v2, см. [`reputation.md`](./reputation.md)).

> **Семантика `memberClubCount` = «клубы с track-record»** (где у юзера есть строка в
> `user_club_reputation`). После анти-фарм правила 1 владелец **не входит** в счёт по **своему**
> клубу (там ему репутация не начисляется → строки нет). Новичок без исходов тоже не имеет строки.
> Поэтому «В N клубах» считает клубы, где есть реальный track-record (надёжность зарабатывается в
> чужих клубах) — это намеренно, не баг. Edge-cases ниже без изменений.

Поля:
- `memberClubCount` = `COUNT(*) FROM user_club_reputation WHERE user_id = :id`
- `totalConfirmations` = `COALESCE(SUM(total_confirmations), 0)`
- `totalAttendances` = `COALESCE(SUM(total_attendances), 0)`

Семантика (см. `docs/modules/reputation.md`):
- `total_confirmations` — сколько раз пользователь сказал «иду / не иду»
  на Stage 2 (final commitment).
- `total_attendances` — сколько раз реально пришёл (из тех, кто сказал «иду»).
- Формула «посетил X из Y» = `totalAttendances` / `totalConfirmations`,
  где Y = confirmations, X = attendances. **НЕ stage-1 voting** (отдельный
  агрегат не существует).

**Edge cases**:

| Случай | Условие | Фраза на фронте |
|---|---|---|
| Новый пользователь | `memberClubCount = 0` | «Новый пользователь» |
| Есть клубы, нет событий | `memberClubCount ≥ 1 AND totalConfirmations = 0` | «В {N} клубах · ещё не было событий» |
| Нормальный случай | `memberClubCount ≥ 1 AND totalConfirmations ≥ 1` | «В {N} клубах · посетил {X} из {Y} событий» |

Где:
- `{N}` — `memberClubCount` со склонением (`клуб` / `клуба` / `клубах`,
  для предложного падежа всегда «клубах»).
- `{X}` — `totalAttendances`
- `{Y}` — `totalConfirmations`
- `{X} из {Y} событий` — со склонением «событие/события/событий».

**Глагол «посетил»** (perfective, агрегатный) выбран осознанно: «посещал»
читалось бы как процесс одного события. Альтернатива «всего посетил X из Y»
(вставка «всего») — на случай если в QA скажут что «посетил» без усилителя
ассоциируется с одним событием. Третий вариант — «Сдержал слово {X} из {Y}
раз · {N} клубов» — label-style, как fallback. Выбор формулировки — на
этапе ревью с пользователем.

Бэкенд **отдаёт сырые числа**; форматирование фразы целиком на фронте
(`features/applications-inbox/lib/peer-signal-format.ts` или эквивалент).

## Frontend

### Хуки

Файл: `frontend/src/queries/applications.ts` (дополнить).

```typescript
export function useMyPendingApplicationsQuery() {
  return useQuery({
    queryKey: queryKeys.applications.myPending,
    queryFn: getMyPendingApplications,
  });
}

export function useMyPendingApplicationsCountQuery() {
  return useQuery({
    queryKey: queryKeys.applications.myPendingCount,
    queryFn: getMyPendingApplicationsCount,
    select: (data) => data.count,
    staleTime: 60_000,
  });
}
```

Зеркалирует `useSkladchinaActionRequiredCountQuery` (`staleTime: 60_000`,
`select` для unwrap'а).

### `MyClubsPage` — новая секция

Положение: **между** «Активные» и «Заявки» (applicant-side). Рендерится
только если `useMyPendingApplicationsQuery().data.length > 0` ИЛИ
ответ ещё в loading после первого fetch.

Структура:
```
[mc-section-label] Заявки на рассмотрении · {N}
[mc-list]
  [pending-app-card] × N
```

`pending-app-card` (новый компонент, либо inline-функция в `MyClubsPage`):
- Avatar (40px) — `applicant.avatarUrl` или инициалы.
- Body:
  - top: `{firstName} {lastName ?? ''}` + `@{telegramUsername}` (если есть)
    + компактный level-чип (`LevelPill size="sm"`) **только для mid/top тиров** (base/«Гость» не шумит)
  - meta: peer-signal-строка (см. формат выше)
  - club-chip: маленький pill «{club.name}» (≤ 20 chars, ellipsis)
  - hint: `{hoursUntilAutoReject}ч до автоотклонения` (red если ≤ 6,
    grey иначе)
- Tap → открыть `ApplicationReviewModal` с этой заявкой.

### `ApplicationReviewModal`

Файл: `frontend/src/components/applications/ApplicationReviewModal.tsx`
(новый).

Паттерн модалки — как у `ProfileEditModal` (`createPortal`, z-index `150`).

Контент:
- Hero: avatar (большой) + firstName + lastName + `@username` + город + **пилл уровня**
  (`levelName · ур.N`, gold у top-тира / accent у mid / нейтральный у base).
- «О себе» + «Интересы» (chips) — из `ApplicantInfoDto`.
- **«Активность на платформе»** (`PlatformActivity`): донат «N из M клубов» (`reliableClubs/trackRecordClubs`)
  + заголовок «Надёжен в N из M клубов» + строка `formatPeerSignal` («В N клубах · посетил X из Y событий»).
  Edge: `memberClubCount === 0` или `trackRecordClubs === 0` → без доната, только строка. Дисклеймер
  «(не организаторский опыт)» НЕ добавляется — заголовок сам задаёт рамку (owner-blind: глобальная репа
  слепа к организаторству, т.к. ledger не пишет строк в своём клубе владельца).
- Club row: «Клуб: **{club.name}**».
- Q&A: если `answerText` есть — «Ответ на вопрос:» + текст (с переносами).
- Hint: «До автоотклонения: {hours}ч».
- Действия (внизу sticky):
  - Default state: две кнопки `[Принять] [Отклонить]`.
  - После клика `Отклонить` — раскрывается секция:
    - `Textarea` placeholder «Объясните причину (минимум 5 символов)».
    - Counter «{n}/500».
    - Кнопки `[Отмена] [Подтвердить отклонение]`.
    - `[Подтвердить отклонение]` `disabled` пока `reason.trim().length < 5`.
- Закрытие: tap по backdrop, по кнопке × вверху, по Escape.

Поведение после действия:
- Approve / reject вызывают существующие `useApproveApplicationMutation` /
  `useRejectApplicationMutation` (контракт reason ужесточён в DTO).
- В `onSuccess` инвалидируются `applications.myPending` и
  `applications.myPendingCount` (помимо существующих инвалидаций).
- Haptic: `notify('success')` на approve, `notify('warning')` на reject.

### `BottomTabBar` — точка-индикатор

Применяется тот же паттерн, что для `/activities`:
- Опрашиваем `useMyPendingApplicationsCountQuery()`.
- На табе `/my-clubs` если `count > 0` — рендерим
  `<span className="tab-dot" aria-label="Есть необработанные заявки" />`
  как **sibling** от `.ico` (CSS уже описывает `.brand-tabbar .tab .tab-dot`
  на line 620 `brand-theme.css`).

### Показ `rejectedReason` заявителю

#### `MyClubsPage` — `AppCard` (applicant-side, ~line 125)

Если `application.status === 'rejected'` И `application.rejectedReason`
непуст, под status-label `«Отклонено»` дописывается третья строка:
`«Причина: {rejectedReason}»` (truncate по 2 строкам).

Источник данных: `ApplicationDto.rejectedReason` уже есть.

#### `ProfilePage` — pending-apps секция (~line 210)

В блок `pf-rep-card` под `.role` (`STATUS_LABELS[app.status]`)
добавить, если `app.status === 'rejected'` и `app.rejectedReason`
непуст: `<div className="reason">{app.rejectedReason}</div>` (truncate
по 2 строкам).

> **Уточнение к спецификации `profile.md` (зафиксировано в коде):** фильтр
> `pendingApps` на `ProfilePage` расширен с `status === 'pending'` до
> `pending | rejected | auto_rejected`, чтобы у отклонённых заявок было место
> показать причину. Approved-заявки в эту секцию по-прежнему не попадают
> (после approve пользователь становится member клуба). См. `profile.md`
> § AC-12 «rejected reason виден заявителю».

### Deep-link из DM

**Не через `startParam` / `DeepLinkHandler`.** Существующий паттерн в
`NotificationService.sendDirectMessageWithDeepLink` использует `WebAppInfo`
с прямым frontend URL (комментарий `NotificationService.kt:84-86`:
Telegram блокирует self-bot `t.me`-ссылки в DM с тем же ботом, поэтому
`WebAppInfo(url)` — единственный надёжный способ).

DM-кнопка:
- Текст: «Открыть заявки»
- `WebAppInfo(url = "${webAppBaseUrl}/my-clubs?focus=inbox")`

Frontend: `MyClubsPage` читает `useSearchParams().get('focus')`. Если
`focus === 'inbox'`:
1. После рендера секции «Заявки на рассмотрении» (т.е. когда query загружен
   и секция в DOM) — `scrollIntoView({ behavior: 'smooth', block: 'start' })`
   на якорь секции.
2. Сразу очистить query из URL (`setSearchParams({}, { replace: true })`),
   чтобы повторный refresh не триггерил scroll.

Если секции нет (caller не организатор / нет pending) — просто игнорировать.

`DeepLinkHandler` **не трогаем** — он работает только с `t.me?startapp=...`,
который мы не используем.

### Удаление `ApplicationsTab` из `OrganizerClubManage`

- В `TABS` (`OrganizerClubManage.tsx:40-45`) убрать строку
  `{ key: 'applications', label: 'Заявки' }`.
- В `type TabKey` (line 38) убрать `'applications'`.
- В `switch (activeTab)` (line 595) убрать кейс `'applications'`.
- Удалить весь компонент `ApplicationsTab` (lines 130-251) и неиспользуемые
  больше импорты: `useApproveApplicationMutation`, `useClubApplicationsQuery`,
  `useRejectApplicationMutation`, `Badge`, локальная функция `hoursRemaining`
  если больше нигде не используется. **Проверить** что эти хуки/функции
  не используются другими табами в файле — нельзя удалить если ещё кто-то
  импортирует.
- В `LEGACY_TAB_KEYS` (line 52) добавить `'applications'` — старые
  deep-links `?tab=applications` будут редиректить на `members`.

`useClubApplicationsQuery` + фетчер `getClubApplications` **удалены**
(2026-06-21, чистка мёртвого кода) — после переезда organizer-inbox на
`MyClubsPage` consumer'ов не осталось. `queryKeys.clubs.applications` сохранён
(используется в invalidation мутаций approve/reject + тестах). Backend-эндпоинт
`GET /api/clubs/{clubId}/applications` пока не трогали.

## Бэкенд — изменения по файлам

| Файл | Изменение |
|---|---|
| `application/PendingApplicationDto.kt` | **NEW** — DTO выше |
| `application/ApplicationDto.kt` | `RejectApplicationRequest.reason` → `@NotBlank @Size(min=5, max=500)`, тип `String` (не nullable) |
| `application/ApplicationController.kt` | Контроллер reject — `request` параметр становится `@Valid @RequestBody request: RejectApplicationRequest` (не nullable). Service вызов: `applicationService.rejectApplication(id, user.userId, request.reason)`. |
| `application/ApplicationService.kt` | Inject `notificationService: NotificationService`, `userRepository: UserRepository`. После `log.info("Application submitted...")` — async DM-вызов. Новые методы `getMyPendingApplications(organizerId)` и `getMyPendingApplicationsCount(organizerId)`. |
| `application/ApplicationRepository.kt` | Добавить `findPendingByClubIds(clubIds: List<UUID>): List<Application>`, `countPendingByClubIds(clubIds: List<UUID>): Int`, и `findApprovedWithoutMembershipByClubId(clubId: UUID): List<Application>` (per-club mirror для organizer-view `ClubMembersTab`). |
| `application/JooqApplicationRepository.kt` | Реализация трёх методов выше. `findApprovedWithoutMembershipByClubId` зеркалирует `findApprovedWithoutMembershipByUserId` — фильтр по `clubId` + JOIN CLUBS на `SUBSCRIPTION_PRICE > 0` + `NOT EXISTS` membership(active/grace). |
| `application/JooqApplicationRepository.kt` | (опционально) batch helper для applicant + peer-stats, либо использовать `UserRepository` + новый метод `ReputationRepository.aggregateByUserIds(...)`. |
| `application/ApplicationController.kt` | Перенести `GET /api/users/me/applications-pending` и `/applications-pending-count` сюда (рядом с `/me/applications` который в `UserController` — но новые endpoints логичнее в `ApplicationController`; решение Developer'а). Добавить organizer-only endpoint `GET /api/clubs/{clubId}/awaiting-payment-applicants`. |
| `application/ApplicationService.kt` | Добавить `getAwaitingPaymentApplicantsByClub(clubId, callerUserId)`: 404 если club нет, 403 если caller ≠ owner, batch-load applicants через `userRepository.findByIds`, map через новый `ApplicationMapper.toAwaitingPaymentApplicant`. |
| `application/ApplicationMapper.kt` | Добавить `toAwaitingPaymentApplicant(application, applicantRecord): AwaitingPaymentApplicantDto`. |
| `application/PendingApplicationDto.kt` | Добавить `AwaitingPaymentApplicantDto` (organizer-side mirror). |
| `bot/NotificationService.kt` | Новый `@Async fun sendApplicationCreatedDM(organizerTelegramId: Long, applicantDisplayName: String, clubName: String)`. Внутри использует существующий `sendDirectMessageWithDeepLink(telegramId, text, webAppPath = "/my-clubs?focus=inbox", buttonText = "Открыть заявки")`. |
| `reputation/ReputationRepository.kt` | `aggregateByUserIds(userIds): Map<UUID, PeerStatsAggregate>` (memberClubCount/confirmations/attendances). **P1b:** + `findOutcomesByUserIds(userIds): Map<UUID, List<ClubLedgerOutcome>>` (батч ledger). |
| `reputation/ApplicantSignalService.kt` | **NEW (P1b)** — `signalsFor(userIds): Map<UUID, ApplicantSignal>`: «N из M» (`TrustService.globalForOutcomes`) + уровень/тир (`XpService.levelForOutcomes`) одним батч-чтением. `ApplicationService.getMyPendingApplications` мёржит в `PeerStatsDto`. |
| `club/ClubRepository.kt` | Если ещё нет — `findIdsByOwnerId(ownerId: UUID): List<UUID>`. |

## Frontend — изменения по файлам

| Файл | Изменение |
|---|---|
| `frontend/src/api/membership.ts` или `api/applications.ts` | Новые функции `getMyPendingApplications()`, `getMyPendingApplicationsCount()`. |
| `frontend/src/types/api.ts` | Типы `PendingApplicationDto`, `PendingApplicationsCountDto`, `ApplicantInfoDto`, `PeerStatsDto`, `ClubBriefDto`. |
| `frontend/src/queries/queryKeys.ts` | `applications.myPending`, `applications.myPendingCount`. |
| `frontend/src/queries/applications.ts` | Хуки `useMyPendingApplicationsQuery`, `useMyPendingApplicationsCountQuery`. Существующий `useRejectApplicationMutation` — типизировать `reason: string` (обязательный, не optional). |
| `frontend/src/components/applications/ApplicationReviewModal.tsx` | **NEW** — модалка. |
| `frontend/src/features/applications-inbox/lib/peer-signal-format.ts` | (или эквивалент) — функция `formatPeerSignal(stats: PeerStatsDto): string` с edge cases. |
| `frontend/src/pages/MyClubsPage.tsx` | Новая секция «Заявки на рассмотрении», логика `focus=inbox` scroll, импорт `useMyPendingApplicationsQuery` + модалки. AppCard (applicant-side) — добавить показ `rejectedReason` под status. |
| `frontend/src/pages/ProfilePage.tsx` | Pending-apps секция — добавить показ `rejectedReason`. Если фильтр исключает rejected — расширить. |
| `frontend/src/pages/OrganizerClubManage.tsx` | Удалить `ApplicationsTab` + связанные импорты, обновить `TABS`, `TabKey`, `LEGACY_TAB_KEYS`, `switch`. |
| `frontend/src/components/BottomTabBar.tsx` | Импорт `useMyPendingApplicationsCountQuery`. Точка на табе `/my-clubs` если count > 0. |
| `frontend/src/components/DeepLinkHandler.tsx` | **Без изменений** (см. «Deep-link из DM»). |
| `frontend/src/styles/brand-theme.css` | (опционально) — стили для `.pending-app-card`, секции, reason-блока в AppCard. CSS правило `.brand-tabbar .tab .tab-dot` уже существует — переиспользовать. |
| `frontend/src/types/api.ts` | Добавить `AwaitingPaymentApplicantDto` (organizer-view rows на `ClubMembersTab`). |
| `frontend/src/api/membership.ts` | `getClubAwaitingPaymentApplicants(clubId)` → GET `/api/clubs/{clubId}/awaiting-payment-applicants`. |
| `frontend/src/queries/queryKeys.ts` | Добавить `clubs.awaitingPaymentApplicants(clubId)`. |
| `frontend/src/queries/members.ts` | Хук `useClubAwaitingPaymentApplicantsQuery(clubId, { enabled })`. `staleTime: 60_000`. |
| `frontend/src/components/club/ClubMembersTab.tsx` | Принимает `isOrganizer?: boolean`. Если `isOrganizer && data.length > 0` — рендерит секцию «Ожидают оплаты · N» ПЕРЕД списком участников. Не-organizer не делает запрос (backend всё равно вернёт 403). |
| `frontend/src/pages/ClubPage.tsx` | Передаёт `isOrganizer` пропом в `<ClubMembersTab>`. |
| `frontend/src/styles/brand-theme.css` | `.mc-app .status.awaiting-payment` расширен селектором `.mc-app-status.awaiting-payment` (standalone chip-вариант для использования вне `.mc-app`-карточки). Добавлены `.cp-member-awaiting-payment` (non-interactive вариант `.cp-member` + `.username` метa). |

## Acceptance Criteria

### AC-1: organizer видит pending-заявки по всем своим клубам
```
GIVEN caller — owner клубов A и B
AND в A 2 pending, в B 3 pending, в C (не его клуб) 1 pending
WHEN GET /api/users/me/applications-pending
THEN 200 OK
AND вернулось 5 заявок (по 2 из A + 3 из B)
AND заявка из C отсутствует
AND для каждой заполнены applicant, peerStats, club, hoursUntilAutoReject
```

### AC-2: organizer без клубов
```
GIVEN caller не owner ни одного клуба
WHEN GET /api/users/me/applications-pending
THEN 200 OK с пустым массивом []
```

### AC-3: organizer с клубами но без pending
```
GIVEN caller owner клуба X, в X нет pending-заявок (только approved/rejected)
WHEN GET /api/users/me/applications-pending
THEN 200 OK с пустым массивом []
```

### AC-4: count endpoint точность
```
GIVEN caller — owner клубов A и B, 5 pending всего (2+3)
AND у caller нет своих approved-but-unpaid заявок
WHEN GET /api/users/me/applications-pending-count
THEN 200 OK { inboxCount: 5, awaitingPaymentCount: 0 }
AND inboxCount совпадает с длиной /applications-pending массива
```

### AC-5: count для не-организатора без awaiting payment
```
GIVEN caller не owner ни одного клуба
AND у caller нет approved-but-unpaid заявок
WHEN GET /api/users/me/applications-pending-count
THEN 200 OK { inboxCount: 0, awaitingPaymentCount: 0 }
```

### AC-5a: count учитывает awaiting payment
```
GIVEN caller — applicant, у него 1 approved-but-unpaid заявка
AND caller — owner клуба X с 2 pending заявками
WHEN GET /api/users/me/applications-pending-count
THEN 200 OK { inboxCount: 2, awaitingPaymentCount: 1 }
```

### AC-6: reject требует reason ≥5 символов
```
GIVEN заявка pending, caller — organizer
WHEN POST /api/applications/{id}/reject { reason: "ok" }  // 2 символа
THEN 400 VALIDATION_ERROR
AND application.status НЕ меняется
AND применима та же ошибка для { reason: "" }, { reason: "    " }, отсутствующего body
```

### AC-7: reject 5 символов проходит + post-trim defense-in-depth
```
WHEN POST /api/applications/{id}/reject { reason: "норм" }   // 4 — fail (Bean Validation @Size(min=5))
THEN 400 VALIDATION_ERROR

WHEN POST /api/applications/{id}/reject { reason: "норма" }  // 5 — pass
THEN 200 OK
AND applications.rejected_reason = "норма"
AND applications.status = 'rejected'

WHEN POST /api/applications/{id}/reject { reason: "  ab " }  // 5 raw chars, 2 после trim
THEN 400 VALIDATION_ERROR "Reason must be at least 5 characters after trim"
   (Bean Validation проходит — sees 5 chars; Service-level guard в
    ApplicationService.rejectApplication ловит post-trim длину <5)
AND applications.status НЕ меняется
```

### AC-8: reject 500-символьный reason
```
WHEN POST /api/applications/{id}/reject { reason: "x" * 500 }
THEN 200 OK

WHEN POST /api/applications/{id}/reject { reason: "x" * 501 }
THEN 400 VALIDATION_ERROR
```

### AC-9: DM при создании заявки
```
GIVEN клуб X (closed, owner = organizer-tg-id)
WHEN POST /api/clubs/X/apply { answerText: "..." } от пользователя U
THEN 201 Created
AND применение завершилось < 200ms (DM async)
AND в течение 5 секунд DM от бота приходит на organizer-tg-id
AND текст DM содержит applicantDisplayName и clubName
AND DM содержит inline web_app button «Открыть заявки» с URL ${webAppBaseUrl}/my-clubs?focus=inbox
```

### AC-10: DM failure не откатывает заявку
```
GIVEN клуб X, owner существует но Telegram API возвращает ошибку (например 403 user blocked bot)
WHEN POST /api/clubs/X/apply { answerText: "..." }
THEN 201 Created
AND заявка в БД создана
AND ERROR-лог в backend о неудачной отправке DM
AND ApplicationService.submitApplication транзакция НЕ откачена
```

### AC-11: deep-link из DM открывает инбокс с автоскроллом
```
GIVEN organizer тапает по кнопке «Открыть заявки» в DM
WHEN Mini App открывается на /my-clubs?focus=inbox
THEN MyClubsPage рендерится
AND после загрузки данных автоматически срабатывает scroll к секции «Заявки на рассмотрении»
AND query параметр focus удаляется из URL (replace)
```

### AC-12: deep-link если нет pending-заявок
```
GIVEN organizer тапает «Открыть заявки», но pending count = 0 (уже разобрал)
WHEN Mini App открывается на /my-clubs?focus=inbox
THEN MyClubsPage рендерится без секции «Заявки на рассмотрении»
AND scroll не происходит (секции нет)
AND query параметр focus удаляется из URL
```

### AC-13: tab-dot появляется и исчезает по count
```
GIVEN BottomTabBar отображается, изначально count = 0
WHEN заявка создана у одного из клубов organizer'а
AND queryClient инвалидировал applications.myPendingCount (после 60s staleTime + refetch / явная инвалидация)
THEN на табе «Мои клубы» появляется точка

GIVEN та же ситуация, organizer одобрил последнюю pending
WHEN ApplicationReviewModal закрылась после approve
THEN applications.myPendingCount инвалидирован и refetch вернул 0
AND точка исчезает
```

### AC-14: rejectedReason виден заявителю на ProfilePage
```
GIVEN U — заявитель, его заявка в клубе X отклонена с reason = "не подходит по интересам"
WHEN U открывает /profile
THEN в секции «Активные заявки» (или эквивалент) карточка X показывает:
  - название клуба
  - status-label «Отклонено»
  - строка с reason «не подходит по интересам»
```

### AC-15: rejectedReason виден заявителю на MyClubsPage
```
GIVEN U — заявитель, его заявка в клубе X отклонена с reason "..."
WHEN U открывает /my-clubs
THEN в секции «Заявки» AppCard клуба X показывает:
  - название
  - дату подачи
  - status-pill «Отклонено»
  - строка с reason под основным контентом (truncate 2 строки)
```

### AC-16: ApplicationsTab полностью удалён из OrganizerClubManage
```
WHEN organizer открывает /clubs/{id}/manage
THEN в ManageTabs только три таба: Участники, Финансы, Настройки
AND deep-link /clubs/{id}/manage?tab=applications редиректит на ?tab=members (или просто на members без query)
AND build / typecheck не имеет dead imports
```

### AC-17: peer-signal — новый пользователь
```
GIVEN заявитель U не имеет ни одной строки в user_club_reputation
WHEN organizer открывает ApplicationReviewModal на заявке U
THEN отображается «Новый пользователь»
AND peerStats в API ответе: { memberClubCount: 0, totalConfirmations: 0, totalAttendances: 0 }
```

### AC-18: peer-signal — есть клубы, нет событий
```
GIVEN U есть запись в user_club_reputation в 2 клубах, total_confirmations = 0 в обеих
WHEN organizer открывает модалку
THEN отображается «В 2 клубах · ещё не было событий»
AND peerStats: { memberClubCount: 2, totalConfirmations: 0, totalAttendances: 0 }
```

### AC-19: peer-signal — нормальный случай (агрегация по клубам)
```
GIVEN U есть строки в user_club_reputation:
  клуб A: total_confirmations=3, total_attendances=2
  клуб B: total_confirmations=1, total_attendances=1
  клуб C: total_confirmations=0, total_attendances=0  (член, но не было событий)
WHEN organizer открывает модалку
THEN отображается «В 3 клубах · посетил 3 из 4 событий»
AND peerStats: { memberClubCount: 3, totalConfirmations: 4, totalAttendances: 3 }
```

### AC-20: организатор-only ownership соблюдается (IDOR-test)
```
GIVEN caller — не owner клуба X, в котором есть pending-заявка
WHEN caller GET /api/users/me/applications-pending
THEN ответ не содержит заявок клуба X
AND попытка POST /api/applications/{id}/approve на ID заявки клуба X
THEN 403 Forbidden (поведение из application.md остаётся)
AND попытка POST /api/applications/{id}/reject { reason: "..." } на ID заявки клуба X
THEN 403 Forbidden
```

### AC-21: hoursUntilAutoReject правильно вычисляется
```
GIVEN заявка создана 2 часа назад
WHEN GET /api/users/me/applications-pending
THEN hoursUntilAutoReject = 46

GIVEN заявка создана 47 часов 50 минут назад
THEN hoursUntilAutoReject = 0  (floor от 10 минут)

GIVEN scheduler ещё не отработал, но прошло 49 часов
THEN hoursUntilAutoReject = 0  (min 0, не отрицательный)
```

### AC-22: красный индикатор при ≤6 часов
```
GIVEN заявка с hoursUntilAutoReject = 6
WHEN organizer видит карточку
THEN индикатор отрисован красным

GIVEN заявка с hoursUntilAutoReject = 7
THEN индикатор серый
```

### AC-23: модалка — disabled-state кнопки reject
```
GIVEN ApplicationReviewModal открыта, organizer тапнул «Отклонить»
WHEN textarea пустой ИЛИ содержит "abcd" (4 chars)
THEN кнопка «Подтвердить отклонение» disabled

WHEN textarea содержит "abcde" (5 chars)
THEN кнопка enabled
AND tap → mutation запускается с reason = "abcde"
```

### AC-24: после approve/reject заявка пропадает из инбокса
```
GIVEN organizer открыл ApplicationReviewModal, заявка осталась pending
WHEN approve успешен
THEN applications.myPending и applications.myPendingCount инвалидированы
AND после refetch заявка пропадает из секции «Заявки на рассмотрении»
AND tab-dot обновляется

То же для reject (с валидным reason).
```

### AC-25a: awaiting-payment список фильтрует корректно
```
GIVEN caller — applicant
AND у caller 3 approved заявки:
  клуб A — есть active membership (оплачено)
  клуб B — есть grace_period membership (оплачено, просрочка)
  клуб C — нет membership записи (инвойс не оплачен)
WHEN GET /api/users/me/applications-awaiting-payment
THEN 200 OK
AND массив содержит только заявку клуба C
AND для неё заполнены applicationId, approvedAt, club, subscriptionPrice
AND сортировка по approvedAt DESC если ≥2 заявки
```

### AC-25b: resend-invoice — успешный сценарий
```
GIVEN заявка approved, у caller нет active membership в клубе
WHEN POST /api/applications/{id}/resend-invoice от applicant
THEN 204 No Content
AND PaymentService.createInvoice вызван 1 раз с (userId, clubId) заявки
AND INFO-лог "Invoice resent: applicationId=... userId=... clubId=..."
```

### AC-25c: resend-invoice — не applicant
```
GIVEN заявка approved принадлежит user U
WHEN POST /api/applications/{id}/resend-invoice от другого user'а V
THEN 403 FORBIDDEN
AND PaymentService.createInvoice НЕ вызван
```

### AC-25d: resend-invoice — уже оплачено
```
GIVEN заявка approved AND у applicant'а уже есть active membership в клубе
WHEN POST /api/applications/{id}/resend-invoice
THEN 400 VALIDATION_ERROR "No payment pending"
AND PaymentService.createInvoice НЕ вызван
```

### AC-25e: resend-invoice — rate limit 1/60s per application
```
GIVEN successful resend для applicationId X в момент T
WHEN второй POST /api/applications/X/resend-invoice в момент T+30s
THEN 429 RATE_LIMIT_EXCEEDED "Please wait before resending the invoice"
AND PaymentService.createInvoice НЕ вызван второй раз

WHEN тот же POST в момент T+61s
THEN 204 No Content
AND PaymentService.createInvoice вызван
```

### AC-25f: resend-invoice — статус не approved
```
GIVEN заявка pending (или rejected / auto_rejected)
WHEN POST /api/applications/{id}/resend-invoice от applicant
THEN 400 VALIDATION_ERROR "No payment pending"
```

### AC-25g: complete-free-membership — успешный сценарий (fresh insert)
```
GIVEN заявка approved в клубе с subscription_price = 0
AND у applicant НЕТ строки в memberships для этого клуба
WHEN POST /api/applications/{id}/complete-free-membership от applicant
THEN 200 OK с MembershipDto (status="active", role="member")
AND membershipRepository.create(userId, clubId) вызван 1 раз
AND отдельного вызова записи счётчика нет (live-счёт растёт на 1 за счёт новой active-membership; колонка дропнута в V33)
AND INFO-лог "Free membership completed for stuck application: applicationId=... userId=... clubId=..."
```

### AC-25g2: complete-free-membership — реактивация cancelled / expired строки (NEW)
```
GIVEN заявка approved в клубе с subscription_price = 0
AND у applicant в memberships есть строка для этого клуба со status ∈ {cancelled, expired}
  (UNIQUE(user_id, club_id) запрещает второй INSERT — без реактивации был бы 500 DuplicateKeyException)
WHEN POST /api/applications/{id}/complete-free-membership от applicant
THEN 200 OK с MembershipDto (status="active", role="member", subscriptionExpiresAt=null)
AND membershipRepository.reactivateFree(membershipId) вызван 1 раз
AND membershipRepository.create НЕ вызван
AND отдельного вызова записи счётчика нет (его не существует — колонка дропнута в V33; реактивированная active-строка снова входит в live-счёт)
AND joined_at в БД обновлён на NOW(), subscription_expires_at = NULL, updated_at = NOW()
AND INFO-лог "Free membership reactivated: id=... previousStatus=cancelled|expired"
```

### AC-25h: complete-free-membership — не applicant
```
GIVEN заявка approved принадлежит user U
WHEN POST /api/applications/{id}/complete-free-membership от другого user'а V
THEN 403 FORBIDDEN
AND membershipRepository.create НЕ вызван
```

### AC-25i: complete-free-membership — клуб платный
```
GIVEN заявка approved в клубе с subscription_price > 0
WHEN POST /api/applications/{id}/complete-free-membership от applicant
THEN 400 VALIDATION_ERROR "Club is not free — pay the invoice instead"
AND membershipRepository.create НЕ вызван
```

### AC-25j: complete-free-membership — уже member
```
GIVEN заявка approved в бесплатном клубе
AND у applicant уже есть active / grace_period membership в клубе
WHEN POST /api/applications/{id}/complete-free-membership
THEN 400 VALIDATION_ERROR "Already a member"
AND membershipRepository.create НЕ вызван
```

### AC-26a: organizer видит awaiting-payment applicants своего клуба
```
GIVEN caller — owner клуба X (subscription_price = 250 stars)
AND в X 2 approved заявки без active/grace membership (инвойсы не оплачены)
AND в X 5 active members и 1 rejected заявка
WHEN GET /api/clubs/X/awaiting-payment-applicants
THEN 200 OK
AND вернулось 2 элемента (только approved-без-membership)
AND каждый элемент содержит applicationId, userId, firstName, lastName, telegramUsername, avatarUrl, approvedAt
AND сортировка по approvedAt DESC
```

### AC-26b: не-organizer получает 403
```
GIVEN caller — НЕ owner клуба X (visitor / member / owner другого клуба)
WHEN GET /api/clubs/X/awaiting-payment-applicants
THEN 403 FORBIDDEN
AND тело ответа НЕ содержит ни одного applicant
```

### AC-26d: organizer видит cross-club awaiting-payment по всем своим клубам
```
GIVEN caller — owner клубов A и B (оба paid, subscription_price > 0)
AND в A 2 approved заявки без active/grace membership
AND в B 1 approved заявка без active/grace membership
AND в C (не его клуб) 1 такая же заявка
WHEN GET /api/users/me/organizer/awaiting-payment-applicants
THEN 200 OK
AND вернулось 3 элемента (2 из A + 1 из B)
AND заявка клуба C отсутствует
AND каждый элемент содержит applicationId, userId, firstName, lastName, telegramUsername, avatarUrl, club{id,name,avatarUrl}, subscriptionPrice
AND сортировка по approvedAt DESC
```

### AC-26e: не-organizer получает пустой массив (без 403)
```
GIVEN caller не owner ни одного клуба
WHEN GET /api/users/me/organizer/awaiting-payment-applicants
THEN 200 OK с пустым массивом []
```

### AC-26f: после оплаты applicant пропадает из organizer cross-club списка
```
GIVEN caller — owner клуба X, в X 1 approved заявка без membership
WHEN applicant оплачивает Stars-инвойс → membership(active) создан webhook'ом
AND organizer обновляет MyClubsPage (или истекает staleTime: 60_000)
WHEN GET /api/users/me/organizer/awaiting-payment-applicants
THEN 200 OK с пустым массивом []
AND organizerAwaitingPaymentCount в /applications-pending-count = 0
```

### AC-26c: бесплатный клуб всегда возвращает пустой массив
```
GIVEN caller — owner клуба X с subscription_price = 0
AND теоретически у X есть approved заявка (membership создаётся синхронно при approve, состояние не должно возникать)
WHEN GET /api/clubs/X/awaiting-payment-applicants
THEN 200 OK с пустым массивом []
   (даже если в данных аномалия — фильтр CLUBS.SUBSCRIPTION_PRICE > 0 не пускает)
```

### AC-25: telegramUsername без @ префикса
```
GIVEN applicant.telegramUsername = "ivan_dev" (без @)
WHEN frontend рендерит карточку
THEN отображается «@ivan_dev»

GIVEN telegramUsername = null
THEN строка username не отображается
```

## Non-functional

### Безопасность

- **Authorization on every endpoint** — JWT обязателен. Оба новых endpoint'а
  (`/me/applications-pending`, `/me/applications-pending-count`) фильтруют
  по `caller.userId` через JOIN на `clubs.owner_id`. Caller никогда не
  получает данные чужих клубов; force-test через IDOR — см. AC-20.
- **Existing reject ownership-check** в `ApplicationService.rejectApplication`
  (`club.ownerId != organizerId → 403`) **сохраняется**. Mandatory reason —
  это валидация входа, не замена ownership-check'у.
- **DM не утечка PII** — текст DM содержит applicantDisplayName и clubName,
  это публичная информация (имя из Telegram, имя клуба). `answerText`,
  `telegramUsername`, `avatarUrl` заявителя в DM **не отправляются** —
  organizer увидит при открытии Mini App после авторизации.
- **Логирование** — `reason` (PII потенциально) **не логируется** на уровне
  INFO (как в текущем коде `application.md` § Non-functional).
- **Reject reason XSS** — рендерится React'ом, HTML-экранирование
  автоматическое. Никакой `dangerouslySetInnerHTML`.
- **Backend rate limiting** — на новых endpoint'ах не критично (только
  чтение, фильтр по owner), но `bucket4j` уже есть. Если организатор
  делает >60 reqs/min — это бот, ответ 429 (общий лимит).

### Производительность

- **N+1 запрещён** в `/me/applications-pending`:
  - 1 запрос: `findIdsByOwnerId(organizerId)` → clubIds
  - 1 запрос: `findPendingByClubIds(clubIds)` → applications
  - 1 запрос: `userRepository.findByIds(applicantUserIds)` → users
  - 1 запрос: `reputationRepository.aggregateByUserIds(applicantUserIds)`
  - 1 запрос: clubs (если ещё не загружены, batch find by IDs)
  - **Итого ≤ 5 запросов** независимо от количества заявок.
- **Count endpoint** — один `COUNT(*)` с JOIN, без N+1, без выборки рядов.
- **Frontend `staleTime: 60_000`** на count — не более 1 запроса в минуту
  при стабильной навигации.
- **DM async** — `submitApplication` отвечает HTTP ≤ 100ms (БД-операции)
  + асинхронный DM. Без блокировки клиента.

### Доступность (availability)

- **Если NotificationService падает** (Telegram API 500, network timeout,
  bot blocked) — заявка всё равно создаётся. ERROR-лог. Organizer узнает
  при следующем открытии Mini App. Acceptable.
- **Если reputationRepository.aggregateByUserIds падает** — endpoint
  возвращает 500, frontend показывает error-state в секции (не блокирует
  остальную страницу).

### Логирование

- INFO в `ApplicationService.submitApplication` — существующий лог +
  «DM dispatched for clubId=... organizerTelegramId=...».
- INFO в `NotificationService.sendApplicationCreatedDM` — успешная
  отправка (как у других sendDm).
- WARN если organizer без `telegram_id` (не должно быть, но защита от NPE).
- ERROR в `NotificationService.sendDm` (catch уже есть в коде, line 114).
- INFO в `ApplicationController` на reject — БЕЗ значения `reason`
  (только факт reject с organizerId).

### UX-метрики (для последующего анализа)

- Drop rate из «новой заявки» в «открыл инбокс» — через analytics-события
  (не в scope этой итерации).
- Доля заявок, отклонённых с reason ≥10 символов vs пограничные ≥5 —
  для понимания качества фидбека.

## Интеграции

| Модуль | Направление | Контракт |
|---|---|---|
| `application` | Этот модуль расширяет `ApplicationService` + `ApplicationController` + `ApplicationRepository` | См. «Бэкенд — изменения по файлам» |
| `bot/NotificationService` | inbox → notifier | Новый метод `sendApplicationCreatedDM(organizerTelegramId, applicantDisplayName, clubName)`. Использует существующий механизм `sendDirectMessageWithDeepLink` с `webAppPath = "/my-clubs?focus=inbox"`. |
| `club/ClubRepository` | inbox → clubs | `findIdsByOwnerId(organizerId)` (если ещё нет — добавить), `findByIds(ids)` для batch club details. |
| `user/UserRepository` | inbox → users | `findByIds(userIds)` для batch applicant info. |
| `reputation/ReputationRepository` | inbox → reputation | Новый `aggregateByUserIds(userIds)` — SUM по `total_confirmations` / `total_attendances` + COUNT clubs per user. Один запрос с `GROUP BY user_id`. |
| `frontend/queries/applications` | UI → API | Новые хуки + типы. |
| `frontend/MyClubsPage` | UI композиция | Новая секция + scroll по `focus=inbox`. |
| `frontend/ProfilePage` | UI композиция | Добавление reason под status в карточках. |
| `frontend/OrganizerClubManage` | удаление | Удаляется tab «Заявки». |
| `frontend/BottomTabBar` | UI композиция | tab-dot по `applications.myPendingCount`. |

## Известные риски и открытые вопросы

1. **Формулировка peer-signal — не финализирована.** Дефолт «В 5 клубах ·
   посетил 3 из 4 событий». QA / пользователь могут попросить поменять на
   «всего посетил» или «Сдержал слово ... раз». Параметр локализации, не
   API-контракт; меняется в `peer-signal-format.ts`.
2. **Reason шаблоны** не входят. Может позже добавиться dropdown с
   быстрыми пресетами («Клуб полный», «Не подходит по интересам», и т.д.),
   но это отдельный backlog.
3. **Resubmit после rejected с причиной.** Пользователь видит причину, но
   submit повторно может (текущий код не запрещает после rejected). Это
   pre-existing поведение из `application.md` § Известные ограничения. Не
   меняем в этой итерации.
4. **Race condition на approve между двумя organizer-сессиями.** Уже есть
   защита `application.status != pending → 400`. Frontend инвалидация
   убирает заявку из обоих UI в течение staleTime.
5. **Organizer без telegram_id в БД** — теоретически невозможно (NOT NULL
   в users), но дефенсивно: если `null` — DM пропускаем, WARN-лог.
6. **focus=inbox + dev-mode HMR** — Vite HMR может несколько раз триггерить
   `useEffect` со scroll'ом. Решение: использовать `useRef(false)` flag
   (как в `DeepLinkHandler`) для idempotent скролла.

## Out of scope (явно НЕ в этой итерации)

См. также `application.md` § «НЕ входит». Дополнительно:
- Архив approved/rejected (UI просмотра истории решений).
- DM-уведомления заявителю об approve/reject (GAP-007).
- Stage 1 «Пойду» агрегат в peer-signal.
- Throttle/batch DM при шквале заявок.
- Reject reason templates / quick presets.
- Resubmit-flow после rejected/auto_rejected.

## Доки, которые ОБЯЗАН обновить post-flight аналитик

Сразу после зелёного Reviewer + Security, перед staging push:

1. **`docs/modules/application.md`**
   - § «POST /api/applications/{id}/reject» — `reason` теперь required,
     `@NotBlank @Size(min=5, max=500)`.
   - § «rejectApplication» бизнес-правила — пункт «reason обязателен ≥5».
   - § «submitApplication» — добавить шаг «8. Async DM organizer'у через
     NotificationService.sendApplicationCreatedDM».
   - § «Интеграции» — добавить telegram-bot строку (сейчас написано «не
     реализованы (GAP-007)»; для organizer-DM на submit GAP закрывается).
   - § «AC-13, AC-14» — пересмотреть (reject без reason и с reason >500
     меняют поведение).
2. **`docs/modules/my-clubs-unified.md`**
   - В § «Структура списка» — добавить новый блок «Заявки на рассмотрении»
     (organizer-side) между «Активные» и «Заявки» (applicant-side).
   - В § «Сортировка» — упомянуть `createdAt ASC` для нового блока.
   - В § «Acceptance Criteria» — добавить пункт о реакции на `focus=inbox`.
3. **`docs/modules/profile.md`**
   - § «Структура страницы» — секция «Активные заявки» теперь показывает
     reason для rejected.
   - § «Acceptance Criteria» — добавить пункт о rejected reason display.
4. **`docs/modules/clubs.md`** (или где документирован `OrganizerClubManage`)
   - Убрать упоминание таба «Заявки».
   - Указать что approve/reject теперь только через `MyClubsPage` inbox.
5. **`docs/modules/telegram-bot.md`**
   - Добавить новый тип DM: «application_created» с текстом, кнопкой,
     deep-link target'ом.
   - В таблице GAP-007 (если есть) — отметить что organizer-DM на submit
     теперь реализован (заявитель-DM на approve/reject — по-прежнему gap).
6. **`docs/modules/reputation.md`**
   - § «Применение метрик» — добавить ссылку на peer-signal в Applications
     Inbox.
7. **`PRD-Clubs.md`** §4.2.2
   - Проверить нет ли явных утверждений «организатор разбирает заявки во
     вкладке Заявки клуба». Если есть — обновить с согласия пользователя
     (Analyst не правит PRD без согласия).

## Перекрёстные ссылки

- `docs/modules/application.md` — backend submit/approve/reject/scheduler ядро.
- `docs/modules/my-clubs-unified.md` — общая структура `MyClubsPage`.
- `docs/modules/profile.md` — общая структура `ProfilePage`.
- `docs/modules/telegram-bot.md` — DM-инфраструктура.
- `docs/modules/reputation.md` — семантика `total_confirmations` /
  `total_attendances`.
- `docs/modules/skladchina.md` — паттерн `useSkladchinaActionRequiredCountQuery`,
  tab-dot, который зеркалируется здесь.
- `PRD-Clubs.md` §4.2.2 — бизнес-контекст «Закрытый клуб».
