# Module: Membership

## TASK-010 — Вступление в открытый клуб

### Endpoint
```
POST /api/clubs/{id}/join
  Response 201: MembershipDto
  Errors: 400, 409
```

### Бизнес-правила
- Клуб должен существовать → 404
- Клуб должен иметь `access_type = open` → 400 "Club is not open for joining"
- Юзер не должен быть уже участником (active/grace_period) → 409 "Already a member"
- Количество active memberships < `member_limit` → 400 "Club is full"
- При создании: `status = active`, `role = member`
- `joined_at = now()`
- `subscription_expires_at = now() + 30 days`
- После создания membership обновить `clubs.member_count + 1`

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

### Corner Cases
- `POST /api/clubs/{unknownId}/join` → 404 NOT_FOUND
- `POST /api/clubs/{closedClubId}/join` → 400 "Club is not open for joining"
- `POST /api/clubs/{fullClubId}/join` → 400 "Club is full"
- Повторное вступление (уже active) → 409 CONFLICT "Already a member"
- Вступление без токена → 401

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
- Пользователь не должен иметь pending заявку в этот клуб → 409 "Application already exists"
- Rate limit: 5 заявок в день от одного юзера → 429

### Бизнес-правила — approveApplication
- Приложение должно существовать → 404
- Статус должен быть `pending` → 400 "Application is not pending"
- Пользователь должен быть организатором клуба → 403
- Создать membership (как в joinOpenClub): status=active, role=member
- Обновить `clubs.member_count + 1`
- Установить `applications.status = approved`, `resolved_at = now()`
- Проверить лимит участников перед созданием → 400 "Club is full"

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
