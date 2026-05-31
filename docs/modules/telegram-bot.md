# Module: Telegram Bot

**Источник:** `backend/src/main/kotlin/com/clubs/bot/` (`ClubsBot.kt`, `NotificationService.kt`, `BotConfig.kt`).
**PRD:** §4.6 (Telegram-бот), §4.7.3 (платежи — pre_checkout / successful_payment).

Этот модуль описывает **реальное** поведение бота после рефакторинга `feature/refactor-bot` (2026-05-12). Расхождения с PRD §4.6 зафиксированы как gap'ы в `docs/backlog/telegram-bot-prd-gaps.md` — на них здесь стоят ссылки `[GAP-N]`.

## Цель

Telegram-бот `@clubs_admin_bot` — точка входа в Clubs Mini App **и** канал DM-нотификаций. Запускается как Spring `@Component`, реализующий `SpringLongPollingBot` + `LongPollingSingleThreadUpdateConsumer` (long-polling). Обрабатывает три типа Telegram updates: команды-сообщения, `pre_checkout_query`, `successful_payment`. Отдельно — отправка DM-уведомлений из других модулей через `NotificationService`.

## Scope

### Входит (реализовано)

- **Команды**:
  - `/start` — приветствие + inline-кнопка «Открыть Clubs»
  - `/кто_идет` (alias `/kto_idet`) — карточка ближайшего события (по всем клубам)
  - `/мой_рейтинг` (alias `/moy_reyting`) — репутация пользователя
- **Telegram Stars handlers**:
  - `pre_checkout_query` — подтверждение формата payload в течение 10 с
  - `successful_payment` — делегирование в `PaymentService.handleSuccessfulPayment`
- **DM-уведомления (через `NotificationService`)**:
  - `sendDirectMessage(telegramId, text)` — generic DM (используется `PaymentNotificationHandler`, `SubscriptionScheduler`)
  - `sendDirectMessageWithDeepLink(telegramId, text, webAppPath, buttonText)` — DM с inline web_app button на конкретный путь Mini App (используется Skladchina, Applications Inbox)
  - `sendApplicationCreatedDM(organizerTelegramId, applicantDisplayName, clubName)` — organizer-DM при подаче новой заявки в закрытый клуб `[подключено к ApplicationService.submitApplication]`
  - `sendEventCreated(event)` — анонс нового события участникам клуба `[не подключено, GAP-003]`
  - `sendStage2Started(event)` — напоминание о подтверждении Stage 2 `[не подключено, GAP-004]`
  - `sendAttendanceMarked(eventId)` — DM пользователям с `attendance=absent` `[не подключено, GAP-005]`
- Generic DM имеют inline-кнопку «📱 Открыть Clubs» с `WebAppInfo("https://t.me/clubs_v2_bot/app")`. Deep-link DM (skladchina / application-created) — кнопку с кастомным `webAppPath` (например `/my-clubs?focus=inbox`).

### НЕ входит (в текущем состоянии кода)

- `/события` — команда не реализована `[GAP-002, PRD §4.6.2]`
- Анонс события в **групповой** чат клуба (PRD §4.6.1 первая буллет) — DM-вариант есть в коде (`sendEventCreated`), но он orphan; групповая публикация не реализована вообще.
- Напоминание о голосовании Stage 1 за N дней до события (PRD §4.6.1) — не реализовано.
- Приветственное сообщение при добавлении нового участника в **групповой** чат (PRD §4.6.1) — не реализовано; welcome-DM есть только в payment-flow (`PaymentNotificationHandler.onPaymentConfirmed`).
- Уведомления waitlist / освобождения места (PRD §4.6.3 буллет 3) `[GAP-006]`.
- Уведомления **заявителю** об approve/reject заявок в закрытые клубы (PRD §4.6.3 буллет 5) `[GAP-007]` — заявитель не получает DM. **Частично закрыт:** DM **организатору** при создании заявки реализован (`sendApplicationCreatedDM`, см. § Нотификации ниже).
- Webhook-режим (PRD не уточняет, но `.claude/rules/backend.md` § Telegram Bot API требует в production). Сейчас — long-polling, см. `docs/backlog/bot-event-dm-not-delivering.md`.
- Deep-link на конкретный клуб из DM — кнопка ведёт в корень Mini App.

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

> US-5 описывает целевое поведение. Сейчас фактически работают только payment-related DM (`PaymentNotificationHandler`, `SubscriptionScheduler`); event-related DM-методы определены но не зовутся (`[GAP-003/004/005]`).

## API контракт (Telegram updates handler)

`ClubsBot.consume(update: Update)` — единственная точка входа. Диспатч **строго в этом порядке** (порядок load-bearing):

1. `update.hasPreCheckoutQuery()` → `handlePreCheckoutQuery(query)` → return
2. `update.hasMessage()` == false → return
3. `update.message.hasSuccessfulPayment()` → `paymentService.handleSuccessfulPayment(...)` → return (важно: проверяется **до** `hasText()`, потому что `successful_payment` приходит как message без `text`)
4. `update.message.hasText()` == false → return
5. Диспатч по `text.startsWith(...)`:
   - `/start` → `handleStart(chatId)`
   - `/кто_идет` или `/kto_idet` → `handleWhoIsGoing(chatId)`
   - `/мой_рейтинг` или `/moy_reyting` → `handleMyRating(chatId, telegramId)`

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

### `/мой_рейтинг` (alias `/moy_reyting`)

**Триггер:** message text начинается с `/мой_рейтинг` или `/moy_reyting`.
**Логика:**
1. Если `update.message.from?.id == null` → ответить «Не удалось определить ваш Telegram ID.», return.
2. `user = userRepository.findByTelegramId(telegramId)` — `UsersRecord?`.
3. Если `user == null` → ответить «Вы ещё не зарегистрированы в Clubs. Откройте приложение, чтобы начать!», return.
4. `reputation = reputationRepository.findLatestByUserId(user.id)` — **репутация одного клуба**, выбранного по `ORDER BY USER_CLUB_REPUTATION.UPDATED_AT DESC LIMIT 1`. Это **не агрегат** `[GAP-001]`.
5. Если `reputation == null` → ответить «Репутация ещё не сформирована. Участвуйте в событиях клуба!», return.
6. Формирование текста (`ClubsBot.handleMyRating`, строки 207-212):
   ```
   ⭐ Ваша репутация:
   Индекс надёжности: {reliabilityIndex}
   Выполнение обещаний: {promiseFulfillmentPct}%
   Подтверждений: {totalConfirmations}
   ```

**Inline-кнопка:** отсутствует `[GAP-008]`.

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

Все методы `@Async` (кроме `sendDirectMessage`, который синхронный). Все DM используют общий приватный `sendDm(chatId, text)`, добавляющий inline-кнопку «📱 Открыть Clubs». Исключения внутри `sendDm` ловятся и логируются `WARN` — отказ Telegram API не валит вызывающий код.

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

### `sendEventCreated(event: EventsRecord)` — **orphan** `[GAP-003]`

**Назначение (по PRD §4.6.3):** при создании события — DM всем участникам клуба.
**Получатели:** `membershipRepository.findMemberTelegramIds(event.clubId)` — возвращает все telegram_id из `MEMBERSHIPS` для клуба **без фильтра по `status`** (т.е. включая `cancelled`/`expired`/`grace_period`). См. комментарий в `JooqMembershipRepository.kt:248-250` и `docs/backlog/bot-event-dm-not-delivering.md` § Связанные. `[GAP-010]`
**Текст** (`NotificationService.kt:37`):
```
🆕 Новое событие в клубе!

📌 {title}
📍 {locationText}
🗓 {eventDatetime, dd.MM.yyyy HH:mm | "TBD"}
👥 Лимит: {participantLimit}

Голосуйте в приложении:
```
**Текущее состояние:** метод определён, но `EventService.createEvent` его не зовёт — DM не доставляются.

### `sendStage2Started(event: EventsRecord)` — **orphan** `[GAP-004]`

**Назначение (по PRD §4.6.3):** при переходе события в `stage_2` — напомнить голосовавшим (going+maybe) подтвердить участие.
**Получатели:** `eventResponseRepository.findResponderTelegramIdsByEventId(event.id)` — **все** воутеры event (любой `stage_1_vote`, включая `not_going`). PRD требует только going+maybe → `[GAP-009]`.
**Текст** (`NotificationService.kt:50`):
```
⏰ Этап 2 начался!

📌 {title} — {eventDatetime, dd.MM.yyyy HH:mm}

Подтвердите или откажитесь от участия в приложении:
```
**Текущее состояние:** метод определён, `Stage2Service` (или scheduler `Stage2TriggerJob` из ARCHITECTURE.md плана) его не зовёт.

### `sendAttendanceMarked(eventId: UUID)` — **orphan** `[GAP-005]`

**Назначение (по PRD §4.6.3):** уведомить отсутствующих с возможностью оспорить.
**Получатели:** `eventResponseRepository.findTelegramIdsByEventAndAttendance(eventId, ATTENDANCE=absent)` — пользователи с `event_responses.attendance = 'absent'`.
**Историческое примечание:** до рефакторинга `feature/refactor-bot` метод фильтровал по `FINAL_STATUS=declined` (баг — имя обещало attendance, а SQL фильтровал final_status). Исправлено в этом рефакторинге с согласия пользователя; функциональное изменение risk-0, потому что caller-ов не было.
**Текст** (`NotificationService.kt:63`):
```
📋 Организатор отметил присутствие на событии.

Вас отметили как отсутствующего. Если это ошибка — оспорьте в приложении:
```
**Текущее состояние:** метод определён, `AttendanceService` его не зовёт.

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

### AC-3: `/мой_рейтинг` показывает репутацию или соответствующее сообщение

```
GIVEN telegram_id не определён в Update
WHEN пользователь отправляет /мой_рейтинг
THEN получает «Не удалось определить ваш Telegram ID.»

GIVEN telegram_id определён, но в users отсутствует запись
WHEN отправляет /мой_рейтинг
THEN получает «Вы ещё не зарегистрированы в Clubs. Откройте приложение, чтобы начать!»

GIVEN пользователь зарегистрирован, но в user_club_reputation нет ни одной строки
WHEN отправляет /мой_рейтинг
THEN получает «Репутация ещё не сформирована. Участвуйте в событиях клуба!»

GIVEN пользователь имеет ≥1 строку в user_club_reputation
WHEN отправляет /мой_рейтинг
THEN получает текст с reliability_index, promise_fulfillment_pct, total_confirmations
   из строки с MAX(updated_at) (одна, не агрегат — [GAP-001])
```

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

### AC-7: Orphan-методы не доставляют DM (текущее поведение)

```
GIVEN organizer создаёт событие через POST /api/clubs/{id}/events
WHEN EventService.createEvent отрабатывает
THEN sendEventCreated НЕ вызывается (метод orphan)
AND участники клуба НЕ получают DM
   — известное расхождение с PRD §4.6.3, см. [GAP-003]
```

(Аналогичные негативные AC для Stage 2 и attendance — см. `[GAP-004]`, `[GAP-005]`.)

## Non-functional

- **Производительность**:
  - Все команды отвечают за <2 с (PRD §4.6 AC). Текущее: 1 SELECT + 1-3 COUNT (для `/кто_идет`) или 2 SELECT (`/мой_рейтинг`) — укладывается.
  - `pre_checkout_query` confirm до 10 с (Telegram API hard limit). Текущее: одна операция execute, без БД-IO.
- **Безопасность**:
  - Bot Token — только env var `TELEGRAM_BOT_TOKEN`. См. `.claude/rules/security.md` § Secrets.
  - Long-polling в production запрещён `.claude/rules/backend.md` § Webhooks vs Long Polling — реальность не соответствует, см. `docs/backlog/bot-event-dm-not-delivering.md`.
  - Команды бота не валидируются на membership/role — `/кто_идет` показывает ближайшее событие **любого** клуба, в т.ч. closed/private. Pre-existing, эскалировано в backlog.
- **Rate limit** (Telegram Bot API):
  - 30 msg/sec на бота — массовые рассылки (`sendEventCreated`, `sendStage2Started`) обязаны учитывать, см. `.claude/rules/backend.md`. Сейчас orphan-методы используют простой `forEach { sendDm(...) }` без батчинга/throttling — при подключении и больших клубах требуется ревизия.
- **Логирование**:
  - `INFO` — `pre_checkout_query answered` (id, ok, payload), `Payment confirmation DM sent`.
  - `WARN` — `Failed to send DM to chat ...` (в `sendDm`).
  - `ERROR` — исключения в `consume` и `handlePreCheckoutQuery`.
  - PII-маскирование: PRD-payload содержит UUID-ы; telegram_id логируется в INFO/WARN — допустимо для нашего уровня sensitivity.
- **Идемпотентность**: за неё отвечают вызываемые сервисы — `PaymentService.handleSuccessfulPayment` через partial UNIQUE `transactions.telegram_payment_charge_id`. `NotificationService` не идемпотентен (повторный вызов = повторный DM).

## Интеграции

- **`payment` модуль** (`PaymentService`): `handlePreCheckoutQuery` и `successful_payment` диспатчатся в `ClubsBot.consume`; `PaymentNotificationHandler` зовёт `NotificationService.sendDirectMessage` после `PaymentConfirmedEvent`. См. `docs/modules/payment.md` § Интеграции.
- **`event` модуль** (`EventRepository`, `EventResponseRepository`): `findNextUpcomingEvent`, `countByVote` для `/кто_идет`; `findResponderTelegramIdsByEventId`, `findTelegramIdsByEventAndAttendance` — для orphan-методов нотификаций.
- **`user` модуль** (`UserRepository`): `findByTelegramId` для `/мой_рейтинг`.
- **`reputation` модуль** (`ReputationRepository`): `findLatestByUserId` для `/мой_рейтинг`.
- **`membership` модуль** (`MembershipRepository`): `findMemberTelegramIds(clubId)` для orphan `sendEventCreated`. См. `docs/modules/membership.md`.
- **Telegram Bot API** (через `TelegramClient` из `BotConfig`):
  - `SendMessage` (команды, DM).
  - `AnswerPreCheckoutQuery` (Stars).
  - `WebAppInfo` URL: `https://t.me/clubs_v2_bot/app` (hardcoded в обоих файлах — кандидат на вынос в env при появлении staging-бота, см. `docs/backlog/bot-event-dm-not-delivering.md`).

## Риски и открытые вопросы

### Расхождения с PRD (см. `docs/backlog/telegram-bot-prd-gaps.md`)

- `[GAP-001]` `/мой_рейтинг` возвращает репутацию одного клуба (по MAX(updated_at)), а не агрегат. PRD §4.6.2 не уточняет — требует решения пользователя.
- `[GAP-002]` Команда `/события` (PRD §4.6.2) не реализована.
- `[GAP-003]` `sendEventCreated` не подключён к `EventService.createEvent` — DM участникам клуба не приходят.
- `[GAP-004]` `sendStage2Started` не подключён к Stage 2 переходу.
- `[GAP-005]` `sendAttendanceMarked` не подключён к `AttendanceService` после Q1-фикса (баг с фильтром закрыт, метод теперь корректен).
- `[GAP-006]` Уведомления waitlist / освобождения места (PRD §4.6.3 буллет 3) не реализованы.
- `[GAP-007]` Уведомления **заявителю** об approve/reject заявок в закрытые клубы (PRD §4.6.3 буллет 5) не реализованы. **Частично закрыт** в `feature/applications-inbox` (2026-05-30): DM **организатору** на submit теперь реализован через `sendApplicationCreatedDM`. Уведомления заявителю об approve/reject — по-прежнему gap.
- `[GAP-008]` Ответы команд `/кто_идет` и `/мой_рейтинг` не содержат inline-кнопку «Открыть Clubs» — нарушает PRD §4.6 AC.
- `[GAP-009]` `sendStage2Started` (orphan) шлёт всем воутерам, включая `not_going`. PRD §4.6.3 требует только going+maybe.
- `[GAP-010]` `findMemberTelegramIds` (membership module) возвращает все membership rows клуба без фильтра по `status` — `sendEventCreated` при подключении будет слать DM и `cancelled`/`expired`. Pre-existing, дублирует комментарий в `JooqMembershipRepository.kt:248` и упомянуто в `docs/backlog/bot-event-dm-not-delivering.md`.

### Прочее

- **Long-polling vs webhook**: см. `docs/backlog/bot-event-dm-not-delivering.md` — отдельный вопрос staging vs prod-бота.
- **Privacy `/кто_идет`**: команда обходит membership-check. Pre-existing. Эскалировано отдельно.
- **`WebAppInfo` URL hardcoded** в двух местах (`ClubsBot.handleStart`, `NotificationService.sendDm`) — при переезде на staging-бот сломается. Кандидат на `@Value` config.
- **Rate-limit massовых DM**: при подключении orphan-методов в клубах с большим числом участников надо вводить батчинг / Redis-очередь (ARCHITECTURE.md §4 планирует `notification/` модуль с `NotificationConsumer` через Redis — пока aspirational, не реализован).
