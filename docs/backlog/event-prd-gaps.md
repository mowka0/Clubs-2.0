# Event module — PRD ↔ implementation gaps

Зафиксированы 2026-05-13 в ходе рефакторинга `feature/refactor-event`. Все — pre-existing на момент рефакторинга, ни один не блокер.

---

## GAP-1: `myVote` поле в `EventDetailDto` обещано, но не реализовано

**Где обещано:** `docs/modules/events.md:138-139` —
> «После реализации TASK-014 `EventDetailDto` должен включать поле `myVote: String?` (если вызывающий пользователь голосовал). Поле null если не голосовал или не участник.»

**Реальность:**
- `EventDetailDto` поля `myVote` не имеет.
- Фронт получает голос через отдельный endpoint `GET /api/events/{id}/my-vote → MyVoteDto { vote: String? }`.

**Impact:** небольшой — лишний round-trip на фронте при открытии страницы события.

**Решение в backlog:**
- Либо добавить `myVote` в `EventDetailDto` (1 поле + JWT user в `EventService.getEvent`) и вычистить из спеки обещание про отдельный endpoint, переведя `/my-vote` в deprecated.
- Либо удалить устаревшее обещание из спеки и оставить отдельный endpoint.

---

## GAP-2: PRD называет поля `stage_1_status` / `stage_2_status`, в БД — `stage_1_vote` / `stage_2_vote`

**Где:** PRD `§5.1` (lines 501, 503) описывает таблицу `event_responses`:
```
- stage_1_status: enum (going, maybe, not_going) — nullable
- stage_2_status: enum (confirmed, declined, waitlisted) — nullable
```

**Реальность (БД, миграция `V5__create_events.sql`):** колонки называются `STAGE_1_VOTE` и `STAGE_2_VOTE`. Соответственно jOOQ-сгенерированные `EVENT_RESPONSES.STAGE_1_VOTE` / `STAGE_2_VOTE` и domain-поля `stage1Vote` / `stage2Vote`.

**Impact:** только документационный — у читателя PRD возникнет confusion при чтении кода.

**Решение в backlog:** обновить PRD §5.1 lines 501, 503 заменив `*_status` на `*_vote` (или наоборот — переименовать БД, но это значительная миграция). Рекомендуется первый вариант, т.к. БД-имена устоялись и используются в коде, миграциях, тестах.

---

## GAP-3: `EventStatus.stage_1` объявлен в enum, но никогда не присваивается

**Где обещано:** PRD `§5.1` line 493:
```
status: enum (upcoming, stage_1, stage_2, completed, cancelled)
```

**Реальность:**
- Enum `EventStatus` (в БД и jOOQ) содержит значение `stage_1`.
- Код **никогда** не переводит событие в `stage_1` — переход идёт `upcoming → stage_2` напрямую в `Stage2Service.transitionToStage2`.
- `EventRepository.findNextUpcomingEvent` фильтрует по `IN (upcoming, stage_1, stage_2)` для устойчивости к данным, которые могли бы появиться, но фактически `stage_1` в проде не существует.

**Impact:** ноль на поведение, но dead-state в enum.

**Решение в backlog:**
- Либо реализовать stage_1 как промежуточный статус (например, «голосование открыто, до Stage 2 ещё больше 24h»), как намекает PRD §4.4.1.
- Либо удалить значение из enum + миграция (значительный chore, не приоритетный).

---

## GAP-4 (BUG): окно оспаривания считается от `event_datetime`, не от момента markAttendance

> ✅ **ИСПРАВЛЕНО** в `bugfix/attendance-dispute-integrity` (2026-06-13, = ATT-1/F5-05).
> Колонка `events.attendance_marked_at` (миграция V24), `markAttendance` её проставляет,
> `finalizeAttendanceBefore` гейтит по `COALESCE(attendance_marked_at, event_datetime)`.
> Реализация теперь PRD-совместима. См. `events.md` § «Окно спора отсчитывается от момента отметки».

**Где обещано:** PRD `§4.4.3` line 271:
> «Окно оспаривания: 48 часов с момента сохранения отметок.»

**Реальность:** `AttendanceService.finalizeAttendance` (cron каждый час):
```kotlin
val cutoff = OffsetDateTime.now().minusHours(DISPUTE_WINDOW_HOURS)
eventRepository.finalizeAttendanceBefore(cutoff)
```
а `finalizeAttendanceBefore` сравнивает `cutoff` с `EVENTS.EVENT_DATETIME`. То есть финализация происходит через 48ч после **самого события**, а не через 48ч после `markAttendance`.

**Impact:** ⚠️ **поведенческий баг**.
- Если организатор поставил отметки сразу после события — поведение PRD-совместимо.
- Если организатор отметил через 5 дней после события (а PRD это разрешает — нет дедлайна на отметку) — финализация и пересчёт репутации случатся **сразу же на следующем cron-запуске**, без 48ч-окна оспаривания. Участники не успеют оспорить.

**Решение в backlog (bugfix):**
- Добавить колонку `events.attendance_marked_at: timestamp` (миграция).
- В `markAttendance` проставлять значение.
- В `finalizeAttendanceBefore` сравнивать `cutoff` с `EVENTS.ATTENDANCE_MARKED_AT`, а не `EVENTS.EVENT_DATETIME`.

---

## GAP-5: бот шлёт уведомление о новом событии в DM, а не в группу клуба

**Где обещано:** PRD `§4.4.1` line 210:
> «Бот отправляет уведомление в группу: «Новая активность: [Название]. Нужно [N] человек. Отметь свой статус».»

**Реальность:** `NotificationService.sendEventCreated` шлёт DM каждому активному участнику клуба.

**Status:** уже зафиксировано в `docs/backlog/telegram-bot-prd-gaps.md` (бот-модуль), здесь cross-link для полноты event-картины.

---

## ~~GAP-6~~: Stage 2 уведомление таргетит **всех** проголосовавших, не только going+maybe — **ЗАКРЫТ 2026-06-13**

**Где обещано:** PRD `§4.4.2` line 233:
> «Система отправляет уведомление ВСЕМ из статуса «Пойду»: ...»

**Реальность (была):** `EventResponseRepository.findResponderTelegramIdsByEventId` фильтровал только по `event_id`, без filter по `STAGE_1_VOTE` — юзер с голосом `not_going` тоже получил бы DM «Этап 2 начался — подтвердите участие».

**Status:** ✅ закрыт (`bugfix/stage2-dm-and-slot-races`, 2026-06-13): метод переименован в `findStage2TargetTelegramIds`, фильтр `stage_1_vote IN (going, maybe)`; тем же PR рассылка при старте Этапа 2 подключена (= GAP-004/GAP-009 в `docs/backlog/telegram-bot-prd-gaps.md`, S2T-2 в реестре багов).

---

## GAP-7: два разных «списка посещений» с разным составом

**Зафиксирован** 2026-07-20 в pre-flight ветки `feature/events-history` (F-1).

**Где обещано:** `PRD-Clubs.md:201` (§4.3.2 «Профиль участника»):
> «История посещений (список событий с отметками: **был / не был**).»

**Реальность:**
- Эта PRD-строка **не реализована нигде** — ни в `MemberProfileModal`, ни в глобальном Профиле.
- Решение PO 2026-07-20 для секции «История» в «Активности → События»: состав —
  **только `attendance = 'attended'`**, «не был» в историю не попадает
  (спека: `docs/modules/events-feed.md` § «Итерация 5»).

**Impact:** формального противоречия нет — PRD описывает историю **в профиле участника**,
фича даёт историю **в глобальной ленте событий**. Но это заготовка под расхождение: если
профильную историю когда-нибудь реализуют буквально по PRD, в продукте будут два списка
с одинаковым названием «История посещений» и разным составом.

**Решение (нужен ответ PO):**
- A) Единый источник: профильная история тоже показывает только `attended` → поправить PRD:201.
- B) Два намеренно разных среза: лента = «куда я ходил» (мотивирующая), профиль =
  «был / не был» (полная, для оценки надёжности) → PRD оставить, но явно развести
  формулировки, чтобы совпадение названий не читалось как баг.

**PRD не правился** — правка PRD только с явного согласия PO.

---

## Итог

| GAP | Severity | Тип | Куда фиксить |
|-----|----------|-----|--------------|
| GAP-1 myVote | Low | docs/feature | event-фича или docs |
| GAP-2 stage_1_status naming | Low | docs | PRD |
| GAP-3 stage_1 enum dead value | Low | feature/chore | event-фича или миграция |
| **GAP-4 dispute window bug** | **Medium** | **bugfix** | **event-bugfix (миграция + код)** |
| GAP-5 DM vs group notification | Medium | feature | bot-фича |
| ~~GAP-6~~ Stage 2 target audience | Medium | bugfix | ✅ закрыт 2026-06-13 (`bugfix/stage2-dm-and-slot-races`) |
| **GAP-7 два «списка посещений»** | Low | docs/decision | **PRD:201 — нужен ответ PO** |

Рефакторинг `feature/refactor-event` намеренно не трогает ни один из этих gap'ов — behavior-preserving.
