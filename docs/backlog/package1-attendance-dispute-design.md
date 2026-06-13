# Дизайн — Пакет 1 F5: attendance/dispute integrity (LOCKED)

> Итог дизайн-сессии 2026-06-13 (ultracode: 4 читателя + 9 адверсариальных скептиков + синтез,
> сверено на `f73b249`). Три продуктовых решения **сняты** (все = вариант A). Это спецификация
> для фазы кодинга — один backend-PR `bugfix/attendance-dispute-integrity` по bugfix-флоу.
>
> Предшественник: `docs/backlog/package1-attendance-dispute-handoff.md`.
> Реестр: `docs/backlog/two-stage-reputation-bug-register.md` § «Аудит F5».

---

## 0. Снятые продуктовые решения

| # | Решение | Выбор | Следствие |
|---|---|---|---|
| **(б)** ATT-1/F5-05 — базис окна спора | **A: от момента отметки** | колонка `events.attendance_marked_at` в V24; finalize гейтит по `COALESCE(marked_at, event_datetime)` |
| **F5-04** — кто может оспорить | **A: по участию** | новый `GET /api/events/{id}/my-attendance` без `isMember`-гейта; `−200` остаётся participation-scoped |
| **F5-16** — терминальность резолва | **A: финален** | колонка `event_responses.dispute_terminal` в V24; спор без appeal |

---

## 1. Целевая стейт-машина

Состояние строки роутера = `EVENT_RESPONSES.attendance ∈ {NULL, attended, absent, disputed}`
(только на `final_status='confirmed'`) + `event_responses.dispute_terminal` + флаги на `EVENTS`:
`attendance_marked`, `attendance_marked_at`, `attendance_finalized`.
**Окно спора открыто, пока `attendance_marked=true AND attendance_finalized=false`**, а его часы
считаются от `attendance_marked_at`.

```
                  event_datetime прошёл, organizer = club.ownerId
   [NULL] ───────────────── markAttendance ─────────────────► [attended] / [absent]
          guard (сервис): !event.attendanceFinalized, now > event_datetime
          setAttendance (per-row UPDATE на EVENT_RESPONSES):
              SET attendance = attended|absent, dispute_terminal = false        ← F5-16 reset
              WHERE event_id=? AND user_id=? AND final_status='confirmed'
                AND attendance IS DISTINCT FROM 'disputed'                       ← F5-15(1)
                AND event_id IN (SELECT id FROM events
                                 WHERE id=? AND attendance_finalized=false)      ← F5-09 durable
          markAttendanceMarked (UPDATE на EVENTS):
              SET attendance_marked=true, attendance_marked_at=now()            ← решение (б)=A
              WHERE id=? AND attendance_finalized=false                          ← F5-09
              → 0 строк ⇒ throw ValidationException("Attendance has been finalized")
          publish AttendanceMarkedEvent(eventId, newlyAbsentUserIds)            ← F5-15(2)

   [absent] ──── disputeAttendance (участник, СВОЯ строка) ────► [disputed]
          guard (сервис): !event.attendanceFinalized, event.attendanceMarked
          disputeAbsentAttendance (UPDATE на EVENT_RESPONSES):
              SET attendance='disputed', dispute_note=?
              WHERE event_id=? AND user_id=? AND attendance='absent'
                AND dispute_terminal=false                                       ← F5-16 (load-bearing)
                AND event_id IN (SELECT id FROM events
                                 WHERE id=? AND attendance_finalized=false)      ← F5-10 (ordering A)
              → 0 строк ⇒ throw ValidationException("Спор по этой отметке уже рассмотрен")
          publish AttendanceDisputedEvent(eventId, userId)

   [disputed] ── resolveDispute(attended) (owner) ──► [attended] + dispute_terminal=true
   [disputed] ── resolveDispute(absent)   (owner) ──► [absent]   + dispute_terminal=true   ← F5-16
          guard (сервис): !event.attendanceFinalized
          publish: НИЧЕГО (репутация не трогается до finalize)

   [disputed] ── finalizeAttendance / ATT-2 (та же txn) ──► [absent] + dispute_terminal=true
          resolveExpiredDisputesToAbsent(ids): disputed→absent WHERE final_status='confirmed'
          ⇒ reputation читает absent ⇒ no_show = −200
```

### Финализация — две непересекающиеся ветки

```
finalizeAttendance @Scheduled hourly @Transactional:
  finalizeAttendanceBefore(cutoff = now − disputeWindowMinutes):
     UPDATE events SET attendance_finalized=true
     WHERE attendance_marked=true AND attendance_finalized=false AND status<>'cancelled'
       AND COALESCE(attendance_marked_at, event_datetime) <= cutoff        ← решение (б)=A
     RETURNING id
  → ATT-2 resolveExpiredDisputesToAbsent(ids)   [disputed→absent + dispute_terminal=true, та же txn]
  → publish AttendanceFinalizedEvent(id) → reputation (AFTER_COMMIT + hourly backstop)

neutrallyFinalizeUnmarkedEvents @Scheduled hourly @Transactional (EXP-2):
  neutrallyFinalizeUnmarkedBefore(cutoff = now − autoFinalizeUnmarkedMinutes):
     UPDATE events SET attendance_finalized=true
     WHERE attendance_marked=false AND attendance_finalized=false AND status<>'cancelled'
       AND event_datetime <= cutoff               ← БАЗИС НЕ МЕНЯЕМ (остаётся event_datetime)
     RETURNING id
  → publish НИЧЕГО → ledger пуст (by design)
```

### Где сериализация против финалайзера физически

- **mark × neutral-finalize (F5-09):** `markAttendanceMarked` и `neutrallyFinalizeUnmarkedBefore`
  пишут в **одну строку `EVENTS`**. Под READ COMMITTED второй писатель блокируется на row-lock'е
  первого и переоценивает `WHERE` против свежей версии → проигравший получает 0 строк ⇒ throw.
  **Новый advisory lock не нужен** (его пришлось бы ретрофитить в оба `@Scheduled`-финалайзера).
- **dispute × finalize (F5-10):** `disputeAbsentAttendance` и финалайзер контендят на строке
  `EVENT_RESPONSES`; row-lock сериализует записи. Подзапрос `event NOT finalized` — это *read*
  на `EVENTS` (без `FOR UPDATE`), он закрывает **только ordering A** (флаг уже видимо закоммичен).
  **Ordering B** (спор коммитит до ATT-2 в той же finalize-txn) остаётся на бэкстопе **ATT-2**
  (`disputed→absent`, −200). Под решением (б)=A это микросекундная граница ровно на cutoff'е
  `marked_at+window` — исход −200 защитим (окно реально истекло). **ATT-2 удалять/рассинхронизировать
  нельзя** — зафиксировать комментарием, что это бэкстоп ordering B.

---

## 2. Согласованный список изменений (по нодам)

Порядок проверки: read-side проекции и предикаты, ни один не вводит новый advisory lock.

### F5-06 — утечка `dispute_note` (read-only)
- `VoteService.getEventResponders`: инжектить `ClubRepository`;
  `val isOwner = clubRepository.findById(event.clubId)?.ownerId == userId`;
  в маппинге `disputeNote = if (isOwner) r.disputeNote else null`.
- **Ключевать строго на `club.ownerId`**, не на `event.createdBy`. SQL `SELECT DISPUTE_NOTE`
  не трогаем (owner'у нота нужна), нуллим в маппинге. DTO `EventResponderDto.disputeNote` без изменений.
- Полностью закрывается бэкендом; фронт `EventPage.tsx:523` отрендерит `null` → ничего.

### F5-09 — mark × finalize lost-update
- `markAttendanceMarked`: сигнатура `Unit → Int`; добавить `.and(EVENTS.ATTENDANCE_FINALIZED.eq(false))`;
  в `markAttendance` при 0 строк → throw `ValidationException` (полный rollback `@Transactional`,
  включая `setAttendance`-записи).
- **Durable-форма (обязательна):** тот же `event NOT finalized` подзапросом внутрь `setAttendance`
  (на уровне `EVENT_RESPONSES`), чтобы каждая запись была независимо безопасна. Попутно закрывает
  post-normal-finalize re-mark desync, который ATT-4 (stale `findById`) ловит лишь частично.
- **Caution:** дешёвая форма (guard только на `markAttendanceMarked`) корректна сегодня лишь потому,
  что `markAttendance` — единая `@Transactional` без `REQUIRES_NEW`. Durable-форма снимает эту хрупкость.

### F5-15 — повторная отметка (два независимых под-фикса)
- **(1) Защита disputed от re-mark:** `setAttendance` + `.and(ATTENDANCE.isDistinctFrom(disputed))`.
  **Только `IS DISTINCT FROM`, не `<>`/`ne()`** — первая отметка имеет `attendance=NULL`,
  `NULL <> 'disputed' = NULL` пропустит строку и сломает happy-path. disputed-строка → 0 строк,
  исключена из `markedCount`; единственный путь её мутации — `resolveDispute`.
- **(2) Прекратить DM-blast:** `AttendanceMarkedEvent(eventId)` → `AttendanceMarkedEvent(eventId,
  newlyAbsentUserIds: List<UUID>)` (собрать `userId` где `!attended` И `setAttendance` вернул > 0);
  `sendAttendanceMarked` DM'ит только этот набор. `userId` известны синхронно до AFTER_COMMIT-листенера
  → fresh-connection visibility hazard не реоткрывается.
- **НЕ** добавлять blanket `attendance_marked`-guard, отклоняющий все вторые отметки — корректирующие
  re-mark до финализации легитимны (ATT-4 рисует линию заморозки на `finalized`, не на `marked`).

### F5-16 — терминальность resolve(absent)
- `resolveDisputedAttendance`: `SET ... dispute_terminal=true` (на любой резолв — audit-симметрия;
  для resolve→attended флаг безвреден, строка всё равно выходит из absent-предиката).
- `resolveExpiredDisputesToAbsent` (ATT-2): тоже `dispute_terminal=true` (belt-and-suspenders).
- `disputeAbsentAttendance`: `+ AND dispute_terminal=false` — **load-bearing DB-guard**
  (у dispute-эндпоинта ноль авторизации сверх JWT, UI-гейта недостаточно).
- `setAttendance`: `dispute_terminal=false` на каждой свежей отметке (**reset обязателен** — иначе
  re-mark в свежий absent унаследует stale `true` и станет неоспоримым).
- **Boolean, НЕ enum-значение:** 4-е значение `attendance_status` сломало бы
  `ReputationPolicy.attendanceKind` (молчаливый дроп из ledger) и необратимо.

### F5-10 — dispute × finalize TOCTOU
- `disputeAbsentAttendance`: подзапрос `AND event_id IN (SELECT id FROM events WHERE id=?
  AND attendance_finalized=false)`. Существующий `updated==0 → ValidationException` покрывает промах.
- **Честный scope:** закрывает ordering A (устраняет spurious dispute + ложный organizer-DM).
  Ordering B → бэкстоп ATT-2. **Не** добавлять `status<>'cancelled'` в подзапрос без отдельной
  сверки cascade-ожиданий.

### F5-17 — reminder для нейтрально-финализированного события
- `findEventsNeedingAttendanceReminder`: `+ .and(EVENTS.ATTENDANCE_FINALIZED.isFalse)`.
  Строго субтрактивно: убирает только `finalized=true`, которые `markAttendance` всё равно отклонит.

### F5-04 — доступ к спору (новый эндпоинт + ослабление гейта)
- **Бэкенд:** новый `GET /api/events/{id}/my-attendance` → вернуть состояние СВОЕЙ строки
  (`attendance`, `disputeTerminal`, `disputeWindowOpen`, `canDispute`, `disputeNote` своей строки)
  **без `isMember`-гейта**, скоупнуто на `userId` вызывающего. 404 если нет `event_response`.
  Это даёт DM-deep-link живую кнопку для вышедшего из клуба.
- `getEventResponders` остаётся member-only (F5-06 не расширяется).
- Сам dispute-эндпоинт (`POST`) уже работает для не-членов (0-rows guard скоупит на свою absent-строку) —
  проверить и убрать лишний `isMember`, если он там есть (см. §3, требует сверки контроллера).
- **Фронт (минимально, в этом же PR — иначе F5-04 не тестируется end-to-end):** `EventPage`
  считает `canDispute` из `/my-attendance`, а не из member-gated роутера. Косметику резолва (текст
  «спор отклонён») можно оставить пакету 4.

---

## 3. Миграция V24

`V24__add_attendance_marked_at_and_dispute_terminal.sql` — идемпотентные `ADD COLUMN IF NOT EXISTS`
(паттерн V19/V21/V23). После добавления: `./gradlew generateJooq`.

```sql
-- решение (б)=A: часы окна спора от момента отметки
ALTER TABLE events ADD COLUMN IF NOT EXISTS attendance_marked_at TIMESTAMPTZ;

-- решение F5-16=A: терминальность резолва спора
ALTER TABLE event_responses ADD COLUMN IF NOT EXISTS dispute_terminal BOOLEAN NOT NULL DEFAULT FALSE;
```

| Колонка | Таблица | Тип | Default | Backfill |
|---|---|---|---|---|
| `attendance_marked_at` | `events` | `TIMESTAMPTZ` (nullable) | — | **не нужен.** Finalize использует `COALESCE(attendance_marked_at, event_datetime)` — legacy marked-but-not-finalized строки остаются на старом базисе, без регрессии. Бэкфилл из `updated_at` НЕ делать (`updated_at` мутируется при любом редактировании). |
| `dispute_terminal` | `event_responses` | `BOOLEAN NOT NULL` | `FALSE` | **не нужен.** `DEFAULT FALSE` на исторических строках = безопасно (только добавляет блок). Старые resolved+finalized строки защищены `attendance_finalized`. |

- **`attendance_marked_at` — на `EVENTS`, не на `EVENT_RESPONSES`:** отметка атомарна (один
  `markAttendanceMarked`), окно и finalize-scan — event-level. Per-row = write-storm без надобности (YAGNI).
- **Индекс не нужен:** finalize-scan фильтрует `marked=true AND finalized=false` (крошечный набор)
  до timestamp'а. Partial index `ON events(attendance_marked_at) WHERE attendance_marked AND NOT
  attendance_finalized` — только если poll всплывёт в slow-query logs.
- **Деплой-ордеринг:** предикат `COALESCE(...)` едет в ТОМ ЖЕ релизе, что и колонка (Flyway-before-app
  гарантирует).

---

## 4. Матрица тестов на гонки (Testcontainers postgres:16, паттерн `Stage2SlotRaceIntegrationTest`)

| Тест | Сетап | Утверждает |
|---|---|---|
| **mark × neutral-finalize (F5-09)** | event past, `marked=false`, `finalized=false`; neutral-finalize коммитит между TOCTOU-read и `markAttendanceMarked` | `markAttendance` бросает `ValidationException`; финал `{marked=false, finalized=true, attendance=NULL}`; `setAttendance`-записи откачены |
| **mark × normal-finalize re-mark (F5-09 durable)** | `marked=true`, прошло окно, `finalizeAttendance` флипнул `finalized=true`; повторный `markAttendance` | `markAttendanceMarked` → 0 строк → throw; `setAttendance` НЕ переписывает frozen-ledger строку |
| **dispute × finalize ordering A (F5-10)** | `marked=true`; `finalizeAttendanceBefore` коммитит `finalized=true` ДО старта dispute-statement | `disputeAbsentAttendance` → 0 строк; dispute отклонён; `AttendanceDisputedEvent` НЕ публикуется |
| **ordering B (F5-10, документ не assert)** | dispute коммитит `disputed` до ATT-2 в той же finalize-txn | задокументировать: ATT-2 конвертит `disputed→absent` (−200); не assert'ить без `FOR UPDATE` |
| **re-mark over disputed (F5-15.1)** | `attendance='disputed'`; повторный `markAttendance` | `setAttendance` → 0 для этой строки; спор сохранён; исключена из `markedCount` |
| **first-mark NULL регрессия (F5-15.1)** | `attendance=NULL`, первая отметка | `setAttendance` записывает (`IS DISTINCT FROM 'disputed'` = TRUE для NULL); happy-path цел |
| **DM only newly-absent (F5-15.2)** | вторая корректирующая отметка, часть строк уже absent | `AttendanceMarkedEvent` несёт только свежий `absent`; ранее-уведомлённые не пере-DM'ятся |
| **repeat-dispute after resolve (F5-16)** | resolve-to-absent (`dispute_terminal=true`); участник пытается переоспорить | `disputeAbsentAttendance` (`AND dispute_terminal=false`) → 0 → throw «уже рассмотрен» |
| **re-mark re-opens dispute (F5-16 reset)** | resolved-to-absent (`dispute_terminal=true`); орг делает свежую отметку absent | `setAttendance` сбрасывает `dispute_terminal=false`; последующий dispute снова проходит |
| **window-from-marked_at (б=A)** | `event_datetime=now−47h`, отметка `now()` (`marked_at=now`), `window=48h`; запуск `finalizeAttendance` | НЕ финализируется (`COALESCE(marked_at, event_datetime) > cutoff`); legacy `marked_at=NULL` финализируется по `event_datetime` |
| **reminder excludes neutral-finalized (F5-17)** | `marked=false`, `finalized=true`, past-cutoff, confirmed-роутер | `findEventsNeedingAttendanceReminder` НЕ возвращает; регресс-guard: `finalized=false` ВСЁ ЕЩЁ возвращается |
| **F5-06 owner-only note** | owner и non-owner-member читают `/responses` | non-owner → `disputeNote=null`; owner → нота |
| **F5-04 my-attendance без членства** | вышедший из клуба, есть `event_response`, `attendance='absent'` | `GET /my-attendance` → 200 со своей строкой и `canDispute=true`; `POST dispute` проходит; `/responses` тому же юзеру → 403 (роутер остаётся member-only) |

**Юнит-правки:** `AttendanceServiceTest` (mockk) — изменение арности `AttendanceMarkedEvent` сломает
equality-assert'ы (~109, ~144-145); happy-path требует мок `markAttendanceMarked` возвращать `1`, не `Unit`.

---

## 5. Порядок реализации (чтобы не переоткрыть гонки)

1. **V24** (обе колонки) + `generateJooq`.
2. **F5-16 + F5-15(1)** одним проходом по `setAttendance` (обе правят его; раздельно — re-mark сотрёт
   спор раньше, чем сбросит флаг).
3. **F5-09** (`markAttendanceMarked` Int + durable `setAttendance` подзапрос).
4. **F5-10** (`disputeAbsentAttendance` подзапрос) + **F5-16 guard** (`dispute_terminal=false`) —
   обе правят `disputeAbsentAttendance`, одним проходом.
5. **F5-05/решение (б)** (`finalizeAttendanceBefore` → `COALESCE`, `markAttendanceMarked` пишет `marked_at`).
6. **F5-15(2)** (`AttendanceMarkedEvent` арность + listener + `sendAttendanceMarked`).
7. **F5-06** (`VoteService` owner-фильтр).
8. **F5-17** (reminder-предикат).
9. **F5-04** (новый эндпоинт + ослабление гейта + минимальный фронт `canDispute` из `/my-attendance`).
10. Тесты §4.

---

## 6. Риски / не сломать

- **EXP-2 disjointness:** `neutrallyFinalizeUnmarkedBefore` остаётся на `event_datetime` (НЕ переводить
  на `marked_at`). Под (б)=A поздняя отметка флипает `marked=true` до `event+autoFinalize`, neutral-финалайзер
  пропускает (`marked=true`), normal-финалайзер ждёт `marked_at+window` — финалайзеры на disjoint-строках.
- **ATT-2 timing:** `resolveExpiredDisputesToAbsent` бежит ВНУТРИ finalize-txn ДО чтения роутера
  reputation-листенером. F5-10-предикат **не замещает** ATT-2 (бэкстоп ordering B) — комментарий обязателен.
- **PR #60 cancelled-guards:** `status<>'cancelled'` уже в `finalizeAttendanceBefore` и `claimEvent` —
  НЕ дублировать. F5-09-предикат трогает `attendance_finalized` (другая колонка) — ортогонален.
- **Advisory-lock:** `lockEventSlots` — другое key-space (stage-2 слоты), `markAttendance` его не берёт.
  Для mark×finalize достаточно предиката + EVENTS-row contention. Не вводить новый.
- **Опциональный add-on к (б)=A:** loud-guard в `markAttendance`, отклоняющий отметку после
  `event_datetime + autoFinalizeUnmarkedMinutes` — конвертит «silent neutral-zero» в внятный 4xx.
  Дёшево, улучшает UX организатора. Включить, если не раздувает PR.
