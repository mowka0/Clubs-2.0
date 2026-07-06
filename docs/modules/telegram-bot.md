# Module: Telegram Bot

**Источник:** `backend/src/main/kotlin/com/clubs/bot/` (`ClubsBot.kt`, `NotificationService.kt`, `BotConfig.kt`; event-листенеры `EventBotNotifier`, `Stage2StartedListener`, `AttendanceMarkedListener`, `AttendanceDisputedListener`, `SkladchinaBotNotifier`; шедулеры `EventReminderScheduler`, `SkladchinaReminderScheduler`).
**PRD:** §4.6 (Telegram-бот), §4.7.3 (платежи — pre_checkout / successful_payment).

Этот модуль описывает **реальное** поведение бота после рефакторинга `feature/refactor-bot` (2026-05-12). Расхождения с PRD §4.6 зафиксированы как gap'ы в `docs/backlog/telegram-bot-prd-gaps.md` — на них здесь стоят ссылки `[GAP-N]`.

## Цель

Telegram-бот `@clubs_admin_bot` — точка входа в Clubs Mini App **и** канал DM-нотификаций. Запускается как Spring `@Component`, реализующий `SpringLongPollingBot` + `LongPollingSingleThreadUpdateConsumer` (long-polling). Обрабатывает три типа Telegram updates: команды-сообщения, `pre_checkout_query`, `successful_payment`. Отдельно — отправка DM-уведомлений из других модулей через `NotificationService`.

## Scope

### Входит (реализовано)

- **Команды**:
  - `/start` — приветствие + inline-кнопка «Открыть Clubs»
  - `/кто_идет` (alias `/kto_idet`) — карточка ближайшего события (по всем клубам)
  - ~~`/мой_рейтинг`~~ — **удалена 2026-06-12** (продуктовое решение: команда не нужна). Репутация видна в Mini App (профиль, карточка участника). Вместе с ней удалён `ReputationRepository.findLatestByUserId` → закрыты баги REP-3/F5-24.
- **Telegram Stars handlers**:
  - `pre_checkout_query` — подтверждение формата payload в течение 10 с
  - `successful_payment` — делегирование в `PaymentService.handleSuccessfulPayment`
- **DM-уведомления (через `NotificationService`)**:
  - `sendDirectMessage(telegramId, text)` — generic DM (используется `PaymentNotificationHandler`, `SubscriptionScheduler`)
  - `sendDirectMessageWithDeepLink(telegramId, text, webAppPath, buttonText)` — DM с inline web_app button на конкретный путь Mini App (используется Skladchina, Applications Inbox)
  - `sendApplicationCreatedDM(organizerTelegramId, applicantDisplayName, clubName)` — organizer-DM при подаче новой заявки в закрытый клуб `[подключено к ApplicationService.submitApplication]`
  - `sendApplicationApprovedDM(applicantTelegramId, clubName, clubId, paid)` — applicant-DM при одобрении заявки, deep-link на клуб (платный → «Оплатить взнос», бесплатный → «Открыть клуб») `[подключено: ApplicationService.approveApplication, сессия 2]`
  - `sendDuesClaimedDM(organizerTelegramId, memberName, clubName, method)` — organizer-DM, когда участник заявил об оплате взноса (de-Stars) `[подключено: AccessGateService.claimDues]`
  - `sendAccessFrozenDM(memberTelegramId, clubName, clubId)` — member-DM, когда организатор закрыл доступ («Закрыть доступ» → frozen): deep-link на клуб, кнопка «Оплатить взнос» `[подключено: AccessGateService.freezeAccess, сессия 4]` `[2026-07-06: UI-кнопка «Закрыть доступ» удалена; эндпоинт/DM живы без UI-вызова — см. docs/backlog/freeze-flow-rethink.md]`
  - `sendEventCreated(event)` — анонс нового события участникам клуба `[подключено: EventBotNotifier @TransactionalEventListener ← EventService.createEvent, GAP-003 ✅ / GAP-010 ✅]`
  - `sendStage2Started(event)` — DM «Этап 2 начался — подтвердите участие» going/maybe-воутерам `[подключено: Stage2StartedListener @TransactionalEventListener ← Stage2Service.triggerStage2, GAP-004 ✅ / GAP-009 ✅ / S2T-2 ✅, 2026-06-13]`
  - `sendWaitlistPromoted(event, promotedUserId)` — DM «🎉 Освободилось место» повышённому из листа ожидания, кнопка на `/events/{id}` `[подключено: WaitlistPromotedListener @TransactionalEventListener ← Stage2Service.declineParticipation И MembershipService (выход из клуба), 2026-07-05]`
  - `sendAttendanceMarked(eventId, newlyAbsentUserIds)` — DM участникам, **впервые** отмеченным `absent` в этой отметке (F5-15.2; раньше — всем `attendance=absent`) `[подключено: AttendanceMarkedListener @TransactionalEventListener ← AttendanceService.markAttendance, GAP-005 ✅ / ATT-3 ✅, Блок 1 2026-06-07]`
  - `sendConfirmReminder(event)` / `sendAttendanceReminder(event, organizerTelegramId)` — poll-напоминания «подтверди участие» (за 2ч) / «отметь явку» (через 24ч), зовутся из `EventReminderScheduler` (Блок 1; детали и дедуп-флаги — `docs/modules/events.md` § «Напоминания событий»)
  - `sendAttendanceDisputed(event, organizerTelegramId, disputerName)` — DM организатору при споре отметки (`AttendanceDisputedListener`, Блок 1, см. `events.md` § ATT-3)
- Generic DM имеют inline-кнопку «📱 Открыть Clubs» с `WebAppInfo("https://t.me/clubs_v2_bot/app")`. Deep-link DM (skladchina / application-created) — кнопку с кастомным `webAppPath` (например `/my-clubs?focus=inbox`).

### НЕ входит (в текущем состоянии кода)

- `/события` — команда не реализована `[GAP-002, PRD §4.6.2]`
- Анонс события в **групповой** чат клуба (PRD §4.6.1 первая буллет) — DM-вариант участникам подключён (`sendEventCreated` через `EventBotNotifier`); публикация в **групповой** чат не реализована.
- Напоминание о голосовании Stage 1 за N дней до события (PRD §4.6.1) — не реализовано.
- Приветственное сообщение при добавлении нового участника в **групповой** чат (PRD §4.6.1) — не реализовано; welcome-DM есть только в payment-flow (`PaymentNotificationHandler.onPaymentConfirmed`).
- Уведомления waitlist / освобождения места (PRD §4.6.3 буллет 3) `[GAP-006]`.
- Уведомления **заявителю** об approve/reject заявок в закрытые клубы (PRD §4.6.3 буллет 5) `[GAP-007]` — заявитель не получает DM. **Частично закрыт:** DM **организатору** при создании заявки реализован (`sendApplicationCreatedDM`, см. § Нотификации ниже).
- Webhook-режим (PRD не уточняет, но `.claude/rules/backend.md` § Telegram Bot API требует в production). Сейчас — long-polling, см. `docs/backlog/bot-event-dm-not-delivering.md`.
- ~~Deep-link на конкретный клуб из DM — кнопка ведёт в корень Mini App.~~ **Закрыто:** `sendApplicationApprovedDM` и `sendAccessFrozenDM` ведут кнопкой на `/clubs/{id}` (где «Оплатить взнос»).

## User Stories

### US-1: Стартовое сообщение

**Как** новый пользователь, нашедший бота
**Я хочу** при первом `/start` получить кнопку открытия приложения
**Чтобы** не искать вручную URL Mini App

### US-2: Быстрый просмотр ближайшего события

**Как** участник клуба
**Я хочу** в любой момент проверить «кто идёт» на ближайшее событие
**Чтобы** не открывать приложение для одного факта

### US-3: Просмотр своей репутации

**Как** участник
**Я хочу** видеть свою репутацию командой бота
**Чтобы** быстро понять текущий статус

### US-4: Оплата подписки

**Как** пользователь, желающий вступить в платный клуб
**Я хочу** оплатить через Telegram Stars и получить подтверждение в DM
**Чтобы** оплата работала в рамках Telegram без редиректов

### US-5: Получение DM-уведомлений

**Как** участник
**Я хочу** получать DM при наступлении важных событий в клубе (новое событие, начало Stage 2, отметка отсутствия)
**Чтобы** не пропускать дедлайны

> US-5 реализована полностью: payment-related DM (`PaymentNotificationHandler`, `SubscriptionScheduler`) + все три event-related DM подключены через transactional event listeners — новое событие (`EventBotNotifier`, GAP-003 ✅), старт Этапа 2 (`Stage2StartedListener`, GAP-004 ✅, 2026-06-13), отметка отсутствия (`AttendanceMarkedListener`, GAP-005 ✅).

## API контракт (Telegram updates handler)

`ClubsBot.consume(update: Update)` — единственная точка входа. Диспатч **строго в этом порядке** (порядок load-bearing):

1. `update.hasPreCheckoutQuery()` → `handlePreCheckoutQuery(query)` → return
2. `update.hasMessage()` == false → return
3. `update.message.hasSuccessfulPayment()` → `paymentService.handleSuccessfulPayment(...)` → return (важно: проверяется **до** `hasText()`, потому что `successful_payment` приходит как message без `text`)
4. `update.message.hasText()` == false → return
5. Диспатч по `text.startsWith(...)`:
   - `/start` → `handleStart(chatId)`
   - `/кто_идет` или `/kto_idet` → `handleWhoIsGoing(chatId)`

Любое исключение во время диспатча команды ловится `catch (e: Exception)` на уровне `consume` и логируется `ERROR`. Long-polling loop при этом не падает.

### `/start`

**Триггер:** message text начинается с `/start`.
**Response (через `telegramClient.execute(SendMessage)`):**
```
👋 Привет! Clubs — платформа для офлайн-сообществ.
Открой приложение, чтобы найти клуб или создать свой:
[📱 Открыть Clubs]  (inline-кнопка с WebAppInfo)
```
**Источник текста:** `ClubsBot.handleStart` (строка 123).

### `/кто_идет` (alias `/kto_idet`)

**Триггер:** message text начинается с `/кто_идет` или `/kto_idet`.
**Логика:**
1. `now = OffsetDateTime.now()`
2. `event = eventRepository.findNextUpcomingEvent(now)` — ближайшее событие со статусом `upcoming|stage_1|stage_2` и `event_datetime > now` **по всем клубам платформы** (без фильтрации по членству вызывающего пользователя).
3. Если `event == null` → ответить «Нет ближайших событий», return.
4. `counts = eventResponseRepository.countByVote(event.id)` — карта `{"going": N, "maybe": N, "notGoing": N}`.
5. Формирование текста (точная цитата из `ClubsBot.handleWhoIsGoing`, строки 157-163):
   ```
   📅 Ближайшее событие: {title}
   📍 {locationText}
   🗓 {eventDatetime, dd.MM.yyyy HH:mm | "не указана"}
   ✅ Пойдут: {goingCount}
   🤔 Возможно: {maybeCount}
   👥 Лимит: {participantLimit}
   ```

**Inline-кнопка:** отсутствует `[GAP-008]`. PRD §4.6 AC требует «Все уведомления содержат inline-кнопку перехода в Mini App».

**Privacy примечание:** команда показывает ближайшее событие любого клуба, в т.ч. closed/private. Поля `title`, `locationText`, счётчики — текущее поведение (pre-existing, не связано с рефакторингом). Эскалировано отдельно в backlog (`docs/backlog/telegram-bot-prd-gaps.md` § Privacy notes).

### ~~`/мой_рейтинг`~~ — удалена (2026-06-12)

Команда и её обработчик `handleMyRating` удалены по продуктовому решению (репутация
доступна в Mini App — профиль и карточка участника, с порогом «право на ошибку»).
Вместе с командой удалён её единственный потребитель `ReputationRepository.findLatestByUserId`
(выбор «последней» строки по `updated_at DESC` + порог `isShown`), что закрыло баги
**REP-3** и **F5-24** в `docs/backlog/two-stage-reputation-bug-register.md`. Исторический
контракт ответа — в git-истории.

### `pre_checkout_query` (Telegram Stars)

**Триггер:** `update.hasPreCheckoutQuery()`.
**Контракт:** Telegram требует ответа **в течение 10 секунд** через `AnswerPreCheckoutQuery`, иначе платёж отменяется.
**Логика (`ClubsBot.handlePreCheckoutQuery`):**
1. `parts = query.invoicePayload.split(":")`
2. `valid = parts.size == 3 && parts[0] == "club_subscription"` (полный формат payload: `club_subscription:{clubId}:{userId}`; см. `docs/modules/payment.md` § «Контракт payload»).
3. Если `valid` → `AnswerPreCheckoutQuery(ok=true)`. Иначе → `ok=false` + `errorMessage="Некорректный формат заказа. Попробуйте вступить снова из приложения."`.
4. `telegramClient.execute(answer)`. Исключения swallow-ятся `log.error(...)` — fail в боте не должен ронять long-polling loop. См. `docs/modules/payment.md` § «Интеграции».

### `successful_payment`

**Триггер:** `update.hasMessage() && update.message.hasSuccessfulPayment()`.
**Логика:** делегирует в `paymentService.handleSuccessfulPayment(telegramId, telegramChargeId, payload, amount)`. Полная семантика, идемпотентность через partial UNIQUE на `transactions.telegram_payment_charge_id` — описана в `docs/modules/payment.md` § «Контракт `handleSuccessfulPayment`».

## Нотификации (`NotificationService`)

Все методы `@Async` (кроме `sendDirectMessage`, который синхронный). Все DM используют общий приватный `sendDm(chatId, text, webAppPath?, buttonText?)`: без опциональных параметров — inline-кнопка «📱 Открыть Clubs» в корень Mini App, с ними — deep-link кнопка на конкретный путь (`/events/{id}` и т.п.). Исключения внутри `sendDm` ловятся и логируются `WARN` — отказ Telegram API не валит вызывающий код.

### `sendDirectMessage(telegramId: Long, text: String)`

**Триггер:** прямой вызов из других модулей.
**Получатель:** один `telegramId`.
**Caller'ы (реальные):**
- `PaymentNotificationHandler.onPaymentConfirmed` — welcome DM после успешной оплаты (`@TransactionalEventListener`, AFTER_COMMIT).
- `SubscriptionScheduler.checkSubscriptions` — два варианта: «истекает через 3 дня» и «вошла в grace_period».
**Текст:** формируется caller'ом.
**Inline-кнопка:** «📱 Открыть Clubs» → корень Mini App.

### `sendApplicationCreatedDM(organizerTelegramId: Long, applicantDisplayName: String, clubName: String)`

**Назначение:** уведомить организатора **закрытого** клуба о новой заявке на вступление. Реализовано в `feature/applications-inbox` (2026-05-30). Полная спека — [`applications-inbox.md`](./applications-inbox.md).
**Триггер:** вызов из `ApplicationService.submitApplication` после INSERT заявки (см. `application.md` § «submitApplication», шаг 9). Fail-isolated: метод `@Async`, исключения внутри `sendDm` ловятся `WARN`-логом, поэтому Telegram API errors **не откатывают** транзакцию заявки.
**Получатель:** один `organizerTelegramId` (Long из `users.telegram_id` владельца клуба).
**Текст** (`NotificationService.kt:88`):
```
📥 Новая заявка от {applicantDisplayName} в клуб «{clubName}»
```
**Inline-кнопка:** «Открыть заявки» — `WebAppInfo(url = "${webAppBaseUrl}/my-clubs?focus=inbox")`. Frontend на `MyClubsPage` читает `?focus=inbox` query, делает `smooth scroll` к organizer-inbox секции, затем стирает query через `setSearchParams({ replace: true })`. См. `applications-inbox.md` § «Deep-link из DM».
**Privacy:** в DM попадают только `applicantDisplayName` (имя из Telegram — публичное) и `clubName` (публичное). `answerText`, `telegramUsername`, `avatarUrl` заявителя — **не отправляются**; organizer видит их после открытия Mini App.
**Edge case:** если у организатора `telegramId` не найден (теоретически не бывает — NOT NULL в БД) — DM пропускается с `WARN`-логом «Skipping application-created DM: organizer not found ownerId=… clubId=…».

### `sendEventCreated(event: Event)` — **подключено** `[GAP-003 ✅, GAP-010 ✅]`

**Назначение (по PRD §4.6.3):** при создании события — DM участникам клуба, имеющим доступ.
**Получатели:** `membershipRepository.findMemberTelegramIds(event.clubId)` — telegram_id участников **с доступом к клубу**: `active` ИЛИ `cancelled` с неистёкшей подпиской (зеркалит предикат `isMember` / `@RequiresMembership`). `expired`/`grace_period` исключены — у них нет доступа к событию, не должны получать о нём DM. `[GAP-010 ✅]`
**Текст** (`NotificationService.kt:37`):
```
🆕 Новое событие в клубе!

📌 {title}
📍 {locationText}
🗓 {eventDatetime, dd.MM.yyyy HH:mm | "TBD"}
👥 Лимит: {participantLimit}

Голосуйте в приложении:
```
**Inline-кнопка:** «📅 Открыть событие» с `WebAppInfo`, deep-link на `webAppPath=/events/{eventId}` — открывает страницу события (голосование) напрямую через React Router, а не корень Mini App.
**Подключение:** `EventService.createEvent` (`@Transactional`) публикует `EventCreatedEvent` после `eventRepository.create()`; `EventBotNotifier.onEventCreated` (`@TransactionalEventListener`, фаза AFTER_COMMIT) вызывает `sendEventCreated` (`@Async` — не блокирует HTTP-ответ при массовой рассылке). Те же транзакционные гарантии, что у Payment/Skladchina DM: при rollback `createEvent` DM не уходят. Per-DM ошибки Telegram ловятся и логируются внутри `sendDm` (fire-and-forget — сбой бота не валит создание события). Пустой список получателей → `WARN` + return.

### `sendStage2Started(event: Event)` — **подключено** `[GAP-004 ✅, GAP-009 ✅, S2T-2 ✅]`

**Назначение (по PRD §4.6.3 / §4.4.2 шаг 1):** при переходе события в `stage_2` — попросить голосовавших подтвердить участие. Реализовано в `bugfix/stage2-dm-and-slot-races` (2026-06-13).
**Получатели (UPDATED 2026-07-05):** `eventResponseRepository.findStage2InviteTelegramIds(event.id)` — участники клуба с доступом, у кого `stage_1_vote IS DISTINCT FROM 'not_going'`, т.е. `going` / `maybe` / **не ответившие** (Этап 2 открыт всем — зовём подтвердить и тех, кто молчал). `not_going` DM не получают (`[GAP-009 ✅]`), но подтвердить участие могут. Раньше слался только going/maybe (`findStage2TargetTelegramIds`).
**Текст** (`NotificationService.kt`):
```
⏰ Этап 2 начался!

📌 {title} — {eventDatetime, dd.MM.yyyy HH:mm}

Подтвердите или откажитесь от участия в приложении:
```
**Inline-кнопка:** «✅ Подтвердить участие» с `WebAppInfo`, deep-link на `webAppPath=/events/{eventId}` — открывает страницу события (кнопки этапа 2) напрямую.
**Подключение:** `Stage2Service.triggerStage2` (в транзакции scheduler'а, после `transitionToStage2`) публикует `Stage2StartedEvent` (`event/Stage2StartedEvent.kt`, несёт snapshot domain-`Event` — DM нужны title/datetime); `bot/Stage2StartedListener.onStage2Started` (`@TransactionalEventListener`, фаза AFTER_COMMIT) вызывает `sendStage2Started` (`@Async`). AFTER_COMMIT обязателен: `@Async`-DM читает строки воутеров на отдельном соединении, которое видит переход только после коммита. Best-effort: ошибки доставки гасятся внутри `sendDm` (`WARN`), сбой Telegram не валит триггер этапа 2. Пустой список получателей → `INFO` + return. Та же схема, что у `EventBotNotifier` (GAP-003) и `AttendanceMarkedListener` (ATT-3). NB (2026-07-05): переход больше НЕ назначает overflow → waitlist — места разыгрываются гонкой подтверждений на Этапе 2 (см. `events.md` § «Логика перехода в Stage 2»).

### `sendWaitlistPromoted(event: Event, promotedUserId: UUID)` — **подключено** `[2026-07-05]`

**Назначение (по PRD §4.4.2 шаг 4):** когда участник автоматически повышен из листа ожидания в `confirmed` (освободился слот), сообщить ему «место ваше» с кнопкой на событие.
**Получатель:** `eventResponseRepository.findTelegramIdsByEventAndUserIds(event.id, [promotedUserId])` — telegram id повышённого из его строки ответа; пусто → `WARN` + return (best-effort).
**Текст** (`NotificationService.kt`):
```
🎉 Освободилось место!

📌 {title} — {eventDatetime, dd.MM.yyyy HH:mm}

Вы перешли из листа ожидания — место ваше. Откройте событие:
```
**Inline-кнопка:** «Открыть событие» с `WebAppInfo`, deep-link на `webAppPath=/events/{eventId}`.
**Подключение:** публикуется `WaitlistPromotedEvent(eventId, promotedUserId)` в ДВУХ местах авто-повышения: `Stage2Service.declineParticipation` (отказ подтверждённого освободил слот) и `MembershipService` (выход подтверждённого из клуба; `promoteFirstWaitlisted` возвращает `UUID?` повышённого). `bot/WaitlistPromotedListener` (`@TransactionalEventListener`, AFTER_COMMIT) дозапрашивает событие по `eventId` и зовёт `@Async sendWaitlistPromoted`. AFTER_COMMIT обязателен: DM читает закоммиченное повышение. Best-effort, зеркалит `sendStage2Started`.

### `sendAttendanceMarked(eventId: UUID, newlyAbsentUserIds: List<UUID>)` — **подключено** `[GAP-005 ✅, ATT-3 ✅]`

**Назначение (по PRD §4.6.3):** уведомить **впервые** отмеченных «Не пришёл» с возможностью оспорить. Подключено в Блоке 1 (`feature/two-stage-confirmation-gaps`, 2026-06-07).
**Получатели (с F5-15.2, `bugfix/attendance-dispute-integrity`):** `eventResponseRepository.findTelegramIdsByEventAndUserIds(eventId, newlyAbsentUserIds)` — telegram-id только тех участников, чья строка **впервые** стала `absent` в этой отметке (id'ы приходят на `AttendanceMarkedEvent.newlyAbsentUserIds`, собраны синхронно в `markAttendance`). Пустой список → ранний return. Раньше DM уходил **всем** `attendance='absent'` (`findTelegramIdsByEventAndAttendance`), из-за чего повторная/корректирующая отметка пере-спамила уже-уведомлённых.
**Историческое примечание:** до рефакторинга `feature/refactor-bot` метод фильтровал по `FINAL_STATUS=declined` (баг — имя обещало attendance, а SQL фильтровал final_status). Исправлено в том рефакторинге; затем (F5-15.2) recipient-набор сузился до newly-absent.
**Текст** (`NotificationService.kt`):
```
📋 Организатор отметил присутствие на событии.

Вас отметили как отсутствующего. Если это ошибка — оспорьте на странице события:
```
**Inline-кнопка:** «Оспорить явку», deep-link на `/events/{eventId}`.
**Подключение:** `AttendanceService.markAttendance` публикует `AttendanceMarkedEvent(eventId, newlyAbsentUserIds)`; `bot/AttendanceMarkedListener` (`@TransactionalEventListener`, AFTER_COMMIT) зовёт `@Async sendAttendanceMarked`. Детали потока спора — `docs/modules/events.md` § «Репутация — Блок 1» → ATT-3 и § «Целостность отметки/спора (пакет 1 F5)».

## Acceptance Criteria

### AC-1: `/start` отдаёт кнопку Mini App

```
GIVEN пользователь впервые открыл @clubs_admin_bot
WHEN отправляет /start
THEN получает текст-приветствие
AND видит inline-кнопку «📱 Открыть Clubs»
AND нажатие открывает Mini App https://t.me/clubs_v2_bot/app
```

### AC-2: `/кто_идет` показывает ближайшее событие или сообщение о его отсутствии

```
GIVEN на платформе есть хотя бы одно событие со статусом upcoming|stage_1|stage_2 и event_datetime > now
WHEN пользователь отправляет /кто_идет
THEN получает карточку ближайшего события с title, locationText, датой, счётчиками going/maybe и participant_limit

GIVEN на платформе нет упомянутых событий
WHEN пользователь отправляет /кто_идет
THEN получает «Нет ближайших событий»
```

### ~~AC-3: `/мой_рейтинг`~~ — снят (команда удалена 2026-06-12)

Команда `/мой_рейтинг` удалена; критерии приёмки сняты. Репутация доступна в Mini App.

### AC-4: `pre_checkout_query` отвечает за <10 секунд

```
GIVEN Telegram присылает pre_checkout_query с payload "club_subscription:{uuid}:{uuid}"
WHEN ClubsBot.consume получает Update
THEN handlePreCheckoutQuery вызывает AnswerPreCheckoutQuery(ok=true) до 10 с

GIVEN payload в неверном формате (parts.size != 3 ИЛИ parts[0] != "club_subscription")
WHEN handler срабатывает
THEN AnswerPreCheckoutQuery(ok=false, errorMessage="Некорректный формат заказа. Попробуйте вступить снова из приложения.")

GIVEN telegramClient.execute бросает исключение
WHEN handlePreCheckoutQuery вызывается
THEN исключение логируется ERROR
AND long-polling loop продолжает работать (consume не пробрасывает наружу)
```

### AC-5: `successful_payment` обрабатывается до `hasText()`

```
GIVEN Telegram присылает Update с message.successfulPayment, message.text == null
WHEN consume диспатчит
THEN paymentService.handleSuccessfulPayment вызван с telegramId, charge_id, payload, amount
AND early-return срабатывает до проверки hasText() (порядок диспатча сохранён)
```

### AC-6: `sendDirectMessage` доставляет DM с inline-кнопкой

```
GIVEN PaymentNotificationHandler ловит PaymentConfirmedEvent после commit
WHEN onPaymentConfirmed вызывает notificationService.sendDirectMessage(telegramId, text)
THEN Telegram-пользователь получает DM с текстом + inline-кнопкой «📱 Открыть Clubs»
AND отказ Telegram API логируется WARN, не пробрасывается caller'у
```

### AC-7: DM участникам при создании события `[GAP-003 ✅ / GAP-010 ✅]`

```
GIVEN organizer создаёт событие через POST /api/clubs/{id}/events
WHEN транзакция EventService.createEvent коммитится
THEN EventBotNotifier (AFTER_COMMIT) вызывает sendEventCreated
AND участники с доступом к клубу (active | cancelled с неистёкшей подпиской)
    получают DM с inline-кнопкой «📱 Открыть Clubs»
AND участники со статусом expired/grace_period DM НЕ получают
AND при rollback createEvent DM не отправляются
```

### AC-8: DM «Этап 2 начался» going/maybe-воутерам `[GAP-004 ✅ / GAP-009 ✅ / S2T-2 ✅]`

```
GIVEN событие переходит в stage_2 (Stage2Service.triggerStage2ForReadyEvents)
WHEN транзакция триггера коммитится
THEN Stage2StartedListener (AFTER_COMMIT) вызывает sendStage2Started
AND воутеры с stage_1_vote IN (going, maybe) получают DM
    с inline-кнопкой «✅ Подтвердить участие» (deep-link /events/{id})
AND воутеры с stage_1_vote = not_going DM НЕ получают
AND при rollback транзакции триггера DM не отправляются
AND отказ Telegram API не откатывает переход в stage_2 (best-effort)
```

## Non-functional

- **Производительность**:
  - Все команды отвечают за <2 с (PRD §4.6 AC). Текущее: 1 SELECT + 1-3 COUNT (для `/кто_идет`) — укладывается.
  - `pre_checkout_query` confirm до 10 с (Telegram API hard limit). Текущее: одна операция execute, без БД-IO.
- **Безопасность**:
  - Bot Token — только env var `TELEGRAM_BOT_TOKEN`. См. `.claude/rules/security.md` § Secrets.
  - Long-polling в production запрещён `.claude/rules/backend.md` § Webhooks vs Long Polling — реальность не соответствует, см. `docs/backlog/bot-event-dm-not-delivering.md`.
  - Команды бота не валидируются на membership/role — `/кто_идет` показывает ближайшее событие **любого** клуба, в т.ч. closed/private. Pre-existing, эскалировано в backlog.
- **Rate limit** (Telegram Bot API):
  - 30 msg/sec на бота — массовые рассылки (`sendEventCreated`, `sendStage2Started`, `sendConfirmReminder`) обязаны учитывать, см. `.claude/rules/backend.md`. Все три **подключены и живые**, но по-прежнему шлют простым `forEach { sendDm(...) }` без батчинга/throttling — для больших клубов требуется ревизия (SEC-1 в `docs/backlog/two-stage-reputation-bug-register.md`).
- **Логирование**:
  - `INFO` — `pre_checkout_query answered` (id, ok, payload), `Payment confirmation DM sent`.
  - `WARN` — `Failed to send DM to chat ...` (в `sendDm`).
  - `ERROR` — исключения в `consume` и `handlePreCheckoutQuery`.
  - PII-маскирование: PRD-payload содержит UUID-ы; telegram_id логируется в INFO/WARN — допустимо для нашего уровня sensitivity.
- **Идемпотентность**: за неё отвечают вызываемые сервисы — `PaymentService.handleSuccessfulPayment` через partial UNIQUE `transactions.telegram_payment_charge_id`. `NotificationService` не идемпотентен (повторный вызов = повторный DM).

## Интеграции

- **`payment` модуль** (`PaymentService`): `handlePreCheckoutQuery` и `successful_payment` диспатчатся в `ClubsBot.consume`; `PaymentNotificationHandler` зовёт `NotificationService.sendDirectMessage` после `PaymentConfirmedEvent`. См. `docs/modules/payment.md` § Интеграции.
- **`event` модуль** (`EventRepository`, `EventResponseRepository`): `findNextUpcomingEvent`, `countByVote` для `/кто_идет`; `findStage2InviteTelegramIds` (участники с доступом кроме `not_going`, для `sendStage2Started`; членство-driven), `findTelegramIdsByEventAndUserIds` (newly-absent набор, для `sendAttendanceMarked` — F5-15.2), `findUnconfirmedVoterTelegramIds` (для `sendConfirmReminder`). Доменные события: `Stage2StartedEvent`, `AttendanceMarkedEvent(eventId, newlyAbsentUserIds)` (+ disputed) — слушатели в bot-пакете.
- **`membership` модуль** (`MembershipRepository`): `findMemberTelegramIds(clubId)` для `sendEventCreated`. См. `docs/modules/membership.md`.
- **Telegram Bot API** (через `TelegramClient` из `BotConfig`):
  - `SendMessage` (команды, DM).
  - `AnswerPreCheckoutQuery` (Stars).
  - `WebAppInfo` URL: `https://t.me/clubs_v2_bot/app` (hardcoded в обоих файлах — кандидат на вынос в env при появлении staging-бота, см. `docs/backlog/bot-event-dm-not-delivering.md`).

## Риски и открытые вопросы

### Расхождения с PRD (см. `docs/backlog/telegram-bot-prd-gaps.md`)

- ~~`[GAP-001]`~~ **снят 2026-06-12**: команда `/мой_рейтинг` удалена (продуктовое решение), вопрос «один клуб vs агрегат» неактуален.
- `[GAP-002]` Команда `/события` (PRD §4.6.2) не реализована.
- ~~`[GAP-003]`~~ ✅ **закрыт 2026-06-06** (`bugfix/event-dm-notification`): `sendEventCreated` подключён через `EventBotNotifier`.
- ~~`[GAP-004]`~~ ✅ **закрыт 2026-06-13** (`bugfix/stage2-dm-and-slot-races`): `sendStage2Started` подключён через `Stage2StartedEvent` → `Stage2StartedListener` (= S2T-2).
- ~~`[GAP-005]`~~ ✅ **закрыт 2026-06-07** (Блок 1, = ATT-3): `sendAttendanceMarked` подключён через `AttendanceMarkedEvent` → `AttendanceMarkedListener`.
- `[GAP-006]` Уведомления waitlist / освобождения места (PRD §4.6.3 буллет 3) не реализованы.
- `[GAP-007]` Уведомления **заявителю** об approve/reject заявок в закрытые клубы (PRD §4.6.3 буллет 5) не реализованы. **Частично закрыт** в `feature/applications-inbox` (2026-05-30): DM **организатору** на submit теперь реализован через `sendApplicationCreatedDM`. Уведомления заявителю об approve/reject — по-прежнему gap.
- `[GAP-008]` Ответ команды `/кто_идет` не содержит inline-кнопку «Открыть Clubs» — нарушает PRD §4.6 AC. (`/мой_рейтинг` удалён.)
- ~~`[GAP-009]`~~ ✅ **закрыт 2026-06-13** (вместе с GAP-004): `findStage2TargetTelegramIds` фильтрует `stage_1_vote IN (going, maybe)`.
- ~~`[GAP-010]`~~ ✅ **закрыт 2026-06-06**: `findMemberTelegramIds` фильтрует по предикату доступа `MembershipAccess.hasAccess`.

### Прочее

- **Long-polling vs webhook**: см. `docs/backlog/bot-event-dm-not-delivering.md` — отдельный вопрос staging vs prod-бота.
- **Privacy `/кто_идет`**: команда обходит membership-check. Pre-existing. Эскалировано отдельно.
- **`WebAppInfo` URL hardcoded** в двух местах (`ClubsBot.handleStart`, `NotificationService.sendDm`) — при переезде на staging-бот сломается. Кандидат на `@Value` config.
- **Rate-limit massовых DM**: при подключении orphan-методов в клубах с большим числом участников надо вводить батчинг / Redis-очередь (ARCHITECTURE.md §4 планирует `notification/` модуль с `NotificationConsumer` через Redis — пока aspirational, не реализован).
