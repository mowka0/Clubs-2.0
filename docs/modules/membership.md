# Module: Membership

Связка с `payment`: для платных клубов `MembershipService` и `ApplicationService` **не создают** membership напрямую — они делегируют `PaymentService.createInvoice`. Membership создаётся в `handleSuccessfulPayment` после оплаты Telegram Stars. Для бесплатных клубов — напрямую (старое поведение).

## TASK-010 — Вступление в открытый клуб

### Endpoint
```
POST /api/clubs/{id}/join
  Response 201: MembershipDto              — free club, membership active
  Response 202: PendingPaymentDto          — paid club, invoice sent to user's DM
  Errors: 400, 404, 409
```

### Бизнес-правила
- Клуб должен существовать → 404
- Клуб должен иметь `access_type = open` → 400 "Club is not open for joining"
- Юзер не должен быть уже участником (active/grace_period) → 409 "Already a member"
- Количество active memberships < `member_limit` → 400 "Club is full"
- **Если `club.subscription_price > 0` (платный):**
  - Вызвать `paymentService.createInvoice(userId, clubId)` — шлёт Stars invoice в DM
  - **НЕ создавать** membership — он будет создан в `handleSuccessfulPayment` после оплаты
  - Вернуть `202 Accepted` + `PendingPaymentDto`
- **Если `club.subscription_price == 0 || null` (бесплатный):**
  - Делегировать в `FreeMembershipActivator.activate(userId, clubId)` (см. §
    «Free-club reactivate-or-create» ниже): fresh INSERT для новых
    пользователей либо реактивация cancelled/expired-строки для возвращающихся.
  - Вернуть `201 Created` + `MembershipDto`

### MembershipDto
```json
{
  "id": "uuid",
  "userId": "uuid",
  "clubId": "uuid",
  "status": "active",
  "role": "member",
  "joinedAt": "ISO datetime",
  "subscriptionExpiresAt": "ISO datetime"
}
```

### PendingPaymentDto
```json
{
  "status": "pending_payment",
  "clubId": "uuid",
  "priceStars": 500,
  "message": "Оплатите подписку через бота. Счёт отправлен в Telegram."
}
```

### Corner Cases
- `POST /api/clubs/{unknownId}/join` → 404 NOT_FOUND
- `POST /api/clubs/{closedClubId}/join` → 400 "Club is not open for joining"
- `POST /api/clubs/{fullClubId}/join` → 400 "Club is full"
- Повторное вступление (уже active/grace_period) → 409 CONFLICT "Already a member"
- Повторное нажатие «Вступить» в платный клуб, пока оплата не прошла → идемпотентно: `createInvoice` вызывается снова (Telegram сам управляет дубликатами invoice), ответ 202
- Вступление без токена → 401
- Expired/cancelled membership существует для **бесплатного** клуба → автоматическая реактивация через `FreeMembershipActivator` (status=active, joined_at=now, subscription_expires_at=null, updated_at=now; `member_count` НЕ инкрементится). Ответ 201 с MembershipDto, как для нового вступления. См. § «Free-club reactivate-or-create».
- Expired/cancelled membership существует для **платного** клуба → ветка payment-инвойса; повторное вступление через оплату Stars инициирует новый webhook-cycle. (Поведение для платных клубов в reactivate-flow остаётся как было — см. GAP-7.)

## TASK-010b — Вступление по invite-коду (приватный клуб)

### Endpoint
```
POST /api/invite/{code}/join
  Response 201: MembershipDto              — free club, membership active
  Response 202: PendingPaymentDto          — paid club, invoice sent
  Errors: 400, 404, 409
```

### Бизнес-правила
Те же что у `TASK-010`, за исключением:
- Клуб ищется по `invite_link = code` → 404 "Invite link not found"
- Проверка `access_type` не выполняется (invite-ссылка сама по себе выдаёт право присоединиться).
- Ветвление paid/free — идентично open join.

### Corner Cases
- Неизвестный invite-код → 404 "Invite link not found"
- Остальные — аналогично TASK-010

---

## Заявки в закрытые клубы

См. отдельную спеку: [docs/modules/application.md](application.md)

---

## Free-club reactivate-or-create

`FreeMembershipActivator` (`membership/FreeMembershipActivator.kt`) — Spring
`@Component`, инкапсулирует единый lifecycle для membership в **бесплатных**
клубах. Используется в трёх местах:

1. `MembershipService.joinOpenClub` / `joinByInviteCode` — free-ветка.
2. `ApplicationService.approveApplication` — free-ветка.
3. `ApplicationService.completeFreeMembership` — recovery stuck-заявок.

### Зачем

Таблица `memberships` имеет `UNIQUE (user_id, club_id)`. Если для пары
`(user, club)` уже есть строка со статусом `cancelled` / `expired`, прямой
`INSERT` ловит `DuplicateKeyException` (HTTP 500). До этого все три call-site
использовали паттерн «findActiveByUserAndClub → INSERT», который покрывал
только `active`/`grace_period` строки; мёртвые строки приводили к 500.

### Контракт `activate(userId, clubId)`

| Состояние строки в `memberships` | Действие | `clubs.member_count` |
|---|---|---|
| Нет строки | INSERT (`status=active`, `joined_at=now`, `subscription_expires_at=now+30d`*) | `+= 1` |
| `status ∈ {cancelled, expired}` | UPDATE: `status=active`, `joined_at=now`, `subscription_expires_at=null`, `updated_at=now` | `+= 1` (см. note ниже) |
| `status ∈ {active, grace_period}` | `IllegalStateException` | — |

> **Note (изменено в [club-leave](club-leave.md))**: До PR-1 «Club Leave» reactivate
> НЕ инкрементил `member_count` — счётчик считался учтённым при первичном INSERT.
> С введением `MembershipService.leaveClub` (free leave декрементит счётчик)
> reactivate теперь обязан инкрементить — иначе при leave/rejoin цикле счётчик
> уплывёт в минус. `decrementMemberCountSafely` гарантирует floor=0, поэтому
> legacy `/cancel` → `/join` цикл (cancel НЕ декрементил) безопасен, но
> `member_count` фактически сместится на +1 относительно state до PR. Это
> сознательная цена за корректность нового /leave-цикла.

(*) `subscription_expires_at = now+30d` — текущее поведение `repository.create`,
актуально только для свежего INSERT. Реактивация ставит `null` (бесплатный
клуб не имеет Stars-биллинга).

> **`IllegalStateException` для активного membership** — defensive: caller
> ОБЯЗАН проверить `findActiveByUserAndClub` до вызова и сурфейснуть
> бизнес-ошибку (409 ConflictException в `joinOpenClub`, 400 ValidationException
> «Already a member» в `completeFreeMembership`). Активатор бросает технический
> exception как страховку от пропущенной проверки в новых call-site'ах.

### Транзакционность

Активатор НЕ имеет `@Transactional` — он вызывается из уже-`@Transactional`-методов
Service-слоя. INSERT + `incrementMemberCount` (или UPDATE) выполняются в одной
внешней транзакции; rollback при падении любой операции.

### Логи

- INFO `"Free membership created: userId=... clubId=..."` (fresh insert)
- INFO `"Free membership reactivated: id=... userId=... clubId=... previousStatus=cancelled|expired"`

---

## Отмена подписки

### Цель
Дать участнику возможность остановить дальнейшие списания за подписку. До конца оплаченного периода доступ к Mini App клуба сохраняется (см. PRD-Clubs.md §4.7.3, пункт «Отмена»).

### Endpoint
```
POST /api/clubs/{id}/cancel
  Path: id = clubId (UUID) — клуб, в котором отменяем своё membership
  Response 200: MembershipDto со status=cancelled
  Errors: 400, 404
```

Caller (JWT principal) = владелец membership. Отдельный userId в пути не нужен — нельзя отменить чужую подписку.

### Бизнес-правила
- У caller должен существовать membership в этом клубе со статусом `active` или `grace_period` → иначе 404 "Membership not found"
- Если статус уже `cancelled` → 400 "Membership already cancelled"
- При успехе:
  - `memberships.status = cancelled` (через `MEMBERSHIPS.STATUS`)
  - `subscription_expires_at` **не трогаем** — доступ сохраняется до этой даты
  - `clubs.member_count` **не декрементируем** — пользователь формально остаётся участником до истечения периода
- Операция оборачивается в одну транзакцию: atomic между чтением и update.

### MembershipDto
См. формат в секции TASK-010 выше — `status` будет равен `cancelled`.

### Authorization
- JWT обязателен (Spring Security → AuthenticatedUser principal)
- Owner-check неявный: cancel применяется к membership пары (caller.userId, clubId). Нельзя отменить чужой membership — endpoint просто не имеет параметра userId.

### Acceptance Criteria
**AC-1: успешная отмена active membership**
GIVEN caller — active member клуба X
WHEN POST /api/clubs/X/cancel
THEN 200 OK
AND response.status = "cancelled"
AND в БД memberships.status = cancelled
AND subscription_expires_at не изменился
AND member_count клуба не изменился

**AC-2: отмена в grace_period**
GIVEN caller — member клуба X со status=grace_period
WHEN POST /api/clubs/X/cancel
THEN 200 OK, аналогично AC-1

**AC-3: повторная отмена**
GIVEN caller уже отменил подписку (status=cancelled)
WHEN POST /api/clubs/X/cancel
THEN 404 "Membership not found"
(потому что `findActiveByUserAndClub` ищет только active+grace_period; cancelled не попадает в выборку — это сознательно)

### Corner Cases
| Ситуация | Код | Сообщение |
|----------|-----|-----------|
| Нет membership в этом клубе | 404 | "Membership not found" |
| Membership уже cancelled | 404 | "Membership not found" (см. AC-3 — фактически filter активных) |
| Membership уже expired | 404 | "Membership not found" |
| Клуб не существует (clubId не валидный UUID или нет в БД) | 404 | "Membership not found" (отдельной проверки `findById(club)` нет — отсутствие записи в memberships эквивалентно) |
| Запрос без JWT | 401 | стандартный Spring Security |

---

## Список участников клуба

### Цель
Показать участникам клуба, кто ещё состоит в их сообществе, с метриками репутации. См. PRD-Clubs.md §4.3.3.

### Endpoint
```
GET /api/clubs/{id}/members
  Path: id = clubId (UUID)
  Response 200: MemberListItemDto[]
  Errors: 403
```

### MemberListItemDto
```json
{
  "userId": "uuid",
  "firstName": "string",
  "lastName": "string|null",
  "avatarUrl": "string|null",
  "role": "member|organizer",
  "joinedAt": "ISO datetime|null",
  "reliabilityIndex": 150,
  "promiseFulfillmentPct": 87.5
}
```

### Бизнес-правила
- Возвращаются ТОЛЬКО участники со статусом `active` (grace_period, cancelled, expired — не входят)
- Сортировка: `reliability_index DESC`. При NULL репутации — coalesce до значения 100 (default нового участника), но реальная репутация в БД не создаётся
- `promiseFulfillmentPct` для участников без записи в `user_club_reputation` = 0
- Дефолт role при отсутствии — `member`

### Authorization
- JWT обязателен
- Caller должен быть `active` member клуба (`isMember(callerId, clubId)`) → иначе 403 "Not a member of this club"

### Acceptance Criteria
**AC-1: член клуба видит список**
GIVEN caller — active member клуба X, в клубе 3 активных участника
WHEN GET /api/clubs/X/members
THEN 200 OK
AND response — массив из 3 элементов
AND элементы упорядочены по reliabilityIndex DESC
AND для участника без репутации reliabilityIndex=100, promiseFulfillmentPct=0

**AC-2: не-член клуба получает 403**
GIVEN caller не состоит в клубе X (или status != active)
WHEN GET /api/clubs/X/members
THEN 403 "Not a member of this club"

### Corner Cases
| Ситуация | Код | Сообщение |
|----------|-----|-----------|
| Caller не member | 403 | "Not a member of this club" |
| Caller в grace_period | 403 | "Not a member of this club" (isMember проверяет только active) |
| В клубе нет других участников (только сам caller) | 200 | Массив из одного элемента (сам caller) |
| Клуб не существует | 403 | "Not a member of this club" (privacy — не раскрываем существование клуба) |
| Запрос без JWT | 401 | стандартный Spring Security |

---

## Профиль участника в контексте клуба

### Цель
Показать страницу профиля одного участника так, как она видна другому участнику этого же клуба (имя, аватар, метрики репутации в этом клубе). См. PRD-Clubs.md §4.3.2.

### Endpoint
```
GET /api/clubs/{clubId}/members/{userId}
  Path: clubId — клуб контекста, userId — профиль которого участника смотрим
  Response 200: MemberProfileDto
  Errors: 403, 404
```

### MemberProfileDto
```json
{
  "userId": "uuid",
  "clubId": "uuid",
  "firstName": "string",
  "username": "string|null",
  "avatarUrl": "string|null",
  "reliabilityIndex": 150,
  "promiseFulfillmentPct": 87.5,
  "totalConfirmations": 8,
  "totalAttendances": 7
}
```

`username` — telegram username (без `@`). `lastName` в этом DTO **не возвращается** — только firstName.

### Бизнес-правила
- Caller должен быть `active` member клуба `clubId` → иначе 403
- Пользователь `userId` должен существовать в `users` → иначе 404 "User not found"
- При отсутствии записи в `user_club_reputation` для пары (userId, clubId) — дефолты:
  - `reliabilityIndex = 100`
  - `promiseFulfillmentPct = 0`
  - `totalConfirmations = 0`
  - `totalAttendances = 0`
- Проверка того, что `userId` — реально member клуба `clubId`, **не выполняется** в текущей реализации. Endpoint вернёт профиль любого существующего пользователя при дефолтных метриках репутации, если в этой паре с клубом нет записи. (См. risks ниже.)

### Authorization
- JWT обязателен
- Caller должен быть active member клуба

### Acceptance Criteria
**AC-1: член клуба смотрит профиль другого участника**
GIVEN caller — active member клуба X; userId Y — тоже active member X; у Y есть запись в user_club_reputation
WHEN GET /api/clubs/X/members/Y
THEN 200 OK
AND response.firstName, username, avatarUrl — данные Y
AND метрики совпадают с user_club_reputation для (Y, X)

**AC-2: не-член клуба получает 403**
GIVEN caller не member клуба X
WHEN GET /api/clubs/X/members/Y
THEN 403 "Not a member of this club"

**AC-3: участник без репутации**
GIVEN Y — member клуба X, но в user_club_reputation нет записи
WHEN GET /api/clubs/X/members/Y
THEN 200 OK с reliabilityIndex=100, promiseFulfillmentPct=0, totalConfirmations=0, totalAttendances=0

### Corner Cases
| Ситуация | Код | Сообщение |
|----------|-----|-----------|
| Caller не member клуба | 403 | "Not a member of this club" |
| userId не существует в users | 404 | "User not found" |
| userId существует, но не member клуба X | 200 | возвращает профиль с дефолтной репутацией; см. Risks |
| Запрос без JWT | 401 | стандартный Spring Security |
| clubId не существует | 403 | "Not a member of this club" (privacy) |

### Risks / Open
- **Open question:** endpoint не верифицирует что `userId` — member клуба `clubId`. Caller (active member клуба) может запросить профиль произвольного существующего user'а в контексте этого клуба и получить дефолтную репутацию. Утечка: подтверждение факта существования любого пользователя по UUID. Не считается критической в MVP (UUID-перебор практически нереален), но зафиксировано как потенциальное место для ужесточения. Закладывать в backlog только если станет проблемой.

---

## Свои клубы пользователя

### Цель
Отдать caller'у список клубов, в которых он сейчас активный участник (вкладка «Мои клубы» в Mini App).

### Endpoint
```
GET /api/users/me/clubs
  Response 200: MembershipDto[]
```

### MembershipDto
См. формат в секции TASK-010 выше.

### Бизнес-правила
- Возвращаются только membership'ы со статусом `active` или `grace_period`
- Дополнительный фильтр: связанный `club.is_active = true` (soft-deleted клубы исключаются — см. PRD-Clubs.md §4.5.4)
- Сортировка явно не задаётся — порядок зависит от БД (внутри одной транзакции стабилен)
- Пустой список — валидный ответ (200 + `[]`)

### Authorization
- JWT обязателен
- Caller получает ТОЛЬКО свои membership'ы — `userId` неявно = `principal.userId`. Подмена невозможна.

### Acceptance Criteria
**AC-1: вернуть active+grace_period в active-клубах**
GIVEN caller имеет 3 membership: 1 active в active-клубе, 1 grace_period в active-клубе, 1 cancelled
WHEN GET /api/users/me/clubs
THEN 200 OK
AND response — массив из 2 элементов (active + grace_period; cancelled исключён)

**AC-2: исключить membership'ы в soft-deleted клубах**
GIVEN caller имеет active membership в клубе со `is_active = false`
WHEN GET /api/users/me/clubs
THEN этот membership НЕ возвращается

**AC-3: пользователь без клубов**
GIVEN caller только что зарегистрировался
WHEN GET /api/users/me/clubs
THEN 200 OK с `[]`

### Corner Cases
| Ситуация | Код | Поведение |
|----------|-----|-----------|
| Нет membership'ов | 200 | `[]` |
| Только expired/cancelled | 200 | `[]` (фильтр пропустит) |
| Запрос без JWT | 401 | стандартный Spring Security |
| Membership active, клуб is_active=false (soft-deleted) | 200 | membership не попадает в выдачу |

