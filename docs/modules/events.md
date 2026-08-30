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

PUT  /api/events/{id}               (2026-07-26; см. § «Редактирование встречи»)
  Body: UpdateEventRequest { title, description?, locationText?, locationLat?, locationLon?,
        locationHint?, eventDatetime, participantLimit?, stage2LeadMinutes?, photoUrl? }
  Response 200: EventDetailDto
  Errors: 400 (дата в прошлом / нет места / инвариант формата), 403 (не менеджер), 409 (не Этап 1)
```

### Бизнес-правила
- Создавать события может только **организатор клуба** (clubs.owner_id = user.id) → 403
- `eventDatetime` должен быть в будущем → 400
- `participantLimit > 0` → 400; `null` — валиден: **открытая встреча** (V62, см. § «Открытая встреча»)
- `votingOpensDaysBefore` 1-14, default = 14 → 400
- При создании: `status = upcoming`, `stage_2_triggered = false`
- `created_by = user.id` из JWT

### Валидация CreateEventRequest
| Поле | Правило |
|------|---------|
| title | 1-255 символов, not blank |
| locationText | 1-500 символов, not blank (с фичи event-geo фронт заполняет адресом из обратного геокодера) |
| locationLat | **обязательно** (`@NotNull`), ∈ [-90, 90] — гео-точка места, V57 (event-geo) |
| locationLon | **обязательно** (`@NotNull`), ∈ [-180, 180] |
| locationHint | опционально, ≤ 200 символов; пустое/пробельное схлопывается в null в `EventService` |
| eventDatetime | ISO datetime, must be > now() |
| participantLimit | > 0 (`@Positive`) ИЛИ null = открытая встреча (V62); `@NotNull` снят |
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
  [`unified-activity-creation.md`](./unified-activity-creation.md) § «Карточка ActivityCard (фото-редизайн)»).
  Складчина переиспользует уже существовавшее `skladchinas.photo_url`.

Фронт: `CreateEventPage` получил поле загрузки фото через компонент
`AvatarUpload` → `CreateEventBody.photoUrl`. **Отображение (PO 2026-07-11):**
- фон хиро на странице события (`EventPage`, `rd-hero-bg`); фолбэк — аватар клуба;
- обложка карточки в табе «Активности» (`EventCard`, `rd-act-cover`) с тёмным скримом
  сверху вниз (`rd-act-photo`, зеркалит клубный `.rd-cover::after`); фолбэк — аватар клуба
  (`MyEventListItemDto.photoUrl` добавлен для этого);
- DM бота о новом событии уходит фото-сообщением (`SendPhoto`, caption = текст DM);
  относительный `/uploads/…` превращается в абсолютный URL фронта (Telegram скачивает
  сам); сбой фото деградирует до текстового DM. (Левый thumbnail карточки
(`ActivityThumb`) убран в Banco-редизайне — карточка перешла на `rd-feature
rd-glass` без тамбнейла; см. [`redesign-banco-style.md`](./redesign-banco-style.md).)

### Гео-точка места (`location_lat`/`location_lon`/`location_hint`, миграции V57/V58, 2026-07-11)
Место события — опциональная точка на Яндекс.Картах (поиск адреса + уточнение пином);
правило: **точка ИЛИ непустое «Уточнение к месту» обязательны** (V58: `location_text`
nullable, прежний fail-closed отменён — поиск умеет только адреса, организации отложены).
События без точки показываются текстом (hint-only — уточнение как место); инвариант «оба NULL или
оба заданы» закреплён CHECK-констрейнтом `chk_events_location_pair`. Участнику на странице
события — статичная мини-карта + кнопки «🧭 Маршрут» / «Открыть в Картах». Координаты есть
только в `EventDetailDto` — списковые DTO (`EventListItemDto`, `MyEventListItemDto`,
`ActivityItemDto`) их **не получают** (YAGNI, карта только на странице события).
Полная спека: [`event-geo.md`](./event-geo.md).

### EventDetailDto (TASK-013 scope — без vote counts из EventResponses)
```json
{
  "id": "uuid",
  "clubId": "uuid",
  "title": "string",
  "description": "string|null",
  "locationText": "string",
  "locationLat": 55.751244,
  "locationLon": 37.618423,
  "locationHint": "string|null",
  "eventDatetime": "ISO datetime",
  "participantLimit": 15,
  "votingOpensDaysBefore": 14,
  "status": "upcoming",
  "goingCount": 0,
  "maybeCount": 0,
  "notGoingCount": 0,
  "confirmedCount": 0,
  "waitlistedCount": 0,
  "rosterDeadline": "ISO datetime|null",
  "rosterClosed": false,
  "rosterShortfall": false,
  "declineCostPoints": 0,
  "attendanceMarked": false,
  "attendanceFinalized": false,
  "createdAt": "ISO datetime",
  "photoUrl": "string|null"
}
```
> `declineCostPoints` (V83) = сколько очков спишется, если участник ИЗ СОСТАВА откажется прямо
> сейчас; 0 = бесплатно. Считает `EventMapper` через `RosterPolicy` — ту же чистую политику, что
> применяет `declineParticipation`, поэтому названная цена и фактическое списание не расходятся по
> построению. Не пер-юзер: зависит только от состояния события (закрыт ли состав, близко ли встреча,
> есть ли замена), поэтому живёт в общем DTO. Waitlisted поле не касается — выход из очереди
> бесплатен всегда.
>
> **Удалены в V83:** `confirmedDeclineDeadline` (означал «после этого момента отказаться нельзя» —
> запрета больше нет, отказ доступен до старта встречи) и `abandonedSlotPenaltyPoints`
> (фиксированные −100 покрывал только один из четырёх случаев, его заменил `declineCostPoints`).

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
- POST без `locationLat`/`locationLon` или с координатами вне диапазонов → 400 VALIDATION_ERROR
- POST с `locationHint` > 200 символов → 400 VALIDATION_ERROR
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
- Период тика — `events.stage2-poll-ms` (дефолт 60000 = 1 мин; env `STAGE2_POLL_MS`). Раньше было
  захардкожено 5 мин — при коротком lead (staging `STAGE2_TRIGGER_MINUTES_BEFORE=3`) фаза тика
  съедала всё окно подтверждения: флип случался в интервале `[T−lead .. T−lead+период]`, т.е.
  зачастую ПОСЛЕ старта события (окно = 0, участники получали DM «подтвердите» в никуда).
  Окно подтверждения = `lead − фаза тика`, поэтому тик обязан быть сильно мельче lead.
- Ищет события: `status = upcoming AND stage_2_triggered = false AND event_datetime <= now() + lead`,
  где `lead` = **пер-событийный** `events.stage2_lead_minutes` (V67, решение PO 2026-07-23:
  организатор выбирает интервал при создании — пресеты 18ч/36ч/3 дня/5 дней, CHECK 1080–7200 после
  V68; «ближе 18 часов» закрывает формат «Срочная встреча», см. ниже) либо,
  при NULL, глобальный `events.stage2-trigger-minutes-before` (дефолт 1080 мин = 18ч, решение PO
  2026-07-23, ранее 24ч). Сравнение «пора ли» делает SQL (`event_datetime − COALESCE(своё, дефолт)
  ≤ now` в `findEventsToTriggerStage2`). Если при создании до события осталось меньше выбранного
  интервала — Этап 2 стартует на ближайшем тике, окно Этапа 1 схлопывается в ноль (осознанное
  поведение: форма предупреждает «подтверждение мест начнётся сразу»). Эффективное значение
  отдаётся фронту в `EventDetailDto.stage2LeadMinutes` (null = открытая встреча) — клиент порог
  не хардкодит (тот же урок, что `confirmedDeclineDeadline`). На staging глобальный дефолт можно
  ужать (`STAGE2_TRIGGER_MINUTES_BEFORE`), чтобы протестировать полный поток голос → переход →
  подтверждение: форма шлёт `stage2LeadMinutes` только после явного клика по чипу, поэтому событие,
  созданное без выбора интервала, несёт NULL и подчиняется staging-ужимке
- Для каждого такого события: переводит в Stage 2 — **кроме формата «🎟 встреча с местами»** (V83):
  у него тик закрывает НАБОР СОСТАВА, а не открывает подтверждение. Развилка —
  `RosterService.handleRosterDeadline`: набрали порог → состав закрыт + DM всем; не набрали →
  `roster_shortfall_at` + DM организатору с выбором, статус остаётся `upcoming`.
  Полная механика — `event-roster-threshold.md`.

### Логика перехода в Stage 2 (UPDATED 2026-07-05 — гонка за места по Этапу 2)
1. Установить `event.status = stage_2`, `event.stage_2_triggered = true`
2. **Мест НЕ резервируем и очередь заранее НЕ формируем.** Этап 1 — только предварительный визуал:
   он не даёт приоритета на место. При старте Этапа 2 никто не помечается `waitlisted` — все
   `going`/`maybe` остаются `stage_2_vote = NULL` (pending).
3. Места разыгрываются **гонкой за места** на Этапе 2: кто первым нажмёт «Подтвердить», тот в зале
   (см. § «Логика confirm»: `confirmedCount < limit → confirmed`, иначе `waitlisted`). Очередь листа
   ожидания и её продвижение упорядочены по `stage_2_timestamp` (времени подтверждения), НЕ по Этапу 1.
4. Опубликовать `Stage2StartedEvent` → после коммита транзакции уходит DM «Этап 2 начался —
   подтвердите участие» (S2T-2 ✅, 2026-06-13; см. § «DM при старте Этапа 2 + сериализация
   слотов» ниже и `telegram-bot.md` § `sendStage2Started`).
   **Аудитория DM (UPDATED 2026-07-04):** участники клуба с доступом, которые НЕ голосовали
   `not_going` — т.е. `going` / `maybe` / **вообще не ответившие** (`findStage2InviteTelegramIds`,
   строится от memberships). Проголосовавшим `not_going` DM не шлём, но подтвердить участие они
   всё равно смогут (Этап 2 открыт всем — см. § «Логика confirm»).
   **Гейт (2026-07-04):** если флип случился уже ПОСЛЕ старта события (поздний тик / событие
   создано внутри lead-окна) — статус всё равно переводится (на переходе завязаны sweep
   авто-истечения и completion), но DM НЕ публикуется: окно подтверждения закрывается в момент
   старта (`confirmParticipation` → «Confirmation window has closed»), и призыв был бы тупиковым.

### Confirm/Decline endpoints
```
POST /api/events/{id}/confirm
  Response 200: ConfirmResponseDto
  Errors: 400 (not stage_2, already confirmed, event full), 403 (не участник)

POST /api/events/{id}/decline
  Response 200: ConfirmResponseDto

GET  /api/events/{id}/pending         # кто ещё не ответил, поимённо (менеджер)
  Response 200: EventResponderDto[] — status: going | maybe | no_answer, плюс remindedAt

POST /api/events/{id}/remind          # ручное напоминание ответить (менеджер)
  Body: {"userId": "<uuid>"} — конкретному участнику | {} — всем, от кого ждут ответа
  Response 200: {"remindedCount": N} — сколько напоминаний реально ушло (повтор даёт 0)
  Errors: 400 (не stage_2 / событие началось), 403 (не владелец и не активный со-организатор)
  Детали и правило «одно напоминание на участника»: event-stage2-composition.md § 6
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
3. Взять per-event advisory-lock `lockEventSlots(eventId)` — **до любого чтения**
   `event_responses` (S2-01/F5-07 ✅, см. § «DM при старте Этапа 2 + сериализация слотов»)
4. **Этап 2 открыт ВСЕМ участникам клуба (UPDATED 2026-07-04).** Прежний гард «нужен going/maybe
   голос» СНЯТ. Кто голосовал `not_going` — просто подтверждается (передумал → может). Кто вообще
   не голосовал — строки ещё нет, `createLateStage2Entry` создаёт её (`stage_1_vote=NULL`,
   `stage_1_timestamp=NULL`), дальше тот же путь. Это закрывает дыру «в короткое событие,
   проскочившее Этап 1, никто не мог вступить».
5. Текущий confirmed count < participant_limit → stage_2_vote = confirmed, final_status = confirmed
6. Иначе → stage_2_vote = waitlisted, final_status = waitlisted. `stage_2_timestamp` (проставляется
   этим же `updateStage2Vote`) задаёт позицию в очереди: кто раньше подтвердил при полном зале — выше.
   > Очередь по Этапу 2, а не по Этапу 1: голос Этапа 1 не влияет на порядок листа ожидания.
   > Идемпотентность: повторное подтверждение уже-`waitlisted` НЕ перезаписывает его `stage_2_timestamp`
   > (ранний `return` в confirm), поэтому позиция в очереди стабильна.

### Логика decline (UPDATED 2026-08-21 — отказ стал платным вместо запрещённого, V83)
1. Проверки события/членства (симметрично confirm), затем тот же advisory-lock
   `lockEventSlots(eventId)` (F5-11 ✅)
2. Найти response пользователя
3. **Цена отказа** вместо прежнего запрета (V83). Запрет «отказаться можно не позже чем за 4 ч»
   СНЯТ (решение PO 2026-08-21): он выталкивал людей в молчаливую неявку, которая стоит дороже
   (−200). Цену считает `RosterPolicy.declineKind` из четырёх условий — формат, закрыт ли состав,
   осталось ли до старта ≤ `events.late-decline-threshold-minutes` (240 = 4ч) и есть ли замена:

   | Когда | Замена есть | Замены нет |
   |---|---|---|
   | Набор идёт (состав не закрыт) | 0 | 0 |
   | Состав закрыт, до старта > 4ч | 0 | −100 `abandoned_slot` |
   | Состав закрыт, до старта ≤ 4ч | −50 `late_decline_covered` | −150 `late_decline_uncovered` |

   Waitlisted не платит никогда (никого не держит и выходит из очереди свободно), открытая
   встреча — целиком вне репутации.
4. stage_2_vote = declined, final_status = declined
5. Если отказавшийся был `confirmed`:
   - **есть первый waitlisted** (по `stage_2_timestamp`) → promote to confirmed.
     Повышённому уходит DM «🎉 Освободилось место» с кнопкой на событие (`WaitlistPromotedEvent` →
     AFTER_COMMIT `WaitlistPromotedListener` → `sendWaitlistPromoted`). То же уведомление шлётся при
     авто-повышении из-за выхода подтверждённого из клуба (`MembershipService.promoteFirstWaitlisted`).
   - штраф, если политика вернула kind, начисляется в ЭТОЙ ЖЕ транзакции
     (`ReputationService.penalizeDecline`, по образцу `penalizeExit`). См. reputation-v2.md.
   - фактически списанное возвращается в `ConfirmResponseDto.penaltyPoints`: названная на экране
     цена могла разойтись с фактической, пока был открыт диалог (очередь успела опустеть).

### Corner Cases
| Ситуация | Поведение |
|----------|-----------|
| Событие ещё в upcoming | 400 "Event is not in confirmation stage" |
| Confirmed отказывается за < 4ч до старта | Отказ проходит: −50 при живой очереди, −150 при пустой (V83) |
| Мест нет (confirmedCount >= limit) | Получает waitlisted |
| Confirmed decline → есть waitlisted | Первый из очереди (по `stage_2_timestamp`) → confirmed + DM «место освободилось»; отказавшийся платит только внутри 4ч (−50) |
| Confirmed decline → нет waitlisted | Слот открывается; отказавшийся получает `abandoned_slot` −100 (или −150 внутри 4ч) |
| Waitlisted decline | Выходит из очереди в любой момент до старта, без штрафа |

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

### Ранний переход при отметке явки (PO 2026-07-08)
`AttendanceService.markAttendance` дополнительно вызывает `eventRepository.markCompleted(eventId)`
(тот же guard `status IN (upcoming, stage_1, stage_2)`): организатор зафиксировал, что встреча
прошла, — статус закрывается сразу, не дожидаясь часового прохода с 6-часовым запасом.
Cron остаётся страховкой для неотмеченных событий.

### «Предстоящие/прошедшие» в ленте активностей — по ВРЕМЕНИ (PO 2026-07-08)
`ActivityMapper.toEventActivity`: `isCompleted = status IN (completed, cancelled) ИЛИ
event_datetime <= now`. Лента клуба больше НЕ зависит ни от шедулера, ни от отметки явки:
событие стартовало — оно уже не «предстоящее». Статусная ветка сохранена для cancelled
(отменённое БУДУЩЕЕ событие тоже уходит из предстоящих). Сам статус completed и cron нужны
остальным механизмам (гейты Этапа 2, скоупы каскадов, «Мои события») — их не убираем.

### Независимость от attendance flow
Перевод статуса в `completed` **не влияет** на flow отметки присутствия. `AttendanceService`
(mark / dispute / resolve / finalize) гейтит **только** на булевых флагах `attendance_marked` /
`attendance_finalized`, на времени (`event_datetime` для гарда отметки, `attendance_marked_at`
для финализации — см. § «Окно спора отсчитывается от момента отметки») и на
`event_responses.dispute_terminal` — **никогда на `status`** (подтверждено code review,
Case A). 6-часовой grace ≪ 48-часового окна спора (PRD §4.4.3), поэтому автозавершение не пересекается
с финализацией репутации.

### Frontend — отметка посещаемости (`EventPage`)
Секция «Отметить посещаемость» на `EventPage` (между «Кто идёт» и Stage 2). Видна менеджеру
(владелец ИЛИ активный со-организатор, см. `co-organizers.md`)
после события: `isManager && event_datetime <= now && !attendanceMarked && !attendanceFinalized`
(зеркалит серверный гейт `AttendanceService` — менеджерский + время, **не статус**; `!attendanceFinalized`
добавлен в Блоке 1 для EXP-2 — после нейтрального авто-закрытия отметка уже невозможна). Чеклист
участников со статусом `confirmed` (toggle-кнопки, по умолчанию «пришёл»), submit →
`POST /api/events/{id}/attendance` (`useMarkAttendanceMutation`). После отметки — read-only
«✓ Посещаемость отмечена» + (пока окно спора открыто) список оспоренных отметок с резолвом.
UI спора/резолва добавлен в Блоке 1 (см. ниже § «Репутация — Блок 1»). Восстановлено в
`bugfix/attendance-marking-ui` (2026-06-06) после потери при унификации создания активностей; см.
`docs/backlog/attendance-marking-ui-missing.md`.

### Frontend — не-участник на странице события (PO 2026-07-08)
Не-участник клуба (кикнутый/вышедший, пришёл по старой ссылке или кнопке живого закрепа из чата)
на странице **будущего** события редиректится на страницу клуба (`/clubs/{clubId}`, replace) —
там CTA вступления/оплаты. Сигнал — 403 от member-gated `GET /responders`. **Прошедшие события
не редиректят**: экс-участнику может быть нужно окно спора явки (F5-04 — `GET /my-attendance`
намеренно не гейтится членством).

### Corner Cases
| Ситуация | Поведение |
|----------|-----------|
| Событие уже `completed`/`cancelled` | Не трогается (`status IN` фильтр) |
| Событие в пределах grace-периода (`event_datetime` в прошлом < 6ч назад) | Остаётся в текущем статусе до следующего прогона |
| Событие `cancelled` с прошедшей датой | Остаётся `cancelled` (отмена при удалении клуба не перезатирается; см. § «Каскадная отмена событий при удалении клуба») |
| attendance ещё не финализирован | Статус → `completed` независимо; attendance-scheduler работает по своим флагам |

---

## Каскадная отмена событий при удалении клуба (`upcoming/stage_1/stage_2 → cancelled`)

> **Реализовано** в `bugfix/club-delete-cascade` (2026-06-13). Это **первый** код-путь,
> который реально выставляет `events.status = cancelled` — до него событий в статусе
> `cancelled` не существовало (организаторской отмены события в коде не было; единственный
> `cancelled`-corner-case в спеке был гипотетическим). Поэтому фича вскрыла и закрыла gap
> в финализации (см. ниже).

### Цель
Soft-delete клуба (`ClubService.deleteClub`) не должен оставлять «живые» события позади:
иначе шедулеры продолжают их обрабатывать (фантомные «отметьте явку»-DM, поздние
penalty-флоу), а их страницы упираются в скрытый клуб. При удалении клуба все его
**нефинализированные** события отменяются.

### Логика отмены
- `EventRepository.cancelActiveEventsByClub(clubId)` (в той же `@Transactional`, что и
  soft-delete; **перед** `clubRepository.softDelete`):
  `UPDATE events SET status = cancelled, updated_at = now()
   WHERE club_id = :id AND status IN (upcoming, stage_1, stage_2) AND attendance_finalized = false`
- **`attendance_finalized = false` — load-bearing:** `stage_2`-событие может быть уже
  отмечено по явке (`attendance_marked`, отметка разрешена в ~6ч до completion-sweep).
  Такое событие, если оно уже **финализировано**, имеет запертую репутацию — его отменять
  нельзя. Завершённые (`completed`) / уже `cancelled` события тоже не трогаются (`status IN` фильтр).
- **Репутацию каскад не трогает** (продуктовое требование 2026-06-13): финализированные
  события сохраняют свои ledger-строки, отменяются только ещё-не-финализированные.
- **Живые закрепы в чате клуба** снимает не этот каскад, а освобождение чата
  (`chatLinkService.releaseOnClubDeleted` в том же `deleteClub`) — оно откручивает закрепы по
  `chat_id`, независимо от статусов событий, поэтому порядок двух каскадов не важен.
  См. `docs/modules/club-chat-link.md` § «Освобождение чата при удалении клуба».

### Гарды финализации — отменённое событие не начисляет репутацию
Отмена может зацепить событие, которое **уже `attendance_marked`, но ещё не финализировано**
(`stage_2`, отмеченное в окне до sweep'а). Без гардов шедулер `AttendanceService.finalizeAttendance`
позже финализировал бы его и начислил репутацию за удалённый клуб. Закрыто двумя гардами:
- `JooqEventRepository.finalizeAttendanceBefore` теперь исключает `status = cancelled`
  (`AND status <> cancelled`) — зеркалит соседний `neutrallyFinalizeUnmarkedBefore`, который
  уже исключал `cancelled`. **Это и был баг-блокер, найденный ревью:** `finalizeAttendanceBefore`
  единственный из finalize-методов не фильтровал `cancelled`.
- `JooqReputationRepository.claimEvent` — defensive-гард `AND status <> cancelled` (в дополнение
  к `attendance_finalized` / `attendance_marked`): отменённое событие не клеймится под репутацию
  никаким caller'ом.

### Связь с автозавершением и attendance-flow
В отличие от `EventCompletionService` (status-независимого, гейтит на флагах), здесь статус
`cancelled` **load-bearing для финализации** — именно поэтому добавлены гарды выше. Складчины
этого же клуба отменяются параллельно (`SkladchinaRepository.cancelActiveByClub`) — см.
`docs/modules/skladchina.md` § «Удаление клуба» и `docs/modules/clubs.md` § DELETE.

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
| `events.stage2-trigger-minutes-before` | `STAGE2_TRIGGER_MINUTES_BEFORE` | `1080` (18ч, PO 2026-07-23; ранее 24ч) | За сколько **минут** до старта `upcoming`-событие авто-переходит в `stage_2`; у формата 🎟 это дедлайн НАБОРА состава (V83) — **дефолт** для событий без своего `stage2_lead_minutes` (V67) |
| `events.roster-shortfall-response-minutes` | `ROSTER_SHORTFALL_RESPONSE_MINUTES` | `360` (6ч) | Сколько ждём решения организатора при недоборе, прежде чем отменить встречу автоматически (V83) |
| `events.roster-deadline-min-lead-minutes` | `ROSTER_DEADLINE_MIN_LEAD_MINUTES` | `120` (2ч) | Насколько близко к встрече разрешено двигать дедлайн набора продлением «+6/+12 часов» (V83) |
| `events.stage2-poll-ms` | `STAGE2_POLL_MS` | `60000` (1мин) | Период тика `triggerStage2ForReadyEvents`; окно подтверждения = trigger-lead − фаза тика, тик должен быть сильно мельче lead |
| `events.late-decline-threshold-minutes` | `LATE_DECLINE_THRESHOLD_MINUTES` | `240` (4ч) | За сколько **минут** до старта закрывается отказ от УЖЕ ПОДТВЕРЖДЁННОГО места (замене нужно время). Фронт дублирует порог константой `CONFIRMED_DECLINE_CUTOFF_HOURS=4`; бэк — источник истины. Waitlisted порогом не гейтится |
| `events.reminder-poll-ms` | `EVENT_REMINDER_POLL_MS` | `300000` (5мин) | Период `EventReminderScheduler` |
| `events.attendance-reminder-minutes-after` | `ATTENDANCE_REMINDER_MINUTES_AFTER` | `1440` (24ч) | Через сколько **минут** после события напомнить оргу отметить явку |

`@Scheduled(fixedDelay)` требует compile-time константу — поэтому период задаётся через
`fixedDelayString = "${...}"`. Окно оспаривания инжектится `@Value` в поле `AttendanceService`
(единица — **минуты**, чтобы выразить тестовые 5 минут; `minusMinutes`).

> **Тест на staging:** в Coolify-приложении staging выставить `ATTENDANCE_DISPUTE_WINDOW_MINUTES=5`,
> `ATTENDANCE_FINALIZE_POLL_MS=60000` (1 мин), при желании `STAGE2_EXPIRE_POLL_MS=60000`.
> Для проверки полного двухэтапного потока — `STAGE2_TRIGGER_MINUTES_BEFORE=15` (комфортное окно;
> при дефолтном тике 1 мин окно подтверждения = 14–15 мин, при `=3` — впритык 2–3 мин): создать
> событие со стартом > lead в будущем (останется `upcoming`, голосуем), дождаться границы
> `T − lead` + тик шедулера → событие уходит в `stage_2`, DM «Этап 2 начался», подтверждаем до
> старта. Условие наличия фазы голосования: `(минут до старта) > STAGE2_TRIGGER_MINUTES_BEFORE`.
> Тот же образ, только env. После теста — удалить переменные (prod-дефолты не меняются).
> NB: окно оспаривания отсчитывается от **момента отметки явки** (`events.attendance_marked_at`,
> колонка V24, F5-05/б), а не от `event_datetime`. `finalizeAttendanceBefore` гейтит по
> `COALESCE(attendance_marked_at, event_datetime) <= cutoff` — legacy-строки, помеченные до V24
> (`attendance_marked_at IS NULL`), остаются на старом базисе `event_datetime` без бэкфилла.
> Это приводит реализацию в соответствие с PRD §4.4.3 («48 часов с момента сохранения отметок»).
> Подробности окна — § «Окно спора отсчитывается от момента отметки» ниже.

### Напоминания событий (`EventReminderScheduler`, bot-модуль)

> **Реализовано** в `feature/two-stage-confirmation-gaps` (2026-06-07). Poll-уведомление
> (`backend/.../bot/EventReminderScheduler.kt`, период `events.reminder-poll-ms`). Дедуп — флаг
> `events.attendance_reminder_sent` (миграция V21): флаг ставится ДО `@Async` DM, поэтому
> повторный poll не дублирует рассылку. Цикл **без** `@Transactional` (каждый
> `markAttendanceReminderSent` — самостоятельный auto-commit UPDATE), чтобы одна ошибка
> не валила весь батч (урок EXP-3).

- **B — «отметь явку» (через `attendance-reminder-minutes-after` = 1440мин/24ч после события):**
  `remindOrganizersToMarkAttendance` → события `event_datetime <= now−24ч`, `attendance_marked=false`,
  `attendance_reminder_sent=false`, `status ≠ cancelled` → DM организатору (`findOrganizerTelegramId`).

> **A — «подтверди участие» за ~2ч до события — УДАЛЕНО (решение PO 2026-07-08, V51):** лишний
> пинг — участник получает DM при старте Этапа 2 (`sendStage2Started`, S2T-2, подключено
> 2026-06-13), а дедлайн подтверждения виден в живом закрепе чата. Снесены:
> `remindUnconfirmedVoters`, `sendConfirmReminder`, `findEventsNeedingConfirmReminder` /
> `markConfirmReminderSent`, `findUnconfirmedVoterTelegramIds`, конфиг
> `events.confirm-reminder-minutes-before`, колонка `events.confirm_reminder_sent` (V21→V51).
> Итого участник получает **один** nudge о подтверждении: при старте Этапа 2.
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
- **AC-R1 (confirm reminder):** снят — напоминание удалено PO 2026-07-08 (V51), см. § «Напоминания событий».
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
- **Очередь на странице (UPDATED 2026-08-16):** на Этапе 2+ под составом рендерится
  секция «В очереди» (до 2026-08-16 — «Лист ожидания», переименована ради развода с «Без ответа»,
  см. [`event-stage2-composition.md`](event-stage2-composition.md) § 3) —
  `waitlisted`-участники **в порядке приоритета продвижения**
  (нумерованный список 1..N). Это работает потому, что `findRespondersWithUsers` сортирует по
  `stage_2_timestamp ASC` (NULLS LAST, вторичный ключ `stage_1_timestamp`) — ровно тот ключ, по
  которому `findFirstWaitlisted` продвигает очередь (время вставания в лист ожидания на Этапе 2).
  Тот же запрос теперь включает и поздних участников (`stage_1_vote=NULL`, но есть `final_status`),
  которых раньше отсекал фильтр `stage_1_vote IS NOT NULL` — иначе подтвердившийся не-голосовавший
  выпадал бы из ростера. На Этапе 1 (действий Этапа 2 ещё нет, `stage_2_timestamp` у всех NULL)
  NULLS LAST + `stage_1_timestamp` даёт предварительный порядок «кто раньше откликнулся».

---

## DM при старте Этапа 2 + сериализация слотов (S2T-2 + S2-01/F5-07 + F5-11)

> **Реализовано** в `bugfix/stage2-dm-and-slot-races` (2026-06-13) — «пакет 2 / PR stage2 races»
> из `docs/backlog/two-stage-reputation-bug-register.md`. Закрывает последний разрыв «живого
> пути» двухэтапки (участников никто не звал подтверждать) и обе слот-гонки.

### S2T-2 — DM «Этап 2 начался» (+ GAP-004/GAP-009)
- `Stage2Service.triggerStage2` (в транзакции scheduler'а) публикует `Stage2StartedEvent`
  (`event/Stage2StartedEvent.kt`, несёт snapshot domain-`Event`). NB (UPDATED 2026-07-05): переход
  больше НЕ назначает overflow → waitlist заранее — места разыгрываются гонкой подтверждений на
  Этапе 2 (см. § «Логика перехода в Stage 2»).
- `bot/Stage2StartedListener` (`@TransactionalEventListener`, AFTER_COMMIT) зовёт
  `NotificationService.sendStage2Started` (`@Async`, best-effort: сбой Telegram не валит
  триггер). AFTER_COMMIT обязателен — `@Async`-DM читает строки воутеров на отдельном
  соединении, видящем переход только после коммита.
- Получатели (UPDATED 2026-07-04) — `EventResponseRepository.findStage2InviteTelegramIds(eventId)`:
  участники клуба **с доступом** (`MembershipAccess.hasAccess`), у которых `stage_1_vote IS DISTINCT
  FROM 'not_going'` — т.е. `going` / `maybe` / **не ответившие** (строится от memberships LEFT JOIN
  event_responses, иначе не ответившие бы выпали). `not_going` исключены из DM (GAP-009), но подтвердить
  участие могут (Этап 2 открыт всем — § «Логика confirm»). Прежний `findStage2TargetTelegramIds`
  (только going/maybe) больше НЕ используется в DM (осталась только сигнатура): DM об **отмене**
  события (F5-14) с 2026-07-05 шлётся ВСЕМ участникам клуба с доступом (`findMemberTelegramIds`),
  симметрично уведомлению о создании.
- DM с deep-link кнопкой «✅ Подтвердить участие» на `/events/{id}`. Текст и контракт —
  `telegram-bot.md` § `sendStage2Started`.

### S2-01/F5-07 + F5-11 — advisory-lock на слоты этапа 2
- Новый `EventResponseRepository.lockEventSlots(eventId)` =
  `pg_advisory_xact_lock(hashtext('event-slots:<eventId>'))` — транзакционный
  per-event лок (паттерн `JooqReputationRepository.recompute`; префикс ключа разводит
  пространства). Авто-release на commit/rollback, явного unlock нет.
- Берётся в начале `confirmParticipation` **и** `declineParticipation`: после
  authz-проверок (404/403 не держат лок), но **до любого чтения** `event_responses` —
  заблокированная транзакция после захвата перечитывает уже закоммиченное состояние.
- Закрывает: двойной confirm на последний слот (оба видели `9<10` → 11/10 confirmed,
  S2-01/F5-07) и двойной decline с промоутом одного waitlisted на два освободившихся
  слота (потерянный слот, F5-11).
- Покрытие: `Stage2SlotRaceIntegrationTest` (Testcontainers, реальный Postgres,
  конкурентные транзакции через Spring-прокси) воспроизводит обе гонки; верифицировано,
  что **без лока тесты падают**. Порядок «лок до чтения» — unit-тесты `Stage2ServiceTest`
  (`verifyOrder`).
- Остаточная LOW boundary-гонка confirm × expire-sweep (проверка окна до лока, sweep лок
  не берёт) — зафиксирована как **S2-06** в реестре багов, осознанно не чинится здесь.

### Acceptance Criteria
- **AC-S2T2-1:** GIVEN событие переходит в `stage_2` WHEN транзакция триггера закоммичена
  THEN DM «✅ Подтвердить участие» (`/events/{id}`) уходит участникам с доступом, кроме
  `not_going` (т.е. going / maybe / не ответившим); `not_going` — нет; при rollback DM не уходит.
- **AC-OPEN-1 (2026-07-04):** GIVEN событие в `stage_2` до старта WHEN участник клуба с любым
  Этапом-1 (`not_going` / без голоса) жмёт confirm THEN он `confirmed` (или `waitlisted` при
  переполнении); строки не было — создаётся. Не-участник клуба → 403.
- **AC-S2T2-2:** отказ Telegram API логируется `WARN` и не влияет на переход в `stage_2`.
- **AC-LOCK-1:** GIVEN 1 свободный слот WHEN два параллельных confirm THEN ровно один
  `confirmed`, второй `waitlisted` (никогда `confirmed > participant_limit`).
- **AC-LOCK-2:** GIVEN 2 параллельных decline и ≥2 waitlisted THEN промоутятся **два разных**
  waitlisted-участника (слот не теряется).

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
- `EventResponseRepository.resolveExpiredDisputesToAbsent(eventIds)` — `UPDATE event_responses SET attendance = absent,
  dispute_terminal = true WHERE attendance = disputed AND event_id IN (:eventIds)`. `dispute_terminal = true`
  (колонка V24, F5-16) делает истёкший спор **терминальным** — повторно оспорить его нельзя (бэкстоп
  ordering B; см. ниже § «Окно спора отсчитывается от момента отметки»).
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
- `AttendanceService.markAttendance` после `markAttendanceMarked` публикует
  `AttendanceMarkedEvent(eventId, newlyAbsentUserIds)`. `newlyAbsentUserIds` (F5-15.2) — только те
  участники, чья строка `setAttendance` вернул > 0 при `attended=false`, т.е. **впервые** стали absent
  в этой отметке. При повторной/корректирующей отметке уже-absent-строки матчатся 0 строк и в список не
  попадают → нет повторного DM-blast. Список собран синхронно в `markAttendance` (не перезапрашивается),
  поэтому fresh-connection visibility-hazard не реоткрывается.
- `bot/AttendanceMarkedListener` (`@TransactionalEventListener(AFTER_COMMIT)`) зовёт
  `notificationService.sendAttendanceMarked` для участников из `newlyAbsentUserIds`. **Почему AFTER_COMMIT:**
  DM — `@Async`, читает только что записанные `absent`-строки на отдельном соединении, которое видит их
  лишь после коммита транзакции `markAttendance`. Тот же паттерн, что у `AttendanceFinalizedListener`.
- **DM оргу при споре (2026-06-11):** `disputeAttendance` публикует `AttendanceDisputedEvent(eventId, userId)`
  → `bot/AttendanceDisputedListener` (AFTER_COMMIT) → `sendAttendanceDisputed` — DM организатору
  «N оспорил отметку, разберите до закрытия окна» с deep-link. Без этого орг, не открывающий событие,
  пропускал спор, и ATT-2 наказывал участника без чьего-либо решения.
- **DM спорщику об исходе (2026-07-08, фидбек PO):** `resolveDispute` публикует
  `AttendanceDisputeResolvedEvent(eventId, userId, attended)` → тот же `AttendanceDisputedListener`
  (AFTER_COMMIT) → `sendAttendanceDisputeResolved` — DM участнику: «спор решён в вашу пользу,
  отметка исправлена на „пришёл“» либо «отметка „не пришёл“ осталась в силе», с deep-link на
  событие. Раньше исход спора участник узнавал только случайно со страницы события.

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
  > Редизайн 2026-08-01 ([`event-vote-block.md`](event-vote-block.md)): кольцо на Этапе 1 больше
  > не показывает доли голосов — оно считает занятость мест (`going/limit`), как и на Этапе 2;
  > секция откликов переименована в «Кто откликнулся» и разложена табами going/maybe/not_going.
- **`stage_2` / `completed` (Этап 2+)** — финальный состав: заголовок «Состав · {confirmed}/{limit}»,
  донат = confirmed/лимит, read-only плитки «Подтвердили / Без ответа / В очереди»,
  «Кто идёт» = **только `confirmed`**. Отказавшиеся/истёкшие выпадают; подтверждённые и ещё-не-ответившие
  (pending) не смешиваются в одном числе. Дублирующий блок «Участники → Подтверждено» удалён.
  > Редизайн 2026-08-16 ([`event-stage2-composition.md`](event-stage2-composition.md)): сводка
  > переехала со строк `.rd-kv` на плитки в геометрии кнопок Этапа 1, состав — с сетки на панель
  > со строками и «Показать всех», подписи «Ждут подтверждения» / «Лист ожидания» заменены на
  > «Без ответа» / «В очереди». Менеджеру (`MANAGE_EVENTS`) добавлен второй таб с именами
  > не подтвердивших и переходом в личный чат.
- **AC-PH1:** GIVEN `stage_2`, участник голосовал going и `final_status=declined` WHEN открыт `EventPage`
  THEN он не входит в «Состав · {confirmed}» и не показан в «Кто идёт».
- **Карточка клубной ленты (`ActivityCard`)** — тот же фазовый показ (F5-21, 2026-06-25): счётчик
  карточки = `goingCount`/«идёт» при `upcoming`, `confirmedCount`/«подтв.» при `stage_2`/`completed`.
  `confirmedCount` проброшен в `ActivityItemDto.EventActivity` (`GET /api/clubs/:id/activities`).
  Раньше карточка всегда показывала stage-1 `goingCount`, противореча «Состав · {confirmed}» внутри
  события. Глобальная лента (`feed/EventCard`) фазовый счёт уже делала.
- **AC-PH2:** GIVEN событие в `stage_2` с `goingCount=5`, `confirmedCount=2` WHEN открыта вкладка
  «Активности» клуба THEN карточка показывает «2/{limit} · подтв.» (не «5/{limit} · идёт»).

**Текст confirm-reminder DM (ATT/Feature A).** *(историческое: само напоминание удалено PO
2026-07-08, V51 — см. § «Напоминания событий»)* `NotificationService.sendConfirmReminder` —
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
- **Приватность заметки — только менеджеру клуба (F5-06, `bugfix/attendance-dispute-integrity`; расширено на со-орга в `co-organizers.md`).**
  В `GET /api/events/{id}/responses` поле `disputeNote` возвращается **только менеджеру** — владельцу
  ИЛИ активному со-организатору
  (`VoteService.getEventResponders`: `disputeNote = if (isManager) r.disputeNote else null`, где
  `isManager` = менеджер клуба через `ClubRoleGuard` (capability `MANAGE_EVENTS`; модель прав — `club-roles.md`)); остальным участникам поле
  приходит `null`. Раньше нота уходила всем участникам, а скрытие было только клиентским. SQL
  по-прежнему селектит колонку (владельцу нота нужна), нуллится в маппинге; DTO без изменений.

---

## Целостность отметки/спора (пакет 1 F5, `bugfix/attendance-dispute-integrity`)

> **Реализовано** в `bugfix/attendance-dispute-integrity` (2026-06-13). Дизайн (LOCKED):
> `docs/backlog/package1-attendance-dispute-design.md`; реестр: `docs/backlog/two-stage-reputation-bug-register.md`
> § «Аудит F5». Один backend-PR + минимальный фронт `canDispute`. **Миграция V24**
> (`V24__add_attendance_marked_at_and_dispute_terminal.sql`) добавляет две колонки
> (`ADD COLUMN IF NOT EXISTS`): `events.attendance_marked_at TIMESTAMPTZ` (nullable),
> `event_responses.dispute_terminal BOOLEAN NOT NULL DEFAULT FALSE`. Бэкфилл не нужен.

### Окно спора отсчитывается от момента отметки (F5-05/б)

Часы окна спора считаются от **момента отметки явки**, а не от `event_datetime`. `markAttendanceMarked`
пишет `events.attendance_marked_at = now()` (вместе с `attendance_marked = true`).
`finalizeAttendanceBefore` гейтит по `COALESCE(attendance_marked_at, event_datetime) <= cutoff`
(`cutoff = now − disputeWindowMinutes`). Так поздняя отметка даёт участнику полное окно от момента
фиксации (приводит код к PRD §4.4.3 «48 часов с момента сохранения отметок»). Legacy-строки,
помеченные до V24 (`attendance_marked_at IS NULL`), остаются на старом базисе `event_datetime` через
`COALESCE` — без бэкфилла и регрессии. **EXP-2 не меняем:** `neutrallyFinalizeUnmarkedBefore` остаётся
на `event_datetime` (немаркированное событие не имеет `attendance_marked_at`); финалайзеры работают на
disjoint-строках (`marked=true` vs `marked=false`).

### Терминальность спора — без ping-pong (F5-16)

`event_responses.dispute_terminal` (boolean) делает разрешённый/истёкший спор **окончательным**:
- `resolveDispute` (любой исход, owner) и ATT-2 (`resolveExpiredDisputesToAbsent`) ставят
  `dispute_terminal = true`.
- `disputeAbsentAttendance` несёт `AND dispute_terminal = false` — **load-bearing DB-guard**: повторная
  попытка оспорить терминальную отметку матчит 0 строк → `ValidationException("Спор по этой отметке уже
  рассмотрен")`. У dispute-эндпоинта нет авторизации сверх JWT, поэтому UI-гейта недостаточно.
- `setAttendance` сбрасывает `dispute_terminal = false` на каждой свежей отметке — иначе re-mark в новый
  `absent` унаследовал бы stale `true` и стал бы неоспоримым.
- **Boolean, не 4-е значение enum `attendance`** — новый enum-литерал молча выпал бы из
  `ReputationPolicy.attendanceKind` и был бы необратим.

### Свой статус явки без членства (F5-04)

Новый `GET /api/events/{id}/my-attendance` возвращает состояние **своей** строки вызывающего —
**без `@RequiresMembership`-гейта**, чтобы вышедший из клуба участник всё ещё мог дойти до UI спора по
deep-link из DM (он по-прежнему получает штраф −200 и должен иметь право оспорить). Ответ `MyAttendanceDto`:

```json
{
  "attendance": "absent",        // attended | absent | disputed | null
  "attendanceMarked": true,
  "attendanceFinalized": false,
  "disputeTerminal": false,
  "canDispute": true,            // window open AND attendance=absent AND !disputeTerminal — считается на сервере
  "disputeNote": "..."           // СВОЯ заметка (только своя строка)
}
```

- `404` если у вызывающего нет `event_response` на этом событии.
- `canDispute` — единственный источник правды, по которому фронт (`EventPage`) показывает кнопку
  «Оспорить» (раньше — из member-gated роутера `/responses`, недостижимого для вышедшего из клуба).
- `GET /api/events/{id}/responses` остаётся **member-only** (F5-06 его не расширяет) — два разных
  контракта: `/responses` member-gated (ростер для организатора/участников), `/my-attendance`
  participation-scoped (своя строка, без членства).

### Защита записи от финалайзера — TOCTOU (F5-09 / F5-10)

Запись отметки/спора защищена от гонки с финалайзером на уровне БД-предикатов (без нового advisory lock):
- **F5-09 (mark × finalize):** `markAttendanceMarked` гейтит `AND attendance_finalized = false` и
  возвращает `Int`; в `markAttendance` 0 строк ⇒ `ValidationException("Attendance has been finalized")` —
  полный rollback `@Transactional` (включая `setAttendance`-записи). Durable-форма: тот же `event NOT
  finalized`-подзапрос внутрь `setAttendance` (на уровне `EVENT_RESPONSES`), чтобы каждая запись была
  независимо безопасна.
- **F5-10 (dispute × finalize):** `disputeAbsentAttendance` несёт подзапрос `AND event_id IN (SELECT id
  FROM events WHERE id=? AND attendance_finalized=false)` — закрывает ordering A (флаг уже видимо
  закоммичен). **Ordering B** (спор коммитит до ATT-2 в той же finalize-txn) остаётся на бэкстопе
  **ATT-2** (`disputed→absent`, `dispute_terminal=true`, −200) — `resolveExpiredDisputesToAbsent` удалять
  нельзя.

### Re-mark не стирает активный спор (F5-15.1)

`setAttendance` несёт `AND attendance IS DISTINCT FROM 'disputed'` (строго `IS DISTINCT FROM`, не
`<>`/`ne()` — первая отметка имеет `attendance=NULL`, и `NULL <> 'disputed'` пропустила бы строку,
сломав happy-path). Повторная отметка по `disputed`-строке матчит 0 строк → спор сохранён, строка
исключена из `markedCount`; единственный путь её мутации — `resolveDispute`. Про сокращение DM до
newly-absent (F5-15.2) — см. § ATT-3 выше.

### Reminder не для нейтрально-финализированных (F5-17)

`findEventsNeedingAttendanceReminder` несёт `AND attendance_finalized = false` — после EXP-2 нейтрального
авто-закрытия «отметьте явку»-DM больше не уходит (отметка всё равно была бы отклонена гардом
`attendanceFinalized`). Строго субтрактивно.

### Acceptance Criteria (пакет 1 F5)

- **AC-F5-05 (окно от marked_at):** GIVEN `event_datetime = now−47ч`, отметка `now()` (`marked_at=now`),
  `window=48ч` WHEN `finalizeAttendance` THEN событие НЕ финализируется (`COALESCE(marked_at,
  event_datetime) > cutoff`); legacy `marked_at=NULL` финализируется по `event_datetime`.
- **AC-F5-16-1 (terminal):** GIVEN resolve-to-absent (`dispute_terminal=true`) WHEN участник повторно
  оспаривает THEN `disputeAbsentAttendance` → 0 строк → `ValidationException`.
- **AC-F5-16-2 (reset):** GIVEN resolved-to-absent, орг делает свежую отметку absent WHEN `setAttendance`
  THEN `dispute_terminal=false`; последующий dispute снова проходит.
- **AC-F5-04 (без членства):** GIVEN вышедший из клуба, есть `event_response`, `attendance='absent'`
  WHEN `GET /my-attendance` THEN `200` со своей строкой и `canDispute=true`; `POST /dispute` проходит;
  `GET /responses` тому же юзеру → `403` (роутер остаётся member-only).
- **AC-F5-06 (нота видна менеджеру):** GIVEN менеджер (владелец/активный со-организатор) и обычный
  участник читают `/responses` THEN обычный участник → `disputeNote=null`; менеджер → нота. См. `co-organizers.md`.
- **AC-F5-09 (mark × finalize):** GIVEN финалайзер закоммитил `finalized=true` между TOCTOU-read и
  `markAttendanceMarked` THEN `markAttendance` бросает `ValidationException`, `setAttendance`-записи
  откачены.
- **AC-F5-10 (dispute × finalize A):** GIVEN финалайзер закоммитил `finalized=true` до старта
  dispute-statement THEN `disputeAbsentAttendance` → 0 строк, dispute отклонён,
  `AttendanceDisputedEvent` НЕ публикуется.
- **AC-F5-15.1 (re-mark over disputed):** GIVEN `attendance='disputed'`, повторная отметка THEN
  `setAttendance` → 0 для этой строки, спор сохранён, исключён из `markedCount`.
- **AC-F5-17 (reminder):** GIVEN `marked=false, finalized=true`, past-cutoff THEN
  `findEventsNeedingAttendanceReminder` НЕ возвращает; `finalized=false` всё ещё возвращается.

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
| `JooqEventRepository.kt` | Реализация. Все DDL/DML через jOOQ. Методы: `markAttendanceMarked` (пишет `attendance_marked_at=now()`, гейтит `attendance_finalized=false`, возвращает `Int` — F5-05/F5-09), `finalizeAttendanceBefore` (гейтит `COALESCE(attendance_marked_at, event_datetime) <= cutoff` — F5-05/б), `neutrallyFinalizeUnmarkedBefore` (на `event_datetime` — EXP-2, не меняется), `markPastEventsCompleted`, reminder-finds/marks + `findOrganizerTelegramId` (`findEventsNeedingAttendanceReminder` фильтрует на наличие confirmed-ростера — CC-2 — и `attendance_finalized=false` — F5-17) |
| `EventResponseRepository.kt` | Interface |
| `JooqEventResponseRepository.kt` | Реализация. Методы: `setAttendance` (только `final_status=confirmed`; `IS DISTINCT FROM 'disputed'` + `dispute_terminal=false` reset + durable `event NOT finalized`-подзапрос), `disputeAbsentAttendance` (`+ dispute_terminal=false` + `event NOT finalized`-подзапрос), `resolveDisputedAttendance`/`resolveExpiredDisputesToAbsent` (`+ dispute_terminal=true`), `findByEventAndUser` (F5-04), `findUnconfirmedVoterTelegramIds` |
| `EventController.kt` | HTTP. Делегирует в 4 сервиса |
| `EventService.kt` | CRUD событий, без extension `toDetailDto` (вынесено в Mapper) |
| `VoteService.kt` | Stage 1 голосование + `getEventResponders` (ростер). Использует `MembershipRepository.isMember`; инжектит `ClubRepository` для менеджерской видимости `disputeNote` (F5-06; владелец/активный со-орг) |
| `Stage2Service.kt` | Stage 2 переход + confirm/decline (гард `event_datetime <= now` — Bug B) + авто-истечение брони (Feature A). 2× `@Scheduled` (trigger 5 мин, expire `events.stage2-expire-poll-ms`) |
| `AttendanceService.kt` | mark / dispute / resolve / finalize + `getMyAttendance` (F5-04, `GET /my-attendance`, без членства). `DSLContext` убран — все update через Repository. Окно оспаривания и период финализации — `@Value`/`fixedDelayString` (`events.dispute-window-minutes`, `events.finalize-poll-ms`). TOCTOU-гарды F5-09/F5-10 + терминальность спора F5-16 |
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

---

## Отмена события (F5-14)

### Цель
Дать организатору явно отменить ещё не начавшуюся встречу, которая не состоится. До этого `EventStatus.cancelled` присваивался только каскадом удаления клуба — отдельной отмены одного события не было, поэтому сорвавшаяся встреча проживала полный цикл (reminder'ы, авто-EXP-2), а ошибочная отметка явки могла начислить штрафы за несостоявшееся событие.

### US-1
**Как** организатор клуба, **я хочу** отменить предстоящее событие (с необязательным пояснением), **чтобы** участники узнали об отмене, и встреча не выжигала ничью репутацию.

### Правила (продуктовые решения 2026-06-25)
- **Кто:** менеджер клуба-хоста — владелец ИЛИ активный со-организатор (см. `co-organizers.md`), иначе 403.
- **Когда (окно):** только **до начала события** — `status ∈ {upcoming, stage_1, stage_2}` И `event_datetime > now`. После наступления времени события отмена недоступна (организатор либо отмечает явку, либо событие авто-закрывается нейтрально через EXP-2). Невыполнение → **409 Conflict** «Событие нельзя отменить».
- **Терминально:** отменённое событие не возвращается (как удаление клуба).
- **Репутация — нейтральна для всех:** отмена идёт **через репозитории напрямую** (не через сервисы репутации), поэтому никто не получает `no_show`/`expired_no_confirm`/штраф и никто не получает явку. Все sweep'ы (`findEventsNeedingAttendanceReminder`, `finalizeAttendanceBefore`, `markPastEventsCompleted`, EXP-2-expirer) уже исключают `cancelled`.
- **Причина:** необязательна (`cancellation_reason TEXT NULL`, миграция **V34**). Пустая/пробельная → `null`. Показывается участникам в DM и на странице отменённого события.

### Каскад при отмене
- Само событие: `status → cancelled`, `cancellation_reason`, `updated_at` (предикат гарда выше).
- **Привязанный split-сбор** (`skladchinas.event_id = eventId AND status = active`): `pending`-участники → `released` (без ledger-строк), сам сбор → `cancelled`. **Успешно закрытый** сбор (`closed_success`) НЕ трогаем — деньги уже собраны (honor-system). Зеркало `cancelActiveByClub`, но по `event_id`.
- **DM** заинтересованным (`stage_1_vote ∈ {going, maybe}` = аудитория `findStage2TargetTelegramIds`): «Событие отменено» (+ причина, если есть). `EventCancelledEvent` → `EventCancelledListener` (AFTER_COMMIT, @Async, best-effort) — паттерн `Stage2StartedListener`.

### API контракт
```
POST /api/events/{id}/cancel        (JWT; организатор клуба)
Body (optional): { "reason"?: string }      // CancelEventRequest, @Size(max=500), blank→null
200 → EventDetailDto (status="cancelled", cancellationReason)
403 → не владелец клуба
404 → событие не найдено
409 → событие нельзя отменить (уже началось/завершено/отменено)
```
`EventDetailDto` дополнен `cancellationReason: String?` (домен `Event` + `EventMapper` несут поле из колонки).

### Frontend
- `EventPage`: кнопка «Отменить событие» — менеджеру (владелец/активный со-организатор), пока `!eventHappened` и `status ∉ {cancelled, completed}`. Тап → подтверждающая модалка с необязательным полем причины → `POST …/cancel`.
- Отменённое состояние: баннер «Событие отменено» (+ «: причина»); блоки голосования/подтверждения/явки/«Кто идёт» скрыты.
- `ActivityCard`/лента: отменённое (`isCompleted=true`, как `completed`) уже затемнено и уходит в «прошедшие»; добавляется метка «Отменено».

### Acceptance Criteria
- **AC-CXL1:** GIVEN организатор, событие `upcoming`, `event_datetime` в будущем WHEN `POST …/cancel` THEN `status=cancelled`, репутация участников не изменилась, привязанный активный сбор `cancelled` + его pending → released.
- **AC-CXL2:** GIVEN не-владелец WHEN `POST …/cancel` THEN 403, событие не изменено.
- **AC-CXL3:** GIVEN событие уже наступило (`event_datetime ≤ now`) ИЛИ `completed`/`cancelled` WHEN `POST …/cancel` THEN 409, никаких изменений.
- **AC-CXL4:** GIVEN событие отменено с причиной WHEN заинтересованный участник открывает `EventPage` THEN видит «Событие отменено: <причина>» и не видит кнопок голоса/подтверждения; DM с причиной доставлен (best-effort).
- **AC-CXL5:** GIVEN отменённое событие WHEN отрабатывают шедулеры (reminder/finalize/complete) THEN событие пропускается, в `completed` не переводится.

## Редактирование встречи (только Этап 1)

> Фича PO 2026-07-23 (перенос даты) → расширена PO 2026-07-26 до полного редактирования,
> ветка `feature/access-month-stage2-18h`. До неё событие после создания не редактировалось
> вообще — исправить опечатку или сменить место можно было только отменой + пересозданием
> (с потерей голосов и рассылкой «событие отменено» всему клубу).

### Цель
Дать организатору править ещё не подтверждаемую встречу без пересоздания — и не дёргать
при этом весь клуб из-за косметических изменений.

### US-1
**Как** организатор клуба, **я хочу** изменить детали встречи, пока идёт голосование
Этапа 1, **чтобы** подстроить её под участников, не отменяя.

### Правила (решения PO 2026-07-23 и 2026-07-26)
- **Кто:** менеджер клуба-хоста — владелец ИЛИ активный со-организатор (`MANAGE_EVENTS`), иначе 403.
- **Когда (окно):** ТОЛЬКО Этап 1 — `status = upcoming` И `stage_2_triggered = false` И
  `event_datetime > now`. **С началом подтверждения мест (Этап 2) любые правки запрещены** —
  подтвердившие обещали прийти в конкретное место и время. Срочная встреча (сразу `stage_2`)
  не редактируется никогда; открытая — пока не случился её технический флип статуса.
  Невыполнение → **409 Conflict**.
- **Что редактируется:** название, описание, место (адрес/точка/уточнение), дата и время,
  лимит участников, интервал Этапа 2, фото.
- **Что НЕ редактируется:** формат встречи (обычная/открытая/срочная) — он определяет механику
  мест и репутации, смена на лету переписала бы правила идущего голосования; `voting_opens_days_before`
  (окно Этапа 1 уже открыто).
- **PUT-семантика:** клиент присылает полный набор редактируемых полей, `null` = очистить.
- **Эффективный ≠ хранимый интервал Этапа 2:** `EventDetailDto.stage2LeadMinutes` — ЭФФЕКТИВНЫЙ
  (с подставленным глобальным дефолтом, для показа), `stage2LeadMinutesOverride` — ХРАНИМЫЙ
  (`null` = «дефолт»). Форма редактирования обязана возвращать **override**: отправка
  эффективного значения молча превратила бы дефолт в собственный интервал события, а при
  ужатом дефолте окружения (staging: 5 минут) ещё и упиралась бы в `@Min(1080)`,
  блокируя любую правку — включая смену даты (баг, найденный PO 2026-07-26).
  Трёхзначная логика partial-update («не прислали» vs «очистить») намеренно не вводится.
- **Инварианты формата** проверяются в сервисе (в DTO формат неизвестен): лимит обязателен у
  встречи с местами и запрещён у открытой; свой интервал Этапа 2 неприменим к открытой и срочной.
  Нарушение → 400.
- **Дата ближе интервала Этапа 2 НЕ отклоняется** (паритет с созданием): событие просто
  перейдёт в Этап 2 ближайшим тиком шедулера. Фронт предупреждает, но не блокирует.
- **Голоса Этапа 1 сохраняются** — правка не сбрасывает отклики.
- **Уведомления только при критичных изменениях (PO 2026-07-26):** рассылка уходит, если
  изменились **время** или **место** (адрес, гео-точка ИЛИ уточнение «как найти» — всё это
  отвечает на вопрос «куда идти»). Правка названия, описания, фото, лимита или интервала
  проходит **молча**: рассылка на весь клуб из-за опечатки в заголовке обесценивает
  уведомления как канал.
- **Живой закреп обновляется ВСЕГДА, даже при некритичной правке** (PO 2026-07-26): в нём
  висят название, дата и место, поэтому переименование он тоже обязан догнать. Делается тем
  же дебаунс-механизмом, что у голосов (`LivePinService.markDirty` → flush-проход):
  правки закрепа Telegram не уведомляют, поэтому это тихо по построению.
- **Как уведомляем** (как у отмены): громкий пост в чат клуба «было → стало» + DM всем
  участникам с доступом, кого пост не покрыл (`ChatAwareBroadcast`); живой закреп
  перерисовывается dirty-флагом (flush-проход). `EventEditedEvent` (несёт новое и старое
  состояние + признаки `isDatetimeChanged`/`isLocationChanged`/`hasCriticalChanges`) →
  `EventEditedListener` (AFTER_COMMIT, @Async, best-effort) — зеркало `EventCancelledListener`.
  Событие публикуется на ЛЮБУЮ правку; громкость выбирает слушатель.
- **Текст уведомления** зависит от того, что изменилось: только время → «📅 Встреча перенесена»,
  только место → «📍 Встреча меняет место», и то и другое → «📝 Встреча изменилась» с обеими
  парами «было → стало». Заголовок называет суть, чтобы не вчитываться.
- **Оформление (PO 2026-08-10):** между «где было» и «где стало» — пустая строка (адреса длинные,
  слипшись читаются одним абзацем); у события с гео-точкой к уведомлению добавляется кнопка
  «🗺 Открыть в Яндекс.Картах» — и в DM, и в посте чата клуба. Заканчивается уведомление
  призывом «Проголосуй в клубе!», а пост в чате получает кнопку «К событию» (нейтральная
  формулировка: на Этапе 2 голосование закрыто и ждут подтверждения).

### API контракт
```
PUT /api/events/{id}                (JWT; менеджер клуба-хоста — гейт в сервисе, как /cancel)
Body: UpdateEventRequest {
  title, description?, locationText?, locationLat?, locationLon?, locationHint?,
  eventDatetime, participantLimit?, stage2LeadMinutes?, photoUrl?
}
200 → EventDetailDto (с новыми значениями)
400 → дата в прошлом / нет места (ни точки, ни уточнения) / нарушен инвариант формата
403 → не менеджер клуба
404 → событие не найдено
409 → встречу нельзя изменить (Этап 2 начался / началось / завершено / отменено)
```
Гард — SQL-предикат в `updateEvent` (`JooqEventRepository`): 0 строк ⇒ 409. Условие по
`stage_2_triggered` закрывает гонку с flip'ом шедулера Stage2Service в параллельной транзакции.

> Отдельного `POST …/reschedule` больше нет: он жил только внутри этой ветки и был поглощён
> `PUT`, чтобы у даты не осталось двух путей изменения.

### Frontend
- `EventPage`: кнопка «Редактировать встречу» — менеджеру, только при `status = upcoming` (+
  `!eventHappened`, `!isCancelled`), над кнопкой «Отменить событие». Тап → шторка со всеми
  полями, предзаполненными текущими значениями (дата — в локальном поясе устройства).
- Место выбирается тем же `LocationPickerSheet`, что и при создании.
- Поле лимита не показывается у открытой встречи (формат неизменяем).
- Предупреждение в шторке (не блокирует): новая дата ближе эффективного `stage2LeadMinutes`
  → «подтверждение мест начнётся сразу после сохранения».
- Успех → тост «Изменения сохранены», инвалидация кэшей detail/myFeed/activities (как отмена).

### Acceptance Criteria
- **AC-EDT1:** GIVEN менеджер, событие `upcoming` (голосование) WHEN `PUT …` с новой датой
  THEN 200, `event_datetime` обновлён, голоса Этапа 1 сохранены, участники получили пост/DM
  «было → стало», закреп перерисован.
- **AC-EDT2:** GIVEN менеджер меняет ТОЛЬКО название/описание/фото/лимит WHEN `PUT …`
  THEN 200, изменения сохранены, **ни DM, ни поста в чат**; живой закреп при этом молча
  перерисовывается ближайшим flush-проходом и показывает новое название.
- **AC-EDT3:** GIVEN менеджер меняет место (адрес, точку ИЛИ уточнение) WHEN `PUT …`
  THEN 200 и уведомление «📍 Встреча меняет место» с «было → стало».
- **AC-EDT4:** GIVEN событие в `stage_2` (в т.ч. срочное с рождения) WHEN `PUT …`
  THEN 409, ничего не изменено, уведомлений нет.
- **AC-EDT5:** GIVEN не-менеджер клуба WHEN `PUT …` THEN 403, ничего не изменено.
- **AC-EDT6:** GIVEN дата в прошлом ИЛИ отсутствует место (ни точки, ни уточнения) WHEN `PUT …` THEN 400.
- **AC-EDT7:** GIVEN открытая встреча + `participantLimit` в теле ИЛИ встреча с местами без
  лимита ИЛИ срочная + `stage2LeadMinutes` WHEN `PUT …` THEN 400 (инвариант формата).
- **AC-EDT8:** GIVEN новая дата ближе интервала Этапа 2 WHEN правка сохранена THEN 200,
  ближайший тик шедулера переводит событие в `stage_2`.
- **AC-EDT9:** GIVEN участник открывает `EventPage` не-менеджером ИЛИ событие в `stage_2`
  THEN кнопка «Редактировать встречу» отсутствует.

## Тизер-афиша клуба (события для смотрящего без доступа)

> Фича PO 2026-07-24 (сессия «косяков», ветка `feature/access-month-stage2-18h`). Мотив:
> платный клуб до подтверждения взноса не показывал ВООБЩЕ никакого контента — «человек
> захочет что-то видеть прежде чем платить».

### Цель
Показать гостю и участнику без взноса (`frozen`/`expired`), что клуб живой: ближайшие и
прошедшие встречи в урезанной проекции. Главный аргумент вступить/оплатить — ритм жизни клуба.

### Правила
- **Кому:** любой аутентифицированный пользователь — это ЕДИНСТВЕННЫЙ событийный эндпоинт
  клуба без `@RequiresMembership`. Мягко удалённый клуб (`is_active = false`) → 404.
- **Что в проекции:** название, дата/время, статус, формат (`isUrgent`/`isOpenEvent`),
  счётчики голосов/подтверждений. **ПО ПОСТРОЕНИЮ отсутствуют:** место (`locationText`/
  координаты/уточнение), фото, лимит, состав участников — решение PO «Discovery только по
  клубам» (safety): место встречи не светится никому без доступа.
- **Отбор:** не-отменённые; ближайшие будущие (≤3, ближайшая первой) + последние прошедшие
  (≤3, недавняя первой) + `totalPastCount` (все прошедшие, семантика «Встреча №N»).
  Константы `TEASER_UPCOMING_LIMIT`/`TEASER_PAST_LIMIT` в `EventService`.

### API контракт
```
GET /api/clubs/{id}/events/teaser    (JWT; членство НЕ требуется)
200 → ClubEventsTeaserDto { upcoming: TeaserEventDto[], past: TeaserEventDto[], totalPastCount }
404 → клуб не найден или мягко удалён
```

### Frontend
- `ClubEventsTeaser` (components/club): секция «Афиша клуба» — строки «эмодзи-формат ·
  название · дата · счётчик» (фаза F5-21: до Этапа 2 «идут N», после — «подтвердили N»;
  прошедшие приглушены, «прошла»), под блоком подпись «🔒 Место встреч, голосование и участие
  откроются после …» (вступления / взноса — по зрителю). Fail-soft: пустая афиша/ошибка —
  блок не рендерится.
- `lockHint` **необязателен** (с 2026-07-30): не передан — строки-замка нет, и карточка сама
  держит отбивку до следующего блока. Так афиша стоит на посадочной приглашения, где ровно то же
  говорит плашка `ClubLockedNotice` сразу под ней.
- Встроена в ОБЕ бездоступные ветки `ClubPage` (гость — под замком, над CTA вступления;
  frozen/expired — под замком, над «Оплатить взнос») **и в посадочную приглашения `InvitePage`**
  (с 2026-07-30, без `lockHint`; см. [`club-invites.md`](./club-invites.md) § «Посадочная
  приглашённого»). У участников с доступом не показывается — у них полноценный таб «Активности».

### Acceptance Criteria
- **AC-TSR1:** GIVEN не-участник клуба с событиями WHEN `GET …/events/teaser` THEN 200,
  в ответе есть название/дата/счётчики и НЕТ полей места и фото; отменённые события отсутствуют.
- **AC-TSR2:** GIVEN запрос без JWT THEN 401.
- **AC-TSR3:** GIVEN frozen-участник платного клуба открывает страницу клуба THEN видит
  «Афишу клуба» между замком и кнопкой «Оплатить взнос»; гость — между замком и CTA вступления.
- **AC-TSR4:** GIVEN клуб без событий THEN секция афиши не отображается вовсе.
- **AC-TSR5:** GIVEN активный участник THEN тизер не отображается (виден полный таб «Активности»).

## Срочная встреча (событие без Этапа 1)

> Формат PO 2026-07-23 (ветка `feature/access-month-stage2-18h`). Для встреч «в ближайшие часы».

**Срочная встреча** — обычное событие с местами, но БЕЗ этапа голосования: рождается сразу
в `stage_2` (в транзакции `createEvent`: `transitionToStage2` + `stage_2_triggered = true`),
участники немедленно подтверждают места (Этап 2 открыт всем участникам клуба с PR #92 — прежний
голос не нужен). **Репутация — как у обычного события с местами** (подтверждение, неявка,
брошенный слот; решение PO). Дискриминатор — `events.is_urgent` (V69): на механику не влияет
(обрабатывается как событие, чей Этап 2 уже стартовал), нужен только подаче — бейджу формата
на карточках («⚡ срочная» / «🎟 обычная» / «🌊 открытая», ярлыки PO 2026-07-23; поле
`isUrgent` в `MyEventListItemDto` и `ActivityItemDto.EventActivity`).
Контракт: `CreateEventRequest.isUrgentEvent = true`; несовместим с `isOpenEvent` и
`stage2LeadMinutes` (`@AssertTrue isUrgentConsistent` → 400). DM создания зовёт «Подтверждайте
участие» вместо «Голосуйте» (ветка по `status = stage_2` в `sendEventCreated`). Вход: пикер «+» →
«Событие» → формат «⚡️ Срочная встреча»; форма обычного события при дате ближе 18 часов
предлагает переключиться кнопкой «Сделать срочной» (`?format=urgent`, состояние формы живёт).

## Открытая встреча (событие без лимита участников)

> Фича 2026-07-21 (ветка `feature/unlimited-event-participants`). Решения PO зафиксированы в этом разделе.

### Концепция
**Открытая встреча** — отдельный продуктовый тип события: приходят все, кто подтвердил,
дефицита мест нет. Технически это ТОТ ЖЕ движок событий (та же таблица, двухэтапка,
отметка явки, живой закреп); дискриминатор типа — `events.participant_limit IS NULL`
(миграция **V62** снимает `NOT NULL`; `CHECK participant_limit > 0` сохраняется — NULL
его проходит). Отдельной таблицы/шаблона НЕТ намеренно: отличий три (гонка за места,
штрафы, подача в UI), а общей машинерии — весь остальной жизненный цикл (KISS).

### Отличия от события с местами
| Аспект | С местами (`participant_limit = N`) | Открытая встреча (`NULL`) |
|---|---|---|
| Этап 1 (голосование) | без изменений | без изменений |
| Этап 2: подтверждение | гонка за места: `confirmedCount < limit` → confirmed, иначе waitlisted | **каждый подтвердивший — confirmed**; waitlist недостижим |
| Лист ожидания / промоут / DM «освободилось место» | работает | мёртвая ветка (очередь всегда пуста) |
| Отказ confirmed: порог | нельзя позже `T − late-decline-threshold-minutes` | **порога нет** — отказ до самого старта (окно всё равно закрывается стартом события) |
| Отказ confirmed: штраф `abandoned_slot` (−100) | если waitlist пуст | **не начисляется никогда** |
| `confirmedDeclineDeadline` в `EventDetailDto` | `eventDatetime − cutoff` | **= `eventDatetime`** (фронт не хардкодит различие) |
| Отметка явки организатором | без изменений | **без изменений** — питает «Историю» посещённых и факты клуба |
| Репутация (все виды: посещение, неявка, споры, XP) | работает целиком | **ВНЕ репутации ЦЕЛИКОМ** — конвейер пропускает событие, ни одной ledger-строки (решение PO 2026-07-21, итерация 2, см. ниже) |
| Exit-with-obligations (выход из клуба с confirmed-бронью) | `no_show` −200 | **штраф не начисляется и в превью выхода не считается**; при этом событие остаётся в перечислении обязательств (`EventObligation.isOpenEvent`) — каскад удаления строк, advisory-лок слотов и перерисовка живого закрепа (`EventRosterChangedEvent`) работают как для всех |

### Репутация: полный ноль (итерация 2, PO 2026-07-21)
- **Открытая встреча ВНЕ репутации целиком**: `ReputationService.processFinalizedEvent`
  пропускает событие с `EventReputationContext.isOpenEvent` — ни одной ledger-строки ни за
  какой исход (посещение, неявка, спор). Событие при этом клеймится — поллер к нему не
  возвращается. Отметка явки питает только «Историю» посещений и статистику клуба.
- **Почему не первоначальный `open_no_show` −100 за молчаливую неявку**: анти-фарм уже
  запретил плюсы за посещение, и оставался «кнут без пряника» — лучший исход подтверждения
  был 0, худший −100, т.е. подтверждать открытую встречу было рационально невыгодно. Плюс
  риск ошибочных отметок «не пришёл» на больших сборищах (орг не знает всех в лицо).
- **Нужна обязательность без дефицита** — организатор создаёт «С местами» с большим лимитом:
  там репутация работает целиком (+100/−200).
- Вид `open_no_show` (V63) **зарезервирован и не выдаётся** (как `skladchina_declined`):
  классификация BROKE и −100 сохранены в коде под возможный будущий «строгий режим»
  (галочка «подтверждение обязывает» при создании). Комментарий колонки актуализирован
  миграцией **V64** (файлы V62/V63 после публикации не редактируются — checksum).

### API
- `CreateEventRequest.participantLimit: Int?` — `null` = открытая встреча (`@Positive`
  пропускает null; `@NotNull` снят). Не-null валидируется как раньше (> 0).
- `CreateEventRequest.isOpenEvent: Boolean = false` — **явный флаг формата**. Инвариант
  (`@AssertTrue isParticipantLimitConsistent`): `isOpenEvent=true ⇔ participantLimit=null`.
  Случайно пропущенное поле лимита (без флага) по-прежнему 400, как до V62 — формат
  не может смениться молча из-за ошибки сериализации клиента. В БД флаг не хранится:
  источник истины — `participant_limit IS NULL`.
- `EventDetailDto/EventListItemDto/MyEventListItemDto/ActivityItemDto.EventActivity/ConfirmResponseDto`
  — `participantLimit: Int?` (null = открытая встреча).

### UI (решения PO 2026-07-21)
- **Создание**: в глобальном «+» тап на «Событие» открывает шаг **выбора формата**
  (зеркалит «Сбор → тип сбора»): «С местами» / «Открытая встреча». Формат открытой
  встречи ведёт на ту же `CreateEventPage` с `?format=open` — степпер лимита скрыт.
- **Название типа в интерфейсе**: «Открытая встреча» (бейдж хиро на `EventPage`,
  подпись счётчиков, DM бота).
- Счётчики без знаменателя: «Состав · N», «Набор · N», в карточках `N` вместо `N/limit`;
  пончик при финальном составе — заполненное кольцо (ёмкости нет).
- **Отметка явки** (только для открытой встречи): под списком — поясняющая строка оргу,
  что отметка нужна только для истории посещений и статистики клуба — репутация участников
  не меняется ни в какую сторону (итерация 2: прежнее предупреждение о −100 удалено).
- Диалог отказа confirmed: предупреждение о списании не показывается (отказ свободный).
  У события с лимитом величина штрафа в тексте диалога берётся из
  `EventDetailDto.abandonedSlotPenaltyPoints` (источник — ReputationPolicy на бэке;
  фикс PO 2026-07-21 — фронт число не хардкодит, как и порог отказа).
- Строка-мотиватор «путь назад» («придёте — надёжность вырастет») на открытой встрече
  скрыта: посещение репутацию не начисляет, обещание было бы ложным.
- Пончик состава при 0 подтвердивших — нейтральный (как Этап 1 без голосов), не «заполненный».
- **Карточка участника — блок «Активность в клубе»** (мокап B1,
  `docs/design/open-events/mockups/02-member-card-other-stats.html`, выбор PO 2026-07-21):
  отдельный glass-блок под репутационными кольцами для ВНЕрепутационных метрик. Строки:
  «🌊 Открытые встречи — пришёл X из Y + % прихода» (знаменатель — только confirmed-брони
  открытых встреч с выясненной явкой: attended/absent; NULL и disputed не считаются) и
  «⚡ Спонтанные визиты» (переехали из футера колец; гейт F5-08 сохранён). Данные per-club.
  Видимость — асимметрия AC-5 (только орг и сам о себе: `MemberProfileDto.openEventsAttended/
  openEventsTotal`, null чужому), но порог «Новичка» НЕ применяется — блок виден и у новичка.
  На XP/Trust/заявки не влияет никак (в ledger не пишется).
- Бот/закреп: «👥 Лимит: N» → «👥 Открытая встреча — без репутации и лимита мест»;
  «✅ Подтвердили — N из L» → «✅ Подтвердили — N». Напоминание оргу об отметке явки у
  открытой встречи мотивирует историей/статистикой клуба, не репутацией.
- **Бейджи типа на карточках** (PO 2026-07-21, вторая волна правок): вместо родового «СОБЫТИЕ» —
  «С МЕСТАМИ» / «ОТКРЫТАЯ ВСТРЕЧА» на обложке в глобальной ленте (`feed/EventCard`); на карточке
  активностей страницы клуба (`manage/ActivityCard`) — постоянный нейтральный чип
  «С местами» / «Открытая встреча» (раньше типа не было вовсе).
  > С 2026-08-01 формат виден и на самой странице события: «⚡ СРОЧНАЯ ВСТРЕЧА» /
  > «🌊 ОТКРЫТАЯ ВСТРЕЧА» / «🎟 ВСТРЕЧА С МЕСТАМИ» (`isUrgent` добавлен в `EventDetailDto`);
  > см. [`event-vote-block.md`](event-vote-block.md) § «Бейдж формата в хиро».
- Пикер формата: сабтайтл «С местами» = «Лимит участников, репутация и лист ожидания».
- **Профиль — блок «Статистика»** (мокап P3,
  `docs/design/open-events/mockups/03-profile-total-visits.html`, выбор PO): строки
  «🎟 Всего посетил событий» (все attended-отметки по всем клубам) и «🌊 Из них открытых встреч».
  Данные `MyReputationDto.visits` (сырые отметки, вне репутации); блок скрыт при нуле посещений.
- Карточка участника: роль переехала из футера колец в шапку (рядом с «в клубе с …»).

### Acceptance Criteria
- **AC-OPEN1:** GIVEN открытая встреча в `stage_2`, подтвердились `limit+…` любое число участников WHEN каждый жмёт «Подтвердить» THEN все получают `confirmed`, `waitlisted` не появляется ни у кого.
- **AC-OPEN2:** GIVEN подтверждённый участник открытой встречи, до старта < 4ч WHEN он отказывается THEN отказ проходит (порога нет) и НЕ создаёт ledger-строку `abandoned_slot`.
- **AC-OPEN3:** GIVEN открытая встреча с отмеченной явкой (любой набор attended/absent/не выяснено среди confirmed) WHEN репутация финализируется THEN в леджере НОЛЬ строк по этому событию (любых видов), событие клеймится и в pending-поллинг не возвращается.
- **AC-OPEN4:** GIVEN участник с confirmed-бронью открытой встречи WHEN выходит из клуба THEN exit-штраф `no_show` за эту бронь НЕ начисляется.
- **AC-OPEN5:** GIVEN обычное событие (limit = N) WHEN проходит весь жизненный цикл THEN поведение идентично поведению до фичи (регрессии нет).
- **AC-OPEN6:** GIVEN организатор открытой встречи WHEN открывает отметку явки THEN видит пояснение, что отметка влияет только на историю посещений и статистику клуба, не на репутацию.
- **AC-OPEN7:** GIVEN участник с confirmed-бронями открытых встреч (attended, absent, неотмеченная, disputed) и attended-бронью события с лимитом WHEN организатор или сам участник открывает его карточку THEN блок «Активность в клубе» показывает «пришёл A из R», где R = только attended+absent открытых встреч (неотмеченные/спорные/лимитированные не считаются); чужой участник блока открытых встреч не видит (поля null).

## Шаблоны встреч (заготовки формы создания)

> Фича 2026-08-12, PR #142 (ветка `feature/event-templates`). Полная спека —
> [`event-templates.md`](./event-templates.md); здесь только точка стыковки с движком событий.

Клуб держит именованные заготовки (таблица `event_templates`, V79), которые хранят всё
содержимое формы создания **кроме даты**: название, описание, фото, место с точкой на карте,
уточнение к месту, лимит, формат и интервал Этапа 2. Вместо даты шаблон помнит день недели
и время, и форма подставляет ближайшее будущее совпадение.

**Движок событий фича не трогает вообще.** Применение шаблона — это предзаполненная форма
и тот же `POST /api/clubs/{id}/events`: DM участникам, двухэтапка, репутация, живой закреп
работают ровно как при создании с нуля. Отдельного статуса, поля в `events` и ветки в
жизненном цикле у «встречи из шаблона» нет — по построению её нельзя отличить от обычной.

Права — `MANAGE_EVENTS`, то же, что на создание встречи (см. [`club-roles.md`](./club-roles.md)).
