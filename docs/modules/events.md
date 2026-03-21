# Module: Events

## TASK-013 — CRUD событий

### Endpoints
```
POST /api/clubs/{id}/events
  Body: CreateEventRequest
  Response 201: EventDto
  Errors: 403 (not organizer), 400 (validation)

GET /api/clubs/{id}/events
  Query: status? (upcoming|completed), page=0, size=20
  Response 200: PageResponse<EventListItemDto>

GET /api/events/{id}
  Response 200: EventDetailDto
  Notes: includes goingCount, maybeCount, notGoingCount, confirmedCount
```

### Бизнес-правила
- Создавать события может только **организатор клуба** (clubs.owner_id = user.id) → 403
- `eventDatetime` должен быть в будущем → 400
- `participantLimit > 0` → 400
- `votingOpensDaysBefore` 1-14, default = 5 → 400
- При создании: `status = upcoming`, `stage_2_triggered = false`
- `created_by = user.id` из JWT

### Валидация CreateEventRequest
| Поле | Правило |
|------|---------|
| title | 1-255 символов, not blank |
| locationText | 1-500 символов, not blank |
| eventDatetime | ISO datetime, must be > now() |
| participantLimit | > 0 |
| votingOpensDaysBefore | 1-14, default 5 |

### EventDetailDto (TASK-013 scope — без vote counts из EventResponses)
```json
{
  "id": "uuid",
  "clubId": "uuid",
  "title": "string",
  "description": "string|null",
  "locationText": "string",
  "eventDatetime": "ISO datetime",
  "participantLimit": 15,
  "votingOpensDaysBefore": 5,
  "status": "upcoming",
  "goingCount": 0,
  "maybeCount": 0,
  "notGoingCount": 0,
  "confirmedCount": 0,
  "attendanceMarked": false,
  "attendanceFinalized": false,
  "createdAt": "ISO datetime"
}
```

### EventListItemDto
```json
{
  "id": "uuid",
  "title": "string",
  "eventDatetime": "ISO datetime",
  "locationText": "string",
  "participantLimit": 15,
  "goingCount": 0,
  "status": "upcoming"
}
```

### Corner Cases
- POST от не-организатора → 403 FORBIDDEN
- POST с датой в прошлом → 400 VALIDATION_ERROR "Event datetime must be in the future"
- GET /api/clubs/{unknownId}/events → 404 NOT_FOUND
- GET /api/events/{unknownId} → 404 NOT_FOUND

---

## TASK-014 — Этап 1 голосования

### Endpoints
```
POST /api/events/{id}/vote
  Body: { vote: "going" | "maybe" | "not_going" }
  Response 200: VoteResponseDto
  Errors: 400, 403, 404

GET /api/events/{id}/my-vote
  Response 200: { vote: "going"|"maybe"|"not_going"|null }
```

### VoteResponseDto
```json
{
  "eventId": "uuid",
  "vote": "going",
  "goingCount": 5,
  "maybeCount": 2,
  "notGoingCount": 1
}
```

### Бизнес-правила — castVote
- Событие должно существовать → 404
- Пользователь должен быть **активным участником клуба** (membership.status = active) → 403 "Not a member of this club"
- `event.status` должен быть `upcoming` (не completed/cancelled) → 400 "Voting is not available for this event"
- Голосование открыто если `event.event_datetime - now() <= event.voting_opens_days_before days` → 400 "Voting has not started yet"
- **Upsert**: если запись уже существует — обновить `stage_1_vote` и `stage_1_timestamp = now()`; если нет — создать
- После голосования вернуть обновлённые counts

### EventResponseRepository
```kotlin
fun upsertStage1Vote(eventId: UUID, userId: UUID, vote: Stage_1Vote): EventResponsesRecord
fun findByEventAndUser(eventId: UUID, userId: UUID): EventResponsesRecord?
fun findByEventId(eventId: UUID): List<EventResponsesRecord>
fun countByVote(eventId: UUID): Map<String, Int>   // going/maybe/not_going
```

### Проверка членства
Перед голосованием:
1. Найти event → получить clubId
2. Проверить membership: `userId + clubId + status = active` → 403 если нет

### Corner Cases
| Ситуация | Код | Сообщение |
|----------|-----|-----------|
| Событие не найдено | 404 | "Event not found" |
| Не участник клуба | 403 | "Not a member of this club" |
| Статус события не upcoming | 400 | "Voting is not available for this event" |
| Голосование ещё не открылось | 400 | "Voting has not started yet" |
| Повторное голосование | 200 | Обновляет голос (upsert) |
| GET /api/events/{id} (без токена) | 401 | "Unauthorized" |

### Изменения в GET /api/events/{id}
После реализации TASK-014 `EventDetailDto` должен включать поле `myVote: String?` (если вызывающий пользователь голосовал). Поле null если не голосовал или не участник.
