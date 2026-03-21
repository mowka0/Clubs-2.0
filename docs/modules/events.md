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
