# Telegram bot: known PRD ↔ code gaps

Расхождения между PRD §4.6 и реальной реализацией bot-модуля (после ветки `feature/refactor-bot`, 2026-05-12). Все — **существующие** gap'ы, выявленные post-flight аналитикой. Не являются регрессиями рефакторинга — это нереализованные / частично реализованные фичи в текущем продакшен-коде. Зафиксированы здесь, чтобы не потерялись и чтобы спека `docs/modules/telegram-bot.md` не выглядела «полной».

**Источник:** сверка `PRD-Clubs.md` §4.6 ↔ `backend/src/main/kotlin/com/clubs/bot/` + сверка `EventService` / `Stage2Service` / `AttendanceService` на отсутствие вызовов `NotificationService.send*`.

---

## GAP-001: `/мой_рейтинг` показывает репутацию одного клуба, не агрегат

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

**Реальность:** в `ClubsBot.consume` диспатча для `/события` нет (`when {}` обрабатывает только `/start`, `/кто_идет`, `/мой_рейтинг`). Пользователь, введший `/события`, получает silent no-op.

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

**Реальность:** `NotificationService.sendStage2Started(event)` определён, но не вызывается. `Stage2Service` (или ARCHITECTURE.md-плановый `Stage2TriggerJob`) не подключает его. Voter'ы не узнают, что Stage 2 начался — заходят в Mini App вручную или пропускают дедлайн.

**Impact:** Medium. Совпадает с GAP-003 — engagement-критично для core-loop. Дополнительная проблема — `sendStage2Started` сейчас использует `findResponderTelegramIdsByEventId`, который шлёт DM **всем** воутерам, включая `not_going` (см. `[GAP-009]`).

**Что нужно:**
- В месте перехода event в `stage_2` (после `eventRepository.transitionToStage2`) — вызвать `notificationService.sendStage2Started(eventRecord)`.
- Перед production — закрыть `[GAP-009]` (сменить SQL-метод на «только going+maybe»).
- Учесть rate-limit Telegram аналогично GAP-003.

**Scope:** отдельная bugfix-ветка. Можно объединить с GAP-003/005.

**Severity:** Medium

---

## GAP-005: `sendAttendanceMarked` orphan — DM отсутствующим не приходят

**PRD §4.6.3:** «Уведомление об отметке «Не пришёл» с кнопкой «Оспорить».»

**Реальность:** `NotificationService.sendAttendanceMarked(eventId)` определён, но не вызывается. `AttendanceService` (отвечающий за `POST /api/events/{id}/attendance`) не подключает его.

**Q1 FIX (`feature/refactor-bot`, 2026-05-12):** до рефакторинга метод фильтровал по `event_responses.final_status = declined` — это был баг: имя метода обещает «по attendance», но SQL шёл по `final_status`. Исправлено: фильтр теперь `event_responses.attendance = 'absent'`. Изменение risk-0, потому что метод orphan и caller'ов не было. После подключения этот метод теперь шлёт DM пользователям, кто реально был отмечен `absent`, а не declined в Stage 2.

**Impact:** Medium. После подключения — корректно поддержит «оспорить» flow.

**Что нужно:**
- В `AttendanceService` после отметки `attendance = absent` (или после `attendance_marked = true` на event'е) — вызвать `notificationService.sendAttendanceMarked(eventId)`.
- Учесть rate-limit.
- Подумать: шлём ли DM **всем absent** разом после `markAttendance`, или **только** тем, кто только что был отмечен? (Сейчас метод шлёт всем, кто absent — при повторном вызове придут повторные DM.)

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

## GAP-008: Команды `/кто_идет` и `/мой_рейтинг` без inline-кнопки

**PRD §4.6 AC:** «Все уведомления содержат inline-кнопку для перехода в Mini App.»

**Реальность:** `ClubsBot.handleStart` добавляет inline-кнопку «📱 Открыть Clubs» через `WebAppInfo`. А `handleWhoIsGoing` и `handleMyRating` строят `SendMessage` **без** `replyMarkup` — текст без кнопки. Пользователь, прочитав о ближайшем событии, не имеет one-tap способа перейти в Mini App.

**Impact:** Low. UX-неаккуратность; PRD AC формально нарушен, но «уведомления» в PRD относятся скорее к §4.6.3 (DM), чем к §4.6.2 (команды). Тем не менее — стоит уравнять.

**Что нужно:**
- В `handleWhoIsGoing` и `handleMyRating` собирать `InlineKeyboardMarkup` так же, как в `handleStart` или в `NotificationService.sendDm`. Кандидат на extract в `private fun buildMiniAppMarkup(): InlineKeyboardMarkup` (DRY).

**Scope:** мелкая правка, можно сделать в составе любой bot-задачи.

**Severity:** Low

---

## GAP-009: `sendStage2Started` шлёт DM всем воутерам, включая `not_going`

**PRD §4.6.3:** «Напоминание о необходимости подтвердить участие (Этап 2).»

**Реальность:** `findResponderTelegramIdsByEventId` (используется внутри `sendStage2Started`) делает `SELECT USERS.TELEGRAM_ID FROM EVENT_RESPONSES JOIN USERS ON ... WHERE EVENT_RESPONSES.EVENT_ID = :id` — **без фильтра по `stage_1_vote`**. Это значит DM (после подключения GAP-004) уйдут и тем, кто проголосовал `not_going` — им подтверждать нечего.

**Impact:** Low **сейчас** (метод orphan). После подключения — медиум: пользователи, явно отказавшиеся, получат «подтвердите участие» — выглядит как баг бота.

**Что нужно:**
- Перед подключением GAP-004 либо изменить SQL `findResponderTelegramIdsByEventId` (добавить `.and(STAGE_1_VOTE.in(going, maybe))`), либо ввести отдельный метод `findGoingMaybeTelegramIds` и оставить старый для возможных других caller'ов.
- Имя метода `findResponderTelegramIdsByEventId` сейчас честное (любой responder); если фильтровать — переименовать в `findStage2TargetTelegramIds`.

**Scope:** мелкая правка в EventResponseRepository, обязательно **перед** закрытием GAP-004.

**Severity:** Low (зависит от подключения GAP-004).

---

## GAP-010: `findMemberTelegramIds` без фильтра по `membership.status`

**Источник:** уже зафиксировано в `JooqMembershipRepository.kt:248-250` (комментарий) и в `docs/backlog/bot-event-dm-not-delivering.md` § Связанные. Дублирую здесь, чтобы было видно при работе с bot.

**Реальность:** `findMemberTelegramIds(clubId)` возвращает все `users.telegram_id` для membership'ов клуба, **без фильтра по `status`**. Включает `cancelled`, `expired`, `grace_period`. Используется только в `sendEventCreated` (orphan).

**Impact:** Low **сейчас**. После подключения GAP-003 — Low/Medium: пользователи, отписавшиеся от клуба (`cancelled`/`expired`), будут получать DM о новых событиях — нарушение приватности и неприятный UX.

**Что нужно:**
- Перед закрытием GAP-003 — добавить `.and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active))` (или `.in(active, grace_period)`, в зависимости от продукта).
- Решение по `grace_period`: PRD §4.7.3.3 говорит, что в grace_period доступ ещё есть, но платёж не прошёл. Скорее всего, включать.

**Scope:** мелкая правка в `JooqMembershipRepository`, обязательно перед закрытием GAP-003.

**Severity:** Low

---

## Privacy notes (pre-existing, не нумерую как GAP)

- `/кто_идет` показывает ближайшее событие по **всем** клубам платформы, включая closed/private, и без проверки membership вызывающего. `title`, `locationText` и счётчики уходят пользователю, не имеющему доступ к клубу. Pre-existing, **не введено** рефакторингом — поведение унаследовано от прошлой версии `ClubsBot`. Эскалировано пользователю отдельно при необходимости (возможно решение: фильтровать по клубам, где пользователь — member).
- `WebAppInfo` URL `https://t.me/clubs_v2_bot/app` hardcoded в двух местах (`ClubsBot.handleStart`, `NotificationService.sendDm`). При появлении staging-бота с собственным URL — оба сломаются. Кандидат на `@Value("\${telegram.web-app-url}")`.

---

## Статус

Все GAP-ы зафиксированы в спеке `docs/modules/telegram-bot.md` (раздел «Расхождения с PRD») как known. После реализации каждой фичи запись удаляется отсюда (git history оставит след) и в спеке обновляется соответствующий раздел.

Приоритет (рекомендация):
1. **GAP-003** (event-created DM) — engagement-критичный для MVP.
2. **GAP-010** (membership status фильтр) — обязательное условие для безопасного включения GAP-003.
3. **GAP-004** (Stage 2 DM) — engagement, core-loop.
4. **GAP-009** (going+maybe фильтр) — обязательное условие для GAP-004.
5. **GAP-005** (attendance DM) — UX dispute flow.
6. **GAP-008** (inline-кнопка в командах) — мелочь, в одной из bot-веток.
7. **GAP-001** (`/мой_рейтинг` агрегат) — требует продуктового решения.
8. **GAP-002** (`/события` команда) — nice-to-have.
9. **GAP-007** (application approve/reject DM) — с закрытыми клубами.
10. **GAP-006** (waitlist DM) — после Stage 2 stable.
