# Telegram bot: known PRD ↔ code gaps

Расхождения между PRD §4.6 и реальной реализацией bot-модуля (после ветки `feature/refactor-bot`, 2026-05-12). Все — **существующие** gap'ы, выявленные post-flight аналитикой. Не являются регрессиями рефакторинга — это нереализованные / частично реализованные фичи в текущем продакшен-коде. Зафиксированы здесь, чтобы не потерялись и чтобы спека `docs/modules/telegram-bot.md` не выглядела «полной».

**Источник:** сверка `PRD-Clubs.md` §4.6 ↔ `backend/src/main/kotlin/com/clubs/bot/` + сверка `EventService` / `Stage2Service` / `AttendanceService` на отсутствие вызовов `NotificationService.send*`.

---

## ~~GAP-001~~: `/мой_рейтинг` показывает репутацию одного клуба, не агрегат — **ЗАКРЫТ (N/A) 2026-06-12**

> **Закрыт:** команда `/мой_рейтинг` удалена (продуктовое решение), `findLatestByUserId`
> удалён. Вопрос «один клуб vs агрегат» неактуален. Заодно сняты REP-3 и F5-24.

**PRD §4.6.2:** «`/мой_рейтинг` — показывает индекс надёжности, % выполнения и счётчик спонтанности текущего пользователя.»
PRD неоднозначен: «индекс надёжности» в единственном числе подразумевает одно число, но в БД (`user_club_reputation`) репутация существует **per (user, club)**, и одного «индекса для пользователя» как сущности нет.

**Реальность:** `ClubsBot.handleMyRating` зовёт `ReputationRepository.findLatestByUserId(userId)`, который возвращает запись с `ORDER BY USER_CLUB_REPUTATION.UPDATED_AT DESC LIMIT 1` — т. е. репутацию того клуба, где у пользователя была **последняя** активность. Ни «среднего», ни «суммы», ни выбора по контексту. Пользователь, состоящий в 3 клубах, не понимает, какой клуб ему показали.

**Impact:** Medium. Пользователь видит число без контекста — какой клуб? Можно перепутать с «общей репутацией на платформе». При расхождении репутации по клубам — путаница.

**Варианты решения:**
- **A. Сохранить текущее поведение, добавить контекст** — в текст добавить «(в клубе «X»)». Минимальные правки.
- **B. Список по всем клубам** — построчно: `Клуб «X»: индекс 87 / 95% / 3 подтв.`. Требует нового метода `findAllByUserId`.
- **C. Агрегат** — среднее (или взвешенное) по всем клубам. Требует определения формулы (агрегировать какие поля как).
- **D. Параметризовать команду** — `/мой_рейтинг {clubId|alias}` — overhead UX для редкой команды.

**Scope:** малый bugfix/feature. Решение зависит от продуктового вопроса — отдельная задача с эскалацией.

**Severity:** Medium

---

## GAP-002: Команда `/события` не реализована

**PRD §4.6.2:** «`/события` — список предстоящих событий с кнопкой открытия Mini App.»

**Реальность:** в `ClubsBot.consume` диспатча для `/события` нет (`when {}` обрабатывает только `/start`, `/кто_идет`). Пользователь, введший `/события`, получает silent no-op.

**Impact:** Low. Альтернатива — `/кто_идет` (только ближайшее) или открытие Mini App. Команда фигурирует в PRD как nice-to-have.

**Что нужно:**
- Добавить ветку диспатча в `consume`.
- Реализовать `handleUpcomingEvents`: фетч N ближайших upcoming/stage_1/stage_2 events по всем клубам (или только клубов вызывающего пользователя — продуктовое решение).
- Решение по приватности: показывать ли события private/closed клубов вызывающему — связано с pre-existing privacy вопросом `/кто_идет`.

**Scope:** отдельная фича.

**Severity:** Low

---

## GAP-003: `sendEventCreated` orphan — DM участникам при создании события не приходят

**PRD §4.6.3:** «Уведомление о новом событии в клубе.»
**Статус:** ✅ RESOLVED (`bugfix/event-dm-notification`, 2026-06-06). `EventService.createEvent` (`@Transactional`) публикует `EventCreatedEvent`; `EventBotNotifier` (`@TransactionalEventListener`, AFTER_COMMIT) зовёт `@Async sendEventCreated`. Реализовано через transactional event listener (как payment DM). GAP-010 закрыт вместе. См. `bot-event-dm-not-delivering.md` § Резолюция.

**PRD §4.6.1:** «Автоматическая публикация анонса при создании события (с кнопкой «Открыть в приложении»).»

**Реальность:** `NotificationService.sendEventCreated(event)` определён, шлёт DM каждому участнику клуба и имеет inline-кнопку — корректно. Но **никто его не зовёт**: `grep -r "sendEventCreated" backend/src/main/kotlin` находит только определение. `EventService.createEvent` (основной caller для `POST /api/clubs/{id}/events`) не импортирует `NotificationService`.

**Impact:** Medium. Engagement-критичный gap: organizer создаёт событие → участники не узнают → низкая явка → ниже стимул создавать события. Известное расхождение уже зафиксировано в `docs/backlog/bot-event-dm-not-delivering.md` (там подозревалась проблема с webhook config; реальный root cause — отсутствие вызова).

**Что нужно:**
- В `EventService.createEvent` после `eventRepository.create(...)` вызывать `notificationService.sendEventCreated(eventRecord)`.
- Решить, делать ли вызов синхронным (`@Async` уже на методе — будет неблокирующим) или через transactional event listener (как сделано для payment DM).
- Учесть `[GAP-010]` — фильтр получателей по `membership.status = active` перед production-релизом.
- Учесть rate-limit Telegram (30 msg/sec) для больших клубов — Spring `@Async` без батчинга может выстрелить пачкой.

**Scope:** отдельная bugfix-ветка `bugfix/wire-event-created-dm` (или объединить с GAP-004/005 одним PR).

**Severity:** Medium

**Связанные:** `docs/backlog/bot-event-dm-not-delivering.md` (после фикса этот баг-репорт надо закрыть и уточнить — корень не в webhook config).

---

## GAP-004: `sendStage2Started` orphan — DM при переходе в Stage 2 не приходят

**PRD §4.6.3:** «Напоминание о необходимости подтвердить участие (Этап 2).»
**Статус:** ✅ RESOLVED (`bugfix/stage2-dm-and-slot-races`, 2026-06-13). `Stage2Service.triggerStage2` (после `transitionToStage2` + назначения overflow→waitlist, в той же транзакции) публикует `Stage2StartedEvent`; `bot/Stage2StartedListener` (`@TransactionalEventListener`, AFTER_COMMIT) зовёт `@Async sendStage2Started` — та же схема, что у GAP-003 (`EventBotNotifier`). DM содержит deep-link кнопку «✅ Подтвердить участие» на `/events/{id}`. Получатели — только going/maybe (GAP-009 закрыт тем же PR). = S2T-2 в `two-stage-reputation-bug-register.md`.

**Реальность (была):** `NotificationService.sendStage2Started(event)` определён, но не вызывается. `Stage2Service` (или ARCHITECTURE.md-плановый `Stage2TriggerJob`) не подключает его. Voter'ы не узнают, что Stage 2 начался — заходят в Mini App вручную или пропускают дедлайн.

**Impact:** Medium. Совпадает с GAP-003 — engagement-критично для core-loop. Дополнительная проблема — `sendStage2Started` использовал `findResponderTelegramIdsByEventId`, который слал DM **всем** воутерам, включая `not_going` (см. `[GAP-009]`).

**Что было нужно (выполнено):**
- ✅ В месте перехода event в `stage_2` (после `eventRepository.transitionToStage2`) — вызвать `notificationService.sendStage2Started(event)`.
- ✅ Перед production — закрыть `[GAP-009]` (SQL-метод фильтрует «только going+maybe»).
- ⚠ Rate-limit Telegram (30 msg/sec) — НЕ закрыт, общая проблема всех bulk-DM (SEC-1 в реестре багов).

**Severity:** Medium

---

## GAP-005: `sendAttendanceMarked` orphan — DM отсутствующим не приходят

**PRD §4.6.3:** «Уведомление об отметке «Не пришёл» с кнопкой «Оспорить».»
**Статус:** ✅ RESOLVED (Блок 1, `feature/two-stage-confirmation-gaps`, 2026-06-07 — закрыт как ATT-3, статусная пометка добавлена здесь 2026-06-13 при alignment). `AttendanceService.markAttendance` публикует `AttendanceMarkedEvent`; `bot/AttendanceMarkedListener` (AFTER_COMMIT) зовёт `@Async sendAttendanceMarked` — DM absent-участникам с возможностью оспорить + полный UI спора на `EventPage`. См. `docs/modules/events.md` § «Репутация — Блок 1» → ATT-3.

**Реальность (была):** `NotificationService.sendAttendanceMarked(eventId)` определён, но не вызывается. `AttendanceService` (отвечающий за `POST /api/events/{id}/attendance`) не подключает его.

**Q1 FIX (`feature/refactor-bot`, 2026-05-12):** до рефакторинга метод фильтровал по `event_responses.final_status = declined` — это был баг: имя метода обещает «по attendance», но SQL шёл по `final_status`. Исправлено: фильтр теперь `event_responses.attendance = 'absent'`. Изменение risk-0, потому что метод orphan и caller'ов не было. После подключения этот метод теперь шлёт DM пользователям, кто реально был отмечен `absent`, а не declined в Stage 2.

**Impact:** Medium. После подключения — корректно поддержит «оспорить» flow.

**Что нужно:**
- В `AttendanceService` после отметки `attendance = absent` (или после `attendance_marked = true` на event'е) — вызвать `notificationService.sendAttendanceMarked(eventId)`.
- Учесть rate-limit.
- ~~Подумать: шлём ли DM **всем absent** разом, или **только** только что отмеченным?~~ ✅ **Решено (F5-15.2, `bugfix/attendance-dispute-integrity`):** DM уходит **только** `newlyAbsentUserIds` (тем, чья строка впервые стала `absent` в этой отметке) — `AttendanceMarkedEvent` несёт набор, `sendAttendanceMarked(eventId, newlyAbsentUserIds)`. Повторная отметка не пере-спамит.

**Scope:** отдельная bugfix-ветка. Можно объединить с GAP-003/004.

**Severity:** Medium

---

## GAP-006: Уведомления waitlist / освобождения места не реализованы

**PRD §4.6.3:** «Уведомление о попадании в лист ожидания / освобождении места.»

**Реальность:** в `NotificationService` нет метода `sendWaitlisted` / `sendPromotedFromWaitlist`. Логика waitlist реализована в `EventResponseRepository.findFirstWaitlisted` + `Stage2Service` (по timestamp), но без DM-уведомлений.

**Impact:** Low. Пользователь, попавший в waitlist, не узнаёт об этом до проверки приложения вручную. При промоушене с waitlist → confirmed — тем более критично (упустит шанс подтвердить).

**Что нужно:**
- Два метода в `NotificationService`: `sendWaitlisted(userId, event)`, `sendPromotedFromWaitlist(userId, event)`.
- Вызовы из `Stage2Service` / waitlist promotion job.

**Scope:** отдельная фича.

**Severity:** Low

---

## GAP-007: Уведомления approve/reject заявок в закрытые клубы (**частично закрыт**)

**PRD §4.6.3:** «Уведомление об одобрении / отклонении заявки (закрытые клубы).»
**PRD §4.2.3:** аналогично — после approve пользователь должен узнать.

**Статус (2026-05-30, `feature/applications-inbox`):**
- ✅ **DM организатору при submit** реализован (`NotificationService.sendApplicationCreatedDM`, дёргается из `ApplicationService.submitApplication`). Закрыто покрытие PRD §4.2.2 «Организатор получает push-уведомление через бота в личные сообщения в течение 1 минуты после подачи заявки».
- ❌ **DM заявителю на approve/reject** — по-прежнему отсутствует. Заявитель не узнаёт исхода до следующего открытия Mini App. PRD §4.6.3 буллет 5 в этой части не закрыт.

**Реальность (оставшееся):** в `NotificationService` нет методов `sendApplicationApproved` / `sendApplicationRejected`. `ApplicationService.approveApplication` / `rejectApplication` DM **заявителю** не шлют (для платного approve уходит invoice через `PaymentService.createInvoice` — это де-факто approve-ack, но reject-DM нет вообще).

**Impact:** Medium. Reject — глухой; пользователь видит причину только в `MyClubsPage` / `ProfilePage` при следующем заходе.

**Связанное с payment GAP-6** (две кнопки UX): после approve бесплатного клуба DM не нужен (membership уже создан); для платного — invoice от `createInvoice` де-факто покрывает approve-уведомление.

**Что нужно (для полного закрытия):**
- Методы `sendApplicationApproved` / `sendApplicationRejected` в `NotificationService`.
- Вызовы из `ApplicationService.approveApplication` / `rejectApplication` (после commit, по той же fail-isolated схеме, что и `sendApplicationCreatedDM`).
- Deep-link на клуб (см. payment GAP-9) — `WebAppInfo(url = "${webAppBaseUrl}/clubs/{clubId}")`.

**Scope:** отдельная фича, пересекается с payment GAP-6.

**Severity:** Low (но Medium при работе над закрытыми клубами).

---

## GAP-008: Команда `/кто_идет` без inline-кнопки

> **Update 2026-06-12:** `/мой_рейтинг` удалена — GAP сузился до `/кто_идет`.

**PRD §4.6 AC:** «Все уведомления содержат inline-кнопку для перехода в Mini App.»

**Реальность:** `ClubsBot.handleStart` добавляет inline-кнопку «📱 Открыть Clubs» через `WebAppInfo`. А `handleWhoIsGoing` строит `SendMessage` **без** `replyMarkup` — текст без кнопки. Пользователь, прочитав о ближайшем событии, не имеет one-tap способа перейти в Mini App.

**Impact:** Low. UX-неаккуратность; PRD AC формально нарушен, но «уведомления» в PRD относятся скорее к §4.6.3 (DM), чем к §4.6.2 (команды). Тем не менее — стоит уравнять.

**Что нужно:**
- В `handleWhoIsGoing` и `handleMyRating` собирать `InlineKeyboardMarkup` так же, как в `handleStart` или в `NotificationService.sendDm`. Кандидат на extract в `private fun buildMiniAppMarkup(): InlineKeyboardMarkup` (DRY).

**Scope:** мелкая правка, можно сделать в составе любой bot-задачи.

**Severity:** Low

---

## GAP-009: `sendStage2Started` шлёт DM всем воутерам, включая `not_going`

**PRD §4.6.3:** «Напоминание о необходимости подтвердить участие (Этап 2).»
**Статус:** ✅ RESOLVED (`bugfix/stage2-dm-and-slot-races`, 2026-06-13, одним PR с GAP-004). Метод переименован `findResponderTelegramIdsByEventId` → `findStage2TargetTelegramIds` (ровно как рекомендовано ниже) и получил фильтр `STAGE_1_VOTE IN (going, maybe)`. Других caller'ов у старого метода не было — отдельный «широкий» вариант не нужен. `not_going`-воутеры DM «подтвердите участие» не получают. = часть S2T-2 в `two-stage-reputation-bug-register.md`.

**Реальность (была):** `findResponderTelegramIdsByEventId` (использовался внутри `sendStage2Started`) делал `SELECT USERS.TELEGRAM_ID FROM EVENT_RESPONSES JOIN USERS ON ... WHERE EVENT_RESPONSES.EVENT_ID = :id` — **без фильтра по `stage_1_vote`**. Это значит DM (после подключения GAP-004) ушли бы и тем, кто проголосовал `not_going` — им подтверждать нечего.

**Что было нужно (выполнено):**
- ✅ SQL получил `.and(STAGE_1_VOTE.in(going, maybe))`.
- ✅ Метод переименован в `findStage2TargetTelegramIds` (имя отражает новую семантику).
- ✅ Закрыт **до** включения рассылки GAP-004 (тем же PR, фильтр покрыт `Stage2SlotRaceIntegrationTest`).

**Severity:** Low (зависел от подключения GAP-004).

---

## GAP-010: `findMemberTelegramIds` без фильтра по `membership.status`

**Статус:** ✅ RESOLVED (`bugfix/event-dm-notification`, 2026-06-06). Предикат доступа вынесен в общий `MembershipAccess.hasAccess(now)` (`active` ИЛИ `cancelled` с неистёкшей подпиской; `expired`/`grace_period` исключены) и применён в `isMember`, `isActiveMemberInActiveClub`, `findMemberTelegramIds` (DM) и `JooqEventRepository.findMyFeed` (лента «Активности»). Раньше лента фильтровала по `status = active` only — `cancelled`-но-оплаченный участник получал DM и мог голосовать, но событие не появлялось в его ленте; теперь предикат единый.

**Реальность (была):** `findMemberTelegramIds(clubId)` возвращал все `users.telegram_id` для membership'ов клуба, **без фильтра по `status`** (включая `cancelled`/`expired`/`grace_period`). Использовался только в `sendEventCreated` (был orphan).

**Решение по фильтру:** взят предикат доступа `isMember` / `isActiveMemberInActiveClub` (`active` OR `cancelled` & `subscription_expires_at > now`), а не «active only» и не «active + grace_period». Причина: DM должны получать ровно те, кого `@RequiresMembership` пускает к событию — иначе DM ведёт на недоступный экран.

> ⚠️ **Открытый продуктовый вопрос (drift PRD↔код):** PRD §4.7.3.3 говорит, что в `grace_period` доступ ещё есть, но фактический предикат доступа `grace_period` **не пускает** (`active` и `cancelled`-не-истёкший). Фикс синхронизирован с кодом. Теперь это один helper `MembershipAccess.hasAccess` — если продукт подтвердит, что grace_period должен иметь доступ, правится **в одном месте**, все call-site'ы (доступ, DM, лента) следуют автоматически. `[→ User]`

**Severity:** Low

---

## Privacy notes (pre-existing, не нумерую как GAP)

- `/кто_идет` показывает ближайшее событие по **всем** клубам платформы, включая closed/private, и без проверки membership вызывающего. `title`, `locationText` и счётчики уходят пользователю, не имеющему доступ к клубу. Pre-existing, **не введено** рефакторингом — поведение унаследовано от прошлой версии `ClubsBot`. Эскалировано пользователю отдельно при необходимости (возможно решение: фильтровать по клубам, где пользователь — member).
- `WebAppInfo` URL `https://t.me/clubs_v2_bot/app` hardcoded в двух местах (`ClubsBot.handleStart`, `NotificationService.sendDm`). При появлении staging-бота с собственным URL — оба сломаются. Кандидат на `@Value("\${telegram.web-app-url}")`.

---

## Статус

Все GAP-ы зафиксированы в спеке `docs/modules/telegram-bot.md` (раздел «Расхождения с PRD») как known. После реализации каждой фичи запись удаляется отсюда (git history оставит след) и в спеке обновляется соответствующий раздел.

Приоритет (рекомендация):
1. ~~**GAP-003**~~ (event-created DM) — ✅ закрыт 2026-06-06.
2. ~~**GAP-010**~~ (membership status фильтр) — ✅ закрыт 2026-06-06.
3. ~~**GAP-004**~~ (Stage 2 DM) — ✅ закрыт 2026-06-13 (`bugfix/stage2-dm-and-slot-races`).
4. ~~**GAP-009**~~ (going+maybe фильтр) — ✅ закрыт 2026-06-13, тем же PR.
5. ~~**GAP-005**~~ (attendance DM) — ✅ закрыт в Блоке 1, 2026-06-07 (= ATT-3, `AttendanceMarkedEvent` → `AttendanceMarkedListener`).
6. **GAP-008** (inline-кнопка в `/кто_идет`) — мелочь, в одной из bot-веток.
7. ~~**GAP-001**~~ (`/мой_рейтинг` агрегат) — **закрыт N/A 2026-06-12** (команда удалена).
8. **GAP-002** (`/события` команда) — nice-to-have.
9. **GAP-007** (application approve/reject DM) — с закрытыми клубами.
10. **GAP-006** (waitlist DM) — после Stage 2 stable.
