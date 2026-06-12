# Две-стадийное подтверждение + Репутация — реестр багов

**Источник:** adversarial bug-hunt (6 областей × verify), 2026-06-07, сессия
`feature/two-stage-confirmation-gaps`. Найдено **13 подтверждённых багов + 5 продуктовых
решений** (18 false-positive отсеяно). Большинство — **pre-existing**, не внесены этой веткой.

Статусы: ✅ исправлено в этой ветке · 🔶 отложено (требует решения/отдельной ветки) ·
🧭 нужно продуктовое решение.

---

## ✅ Исправлено в `feature/two-stage-confirmation-gaps`

| ID | Sev | Проблема | Фикс |
|---|---|---|---|
| Bug B | — | confirm/decline доступны после старта события | гард `event_datetime > now` + фронт `!eventHappened` |
| Feature A | — | не подтвердившие висят `NULL` навсегда | авто-истечение → `expired_no_confirm` (V19) |
| Attendance roster | — | «забывшие подтвердить» (going) попадали в список отметки явки | роутер = **только confirmed** (фронт-фильтр + бэк-гард `setAttendance`) |
| **S2-05** | A01 | `declineParticipation` не проверяет членство (а confirm — проверяет) | добавлен `isMember` гард, симметрично confirm |
| **ATT-4** | MED | `markAttendance` можно вызвать **после финализации** → ростер расходится с зафиксированной репутацией | гард `if attendanceFinalized throw`, как в dispute/resolve |
| **S2-02 / S2T-3** | LOW/MED | waitlisted-участник самоповышается в свободный слот в обход FIFO | явная ветка: waitlisted при confirm остаётся waitlisted (промоут только через decline → `findFirstWaitlisted`) |
| **S1-001** | LOW | окно голосования в `castVote` через `ChronoUnit.DAYS` (усечение) открывает голосование на ~24ч раньше, чем показывает лента | точная граница `event_datetime.minusDays(...)`, как в `EventMapper`/`findMyFeed` |

---

## 🔴 HIGH — отложено (требует отдельной ветки/решения)

### S2T-2 — уведомление о старте Этапа 2 НЕ отправляется (ядро сломано)
`NotificationService.sendStage2Started` **ни разу не вызывается**; `Stage2Service` даже не
инжектит `NotificationService`. При триггере Этапа 2 статус меняется, но **DM «Подтверди
участие» не уходит**. В реальном использовании участников никто не просит подтвердить → они не
подтверждают → на старте все авто-истекают в `expired_no_confirm`. **Это объясняет, почему
двухэтапка «не работает» вживую.** (В Mini App можно подтвердить вручную на странице события —
поэтому ручной тест проходит.)
**Фикс:** инжектить `NotificationService` в `Stage2Service`, звать `sendStage2Started(event)`
**после коммита** транзакции (afterCommit / @Async). Учесть GAP: рассылка сейчас без фильтра по
голосу (шлёт всем responders) — см. `docs/backlog/telegram-bot-prd-gaps.md`. PRD §4.4.2 шаг 1.

### EXP-2 — репутация полностью заблокирована ручной отметкой явки (нет авто-финализации)
> ✅ **ИСПРАВЛЕНО в Блоке 1** (`feature/two-stage-confirmation-gaps`, 2026-06-07). Решение —
> **нейтральная авто-финализация**: на дедлайне (`events.auto-finalize-unmarked-minutes`, дефолт 48ч)
> немаркированное прошедшее событие закрывается `finalized=true`/`marked=false`. Пайплайн клеймит
> только маркированные → ledger-строк ноль (никого не наказываем и не награждаем). Детали и AC —
> `docs/modules/events.md` § «Репутация — Блок 1» → EXP-2.

`finalizeAttendance` гейтит на `attendance_marked=true`. Если организатор **не открыл приложение
и не отметил** явку — событие **никогда** не финализируется, репутация **никогда** не считается.
Надёжный участник, который пришёл, получает **0** вместо +100. **Это вторая причина «5 событий
подтвердил — всё ещё Новичок».**

### ATT-2 — нерешённый спор обнуляет штраф −50 «Пустозвон»
> ✅ **ИСПРАВЛЕНО в Блоке 1** (`feature/two-stage-confirmation-gaps`, 2026-06-07).
> `AttendanceService.finalizeAttendance` зовёт `resolveExpiredDisputesToAbsent(finalizedEventIds)`
> в той же транзакции до публикации события → остаточные `disputed → absent` → `no_show (−50)`.
> Детали и AC — `docs/modules/events.md` § «Репутация — Блок 1» → ATT-2.

going+confirmed+absent (−50). Участник жмёт «Оспорить» → `attendance=disputed`. Организатор не
реагирует → на финализации остаётся `disputed` → `attendanceKind(going, disputed) =
confirmed_unresolved = 0`. **Любой ненадёжный участник обнуляет −50 одним «Оспорить» + бездействием
организатора.**

### S2-01 — гонка вместимости (over-capacity)
`confirmParticipation` делает non-atomic read-modify-write: `countConfirmed() < limit` без
блокировки. Два параллельных confirm на последний слот → оба видят `9<10` → **11 confirmed на 10
мест**. Нет `SELECT FOR UPDATE`/advisory-lock/констрейнта.
**Фикс:** сериализовать слот — `pg_advisory_xact_lock(eventId)` в начале confirm/decline (паттерн
уже есть в `JooqReputationRepository.recompute`), или conditional UPDATE с проверкой count в одном
locked-критическом участке. HIGH, но требует аккуратного теста на конкуренцию.
⚠ **2026-06-10:** вероятность коллизии выше, чем казалось — confirm-reminder DM за 2ч синхронно
сгоняет всех неподтверждённых в приложение. Та же гонка есть на decline-стороне (двойной промоут
одного waitlisted → потерянный слот, см. F5-11) — advisory-lock в confirm/decline закрывает обе.

### S2T-1 / S2-03 / S2-04 — Сценарий Б (добор из «Возможно») не реализован
PRD §4.4.2 Сценарий Б (недобор): сначала подтверждают «Пойду», затем при свободных местах
система зовёт «Возможно» («Нужны ещё X человек»), первые X по timestamp добирают, остаток →
уведомление организатору. **Ничего из этого нет.** `findMaybeByEventOrderByTimestamp` —
**мёртвый код** (ноль вызовов). Maybe-голосующие могут случайно занять слоты «Пойду» в overflow
(S2-04), порядок «going-first» не соблюдён.
**Решение (продуктовое + фича):** реализовать оркестрацию Сценария Б, либо явно отложить и
**удалить мёртвый `findMaybeByEventOrderByTimestamp`** (сейчас он создаёт ложное впечатление, что
поток подключён). Большая работа — отдельная фича-ветка + спека.

---

## 🟡 MEDIUM / LOW — отложено

| ID | Sev | Проблема | Фикс / решение |
|---|---|---|---|
| **EXP-3** | MED | `triggerStage2ForReadyEvents`: `try/catch` внутри одного `@Transactional` вокруг цикла — одна ошибка события помечает всю транзакцию rollback-only, ложная «изоляция» (теряется весь батч) | как в `ReputationScheduler`: per-event `@Transactional(REQUIRES_NEW)` через границу бина (self-invocation не сработает) |
| **ATT-1** | MED | окно оспаривания считается от `event_datetime`, не от **момента отметки** (нет колонки `attendance_marked_at`) → медленная отметка = ~0 окна. ⚠ **2026-06-10: последствие усилено ATT-2** — остаточный спор теперь конвертируется в `no_show −50` (раньше 0): участник, оспоривший сразу после поздней отметки, получает −50 на ближайшем тике финализатора (см. F5-05) | миграция `attendance_marked_at TIMESTAMPTZ`; гейт финализации по `marked_at`, не `event_datetime` |
| **ATT-3** ✅ | MED | absent-участнику **не уходит** DM «вас отметили отсутствующим, оспорьте» (`sendAttendanceMarked` — мёртвый код); UI спора не было | ✅ **Исправлено в Блоке 1**: `markAttendance` публикует `AttendanceMarkedEvent` → `bot/AttendanceMarkedListener` (AFTER_COMMIT) зовёт `sendAttendanceMarked`; + полный UI оспаривания/резолва на `EventPage`. См. `events.md` § «Репутация — Блок 1» → ATT-3 |
| ~~**REP-3**~~ ✅ | MED | **СНЯТ 2026-06-12**: единственным потребителем `findLatestByUserId` был бот-`/мой_рейтинг`. Команда удалена, `findLatestByUserId` удалён из интерфейса и Jooq-реализации → выбора «произвольной последней строки» больше нет. Заодно умер F5-24 | — |
| **S1-002** 🧭 | MED | повторный голос (даже тем же значением going→going) сбрасывает `stage_1_timestamp` → теряется позиция FIFO | no-op при неизменном голосе; или не сбрасывать timestamp для FIFO (отдельный `updated_at`). Согласовать `events.md:133` |
| **S1-003** 🧭 | LOW | `not_going`-голоса показываются в списке «Кто идёт» (серая точка); мёртвый `?: "going"` fallback | фильтровать `not_going` из `findRespondersWithUsers`, либо переименовать список в «Ответы»; убрать недостижимый fallback |
| **SEC-1** | LOW | Массовые DM (`sendEventCreated`/`sendStage2Started`/`sendConfirmReminder`) шлются в тугом `forEach` без троттлинга; Telegram лимит 30 msg/sec (backend.md). Pre-existing, во всех bulk-рассылках | очередь/rate-limiter для bulk-DM (≤30/сек), отдельная инфра-задача |

---

## 🧭 Отложено в бэклог — репутация организатора (продуктовое решение 2026-06-07)

**C — штраф организатору за неотмеченную явку.** Решено реализовать **вместе с фичей
«репутация организатора»** (сейчас такой сущности нет; есть только `clubs.activity_rating`).
Зафиксированный замысел на тот момент:
- **Триггер:** событие прошло, через 24ч — DM-напоминание оргу (✅ реализовано как Feature B,
  `EventReminderScheduler.remindOrganizersToMarkAttendance`). Если через **48ч после события**
  явка всё ещё не отмечена → **штраф −5** (величина по аналогии с `ApplicationScheduler`
  ACTIVITY_RATING_PENALTY).
- **Куда штраф:** при реализации решить — `clubs.activity_rating` (существующий механизм) или
  новая метрика репутации организатора. Тогда же дедуп-флаг `attendance_penalty_applied` на events.
- **Связь:** закрывает «организаторскую» половину EXP-2 (надёжный участник не должен страдать
  от бездействия орга; параллельно нужна авто-финализация непомеченных событий — серверная половина EXP-2).

---

## Приоритет (моя рекомендация)
1. **S2T-2** (уведомление Этапа 2) — без него двухэтапка мертва вживую.
2. ✅ **EXP-2** (авто-финализация) — **сделано (Блок 1)**.
3. ✅ **ATT-2** (нерешённый спор → −50) + ✅ **ATT-3** (DM об отметке + UI спора) — **сделано (Блок 1)**.
4. **S2-01** (гонка вместимости) — целостность ростера.
5. **Сценарий Б** (S2T-1/S2-03/S2-04) — крупная фича, отдельная спека.
6. Остальное (EXP-3, ATT-1, S1-002/003) — по мере касания модулей. (REP-3 снят — `findLatestByUserId` удалён.)

> **Блок 1 закрыт** (EXP-2/ATT-2/ATT-3 + UI оспаривания) на `feature/two-stage-confirmation-gaps`.
> Следующий приоритет «живого пути» — **S2T-2** (DM при старте Этапа 2): без него участников никто не
> просит подтвердить, и двухэтапка вживую по-прежнему голодает.

---

# Аудит F5 (2026-06-10) — мульти-агентная охота с адверсариальной верификацией

**Метод:** 9 искателей (линзы: ledger-расчёт, конкурентность пайплайна, явка/споры, двухэтапка,
складчина, миграции V17/V18, UI репутации, UI явки, DTO-граница/authz) → дедуп (37→25) →
**3 независимых скептика на каждую находку** (трассировка / достижимость / защита-в-другом-слое),
все 25 подтверждены 3/3. **Ядро расчёта ledger чистое** — ни одного бага в SUM/счётчиках/паритете
V18/recompute/идемпотентности; всё найденное — стыки флоу, гонки и UI.

Severity — консенсус скептиков (местами ниже заявки искателя: узкие тайминг-окна).

## 🔴 F5 HIGH

### ~~F5-01~~ — ✅ НЕ БАГ (продуктовое решение 2026-06-12): подтверждение этапа 2 необратимо
`EventPage.tsx` (гард `myVote === 'going' || 'maybe'`) + `VoteService.getMyVote` + `Stage2Service.kt`.
Кнопки этапа 2 рендерятся только при `myVote === 'going' || 'maybe'`; после подтверждения остаётся
бейдж «Подтверждён» без кнопки «Отказаться».
**Решение:** это **намеренное поведение** — подтвердив участие на этапе 2, участник берёт на себя
обязательство и **не может «соскочить» одним кликом**. Изменившиеся планы = no-show (−200), и это
правильный стимул. Поэтому «Отказаться» для `confirmed` **не добавляем**.
**Следствие (осознанное):** промоут из листа ожидания через decline confirmed-участника
не срабатывает — confirmed-слот не освобождается до старта. Для текущей модели (коммит = финальный)
это приемлемо. Бэкенд-ветка `declineParticipation` остаётся рабочей для **до**-подтверждения
(going/maybe → «Не смогу», сценарий 7); недостижима только ветка `wasConfirmed → промоут` —
безвредный мёртвый путь, оставлен как защита. PRD §4.4.2 «место предлагается первому в листе»
относится к недобору (Сценарий Б), а не к отзыву подтверждения.

### F5-02 — досрочное закрытие складчины по цели даёт pending-участникам −25 до дедлайна
> ✅ **РЕШЕНО (продуктовое решение 2026-06-12, реализация в пакете 3):** нейтрально —
> при `closed_at < deadline` pending получают новый статус `released`, **ledger-строки нет**
> («обещание — ответить к дедлайну; дедлайн не наступил — нарушения нет»). Усилитель убит
> валидацией `declared == expected` в fixed-режимах. Глоссарий побеждает §Closure.
> Решение принято в составе полного редизайна репутации складчины (молчание −25→**−40**,
> отказ −5→**0 без строки**, rate-limit ≤3 реп-сбора/клуб/7д, reminder-DM как launch-blocker) —
> см. `docs/backlog/skladchina-reputation-redesign.md`.

`SkladchinaService.kt:157, 210-228, 267, 279-281`. `closeInternal` безусловно зовёт
`expirePendingParticipants()` во всех путях закрытия, включая авто-закрытие по goal-reached.
`markPaid` валидирует только `declaredAmountKopecks > 0` (без верхней границы) — любой участник,
заявив сумму ≥ goal, закрывает сбор через час после создания → всем ещё-pending необратимый
`skladchina_expired −25` за «неответ» на дедлайн, который не наступил.
**Вскрыто противоречие спеки** `skladchina.md`: глоссарий (:424) — «expired = deadline истёк,
статус был pending»; §Closure (:205-210) — «pending→expired при любом закрытии».

## 🟠 F5 MEDIUM

| ID | Проблема | Где | Фикс-хинт |
|---|---|---|---|
| **F5-03** | Гонка `markPaid`/`decline` × `closeInternal`: оплата на дедлайне перетирает `expired_no_response` → в БД `paid`, в ledger −25 навсегда | `JooqSkladchinaRepository.kt:336-364`, `SkladchinaService.kt:159-181` | предикат `WHERE status='pending'` в `setParticipantPaid`/`setParticipantDeclined` + проверка rows-affected (0 → 409) |
| **F5-04** | Участник без активного членства (вышел/подписка истекла): DM «оспорьте» приходит, но `/responses` → 403 (`isMember`-гейт) → UI спора недостижим, а −50 пишется | `VoteService.kt:75-80`, `AttendanceService.kt:69-88`, `EventPage.tsx:275-276` | согласовать гейты цепочки ATT-3: либо пускать «участника события» (есть response) к своей строке, либо не слать DM не-членам |
| **F5-05** | = **ATT-1, усиленный**: окно спора от `event_datetime` + поздняя отметка → своевременный спор конвертируется ATT-2 в −50 (раньше 0) | `AttendanceService.kt:112-130`, `JooqEventRepository.kt:316-326` | фикс ATT-1 (`attendance_marked_at`); приоритет поднять — цена бага выросла |
| **F5-06** | **Privacy**: `dispute_note` (комментарий организатору) отдаётся всем членам клуба в `GET /api/events/{id}/responses`; фильтрация только client-side | `VoteService.kt:75-93`, `JooqEventResponseRepository.kt:150` | в DTO отдавать `disputeNote` только организатору (или владельцу строки); A01 Broken Access Control |
| **F5-07** | = **S2-01 подтверждён** (over-capacity confirm) — см. обновлённую запись выше | `Stage2Service.kt:97-109` | `pg_advisory_xact_lock(eventId)` в confirm **и** decline (закрывает и F5-11) |
| **F5-08** | «Обещания 0% · 0 подтв.» у участника с finance-only треком (3 складчины, 0 событий, `outcome_count=3` → порог пройден); ProfilePage те же нули прячет (`hasActivity`), ClubMembersTab/MemberProfileModal — нет | `ClubMembersTab.tsx:157-163`, `MemberProfileModal.tsx:76-79` | guard `totalConfirmations > 0` для attendance-метрик на обеих поверхностях (паритет с ProfilePage) |

## 🟡 F5 LOW

| ID | Проблема | Где | Фикс-хинт |
|---|---|---|---|
| **F5-09** | TOCTOU `markAttendance` × EXP-2: отметка в момент нейтральной финализации → `finalized+marked` → пайплайн начисляет −50 при нулевом окне спора | `AttendanceService.kt:42-56,142-150`, `JooqEventRepository.kt:256-261` | предикат `attendance_finalized=false` в UPDATE `markAttendanceMarked` + rows-affected |
| **F5-10** | TOCTOU `disputeAttendance` × финализация: спор на границе окна обходит ATT-2 (0 вместо −50) или замораживает вечную disputed-строку | `AttendanceService.kt:70-88`, `JooqEventResponseRepository.kt:216-225` | в UPDATE спора — join/предикат на `attendance_finalized=false` |
| **F5-11** | Гонка двух decline: оба промоутят одного waitlisted → освободившийся слот теряется навсегда (decline-сторона S2-01). ⚠ После реклассификации F5-01 (подтверждение необратимо) decline confirmed-участника недостижим — гонка остаётся только на до-подтверждения decline (going/maybe → «Не смогу»), приоритет ниже | `Stage2Service.kt:142-150` | общий advisory-lock с F5-07 |
| **F5-12** | Двойной `closeInternal` (scheduler × auto-close × ручное): дубль `SkladchinaClosedEvent` → два DM оргу, недетерминированный статус cancelled/closed_success | `SkladchinaService.kt:256-297`, `JooqSkladchinaRepository.kt:283-291` | `UPDATE ... WHERE status='active'` + rows-affected как клейм (паттерн `claimEvent`) |
| **F5-13** | Deadlock advisory-локов: пары (user,club) лочатся в insertion-порядке; событие × складчина того же клуба → 40P01, 500 на «Я оплатил» | `ReputationService.kt:66-67` | отсортировать пары перед взятием локов (детерминированный порядок) |
| **F5-14** 🧭 | Отмена события недостижима: `EventStatus.cancelled` нигде не присваивается — нет endpoint/UI; сорвавшаяся встреча живёт полный цикл (reminder'ы, выжигание `expired_no_confirm`); ошибочная отметка явки даст −50 за несостоявшееся событие | `EventService.kt` (нет cancel), `EventController.kt` | **фича, не багфикс**: endpoint + UI + правила (что с confirmed/репутацией при отмене). PRD §4.4.2 Сценарий Б шаг 4 |
| **F5-15** | Повторный `POST /attendance` (stale-вкладка) молча затирает активные споры мимо resolve-флоу + дублирует DM всем absent | `AttendanceService.kt:32-67`, `JooqEventResponseRepository.kt:203-214` | не перезаписывать `disputed` в setAttendance (или гейт на `attendanceMarked` + явный re-mark флоу); DM слать только новым absent |
| **F5-16** | Резолв «Не пришёл» не терминален: `absent` после резолва снова даёт `canDispute=true` → пинг-понг споров до финализации | `EventPage.tsx:276,541-576`, `JooqEventResponseRepository.kt:216-236` | признак «спор уже резолвлен» (колонка/флаг) либо UI-текст «спор отклонён» без повторной кнопки |
| **F5-17** | EXP-2 регрессия: DM оргу «отметьте явку» уходит по уже нейтрально финализированному событию → гарантированный 400 | `JooqEventRepository.kt:281-299` | добавить `ATTENDANCE_FINALIZED.isFalse` в предикат `findEventsNeedingAttendanceReminder` |
| **F5-18** | Ложный KDoc `maybeAutoCloseAfterStateChange` («errors are logged») — try/catch нет; падение реп-хука откатывает `markPaid` юзера (500), вопреки NFR `skladchina.md` | `SkladchinaService.kt:204-228` | try/catch + log.error вокруг closeInternal в auto-close пути (как в scheduler) — либо поправить KDoc и NFR осознанно |
| **F5-19** | Тиры `>=85 high / >=70 mid` откалиброваны под шкалу 0–100, применяются к неограниченной Σ ledger: пустозвон с +500 (50% no-show) зелёный, плательщик с +30 красный | `ProfilePage.tsx:37-47`, дубль `ClubMembersTab.tsx:28-33` | по-настоящему чинится только Trust 0–100 (**P1b**); до того — рассмотреть нейтральную раскраску |
| **F5-20** | Ошибка `GET /users/me/reputation` рендерится как «у тебя нет клубов» + CTA «Найти клуб» (ProfilePage); Discovery — «Найди свой первый клуб» | `ProfilePage.tsx:59,68,92,139,170-183`, `DiscoveryPage.tsx:80-81,152-156` | error-ветка (как в `ClubMembersTab:99-101`): сообщение + retry, не onboarding |
| **F5-21** | Карточки в ленте активностей показывают stage-1 `goingCount` («5/10 идёт») после этапа 2; внутри события «Состав · 2/10» — противоречие. `confirmedCount` в unified-feed DTO не прокинут | `ActivityCard.tsx:44-47`, `ActivityItemDto` | прокинуть `confirmedCount`+`status` в EventActivity, фазовый показ как на EventPage (events.md AC-PH1) |
| **F5-22** | Ложный empty-state «Нет подтверждённых участников для отметки» пока `/responses` грузится/упал (ошибка проглатывается); орг может закрыть страницу → событие уйдёт в EXP-2 | `EventPage.tsx:421-426` (источник 90,260-262) | учитывать `respondersQuery.isPending/error` до рендера empty-state |
| **F5-23** | `actionError` рендерится дважды в фазе stage_2 (над «Составом» и в секции подтверждения) | `EventPage.tsx:353,590` | один слот для ошибки confirm/decline |
| ~~**F5-24**~~ ✅ | **СНЯТ 2026-06-12** вместе с REP-3: `/мой_рейтинг` и `findLatestByUserId` удалены, поверхности бага больше нет | — | — |
| **F5-25** | V18 аудит-NOTICE неполный против спеки `reputation-v2.md:189-190`: нет суммы снятых очков, finance owner-пары не учтены (на данные не влияет; восстановимо запросом к `_pre_v18`) | `V18__backfill_reputation_ledger.sql:113-130` | one-off миграция уже применена — зафиксировать как известное ограничение в спеке, не чинить |

## Пакеты фиксов (рекомендация)

Один PR на пакет, по bugfix-флоу (Developer → Reviewer → Security → Analyst → staging):

1. **PR «attendance/dispute integrity»** (бэкенд): F5-04, F5-06, F5-09, F5-10, F5-15, F5-16(бэк-часть), F5-17 + ATT-1/F5-05 (если решён вопрос `attendance_marked_at`).
2. **PR «stage2 races»**: F5-07/S2-01 + F5-11 (advisory-lock confirm/decline). ~~F5-01~~ снят с пакета — реклассифицирован как «не баг» (подтверждение необратимо, решение 2026-06-12).
3. **PR «skladchina»**: F5-03, F5-12, F5-13, F5-18 + F5-02 (решение принято 2026-06-12) —
   пакет расширен редизайном репутации складчины, полный scope в
   `docs/backlog/skladchina-reputation-redesign.md` (новые веса, `released`, валидации,
   reminder-DM, UI «Важный сбор»).
4. **PR «UI полировка»** (фронт): F5-08, F5-16(фронт), F5-20, F5-21, F5-22, F5-23.
5. **Отложить осознанно**: F5-14 (фича «отмена события» — спека), F5-19 (ждёт P1b Trust), F5-25 (не чинить). (~~F5-24~~ и ~~REP-3~~ закрыты удалением `/мой_рейтинг`.)

**🧭 Продуктовые решения до старта пакетов:** (а) ~~F5-02~~ ✅ решено 2026-06-12 (released/нейтрально,
см. `skladchina-reputation-redesign.md`); (б) ATT-1/F5-05 — переводим ли окно спора на
`attendance_marked_at` (миграция); (в) F5-14 — отмена события: делаем фичу или фиксируем как gap;
(г) F5-19 — терпим тиры до P1b или нейтрализуем раскраску.
