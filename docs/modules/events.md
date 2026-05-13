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
  Errors: 403 (not active member of this club; also returned when club doesn't exist — privacy)

GET /api/events/{id}
  Response 200: EventDetailDto
  Notes: includes goingCount, maybeCount, notGoingCount, confirmedCount
```

### Бизнес-правила
- Создавать события может только **организатор клуба** (clubs.owner_id = user.id) → 403
- `eventDatetime` должен быть в будущем → 400
- `participantLimit > 0` → 400
- `votingOpensDaysBefore` 1-14, default = 14 → 400
- При создании: `status = upcoming`, `stage_2_triggered = false`
- `created_by = user.id` из JWT

### Валидация CreateEventRequest
| Поле | Правило |
|------|---------|
| title | 1-255 символов, not blank |
| locationText | 1-500 символов, not blank |
| eventDatetime | ISO datetime, must be > now() |
| participantLimit | > 0 |
| votingOpensDaysBefore | 1-14, default 14 |

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
  "votingOpensDaysBefore": 14,
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
- GET /api/clubs/{id}/events от не-участника клуба → 403 FORBIDDEN "You must be an active member of this club"
- GET /api/clubs/{unknownId}/events → 403 FORBIDDEN (privacy: existence клуба не раскрывается non-member'ам)
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

---

## TASK-015 — Этап 2: автоматическое подтверждение

### Cron-задача (Spring Scheduler)
- Запускается каждые 5 минут: `@Scheduled(fixedDelay = 300_000)`
- Ищет события: `status = upcoming AND stage_2_triggered = false AND event_datetime <= now() + 24h`
- Для каждого такого события: переводит в Stage 2

### Логика перехода в Stage 2
1. Установить `event.status = stage_2`, `event.stage_2_triggered = true`
2. Получить всех going-участников (stage_1_vote = going), отсортированных по stage_1_timestamp ASC
3. Первые N (где N = participant_limit) → они могут подтвердить первыми
4. Если going < participant_limit → добавить maybe-участников в очередь

### Confirm/Decline endpoints
```
POST /api/events/{id}/confirm
  Response 200: ConfirmResponseDto
  Errors: 400 (not stage_2, already confirmed, event full), 403 (не участник)

POST /api/events/{id}/decline
  Response 200: ConfirmResponseDto
```

### ConfirmResponseDto
```json
{
  "eventId": "uuid",
  "status": "confirmed" | "waitlisted" | "declined",
  "confirmedCount": 7,
  "participantLimit": 10
}
```

### Логика confirm
1. Событие должно быть в stage_2 → 400
2. Пользователь должен быть участником клуба → 403
3. У пользователя должен быть going/maybe голос → 400 "You didn't vote going or maybe"
4. Текущий confirmed count < participant_limit → stage_2_vote = confirmed, final_status = confirmed
5. Иначе → stage_2_vote = waitlisted, final_status = waitlisted

### Логика decline
1. Найти response пользователя
2. stage_2_vote = declined, final_status = declined
3. Найти первого waitlisted участника (по stage_1_timestamp) → promote to confirmed

### Corner Cases
| Ситуация | Поведение |
|----------|-----------|
| Событие ещё в upcoming | 400 "Event is not in confirmation stage" |
| Пользователь не голосовал в Stage 1 | 400 "You didn't vote going" |
| Мест нет (confirmedCount >= limit) | Получает waitlisted |
| Decline → нет waitlisted | Просто decline, место "теряется" |

---

## Архитектура

```
EventController ──┬─► EventService ────► EventRepository ──► JooqEventRepository ──► EVENTS
                  │       │                                                        │
                  │       └──► EventMapper (record↔Event, Event→DTO)               │
                  ├─► VoteService ─────► EventResponseRepository ──► JooqEventResponseRepository ──► EVENT_RESPONSES
                  │       │                                                        │
                  │       └──► MembershipRepository.isMember                       │
                  ├─► Stage2Service ───► EventRepository + EventResponseRepository (+ EventResponseMapper)
                  │       │                                                        │
                  │       └──► @Scheduled triggerStage2ForReadyEvents (5 min)      │
                  └─► AttendanceService ─► EventRepository + EventResponseRepository + ClubRepository
                          │                                                        │
                          └──► @Scheduled finalizeAttendance (1 h)                 │
```

### Файлы

| Файл | Роль |
|------|------|
| `Event.kt` | Domain (data class, все поля non-null соответствуют DB NOT NULL) |
| `EventResponse.kt` | Domain |
| `EventMapper.kt` | `@Component`: `toDomain(EventsRecord) → Event`, `toDetailDto(Event, counts)`, `toListItemDto`. Содержит `DEFAULT_VOTING_OPENS_DAYS_BEFORE = 14` |
| `EventResponseMapper.kt` | `@Component`: `toDomain(EventResponsesRecord) → EventResponse` |
| `EventRepository.kt` | Interface |
| `JooqEventRepository.kt` | Реализация. Все DDL/DML через jOOQ. Новые методы: `markAttendanceMarked`, `finalizeAttendanceBefore` |
| `EventResponseRepository.kt` | Interface |
| `JooqEventResponseRepository.kt` | Реализация. Новые методы: `setAttendance`, `disputeAbsentAttendance`, `resolveDisputedAttendance` |
| `EventController.kt` | HTTP. Делегирует в 4 сервиса |
| `EventService.kt` | CRUD событий, без extension `toDetailDto` (вынесено в Mapper) |
| `VoteService.kt` | Stage 1 голосование. Использует `MembershipRepository.isMember` |
| `Stage2Service.kt` | Stage 2 переход + confirm/decline. `@Scheduled` каждые 5 мин |
| `AttendanceService.kt` | mark / dispute / resolve / finalize. `DSLContext` убран — все update через Repository. `@Scheduled` каждый час |
| `EventDto.kt`, `VoteDto.kt`, `Stage2Dto.kt`, `AttendanceDto.kt` | Request/Response DTO |

### Boundary правила
- `EventsRecord` / `EventResponsesRecord` → **только** в `JooqEvent*Repository.kt` и `Event*Mapper.kt`.
- `DSLContext` → **только** в `JooqEvent*Repository.kt`.
- Сервисы получают domain `Event` / `EventResponse` от Repository, передают DTO в Controller.
- `NotificationService.sendEventCreated/sendStage2Started(event: Event)` и `ClubsBot.handleWhoIsGoing` принимают domain, не Record.
