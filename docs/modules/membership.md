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
  - Создать membership: `status = active`, `role = member`, `joined_at = now()`, `subscription_expires_at = now() + 30d`
  - `clubs.member_count += 1`
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
- Expired/cancelled membership существует → 409 "Already a member" (повторное вступление для expired/cancelled не поддерживается в MVP; см. GAP-7 в backlog для будущего flow)

## TASK-010b — Вступление по invite-коду (приватный клуб)

### Endpoint
```
POST /api/clubs/join/{inviteCode}
  Response 201: MembershipDto              — free club, membership active
  Response 202: PendingPaymentDto          — paid club, invoice sent
  Errors: 400, 404, 409
```

### Бизнес-правила
Те же что у `TASK-010`, за исключением:
- Клуб ищется по `invite_link = inviteCode` → 404 "Invite link not found"
- Проверка `access_type` не выполняется (invite-ссылка сама по себе выдаёт право присоединиться).
- Ветвление paid/free — идентично open join.

### Corner Cases
- Неизвестный invite-код → 404 "Invite link not found"
- Остальные — аналогично TASK-010

---

## TASK-011 — Заявки в закрытый клуб

### Endpoints
```
POST /api/clubs/{id}/apply
  Body: { answerText?: string }
  Response 201: ApplicationDto
  Errors: 400, 404, 409

GET /api/clubs/{id}/applications
  Query: status? (pending|approved|rejected)
  Response 200: ApplicationDto[]
  Access: только организатор клуба → 403 иначе

POST /api/applications/{id}/approve
  Response 200: ApplicationDto
  Access: только организатор → 403

POST /api/applications/{id}/reject
  Body: { reason?: string }
  Response 200: ApplicationDto
  Access: только организатор → 403

GET /api/users/me/applications
  Response 200: ApplicationDto[]
```

### ApplicationDto
```json
{
  "id": "uuid",
  "userId": "uuid",
  "clubId": "uuid",
  "status": "pending",
  "answerText": "string|null",
  "rejectedReason": "string|null",
  "createdAt": "ISO datetime",
  "resolvedAt": "ISO datetime|null"
}
```

### Бизнес-правила — submitApplication
- Клуб должен существовать → 404
- Клуб должен иметь `access_type = closed` → 400 "Club does not accept applications"
- Если `club.application_question != null` → `answerText` обязателен → 400 "Answer is required"
- Пользователь не должен быть уже участником (active) → 409 "Already a member"
- Пользователь не должен иметь **активную** заявку в этот клуб → 409. Активная = `pending` ИЛИ `approved` (до оплаты):
  - `pending` → "Application already exists"
  - `approved` → "Application already approved — waiting for payment"
  Это нужно чтобы юзер не мог переподавать заявку между approve организатора и оплатой (иначе в админке появляются дубли).
- Rate limit: 5 заявок в день от одного юзера → 429

### Бизнес-правила — approveApplication
- Приложение должно существовать → 404
- Статус должен быть `pending` → 400 "Application is not pending"
- Пользователь должен быть организатором клуба → 403
- Проверить лимит участников → 400 "Club is full"
- Установить `applications.status = approved`, `resolved_at = now()`
- **Если `club.subscription_price > 0` (платный):**
  - Вызвать `paymentService.createInvoice(application.userId, club.id)` — invoice уйдёт в DM заявителю
  - **НЕ создавать** membership — он будет создан в `handleSuccessfulPayment` после оплаты
  - `clubs.member_count` **НЕ** инкрементируется — это произойдёт при оплате
- **Если `club.subscription_price == 0 || null` (бесплатный):**
  - Создать membership: `status = active`, `role = member`
  - `clubs.member_count += 1`
- Response — обновлённый `ApplicationDto` (status = approved)

Замечание: новая UX-механика «две кнопки (запрос + вступить)» из GAP-6 **не** реализуется в этой итерации. Текущий approve для платного клуба автоматически запускает invoice, как в устаревшем PRD §4.2.2.6. Миграция на двухкнопочный flow — отдельная фича.

### Бизнес-правила — rejectApplication
- Пользователь должен быть организатором клуба → 403
- Статус должен быть `pending` → 400 "Application is not pending"
- Установить `applications.status = rejected`, `rejected_reason = reason`, `resolved_at = now()`

### ApplicationRepository
```kotlin
fun create(userId: UUID, clubId: UUID, answerText: String?): ApplicationsRecord
fun findById(id: UUID): ApplicationsRecord?
fun findByClubId(clubId: UUID, status: ApplicationStatus?): List<ApplicationsRecord>
fun findByUserAndClub(userId: UUID, clubId: UUID): ApplicationsRecord?
fun findPendingByUser(userId: UUID, clubId: UUID): ApplicationsRecord?
fun countTodayByUser(userId: UUID): Int
fun updateStatus(id: UUID, status: ApplicationStatus, reason: String?): ApplicationsRecord
fun findByUserId(userId: UUID): List<ApplicationsRecord>
```

### Corner Cases
| Ситуация | Код | Сообщение |
|----------|-----|-----------|
| Клуб не найден | 404 | "Club not found" |
| Клуб открытый (access_type=open) | 400 | "Club does not accept applications" |
| Ответ не указан (вопрос обязателен) | 400 | "Answer is required for this club" |
| Уже участник | 409 | "Already a member" |
| Pending заявка уже есть | 409 | "Application already exists" |
| Одобрение не организатором | 403 | "Forbidden" |
| Одобрение уже не-pending заявки | 400 | "Application is not pending" |
| Клуб заполнен при одобрении | 400 | "Club is full" |
| Approve платного клуба: заявитель без валидного telegram_id (не может получить invoice) | 200 | ApplicationDto approved, invoice не отправлен — в лог WARN, организатор получает ответ как обычно. Пользователь не получит invoice — будет эскалировано после первого такого инцидента. |
