# Module: Payment

Оплата подписки на клуб через Telegram Stars и жизненный цикл подписки.
Соответствует PRD §4.7 и §4.3 (жизненный цикл подписки). Источник истины для бизнес-правил — PRD.

## Цель
Принять платёж Telegram Stars за месячную подписку на клуб, создать/продлить membership, записать транзакцию, прогнать lifecycle (active → grace_period → expired) по расписанию.

## Scope
### Входит
- Создание Stars-инвойса (DM в Telegram) при вступлении в клуб
- Обработка `successful_payment` от Telegram Bot API: создание/продление membership + запись транзакции
- Scheduler: предупреждения за 3 дня, перевод истёкших в grace_period, финальное истечение через 3 дня
- Идемпотентность webhook `successful_payment` (Telegram может повторить)
- Комиссия платформы 20%, выручка организатора 80% (PRD §4.7.1)

### НЕ входит (из этой итерации)
- Отмена подписки пользователем (статус `cancelled` из PRD §4.3.5 — `enum` есть, UX/API нет)
- Refunds (PRD §4.7 упоминает audit trail, но без flow отмены)
- Ручной payout организатору (Telegram Stars API, отдельная задача)
- Уведомление об успешной оплате (сейчас только лог, без DM)

## Архитектура

```
ClubsBot (pre_checkout_query, successful_payment)
    │
    ▼
PaymentService ──▶ ClubRepository, UserRepository,
    │                MembershipRepository, TransactionRepository
    │
    └── @Transactional handleSuccessfulPayment

@Scheduled(cron="0 0 9 * * *") SubscriptionScheduler
    │
    ▼
MembershipRepository (bulk updates, expiring notifications)
    │
    └──▶ ClubRepository.decrementMemberCount / NotificationService
```

### Файлы (целевая структура)
| Файл | Роль |
|---|---|
| `payment/PaymentService.kt` | Оркестрация: инвойс, handler, идемпотентность |
| `payment/SubscriptionScheduler.kt` | Cron-entry: notifications + делегирование lifecycle |
| `payment/SubscriptionLifecycleService.kt` | Транзакционная часть lifecycle (read-expiring / grace-period transitions) |
| `payment/Transaction.kt` | Domain data class |
| `payment/TransactionRepository.kt` | Интерфейс CRUD транзакций + проверка идемпотентности |
| `payment/JooqTransactionRepository.kt` | Реализация с jOOQ |
| `payment/TransactionMapper.kt` | `TransactionsRecord` → `Transaction` |

### Хранение
Таблица `transactions` (V8, V12):
- `amount`, `platform_fee`, `organizer_revenue` INT (Stars, неотрицательные)
- `type` enum: `subscription`, `renewal`
- `status` enum: `completed`, `failed`, `refunded`
- `telegram_payment_charge_id` VARCHAR(255) — **partial UNIQUE** (WHERE NOT NULL), ключ идемпотентности
- `membership_id` опционален (пишется, если уже существует на момент транзакции)

Таблица `memberships`:
- `status` enum: `pending`, `active`, `grace_period`, `expired`, `cancelled`
- `subscription_expires_at` TIMESTAMPTZ — момент истечения оплаченного периода

## Бизнес-правила (из PRD §4.7, §4.3)

### Стоимость и комиссия
- Цена месячной подписки задаётся организатором в Stars (`clubs.subscription_price`).
- `platform_fee = floor(amount * 0.20)`
- `organizer_revenue = amount − platform_fee`
- Цена **всегда с сервера** (из `clubs.subscription_price`) — клиентскому значению amount не доверяем при построении инвойса.

### Создание инвойса
- Free-клуб (`subscription_price = 0` или `null`) → инвойс **не создаётся**, функция возвращает без действия. Создание membership для free-клубов — зона ответственности `membership` модуля (не payment).
- Платный клуб → `SendInvoice` в DM пользователю, `currency = "XTR"`, `payload = "club_subscription:{clubId}:{userId}"`.

### Обработка successful_payment
Триггер: `ClubsBot` получает `successful_payment`, передаёт в `PaymentService.handleSuccessfulPayment`.

Parsing payload: `"club_subscription:{clubId}:{userId}"`. Некорректный payload → `WARN`-лог и ранний возврат (не роняем поток).

1. **Санити-чек**: `amount > 0`. Иначе `WARN` и ранний возврат.
2. **Идемпотентность (fast path):** если транзакция с `telegram_payment_charge_id` уже есть → `INFO`-лог, выход.
3. Сверка amount с `clubs.subscription_price`: если расходится — `WARN` (audit), не блокируем (цена могла смениться между созданием инвойса и оплатой; Telegram гарантирует что amount == то что мы выставляли в SendInvoice).
4. Найти membership по `(user_id, club_id)` **любого статуса**:
   - нет → `activateSubscription` + `clubs.member_count += 1`, `type=subscription`
   - есть и `expires_at > now` → `renewSubscription` с `expires_at += 30d`, `type=renewal`
   - есть и `expires_at ≤ now` (expired/grace_period/cancelled/null) → `renewSubscription` с `expires_at = now + 30d`, `type=renewal`. member_count **не инкрементируется**.
5. Записать транзакцию (completed) с комиссией из формулы выше. При `DuplicateKeyException` (concurrent retry) — `@Transactional` откатит membership-работу; первый закомиченный поток — источник истины.
6. Всё в одной `@Transactional` (membership + transaction + клубный счётчик).

**Идемпотентность при concurrent retry** гарантируется двумя инвариантами БД:
- `memberships(user_id, club_id)` UNIQUE (V3) — параллельные `activateSubscription` сериализуются
- `transactions.telegram_payment_charge_id` partial UNIQUE (V12) — финальный insert гарантирует atomic convergence

Fast-path проверка `existsByTelegramChargeId` — оптимизация, не primary защита.

**Post-commit welcome DM.** После успешной обработки `handleSuccessfulPayment` публикует `PaymentConfirmedEvent(telegramId, clubName)` через `ApplicationEventPublisher`. `PaymentNotificationHandler` слушает его через `@TransactionalEventListener` (фаза `AFTER_COMMIT`) и шлёт DM «Оплата принята. Добро пожаловать в клуб «X»!». Это гарантирует, что DM никогда не уйдёт до фактического коммита (rollback → событие отбрасывается) и не держит БД-транзакцию открытой во время сетевого вызова Telegram.

### Lifecycle scheduler
`@Scheduled(cron = "0 0 9 * * *")` — ежедневно в 09:00 сервера.

Разделение слоёв:
- `SubscriptionScheduler.checkSubscriptions()` — cron entry. **Без `@Transactional`**. Сначала снимает read-only снапшоты будущих нотификаций, шлёт DM (сетевые вызовы **вне** транзакции), затем делегирует DB-мутации.
- `SubscriptionLifecycleService.findExpiringWithin(...)` — read-only, подписки с `expires_at ∈ (now, now + 3d]`.
- `SubscriptionLifecycleService.findActiveExpired(now)` — read-only, подписки со статусом `active` и `expires_at ≤ now` (те, которых сейчас переведут в grace).
- `SubscriptionLifecycleService.processExpiry(now)` — `@Transactional` write-часть:
  1. `active` c `expires_at ≤ now` → `grace_period`.
  2. Считает per-club count grace_period с `expires_at ≤ now − 3d` (SELECT). Scheduler — единственный writer этих переходов, поэтому SELECT-then-UPDATE безопасен.
  3. `grace_period` c `expires_at ≤ now − 3d` → `expired` + `clubs.member_count` уменьшен ровно на count из шага 2 (с `GREATEST(...−delta, 0)`).

Порядок в scheduler:
1. Read `findExpiringWithin` — кого уведомить «истекает через 3 дня».
2. Read `findActiveExpired` — кого уведомить «истекла, grace period 3 дня» (PRD §4.7.3.3). **Делаем до** `processExpiry`, иначе статус уже перейдёт в grace и выборка будет пуста.
3. Отправляем обе пачки нотификаций.
4. `processExpiry` — DB mutations в одной короткой транзакции.

Разнесение (notification vs DB mutations) нужно, чтобы БД-транзакция не висела открытой во время сетевых вызовов в Telegram API.

Декремент member_count через `GREATEST` оправдан исторически: на старте данные могли быть рассинхронизированы (до введения рефакторинга), и hard-падение scheduler-а при отрицательном значении вреднее, чем молчаливая стабилизация в 0. Это тех-долг, не бизнес-правило.

## API контракты

Нет публичных HTTP-ручек. Вход — Telegram webhook (`ClubsBot`). Сигнатуры внутренних методов:

```kotlin
class PaymentService {
    fun createInvoice(userId: UUID, clubId: UUID)
    fun handleSuccessfulPayment(
        telegramId: Long,
        telegramChargeId: String,
        payload: String,
        amount: Int
    )
}
```

## Acceptance Criteria

**AC-1: инвойс для платного клуба**
```
GIVEN клуб с subscription_price = 500 Stars
WHEN createInvoice(userId, clubId)
THEN telegramClient.execute(SendInvoice) вызван с chatId = users.telegram_id,
     currency = "XTR", price.amount = 500, payload = "club_subscription:{clubId}:{userId}"
```

**AC-2: free-клуб — без инвойса**
```
GIVEN клуб с subscription_price = 0 (или null)
WHEN createInvoice(userId, clubId)
THEN telegramClient.execute НЕ вызван
AND ни одного membership / transaction не создано (это зона membership-модуля)
```

**AC-3: новый membership после первой оплаты**
```
GIVEN у user нет membership в club
WHEN handleSuccessfulPayment(tgId, chargeId, "club_subscription:{clubId}:{userId}", 500)
THEN memberships: новая запись (status=active, role=member, expires_at ≈ now+30d)
AND clubs.member_count += 1
AND transactions: новая запись (type=subscription, status=completed,
    amount=500, platform_fee=100, organizer_revenue=400, charge_id=chargeId)
```

**AC-4: renewal активной подписки**
```
GIVEN у user membership со status=active, expires_at = now+10d
WHEN handleSuccessfulPayment(..., 500)
THEN memberships: status=active, expires_at ≈ now+40d (10d + 30d)
AND clubs.member_count НЕ меняется
AND transactions: type=renewal
```

**AC-5: renewal истёкшей подписки**
```
GIVEN у user membership со status=expired (или grace_period), expires_at < now
WHEN handleSuccessfulPayment(..., 500)
THEN memberships: status=active, expires_at ≈ now+30d
AND clubs.member_count НЕ меняется
AND transactions: type=renewal
```

**AC-6: идемпотентность webhook**
```
GIVEN транзакция с telegram_payment_charge_id = "X" уже существует
WHEN handleSuccessfulPayment(..., telegramChargeId="X", ..., 500) вызван повторно
THEN НИ memberships, НИ transactions, НИ clubs не изменяются
AND в лог пишется INFO о дубликате
```

**AC-7: некорректный payload не ломает поток**
```
GIVEN payload = "foo:bar" (формат неизвестен)
WHEN handleSuccessfulPayment(..., payload, ...)
THEN WARN-лог "Unknown payment payload"
AND никаких изменений в БД
AND исключение не проброшено (bot продолжает обрабатывать updates)
```

**AC-8: transactional — partial failure откатывается**
```
GIVEN membership создан, но insert transactions падает (например, constraint)
WHEN handleSuccessfulPayment
THEN вся транзакция откатывается: membership не создан, clubs.member_count не изменён
```

**AC-9: предупреждение за 3 дня**
```
GIVEN membership status=active, expires_at = now + 2d
WHEN scheduler.checkSubscriptions() прогон
THEN NotificationService.sendDirectMessage вызван с users.telegram_id
     и текстом про истечение через 3 дня
AND status / expires_at НЕ меняются
```

**AC-10: active → grace_period**
```
GIVEN membership status=active, expires_at = now - 1h
WHEN scheduler.checkSubscriptions()
THEN memberships.status = grace_period
AND clubs.member_count НЕ декрементируется
AND пользователь получает DM "подписка истекла, grace period 3 дня" (PRD §4.7.3.3)
```

**AC-11: grace_period → expired + decrement**
```
GIVEN membership status=grace_period, expires_at = now - 4d
WHEN scheduler.checkSubscriptions()
THEN memberships.status = expired
AND clubs.member_count уменьшен на 1 (но не ниже 0)
```

## Non-functional

- **Транзакции**: `handleSuccessfulPayment` и `checkSubscriptions` — `@Transactional`.
- **Идемпотентность**: обеспечивается `transactions.telegram_payment_charge_id` + partial UNIQUE index (WHERE NOT NULL).
- **Логирование**:
  - INFO: создание инвойса, успешная обработка платежа, bulk-переходы scheduler-а с ненулевым count
  - WARN: unknown payload, дубликат charge_id
  - ERROR: неожиданные ошибки (падает до глобального handler)
- **Безопасность**:
  - Цена берётся из БД, не из client payload
  - `payload` парсится с проверкой формата; UUID-parsing исключения должны быть пойманы (иначе malformed payload роняет обработку)
  - Telegram Bot Token — только env var (см. `.claude/rules/security.md`)
- **Rate limiting**: не нужно — эндпоинта нет, вход только через Telegram webhook.
- **Производительность**: `successful_payment` обрабатывается синхронно, держим в рамках 10s Telegram timeout (сейчас одна БД-транзакция с ≤ 4 запросами — укладывается).

## Интеграции

- **`membership` модуль** — пишет в `memberships` через `MembershipRepository`. payment **не создаёт** membership для free-клубов (это делает membership при вступлении в открытый бесплатный клуб).
- **`club` модуль** — читает `subscription_price`, обновляет `member_count` через `ClubRepository`.
- **`user` модуль** — читает `telegram_id` для отправки инвойса.
- **`bot` модуль** — единственный caller `PaymentService` (invoice отправляется через `TelegramClient`, оба handler-а вызываются из `ClubsBot`).
- **Telegram Bot API** (через `ClubsBot` long-polling consumer):
  - `SendInvoice` — синхронный execute из `PaymentService.createInvoice`
  - `pre_checkout_query` — Telegram шлёт между нажатием «Pay» и списанием; **должен** быть подтверждён через `AnswerPreCheckoutQuery` в течение 10 с, иначе Telegram отменит платёж. Обрабатывается в `ClubsBot.handlePreCheckoutQuery`: валидирует формат payload (`club_subscription:{clubId}:{userId}`), отвечает `ok=true` либо `ok=false` с `error_message`. Исключения Telegram API swallow-ятся логом — fail в боте не должен ронять весь long-polling loop.
  - `successful_payment` — приходит как `Update.message` без поля `text`, поэтому его обработчик в `ClubsBot.consume` должен быть **раньше** `hasText()` early-return. Может повторяться Telegram'ом → идемпотентность через partial UNIQUE (см. выше).

## Риски и открытые вопросы

### Расхождения с PRD (см. `docs/backlog/payment-prd-gaps.md`)

- **GAP-5 (критический)**: flow вступления (`MembershipService.joinOpenClub`/`joinByInviteCode`, `ApplicationService.approveApplication`) не зовёт `PaymentService.createInvoice`. Для платных клубов membership создаётся напрямую, обходя оплату. `createInvoice` и `handleSuccessfulPayment` — dead code в текущем проде. Блокирует монетизацию MVP. Отдельная bugfix-ветка.
- **GAP-1**: нет автосписания Telegram Stars (PRD §4.7.2, §4.7.3.2). Сейчас renewal — только ручной.
- **GAP-2**: нет flow отмены подписки (PRD §4.7.3.4). Статус `cancelled` в enum есть, но недостижим.
- **GAP-3**: при смене `clubs.subscription_price` следующий инвойс уйдёт по новой цене (PRD §4.7.4 ACP нарушено).
- **GAP-4**: после успешной оплаты пользователь **не** добавляется в Telegram-группу автоматически (PRD §4.2.1.4 — ключевое обещание MVP). Виден только после закрытия GAP-5.
- **GAP-6**: UX закрытого клуба меняется на «две кнопки» (запрос + вступить, вторая разблокируется после approve организатора). Текущий PRD §4.2.2 устарел — требует переписывания. Зависит от GAP-5.
- **GAP-7**: для бесплатных клубов нужен lifecycle по вовлечённости (автопродление активным, авто-исключение неактивным через 30 дней). PRD не описывает. Требует дизайна.
- **GAP-8**: нет dedup'а повторных invoice-запросов per (user, club). Клики «Вступить» подряд → несколько Telegram DM. Ограничено глобальным rate-limit, но UX-неаккуратно.
- **GAP-9**: welcome DM после оплаты ведёт в главную страницу Mini App, а не в конкретный клуб (deep-link handler не реализован).

### Прочее

- **Orphan payments**: если payload malformed, club не найден или amount ≤ 0 — метод `return`-ит без следа. Пользователь мог заплатить в Telegram. Для audit trail нужна отдельная таблица (например `payment_errors`) — вне scope этой итерации, документируем как известный gap.
- **Orphan payments**: если payload malformed, club не найден или amount ≤ 0 — метод `return`-ит без следа. Пользователь мог заплатить в Telegram. Для audit trail нужна отдельная таблица (например `payment_errors`) — вне scope этой итерации, документируем как известный gap.
- **createInvoice trust boundary**: метод `public`, принимает `userId` без auth-проверки. Сейчас единственный caller — `ClubsBot` (Telegram webhook). При появлении HTTP-вызова потребуется явная авторизация инициатора.
- **Нет уведомления об успехе платежа**: после `successful_payment` пользователь получает только Telegram-квитанцию от самой платёжной системы, бот не шлёт подтверждение с invite-ссылкой. Треба отдельно.
- **member_count drift**: использование `GREATEST(x-delta, 0)` маскирует рассинхрон. Долгосрочно — завести sanity-check job, считающий `member_count` из фактических active membership'ов.
- **Scheduler и timezone**: cron в UTC контейнера. Если нужен 09:00 МСК — настраивать через `spring.task.scheduling.pool.timezone` или переносить в cron-выражение.
- **Миграция V12 и объём**: partial UNIQUE index создаётся без `CONCURRENTLY` (Flyway в транзакции). На текущем объёме OK; при росте таблицы `transactions` вынести в отдельный скрипт с `spring.flyway.mixed=true`.
- **Неполный membership-domain**: `MembershipRepository.activateSubscription` и `findExpiryRefByUserAndClub` уже возвращают не jOOQ Record, но остальные методы (`create`, `findByUserAndClub`, ...) всё ещё отдают `MembershipsRecord`. Полноценная доменизация — в отдельной итерации рефакторинга membership-модуля.
