# Handoff — Пакет 1 F5: attendance/dispute integrity

> Подготовлено 2026-06-13 для старта в **новой сессии**. Цель документа — чтобы дизайн-сессия
> началась без раскопок. Все file:line сверены на коммите `f73b249` (master после PR #60).
>
> **Первый шаг новой сессии — НЕ код, а дизайн-сессия (ultracode/ultrathink):** карта
> стейт-машины attendance/dispute + снять 3 продуктовых решения (§5). Только после утверждения —
> переключаемся на кодинг одним backend-PR по bugfix-флоу.

---

## 1. Контекст и место в очереди

Очередь работ ([[project_work_queue]]): пакет 3 ✅ (PR #58) → пакет 2 + S2T-2 ✅ (PR #59) →
club-delete cascade ✅ (PR #60, ad-hoc) → **СЕЙЧАС: Пакет 1 F5** → складчина A/B → P1b Trust.

Пакет 1 = **PR «attendance/dispute integrity»** из реестра
`docs/backlog/two-stage-reputation-bug-register.md` § «Пакеты фиксов» п.1.
Это бэкенд-PR. Фронт-часть багов (F5-16 фронт и пр.) идёт в пакет 4.

**Зачем сейчас:** чистим запись attendance-данных ДО того, как пакет P1b построит поверх них
витрину Trust 0–100. Грязные данные явки → грязный Trust.

---

## 2. Ядро проблемы — это ОДНА стейт-машина, не 8 независимых багов

Все баги пакета живут на одном жизненном цикле явки и его гонках с финализатором.
**Их нельзя чинить поштучно** — предикат, закрывающий одну гонку, может переоткрыть соседнюю.
Дизайн-сессия должна сначала зафиксировать стейт-машину целиком, потом — согласованные фиксы.

### Стейт-машина явки (как есть сейчас)

```
событие прошло (event_datetime <= now)
        │
        ▼
[markAttendance]  орг отмечает: дефолт «пришёл», снимает галку с отсутствующих
        │        → attendance: attended | absent ; events.attendance_marked = true
        │        → публикует AttendanceMarkedEvent → DM absent-участникам «оспорьте»
        ▼
[disputeAttendance]  absent-участник жмёт «Оспорить» (+ опц. note организатору)
        │            → attendance: absent → disputed ; публикует AttendanceDisputedEvent → DM оргу
        ▼
[resolveDispute]  орг решает: attended | absent  (disputed → attended/absent)
        │
        ▼
[finalizeAttendance]  ШЕДУЛЕР, hourly: cutoff = now − disputeWindowMinutes (дефолт 48ч)
                      finalizeAttendanceBefore(cutoff): event_datetime <= cutoff
                                                        AND attendance_marked = true
                                                        AND attendance_finalized = false
                                                        AND status != cancelled  ← добавлено PR #60
                      → attendance_finalized = true
                      → resolveExpiredDisputesToAbsent: оставшиеся disputed → absent (ATT-2)
                      → публикует AttendanceFinalizedEvent → пайплайн репутации (claimEvent)
```

Параллельная ветка (EXP-2): `neutrallyFinalizeUnmarkedBefore` для немаркированных событий —
ставит `finalized=true, marked=false`, репутацию НЕ начисляет, AttendanceFinalizedEvent НЕ шлёт.

### Где гонки и дыры (узлы, к которым привязаны баги)

- **Окно спора отсчитывается от `event_datetime`, а не от момента отметки** → если орг отметил
  поздно, окно уже почти закрыто (ATT-1/F5-05). **Колонки `attendance_marked_at` нет.**
- **TOCTOU между `markAttendance`/`disputeAttendance` и финализатором** — нет предиката
  `attendance_finalized = false` в UPDATE'ах (F5-09/F5-10).
- **Резолв «не пришёл» не терминален** → пинг-понг споров (F5-16).
- **Повторная отметка (stale-вкладка)** затирает активные споры + дублирует DM (F5-15).
- **Авторизация цепочки спора противоречива** — DM приходит, а endpoint 403 (F5-04).
- **Privacy: `dispute_note` виден всем членам клуба** (F5-06, A01).
- **Reminder оргу шлётся по уже финализированному событию** (F5-17).

---

## 3. Баги пакета (актуальные file:line на `f73b249`)

| ID | Sev | Суть | Где | Узел стейт-машины |
|---|---|---|---|---|
| **F5-06** | A01/Privacy | `dispute_note` (комментарий оргу) отдаётся ВСЕМ членам клуба в `GET /api/events/{id}/responses`; фильтр только client-side | `VoteService.getEventResponders` (`VoteService.kt:75-93`, поле `disputeNote` :90), DTO `VoteDto.kt:38`, фронт рендерит всем `EventPage.tsx:523` | чтение ростера |
| **F5-09** | High | TOCTOU `markAttendance` × EXP-2-финализация: отметка в момент нейтральной финализации → `finalized+marked` → пайплайн начисляет −200 при нулевом окне спора | `AttendanceService.markAttendance:33-67`, `JooqEventRepository.markAttendanceMarked:301` | mark × finalize |
| **F5-10** | High | TOCTOU `disputeAttendance` × финализация: спор на границе окна обходит ATT-2 (0 вместо −200) или замораживает вечную disputed-строку | `AttendanceService.disputeAttendance:70-93`, `JooqEventResponseRepository.disputeAbsentAttendance:226` | dispute × finalize |
| **F5-04** | High | Вышедший из клуба участник: DM «оспорьте» приходит, но `/responses` → 403 (`isMember`-гейт) → UI спора недостижим, а −200 пишется | `VoteService.getEventResponders:75-80` (isMember), `AttendanceService` (DM), `EventPage.tsx:278` (canDispute) | авторизация спора |
| **F5-05** | High | = **ATT-1 усиленный**: окно спора от `event_datetime`; поздняя отметка → своевременный спор конвертируется ATT-2 в −200 (раньше 0) | `AttendanceService.finalizeAttendance:119-135` (cutoff :120), `JooqEventRepository.finalizeAttendanceBefore:359` | окно спора |
| **F5-15** | Med | Повторный `POST /attendance` (stale-вкладка) молча затирает активные споры мимо resolve-флоу + дублирует DM всем absent | `AttendanceService.markAttendance:33-67`, `JooqEventResponseRepository.setAttendance:213` | re-mark |
| **F5-16** (бэк) | Med | Резолв «не пришёл» не терминален: `absent` после резолва снова даёт `canDispute=true` → пинг-понг до финализации | `JooqEventResponseRepository.resolveDisputedAttendance:237`, фронт `EventPage.tsx:278,547-576` | resolve терминальность |
| **F5-17** | Med | EXP-2-регрессия: DM оргу «отметьте явку» уходит по уже нейтрально финализированному событию → гарантированный 400 | `JooqEventRepository.findEventsNeedingAttendanceReminder:326` | reminder |
| **ATT-1** | Med | (корень F5-05) окно спора от `event_datetime`, не от момента отметки — нет `attendance_marked_at` | как F5-05 | окно спора |

**По сложности:**
- *Механические* (предикат + тест): **F5-17** (1 строка: `ATTENDANCE_FINALIZED.isFalse` в reminder-предикат), **F5-09/F5-10** (предикат `attendance_finalized=false` в UPDATE + проверка rows-affected), **F5-06** (отдавать `disputeNote` только оргу/владельцу строки в DTO).
- *Средние* (стейт-машина): **F5-15** (не затирать `disputed`, DM только новым absent), **F5-16** (флаг «спор разрешён» → терминальность).
- *Требуют продуктового решения* (§5): **F5-04** (модель доступа к спору), **ATT-1/F5-05** (миграция `attendance_marked_at`).

---

## 4. Ключевые файлы

| Файл | Что внутри |
|---|---|
| `backend/.../event/AttendanceService.kt` | mark(:33) / dispute(:70) / resolveDispute(:96) / finalizeAttendance шедулер(:119) / neutrallyFinalize(:149). Окно спора `disputeWindowMinutes` (env `events.dispute-window-minutes:2880`) |
| `backend/.../event/JooqEventRepository.kt` | `markAttendanceMarked:301`, `finalizeAttendanceBefore:359`, `neutrallyFinalizeUnmarkedBefore:371`, `findEventsNeedingAttendanceReminder:326` |
| `backend/.../event/JooqEventResponseRepository.kt` | `setAttendance:213`, `disputeAbsentAttendance:226`, `resolveDisputedAttendance:237`, `resolveExpiredDisputesToAbsent`, `findRespondersWithUsers:~150` (отдаёт disputeNote) |
| `backend/.../event/VoteService.kt` | `getEventResponders:75` (isMember-гейт + disputeNote в DTO) |
| `backend/.../event/VoteDto.kt` | `EventResponderDto.disputeNote:38` |
| `frontend/src/pages/EventPage.tsx` | `canDispute:278`, рендер disputeNote всем :523, dispute UI :547-576 |
| `backend/.../reputation/JooqReputationRepository.kt` | `claimEvent:55` (граница ledger, теперь исключает cancelled) |

**Тест-инфра:** `AttendanceServiceTest.kt` (unit, mockk), `AttendanceFinalizeRepositoryTest.kt`
(Testcontainers Postgres — расширять для finalize/dispute-гонок), `VoteServiceTest.kt`.
Паттерн интеграционного теста с конкуренцией — `Stage2SlotRaceIntegrationTest.kt`
(CountDownLatch + Executors, вызовы через Spring-прокси). Образец миграции — V23.
**Следующая миграция = `V24__...`** (для `attendance_marked_at`, если решение (б) = да).

---

## 5. Три продуктовых решения — снять В ДИЗАЙН-СЕССИИ перед кодом

### (б) ATT-1 / F5-05 — окно спора: от `event_datetime` или от момента отметки?
Сейчас окно (48ч) считается от времени события. Орг отметил поздно (через 47ч) → у участника
~1ч на спор, и ATT-2 конвертирует его в −200.
- **Вариант A (рекомендую):** миграция `V24 attendance_marked_at TIMESTAMPTZ`; `markAttendanceMarked`
  пишет `now()`; `finalizeAttendanceBefore` гейтит по `marked_at + window`, а не `event_datetime + window`.
  Честное окно от момента отметки. **Цена:** миграция + изменение гейта финализатора + бэкфилл
  существующих строк (или гейт «marked_at IS NULL → fallback на event_datetime»).
- **Вариант B:** оставить от `event_datetime`, но запретить отметку после `event_datetime + (autoFinalize − window)`.
  Без миграции, но режет орг-гибкость.
- **Решение влияет на:** нужна ли миграция, объём фикса F5-05, и взаимодействие с EXP-2 (autoFinalize).

### F5-04 — кто может оспорить отметку?
Вышедший из клуба (или с истёкшей подпиской) получает DM «оспорьте», но `/responses` ему 403.
- **Вариант A:** пускать «участника события» (есть `event_response`) к СВОЕЙ строке независимо от
  текущего членства — раз ему пишут −200, он должен иметь право оспорить.
- **Вариант B:** не слать DM «оспорьте» не-членам (и/или не писать им −200 — но это меняет
  репутационную модель).
- **Развилка по сути:** «репутация события привязана к участию или к членству?». Затрагивает
  authz-гейты VoteService + правила DM.

### F5-16 — терминальность резолва «не пришёл»
После `resolveDispute(absent)` участник снова видит `canDispute=true` → может оспорить заново.
- **Вариант A:** колонка/флаг «спор уже разрешён оргом» → `canDispute=false` после первого резолва;
  UI-текст «спор отклонён».
- **Вариант B:** ограничить число споров (1 на участника на событие) без новой колонки (вывести
  из текущего состояния).
- **Решение влияет на:** нужна ли ещё одна колонка в этой же миграции V24.

> Дополнительно к обсуждению (не блокирует, но рядом): **F5-14** (отмена события как фича) и
> **F5-19** (тиры до P1b) — НЕ входят в пакет 1, но F5-14 концептуально близок (теперь, после
> PR #60, статус `cancelled` уже выставляется каскадом — endpoint/UI отмены стал ближе).

---

## 6. Рекомендованный флоу новой сессии

1. **Дизайн-сессия (ultracode ON, ultrathink):**
   - Нарисовать целевую стейт-машину attendance/dispute с окнами и точками гонок.
   - Снять 3 решения (§5) через `AskUserQuestion`.
   - Зафиксировать: одна миграция V24 (какие колонки), список согласованных предикатов,
     порядок фиксов чтобы не переоткрыть гонки.
   - **Кодить не начинать до утверждения.**
2. **Реализация — один backend-PR `bugfix/attendance-dispute-integrity`** по bugfix-флоу:
   Developer → Reviewer → Security → Analyst (docs alignment) → staging → тест → master.
   После дизайна механика рутинная: предикаты + миграция + DTO-фильтр + тесты.
3. **Тесты обязательны на гонки** (Testcontainers, паттерн Stage2SlotRaceIntegrationTest):
   mark×finalize (F5-09), dispute×finalize (F5-10), re-mark поверх disputed (F5-15),
   повторный спор после резолва (F5-16), окно от marked_at (F5-05).

**Оркестрация мульти-агентами НЕ нужна** — один модуль, один связный PR. Глубина (ultracode)
идёт в дизайн стейт-машины и адверсариальную проверку гонок, не в параллельные агенты.

---

## 7. Подводные камни (не сломать уже сделанное)

- **Числа репутации:** прогул = **−200**, приход = **+100** (решение 2026-06-11, реестр § «РЕШЕНИЯ»).
  В старом тексте реестра местами −50/+100 — это до-переоценочные значения; в коде сейчас −200/+100
  через `ReputationPolicy`. Сверять с кодом, не с устаревшими строками реестра.
- **EXP-2** (нейтральная финализация немаркированных) — F5-09 это её гонка с ручной отметкой.
  Два пути финализации (`finalizeAttendanceBefore` marked=true vs `neutrallyFinalizeUnmarkedBefore`
  marked=false) трогают непересекающиеся строки — не слить их случайно.
- **ATT-2** (`resolveExpiredDisputesToAbsent`) — остаточный `disputed` → `absent` на финализации.
  F5-10/F5-05 меняют тайминг этого; проверить, что не появится «вечная disputed».
- **PR #60** уже добавил `status != cancelled` в `finalizeAttendanceBefore` и `claimEvent` —
  не дублировать, учитывать при гейтах.
- **Advisory-lock** `lockEventSlots` (PR #59) — паттерн `pg_advisory_xact_lock` уже в проекте,
  если для mark/finalize понадобится сериализация — переиспользовать подход.
- **Миграции:** перед V24 — `ls db/migration/` целиком, номер = max+1. После публикации не менять.

---

## 8. Источники

- Реестр: `docs/backlog/two-stage-reputation-bug-register.md` § «Аудит F5» (F5-01…F5-25) и
  § «Пакеты фиксов».
- Спеки: `docs/modules/events.md` (§ Репутация Блок 1, EXP-2, ATT-2/ATT-3, attendance flow),
  `docs/modules/reputation-v2.md`, `docs/modules/reputation.md`.
- PRD: §4.4.3 (отметка явки), §4.4.4 (репутация).
- Тест-план: `docs/qa/reputation-test-plan.md`.
- Связанные memory: [[project_two_stage_confirmation_gaps]], [[project_reputation_v2_design]],
  [[project_work_queue]].
