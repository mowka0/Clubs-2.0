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
| photoUrl | опционально, max 1024 символа (`@Size(max=1024)`), nullable |

### Photo события (`photo_url`, миграция V15, 2026-05-24)
Событие может иметь обложку — поле `events.photo_url TEXT` (nullable, миграция
`V15__add_event_photo_url.sql`, `ALTER TABLE events ADD COLUMN IF NOT EXISTS
photo_url`). Зеркалит `skladchinas.photo_url`: у существующих событий фото нет
(NULL). Surface'ится в:
- `Event` (domain) — `photoUrl: String?`
- `EventMapper` — read из `EVENTS.PHOTO_URL`, write `request.photoUrl`
- `JooqEventRepository` — `.set(EVENTS.PHOTO_URL, request.photoUrl)` на create, select на read
- `CreateEventRequest.photoUrl` (`@Size(max=1024)`, default `null`)
- `EventDetailDto.photoUrl`, `EventListItemDto.photoUrl`
- Unified-feed `ActivityItemDto.EventActivity.photoUrl` (см.
  [`unified-activity-creation.md`](./unified-activity-creation.md) § ActivityThumb).
  Складчина переиспользует уже существовавшее `skladchinas.photo_url`.

Фронт: `CreateEventPage` получил поле загрузки фото через компонент
`AvatarUpload` → `CreateEventBody.photoUrl`. В карточке активности фото —
левый thumbnail (`ActivityThumb`), placeholder при отсутствии.

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
  "createdAt": "ISO datetime",
  "photoUrl": "string|null"
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
  "status": "upcoming",
  "photoUrl": "string|null"
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

## Автозавершение прошедших событий (`upcoming/stage_1/stage_2 → completed`)

> **Реализовано** в `feature/unified-activity-creation` (2026-05-24). До этого статус
> `completed` (из enum `event_status`, см. PRD §5.1) **никогда не выставлялся** — Stage2Service
> двигал только `upcoming → stage_2`, и прошедшие события навсегда оставались
> `upcoming`/`stage_2`. Складчины уже автозакрывались через `SkladchinaScheduler`; у событий
> аналога не было. Из-за этого в унифицированной ленте активностей прошедшие события не
> приглушались (dimming = `isCompleted = status IN (completed, cancelled)`).

### Cron-задача (Spring Scheduler)
- `EventCompletionService` (`backend/src/main/kotlin/com/clubs/event/EventCompletionService.kt`)
- `@Scheduled(fixedDelay = 3_600_000)` — раз в час (тот же период что у `AttendanceService`)
- `@Transactional`
- `cutoff = now() − COMPLETION_GRACE_HOURS` (6 часов)
- Вызывает `eventRepository.markPastEventsCompleted(cutoff)`; логирует INFO число обновлённых строк (если > 0)

### Логика перехода
- `UPDATE events SET status = completed WHERE event_datetime < cutoff AND status IN (upcoming, stage_1, stage_2)`
- **НЕ трогает** события со статусом `completed` или `cancelled` (идемпотентно, повторный прогон ничего не меняет)
- Grace-период 6ч защищает идущее событие от приглушения в середине (буфер на timezone / длительность встречи)

### Независимость от attendance flow
Перевод статуса в `completed` **не влияет** на flow отметки присутствия. `AttendanceService`
(mark / dispute / resolve / finalize) гейтит **только** на булевых флагах `attendance_marked` /
`attendance_finalized` и на `event_datetime` — **никогда на `status`** (подтверждено code review,
Case A). 6-часовой grace ≪ 48-часового окна спора (PRD §4.4.3), поэтому автозавершение не пересекается
с финализацией репутации.

### Frontend — отметка посещаемости (`EventPage`)
Секция «Отметить посещаемость» на `EventPage` (между «Кто идёт» и Stage 2). Видна организатору
после события: `isOrganizer && event_datetime <= now && !attendanceMarked && !attendanceFinalized`
(зеркалит серверный гейт `AttendanceService` — owner-only + время, **не статус**; `!attendanceFinalized`
добавлен в Блоке 1 для EXP-2 — после нейтрального авто-закрытия отметка уже невозможна). Чеклист
участников со статусом `confirmed` (toggle-кнопки, по умолчанию «пришёл»), submit →
`POST /api/events/{id}/attendance` (`useMarkAttendanceMutation`). После отметки — read-only
«✓ Посещаемость отмечена» + (пока окно спора открыто) список оспоренных отметок с резолвом.
UI спора/резолва добавлен в Блоке 1 (см. ниже § «Репутация — Блок 1»). Восстановлено в
`bugfix/attendance-marking-ui` (2026-06-06) после потери при унификации создания активностей; см.
`docs/backlog/attendance-marking-ui-missing.md`.

### Corner Cases
| Ситуация | Поведение |
|----------|-----------|
| Событие уже `completed`/`cancelled` | Не трогается (`status IN` фильтр) |
| Событие в пределах grace-периода (`event_datetime` в прошлом < 6ч назад) | Остаётся в текущем статусе до следующего прогона |
| Событие `cancelled` с прошедшей датой | Остаётся `cancelled` (организаторская отмена не перезатирается) |
| attendance ещё не финализирован | Статус → `completed` независимо; attendance-scheduler работает по своим флагам |

---

## Закрытие окна подтверждения + авто-истечение брони (Bug B + Feature A)

> **Реализовано** в `feature/two-stage-confirmation-gaps` (2026-06-07). Закрывает две дыры,
> описанные в `docs/backlog/two-stage-confirmation-gaps.md`.

### Цель
1. **Bug B:** подтверждение/отказ на этапе 2 должны закрываться **на старте события**
   (`event_datetime`), а не висеть до часового авто-завершения. Иначе есть окно
   `[event_start, EventCompletionService poll]`, где можно подтвердить/отказаться от уже
   **прошедшего** события, и фронт продолжает показывать кнопки.
2. **Feature A (PRD §4.4.2 / §623 «авто-отклонение»):** голос `going`/`maybe`, который
   не нажал «Подтвердить» к старту события, должен получить **явный терминальный статус**,
   а не оставаться `stage_2_vote = NULL`/`final_status = NULL` навсегда. Ростер становится
   честным; реализуется упомянутый в PRD §623 scheduler авто-отклонения.

### Новый статус `expired_no_confirm` (миграция V19)
Добавлен в enum-типы `stage_2_vote` и `final_status` (`V19__add_expired_no_confirm_status.sql`,
`ALTER TYPE ... ADD VALUE IF NOT EXISTS`). Семантика: **«бронь сгорела»** — проголосовал
`going`/`maybe`, но не подтвердил к дедлайну. Отличается от `declined` («отказался» —
явное действие), хотя оба дают **0 репутации**.

- **Репутация = 0, без новой строки в §4.4.4.** `JooqReputationRepository.findConfirmedResponses`
  фильтрует `final_status = 'confirmed'`, поэтому `expired_no_confirm`-строки игнорируются —
  ровно как раньше игнорировались `NULL`-дыры (поведение «Передумавший»/«Вечный
  сомневающийся» = 0). Прецеденты именования: `application_status.auto_rejected`,
  `skladchina_participant_status.expired_no_response`.

### Bug B — гард на старте события
`Stage2Service.confirmParticipation` и `declineParticipation` дополнительно отклоняют запрос,
если `event_datetime <= now`:
```
400 VALIDATION_ERROR "Confirmation window has closed"
```
Окно подтверждения = весь период до начала события. Гонка «scheduler истёк ↔ запрос в
последнюю секунду» доброкачественна (оба сравнивают `now` с `event_datetime`; любой исход
безопасен для репутации), поэтому отдельная блокировка `expired_no_confirm`-строки не нужна.

Фронт (`EventPage`): `showStage2 = status === 'stage_2' && !eventHappened` — кнопки
подтверждения исчезают на старте события, не дожидаясь смены статуса.

### Feature A — авто-истечение (scheduler)
- `Stage2Service.expireUnconfirmedParticipants` — `@Scheduled(fixedDelayString = "${events.stage2-expire-poll-ms:300000}")`, `@Transactional`.
- Делегирует в `EventResponseRepository.expireUnconfirmedForStartedEvents(now)` — **один
  bulk-UPDATE**, без per-event цикла:
  ```
  UPDATE event_responses
  SET stage_2_vote = 'expired_no_confirm', final_status = 'expired_no_confirm', ...
  WHERE stage_2_vote IS NULL
    AND stage_1_vote IN ('going','maybe')
    AND event_id IN (SELECT id FROM events
                     WHERE stage_2_triggered = true AND event_datetime <= now
                       AND status <> 'cancelled')
  ```
- **Идемпотентность** бесплатна из предиката `stage_2_vote IS NULL` — повторный прогон
  обновляет 0 строк.
- **Status-независимость** (не гейтит на `status = stage_2`): `EventCompletionService` флипает
  `stage_2 → completed` через 6ч после события, поэтому гейт на `stage_2` пропустил бы
  не подтвердивших на событиях старше 6ч (при простое бэкенда). Исключаются только
  `cancelled`-события (нет ростера для финализации).
- **Не трогает** `confirmed`/`waitlisted`/`declined` (предикат `stage_2_vote IS NULL`),
  поэтому промоут из листа ожидания и подсчёт `confirmedCount` не затронуты.

### Конфигурация таймингов событий (для тестирования на staging)
Захардкоженные константы вынесены в `application.yml` блок `events:` (env-indirection),
чтобы на staging можно было ускорить e2e-тест репутации без правки кода:

| Property | Env var | Default | Назначение |
|---|---|---|---|
| `events.dispute-window-minutes` | `ATTENDANCE_DISPUTE_WINDOW_MINUTES` | `2880` (48ч) | Окно оспаривания явки до финализации репутации |
| `events.finalize-poll-ms` | `ATTENDANCE_FINALIZE_POLL_MS` | `3600000` (1ч) | Период `AttendanceService.finalizeAttendance` |
| `events.stage2-expire-poll-ms` | `STAGE2_EXPIRE_POLL_MS` | `300000` (5мин) | Период авто-истечения брони |
| `events.reminder-poll-ms` | `EVENT_REMINDER_POLL_MS` | `300000` (5мин) | Период `EventReminderScheduler` |
| `events.confirm-reminder-minutes-before` | `CONFIRM_REMINDER_MINUTES_BEFORE` | `120` (2ч) | За сколько **минут** до события слать «подтверди участие» |
| `events.attendance-reminder-minutes-after` | `ATTENDANCE_REMINDER_MINUTES_AFTER` | `1440` (24ч) | Через сколько **минут** после события напомнить оргу отметить явку |

`@Scheduled(fixedDelay)` требует compile-time константу — поэтому период задаётся через
`fixedDelayString = "${...}"`. Окно оспаривания инжектится `@Value` в поле `AttendanceService`
(единица — **минуты**, чтобы выразить тестовые 5 минут; `minusMinutes`).

> **Тест на staging:** в Coolify-приложении staging выставить `ATTENDANCE_DISPUTE_WINDOW_MINUTES=5`,
> `ATTENDANCE_FINALIZE_POLL_MS=60000` (1 мин), при желании `STAGE2_EXPIRE_POLL_MS=60000`.
> Тот же образ, только env. После теста — удалить переменные (prod-дефолты не меняются).
> NB: окно оспаривания отсчитывается от `event_datetime` (нет колонки `attendance_marked_at`),
> а не от момента отметки — pre-existing, см. `reputation-v2.md`.

### Напоминания событий (`EventReminderScheduler`, bot-модуль)

> **Реализовано** в `feature/two-stage-confirmation-gaps` (2026-06-07). Два poll-уведомления
> (`backend/.../bot/EventReminderScheduler.kt`, период `events.reminder-poll-ms`). Дедуп — флаги
> `events.confirm_reminder_sent` / `events.attendance_reminder_sent` (миграция V21): флаг ставится
> ДО `@Async` DM, поэтому повторный poll не дублирует рассылку. Циклы **без** `@Transactional`
> (каждый `mark*ReminderSent` — самостоятельный auto-commit UPDATE), чтобы одна ошибка не валила
> весь батч (урок EXP-3).

- **A — «подтверди участие» (за `confirm-reminder-minutes-before` = 120мин/2ч до события):**
  `remindUnconfirmedVoters` → события `stage_2`, `event_datetime ∈ (now, now+2ч]`,
  `confirm_reminder_sent=false` → DM голосовавшим `going/maybe` с `stage_2_vote IS NULL`
  (`findUnconfirmedVoterTelegramIds`) с deep-link на событие.
- **B — «отметь явку» (через `attendance-reminder-minutes-after` = 1440мин/24ч после события):**
  `remindOrganizersToMarkAttendance` → события `event_datetime <= now−24ч`, `attendance_marked=false`,
  `attendance_reminder_sent=false`, `status ≠ cancelled` → DM организатору (`findOrganizerTelegramId`).

> **Отклонение от PRD (узаконено продуктом 2026-06-07):** PRD §4.4.2 предполагает уведомление о
> подтверждении **при старте Этапа 2 (за 24ч)**, а §4.4.3 — напоминание оргу **через 12ч**. Принято:
> подтверждение-напоминание — **за 2ч** (один nudge близко к событию), орг-напоминание — **через 24ч**.
> PRD-уведомление при старте Этапа 2 (`sendStage2Started`, S2T-2) остаётся **не подключённым** —
> см. `docs/backlog/two-stage-reputation-bug-register.md`.
>
> **Отложено (C):** штраф организатору, если явка не отмечена через 48ч — реализуется вместе с
> «репутацией организатора» (бэклог).

### Acceptance Criteria
- **AC-B1:** GIVEN событие в `stage_2`, `event_datetime <= now` WHEN POST `/confirm` или
  `/decline` THEN `400 "Confirmation window has closed"`, статус не меняется.
- **AC-B2:** GIVEN `event_datetime > now` WHEN POST `/confirm` THEN работает как раньше.
- **AC-B3 (фронт):** GIVEN событие прошло WHEN открыт `EventPage` THEN секция «Подтверждение
  участия» с кнопками **не отображается**.
- **AC-A1:** GIVEN `stage_2_triggered=true`, `event_datetime <= now`, ответ `going`/`maybe`
  c `stage_2_vote = NULL` WHEN отработал scheduler THEN `stage_2_vote = final_status =
  'expired_no_confirm'`.
- **AC-A2 (идемпотентность):** повторный прогон scheduler обновляет 0 строк.
- **AC-A3 (изоляция):** `confirmed`/`waitlisted`/`declined` ответы scheduler **не трогает**;
  `confirmedCount` не меняется.
- **AC-A4 (репутация):** `expired_no_confirm` строка **не создаёт** ledger-записи (репутация 0).
- **AC-A5 (cancelled):** ответы на `cancelled`-событии не истекают.
- **AC-R1 (confirm reminder):** GIVEN событие `stage_2`, старт через 1ч, есть going/maybe c
  `stage_2_vote=NULL`, `confirm_reminder_sent=false` WHEN отработал scheduler THEN им уходит DM,
  `confirm_reminder_sent=true`; повторный прогон DM не дублирует; уже подтверждённым DM не идёт.
- **AC-R2 (attendance reminder):** GIVEN событие прошло >24ч назад, `attendance_marked=false`,
  `attendance_reminder_sent=false`, не `cancelled` WHEN scheduler THEN оргу уходит DM,
  `attendance_reminder_sent=true`, повтор не дублирует. Помеченные/cancelled — пропускаются.
- **AC-A6 (фронт):** responder со статусом `expired_no_confirm` **исключён** из кандидатов
  отметки явки (роутер = только `confirmed`). NB (обновлено фазовым показом, см. ниже):
  на `EventPage` список «Кто идёт» с Этапа 2 показывает **только подтверждённый состав**,
  поэтому `expired_no_confirm`/`declined` в нём не отображаются вовсе (а не «с серой точкой»).
  `EventCard.pickBadge` отдаёт `expired_no_confirm` приоритет над устаревшим stage-1 голосом
  (не вернёт «Иду»/«Возможно» для не подтвердившего). NB: карточка в ленте рендерит только
  `accent`-бейджи (action-required), поэтому терминальные статусы
  (`confirmed`/`declined`/`expired_no_confirm`) бейдж не показывают — видимый эффект для
  `expired_no_confirm` это отсутствие бейджа.

---

## Репутация — Блок 1 (живой путь): EXP-2 + ATT-2 + ATT-3 + UI оспаривания

> **Реализовано** в `feature/two-stage-confirmation-gaps` (2026-06-07). Закрывает три бага «живого пути»
> репутации (подтверждение → явка → финализация → начисление) одним сквозным e2e. Реестр багов:
> `docs/backlog/two-stage-reputation-bug-register.md`. Контракт начислений: `docs/modules/reputation-v2.md`.

### EXP-2 — нейтральная авто-финализация непомеченных событий

**Проблема:** финализация (а с ней — начисление репутации) гейтит на `attendance_marked = true`. Если
организатор не открыл приложение и не отметил явку, событие **никогда** не финализировалось → надёжный
участник, который пришёл, получал **0** вместо +100. Главная причина «5 событий подтвердил — всё ещё Новичок».

**Решение (продуктовое, зафиксировано 2026-06-07):** на дедлайне непомеченное прошедшее событие
**авто-финализируется нейтрально** — событие просто не засчитывается: ни награды, ни штрафа никому. Никого не
наказываем и не награждаем за бездействие организатора.

**Реализация (без миграции, без новой колонки):**
- `EventRepository.neutrallyFinalizeUnmarkedBefore(cutoff)` — `UPDATE events SET attendance_finalized = true
  WHERE attendance_marked = false AND attendance_finalized = false AND event_datetime <= cutoff AND status <> cancelled
  RETURNING id`. Ставит `finalized = true`, но **оставляет `marked = false`**.
- `AttendanceService.neutrallyFinalizeUnmarkedEvents` — `@Scheduled(fixedDelayString = "${events.finalize-poll-ms}")`,
  `@Transactional`. Дедлайн = `now − events.auto-finalize-unmarked-minutes` (дефолт **2880 = 48ч**:
  24ч на напоминание оргу + 24ч на реакцию). **Публикует `AttendanceFinalizedEvent` — НЕ публикует.**
- **Почему «нейтрально» = нет строк в ledger, бесплатно:** пайплайн репутации клеймит события только
  с `attendance_marked = true` (`JooqReputationRepository.claimEvent` / `findPendingFinalizedEventIds`).
  Немаркированное событие невидимо и для poll'а, и для прямого клейма → ledger-строк ноль, счётчики не
  растут, `promise_fulfillment_pct` не портится. Отдельный флаг не нужен — состояние `finalized && !marked`
  само по себе однозначно означает «закрыто нейтрально».
- **Гонка с поздней отметкой** доброкачественна: если орг отмечает в последнюю секунду перед дедлайном,
  `markAttendance` либо проходит (его отметки засчитываются — это и есть корректный исход), либо
  отклоняется гардом `attendanceFinalized` (нейтральный финалайзер успел первым). Оба исхода безопасны.
- **Фронт (`EventPage`):** гейт отметки = `isOrganizer && eventHappened && !attendanceMarked && !attendanceFinalized`;
  при `finalized && !marked` показывается «Окно отметки явки истекло».

### ATT-2 — нерешённый спор больше не обнуляет штраф за прогул

**Проблема:** `(going, confirmed, absent)` = «Пустозвон» (штраф). Участник жал «Оспорить» → `attendance = disputed`.
Если организатор не реагировал, на финализации оставался `disputed` → `attendanceKind = confirmed_unresolved`
= **0**. Любой ненадёжный участник обнулял штраф одним тапом + бездействием организатора.

**Решение (PRD-aligned):** на финализации остаточные `disputed` конвертируются обратно в `absent` (окно
оспаривания истекло без коррекции организатора → действует исходная отметка). Тогда `(going, absent) = no_show
(−200)`, `(maybe, absent) = spectator (−200)`. Текущие значения штрафов — `reputation-v2.md` (решения
2026-06-11: очки только от этапа-2 × явки; прогул = −200). Чтобы игнор спора был осознанным, а не
случайным, организатору при споре уходит DM (см. ATT-3).

**Реализация:**
- `EventResponseRepository.resolveExpiredDisputesToAbsent(eventIds)` — `UPDATE event_responses SET attendance = absent
  WHERE attendance = disputed AND event_id IN (:eventIds)`.
- Вызывается в `AttendanceService.finalizeAttendance` **в той же транзакции** сразу после
  `finalizeAttendanceBefore`, до публикации `AttendanceFinalizedEvent`. Реп-листенер читает ростер AFTER_COMMIT —
  то есть уже после конвертации. `disputed`-строка может существовать только на помеченном событии, поэтому
  нейтрально закрытых (немаркированных) событий это не касается.
- `ReputationPolicy.attendanceKind` оставлен без изменений: ветка `disputed → confirmed_unresolved` —
  defensive-страховка (после конвертации `disputed` до пайплайна не доходит; `null` всё ещё может — это
  существующее поведение confirmed-но-не-отмеченных).

### ATT-3 — спор стал достижим (DM + UI)

**Проблема:** `NotificationService.sendAttendanceMarked` (DM «вас отметили отсутствующим, оспорьте») был
**мёртвым кодом** (ноль вызовов), а на фронте **не было UI оспаривания вообще**. Absent-участник не знал, что
надо спорить, и не мог.

**Решение — DM (бэкенд):**
- `AttendanceService.markAttendance` после `markAttendanceMarked` публикует `AttendanceMarkedEvent(eventId)`.
- `bot/AttendanceMarkedListener` (`@TransactionalEventListener(AFTER_COMMIT)`) зовёт
  `notificationService.sendAttendanceMarked(eventId)`. **Почему AFTER_COMMIT:** `sendAttendanceMarked` —
  `@Async`, читает только что записанные `absent`-строки на отдельном соединении, которое видит их лишь после
  коммита транзакции `markAttendance`. Тот же паттерн, что у `AttendanceFinalizedListener`.
- **DM оргу при споре (2026-06-11):** `disputeAttendance` публикует `AttendanceDisputedEvent(eventId, userId)`
  → `bot/AttendanceDisputedListener` (AFTER_COMMIT) → `sendAttendanceDisputed` — DM организатору
  «N оспорил отметку, разберите до закрытия окна» с deep-link. Без этого орг, не открывающий событие,
  пропускал спор, и ATT-2 наказывал участника без чьего-либо решения.

**Дефолт отметки — «пришёл» (2026-06-11, рев. 2):** в форме отметки все confirmed по умолчанию
отмечены пришедшими (подсказка: «снимите галочку с тех, кто не пришёл») — отсутствующие в офлайне
меньшинство, и организатор снимает отметки точечно. UI отправляет **явное** значение для каждого
видимого участника: снятая галочка = `absent` → штраф −200 + DM «оспорьте». Confirmed-участник,
не попавший в payload (гонка «подтвердился после загрузки формы»), остаётся `attendance = null` →
`confirmed_unresolved` (0) на финализации — defensive-ветка, как и раньше. EXP-2 (форма вообще
не сохранялась) нейтрален.

**Решение — UI оспаривания (фронт, полный):**
- В ростер добавлено поле `attendance` (`attended | absent | disputed | null`): `EventResponderInfo`,
  `findRespondersWithUsers` (select `EVENT_RESPONSES.ATTENDANCE`), `EventResponderDto`, фронт-тип `EventResponderDto`.
- **Участник** (`EventPage`, блок «Ваша явка»): если его строка `attendance = absent` и окно открыто
  (`attendanceMarked && !attendanceFinalized`) — кнопка «Оспорить» → `POST /api/events/{id}/dispute`
  (`useDisputeAttendanceMutation`). После спора (`disputed`) — текст «ждёт решения организатора».
- **Организатор** (`EventPage`, блок «Оспоренные отметки» внутри «Посещаемость отмечена», пока окно открыто):
  список confirmed-участников с `attendance = disputed`, по каждому — иконки-кнопки ✓ (зелёная, «Пришёл») /
  ✗ (красная, «Не пришёл»; `aria-label` сохраняет эти имена) → `POST /api/events/{id}/attendance/{userId}/resolve`
  (`useResolveDisputeMutation`).

### Конфигурация (новая env var)

| Property | Env var | Default | Назначение |
|---|---|---|---|
| `events.auto-finalize-unmarked-minutes` | `ATTENDANCE_AUTO_FINALIZE_UNMARKED_MINUTES` | `2880` (48ч) | EXP-2: дедлайн нейтрального авто-закрытия немаркированных событий |

> **Тест на staging:** чтобы проверить EXP-2, в Coolify-приложении staging выставить
> `ATTENDANCE_AUTO_FINALIZE_UNMARKED_MINUTES=5` (и НЕ отмечать явку) — событие закроется нейтрально через
> ~5 мин + 1 цикл финализатора. После теста переменную удалить.

### Acceptance Criteria

- **AC-EXP2-1:** GIVEN прошедшее событие, `attendance_marked=false`, `event_datetime <= now − дедлайн`, не
  cancelled WHEN отработал `neutrallyFinalizeUnmarkedEvents` THEN `attendance_finalized=true`, `attendance_marked`
  остаётся `false`; `AttendanceFinalizedEvent` НЕ опубликован.
- **AC-EXP2-2 (нет репутации):** confirmed-участник нейтрально закрытого события **не** получает ledger-строки;
  `findPendingFinalizedEventIds` его не возвращает; `claimEvent` = false.
- **AC-EXP2-3 (изоляция):** помеченные (`marked=true`), cancelled, будущие и уже финализированные события
  нейтральный финалайзер не трогает; повторный прогон — 0 строк.
- **AC-EXP2-4 (фронт):** при `finalized && !marked` организатор видит «Окно отметки явки истекло», блока
  «Отметить посещаемость» нет.
- **AC-ATT2-1:** GIVEN confirmed+going участник с `attendance=disputed` на финализируемом событии, орг не
  резолвил WHEN `finalizeAttendance` THEN `disputed → absent` (в одной транзакции до публикации) → начисление
  `no_show (−200)`. Спор на другом, не финализируемом, событии не затронут.
- **AC-ATT3-1 (DM):** GIVEN организатор отметил явку, есть absent-участники WHEN транзакция закоммичена THEN
  им уходит DM «оспорьте» (`AttendanceMarkedListener` AFTER_COMMIT).
- **AC-ATT3-2 (участник):** GIVEN участник `attendance=absent`, окно открыто WHEN открыт `EventPage` THEN
  виден блок «Ваша явка» с «Оспорить»; клик → `disputed`.
- **AC-ATT3-3 (организатор):** GIVEN confirmed-участник `attendance=disputed`, окно открыто WHEN организатор
  открыл `EventPage` THEN виден блок «Оспоренные отметки» с «Пришёл»/«Не пришёл»; клик резолвит.

### Доработки по результатам staging-теста (2026-06-08)

**Фазовый показ состава (`EventPage`).** Раньше «Набор», донат и «Кто идёт» считались по голосам
Этапа 1 (`stage_1_vote`), поэтому участник, проголосовавший «Пойду» и затем отказавшийся на Этапе 2,
всё равно числился идущим. Теперь показ зависит от фазы события (продуктовое решение «фазовый», 2026-06-08):
- **`upcoming` (Этап 1)** — сбор интереса: «Набор · {going}/{limit}», донат going/maybe/notgoing,
  «Кто идёт» = все ответившие (как раньше).
- **`stage_2` / `completed` (Этап 2+)** — финальный состав: заголовок «Состав · {confirmed}/{limit}»,
  донат = confirmed/лимит, read-only панель «Подтвердили / Ждут подтверждения / Лист ожидания»,
  «Кто идёт» = **только `confirmed`**. Отказавшиеся/истёкшие выпадают; подтверждённые и ещё-не-ответившие
  (pending) не смешиваются в одном числе. Дублирующий блок «Участники → Подтверждено» удалён.
- **AC-PH1:** GIVEN `stage_2`, участник голосовал going и `final_status=declined` WHEN открыт `EventPage`
  THEN он не входит в «Состав · {confirmed}» и не показан в «Кто идёт».

**Текст confirm-reminder DM (ATT/Feature A).** `NotificationService.sendConfirmReminder` —
«Подтвердите участие, иначе место займут другие:» (было «…иначе место освободится:» — слабее побуждало).

**Спонтанность видна в UI.** `spontaneityCount` (счётчик «Возможно → Подтвердил → Пришёл») считался и
хранился, но не отдавался на фронт. Проброшен в `MemberProfileDto` и `UserClubReputationDto` (+проекции,
мапперы) и отображается: «Спонтанные визиты» в `MemberProfileModal`; «· N спонт.» в карточке клуба
«Моя репутация» на `ProfilePage` (только при N>0). Подавляется порогом «Новичок», как остальные метрики.

#### Раунд 2 staging-теста (2026-06-08)

**`confirmedCount` больше не захардкожен 0.** `EventService.getEvent` возвращал `confirmedCount = 0`
константой — поэтому донат/«Состав»/«Подтвердили» всегда показывали 0, хотя в ростере подтверждённые
были (баг проявился после фазового показа). Теперь `EventRepository.getVoteCounts` дополнительно
считает `confirmed` (`stage_2_vote = confirmed`, как `fetchConfirmedCounts`), и `getEvent` его
прокидывает. Инвалидация detail-запроса на confirm/decline теперь обновляет донат (раньше всегда 0).

**Deep-link в DM оспаривания.** `NotificationService.sendAttendanceMarked` шлёт DM с inline-кнопкой
«Оспорить явку» и `webAppPath = /events/{id}` (раньше — дефолтная кнопка без перехода на событие).

**Заметка к оспариванию (`dispute_note`, миграция V22).** Участник при оспаривании может оставить
необязательный комментарий организатору (≤500 символов). Хранение — `event_responses.dispute_note TEXT`
(`V22__add_dispute_note.sql`, idempotent `ADD COLUMN IF NOT EXISTS`). Сквозной путь:
- `POST /api/events/{id}/dispute` принимает необязательное тело `{ note?: string }`
  (`DisputeAttendanceRequest`, `@Size(max=500)`; `@RequestBody(required=false)` — старый вызов без тела
  по-прежнему работает). Пустая/пробельная заметка нормализуется в `null`.
- `EventResponseRepository.disputeAbsentAttendance(eventId, userId, note)` пишет `attendance=disputed`
  и `dispute_note`. Заметка выводится оргу: `EventResponderInfo`/`EventResponderDto` несут `disputeNote`
  (`findRespondersWithUsers` селектит колонку); в блоке «Оспоренные отметки» на `EventPage` она
  показывается под именем оспорившего. Фронт: textarea «Комментарий организатору (необязательно)»
  в блоке «Ваша явка».

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
                  │       ├──► @Scheduled triggerStage2ForReadyEvents (5 min)      │
                  │       └──► @Scheduled expireUnconfirmedParticipants (cfg)      │
                  ├─► AttendanceService ─► EventRepository + EventResponseRepository + ClubRepository
                  │       │                                                        │
                  │       └──► @Scheduled finalizeAttendance (cfg, default 1 h)    │
                  ├─► EventCompletionService ─► EventRepository                    │
                  │       │                                                        │
                  │       └──► @Scheduled completePastEvents (1 h)                 │
                  └─(bot)─► EventReminderScheduler ─► EventRepository + NotificationService
                          │                                                        │
                          ├──► @Scheduled remindUnconfirmedVoters (DM 2h before)   │
                          └──► @Scheduled remindOrganizersToMarkAttendance (DM 24h after)
```

### Файлы

| Файл | Роль |
|------|------|
| `Event.kt` | Domain (data class, все поля non-null соответствуют DB NOT NULL) |
| `EventResponse.kt` | Domain |
| `EventMapper.kt` | `@Component`: `toDomain(EventsRecord) → Event`, `toDetailDto(Event, counts)`, `toListItemDto`. Содержит `DEFAULT_VOTING_OPENS_DAYS_BEFORE = 14` |
| `EventResponseMapper.kt` | `@Component`: `toDomain(EventResponsesRecord) → EventResponse` |
| `EventRepository.kt` | Interface. Методы автозавершения: `markPastEventsCompleted(cutoff): Int`. Reminder-методы: `findEventsNeedingConfirmReminder`, `markConfirmReminderSent`, `findEventsNeedingAttendanceReminder`, `markAttendanceReminderSent`, `findOrganizerTelegramId` |
| `JooqEventRepository.kt` | Реализация. Все DDL/DML через jOOQ. Методы: `markAttendanceMarked`, `finalizeAttendanceBefore`, `markPastEventsCompleted`, reminder-finds/marks + `findOrganizerTelegramId` (`findEventsNeedingAttendanceReminder` фильтрует на наличие confirmed-ростера — CC-2) |
| `EventResponseRepository.kt` | Interface |
| `JooqEventResponseRepository.kt` | Реализация. Новые методы: `setAttendance` (только `final_status=confirmed`), `disputeAbsentAttendance`, `resolveDisputedAttendance`, `findUnconfirmedVoterTelegramIds` |
| `EventController.kt` | HTTP. Делегирует в 4 сервиса |
| `EventService.kt` | CRUD событий, без extension `toDetailDto` (вынесено в Mapper) |
| `VoteService.kt` | Stage 1 голосование. Использует `MembershipRepository.isMember` |
| `Stage2Service.kt` | Stage 2 переход + confirm/decline (гард `event_datetime <= now` — Bug B) + авто-истечение брони (Feature A). 2× `@Scheduled` (trigger 5 мин, expire `events.stage2-expire-poll-ms`) |
| `AttendanceService.kt` | mark / dispute / resolve / finalize. `DSLContext` убран — все update через Repository. Окно оспаривания и период финализации — `@Value`/`fixedDelayString` (`events.dispute-window-minutes`, `events.finalize-poll-ms`) |
| `JooqEventResponseRepository.kt` | + `expireUnconfirmedForStartedEvents(now)` — bulk-UPDATE `NULL → expired_no_confirm` |
| `EventCompletionService.kt` | Автозавершение прошедших событий `upcoming/stage_1/stage_2 → completed`. `@Scheduled` каждый час, grace 6ч. Делегирует в `EventRepository.markPastEventsCompleted` |
| `bot/EventReminderScheduler.kt` | **bot-модуль.** 2× `@Scheduled` (`events.reminder-poll-ms`): A — `remindUnconfirmedVoters` (DM за 2ч), B — `remindOrganizersToMarkAttendance` (DM оргу через 24ч). Без `@Transactional` (mark-before-send, EXP-3). Делегирует в `EventRepository` + `NotificationService` |
| `bot/NotificationService.kt` | + `sendConfirmReminder(event)` (unconfirmed voters), `sendAttendanceReminder(event, organizerTelegramId)` — оба `@Async` |
| `EventDto.kt`, `VoteDto.kt`, `Stage2Dto.kt`, `AttendanceDto.kt` | Request/Response DTO |

### Boundary правила
- `EventsRecord` / `EventResponsesRecord` → **только** в `JooqEvent*Repository.kt` и `Event*Mapper.kt`.
- `DSLContext` → **только** в `JooqEvent*Repository.kt`.
- Сервисы получают domain `Event` / `EventResponse` от Repository, передают DTO в Controller.
- `NotificationService.sendEventCreated/sendStage2Started/sendConfirmReminder/sendAttendanceReminder(event: Event)` и `ClubsBot.handleWhoIsGoing` принимают domain, не Record.
- `EventReminderScheduler` (bot) → `EventRepository` + `NotificationService` (bot зависит от event, как `EventBotNotifier`).
