# Биллинг платформы за чат — «первая встреча бесплатно, дальше 199 ₽/мес» (спека Дня 4)

**Статус:** спека к реализации (спринт 1.0, День 4). Трек **L**: миграции, деньги, новый API,
внешний провайдер. Оценка **11–15 рабочих дней** + staging.
**Дата:** 2026-09-07. **Решения PO:** `docs/design/monetization-v3-research-2026-09.md` § 8
(там же — почему именно так; здесь только «что строим»).
**Заменяет:** `docs/modules/payment-v2.md` (superseded) в части платформенной подписки.
Взносы участник→организатор (`payment.md`, `membership-lifecycle.md`) — **не трогаются**.

---

## 1. Что строим одной строкой

Бот ведёт первую встречу чата бесплатно. При создании второй встречи владелец клуба видит
счёт **199 ₽/мес за чат**, платит через Robokassa (деньги приходят самозанятому основателю, чек
НПД формируется сам), подписка идёт 30 дней с оплаты и продлевается автоматически, если ползунок
«Продлевать автоматически» включён (по умолчанию — включён). Неоплата: DM, 7 дней грейса, потом
«новое нельзя, начатое доживает».

**Строка для рекламы и онбординга:** «Первая встреча бесплатно. Дальше 199 ₽ в месяц за чат».

## 2. Правила (залочено PO 2026-09-07)

| # | Правило | Уточнение для кода |
|---|---|---|
| R1 | Платит владелец клуба, рождённого из чата; участники — никогда | `payer_user_id = clubs.owner_id`; при передаче владения (не реализована) счёт остаётся на строке подписки |
| R2 | Единица счёта — чат (клуб с `club_chat_links`, 1:1). Клубы без чата — бесплатны | гейт пропускает клуб без привязки |
| R3 | Цена 199 ₽/мес для всех, круга ранних нет | план `CHAT`, `subscription_pricing` 19 900 коп.; изменение цены — новой строкой с `effective_from`, снимка цены на подписке нет |
| R4 | Первая встреча бесплатна; счёт — при создании второй | гейт стоит **только** в `EventService.createEvent`; складчина не гейтится |
| R5 | Отменённая первая встреча в зачёт не идёт | отмена события, записанного как бесплатное, **возвращает** бесплатную встречу |
| R6 | Одна бесплатная встреча на чат, переживает отвязку/удаление клуба/переезд группы | признак хранится по `chat_id` вне `club_chat_links` (строка привязки удаляется при отвязке) |
| R7 | Только через провайдера; ручных переводов нет | Robokassa, договор на самозанятого, «Робочеки СМЗ» |
| R8 | Часы идут с оплаты: 30 дней от подтверждения ResultURL; повторная оплата продлевает от `max(now, current_period_end)` | правило из `AccessGateService.markDuesPaid` |
| R9 | Ползунок «Продлевать автоматически», по умолчанию вкл. | вкл. = дочернее списание за день до конца периода; выкл. = DM за 3 и 1 день с кнопкой «Оплатить» |
| R10 | Неоплата: DM → грейс 7 дней → «новое нельзя, начатое доживает» | в грейсе всё разрешено; после грейса — 402 на создании встречи, начатое (голосование, Этап 2, явка, закреп, складчина) доживает |
| R11 | Правило «спящий месяц не продлеваем» — **отложено** | не строим; при выключенном автосписании получается само |
| R12 | Гейт Ф0→Ф1 без изменений | «продлился» = второй успешный платёж по подписке |

## 3. Что уже есть и что с ним делать

| Есть | Где | Решение |
|---|---|---|
| Стейт-машина `ACTIVE → CANCELLED_PENDING_END → ENDED` + `PAST_DUE`, forward-only | V35, `JooqSubscriptionRepository.transitionStatus/extendPeriod/endElapsedCancelled` | **оставить**; `CANCELLED_PENDING_END` больше не используется (отмена = выключить ползунок) |
| Идемпотентность вебхука `subscription_event.provider_event_id UNIQUE`, `recordEventIfNew` | V35:53-64 | **оставить**, ключи — `robokassa:paid:<InvId>` |
| Прайсинг с `effective_from` | V36, `currentPriceKopecks` | **оставить**, добавить план `CHAT` |
| Сеам `PaymentProvider` + `StubPaymentProvider` | `payment/PaymentProvider.kt` | **переписать контракт** под чекаут/списание/ResultURL (§ 6); стаб остаётся для dev/тестов |
| Ёмкость платных клубов: `SubscriptionPlanPolicy`, `PricingInvariant`, `requirePaidClubCapacity`, гарды в `subscribe/cancel`, вызовы в `ClubService.createClub:104-106` и `updateClub:305-307` | `subscription/`, `club/ClubService.kt` | **удалить** вместе с тестами `SubscriptionPolicyTest` (8) и частью `SubscriptionServiceTest` (12) |
| `POST /api/subscriptions`, `/cancel`, `/plans`, `/status` | `SubscriptionController` | **удалить** контроллер целиком (закрывает мину `docs/backlog/subscription-endpoint-free-plan-bypass.md` по построению); новый API — § 7 |
| Индекс `uq_service_subscription_active_org (payer_user_id)` | V35:35-37 | **заменить** на «одна живая подписка на клуб» (§ 5) |
| `findActiveOrganizerSubscription(userId)` (`fetchOne`) | `JooqSubscriptionRepository.kt:42-50` | **заменить** на `findLiveByClub(clubId)` |
| `handleWebhook(rawBody, signature)` ищет по `provider_token` через `parseWebhook` | `SubscriptionService.kt:162-187` | **переписать**: ResultURL приходит form-параметрами, подписка ищется по `InvId` через `platform_payment` |
| Фронт: `SubscriptionCard` (профиль), `PlanCard`, `PaywallModal` (только в `CreateClubModal`), `planDisplay.ts`, `api/subscription.ts`, `queries/subscription.ts` | `components/subscription/*`, `ProfilePage.tsx:342`, `CreateClubModal.tsx:206,306` | **удалить**; новые `api/billing.ts`, `queries/billing.ts`, `components/billing/*` |
| `DuesPaymentSheet.tsx` (шит взноса участника) | `components/club/` | **не трогать**; донор вёрстки для `BillingSheet` (сумма, шаги, кнопка, портал) |
| `ClubPage` обработка `?pay=1` | `pages/ClubPage.tsx:154-170` | паттерн для `?billing=1` / `?billing=done` (§ 8.4) |
| Флаг `MEMBER_PAYS_ENABLED`, `payer_role=MEMBER` | `application.yml:93`, V35 | **оставить как есть** (выключено, не трогаем) |
| `countPastEvents(clubId, now)` | `EventRepository.kt:81`, `JooqEventRepository.kt:443-450` | не используется гейтом (гейт считает по признаку, не по счётчику) — оставить |
| `sendDirectMessageWithDeepLink`, `sendDirectMessage` | `bot/NotificationService.kt:490,649` | DM биллинга |
| `RateLimitFilter` с отдельными бакетами | `common/security/RateLimitFilter.kt` | добавить бакет для чекаута |

## 4. Пользовательские сценарии

### 4.1 Первая встреча бесплатна
1. Админ подключил чат, прошёл мастер, жмёт «Создать встречу». Гейт видит: подписки нет, признак
   бесплатной встречи для `chat_id` отсутствует → встреча создаётся, признак записывается с `event_id`.
2. Полоска статуса на странице управления клубом: «Первая встреча — бесплатно. Дальше 199 ₽ в
   месяц за чат». В мастере наполнения и на экране подключения чата та же строка.
3. Если эту встречу отменили до старта — признак снимается, следующая снова бесплатна (R5).
   Отменить после старта нельзя (`EventService.kt:160-161`) — сгорела честно.

### 4.2 Стена и первый платёж
1. Админ создаёт вторую встречу → `402 PAYMENT_REQUIRED` с `reason = FREE_MEETING_USED`,
   `priceKopecks = 19900` → фронт открывает `BillingSheet` поверх формы (форма не теряется).
2. Шит: «199 ₽ в месяц за чат „Название“» · ползунок «Продлевать автоматически» (вкл.) с текстом
   «спишем 199 ₽ дд.мм.гггг, отключить можно в любой момент» · карточка «Оплата через Robokassa,
   получатель — самозанятый <ФИО>, чек придёт на e-mail/в Telegram» · ссылка на оферту · кнопка
   «Оплатить 199 ₽».
3. Кнопка → `POST /api/clubs/{id}/billing/checkout {autopay}` → сервер создаёт `platform_payment`
   (PENDING, новый `InvId`), считает подпись, возвращает URL оплаты → фронт открывает его через
   `openLink` (внешний браузер / in-app browser Telegram).
4. Оплата на странице Robokassa. `SuccessUrl2` ведёт на нашу страницу `/pay/return?club=<id>`
   (обычный веб, вне Mini App): «Оплата принята, возвращайтесь в Telegram» + кнопка
   `https://t.me/<bot>/<app>?startapp=billing_<clubId>`. **SuccessURL не подтверждает оплату** —
   только ResultURL (правило Robokassa).
5. ResultURL → проверка IP и подписи → `platform_payment` → SUCCEEDED, подписка `ACTIVE`,
   `current_period_end = now + 30d`, `provider_token = InvId материнского платежа`,
   `autopay_possible = (PaymentMethod — карта)`; DM владельцу «Оплачено до дд.мм · автопродление
   вкл./выкл.».
6. Mini App при возврате (`startapp=billing_<clubId>` или `?billing=done`) показывает «Проверяем
   оплату…» и опрашивает `GET /api/clubs/{id}/billing` каждые 3 с до 60 с; затем «Если оплата
   прошла, мы сообщим в личку» — вебхук может отставать.
7. Если материнский платёж прошёл **не картой** (СБП и др.): `autopay_possible = false`, ползунок
   на странице клуба показывает «Автопродление недоступно для СБП — напомним за 3 дня»
   (Robokassa: рекуррент только по картам).

### 4.3 Продление с автосписанием (ползунок вкл., `autopay_possible`)
1. За **1 день** до `current_period_end`: DM «Завтра спишем 199 ₽ за чат „Название“. Отключить —
   на странице клуба» (прозрачность, не тёмный паттерн).
2. В день `current_period_end` (тик шедулера): создать `platform_payment` (RECURRING, новый `InvId`,
   `previous_inv_id = provider_token`), вызвать дочернее списание. Ответ `OK<InvoiceID>` — это
   приём заявки, не факт списания.
3. ResultURL по дочернему платежу → SUCCEEDED → `extendPeriod(max(now, period_end) + 30d)`,
   `PAST_DUE → ACTIVE`, DM «Продлено до дд.мм».
4. Нет ResultURL в течение **6 часов** → шедулер опрашивает статус операции (`OpStateExt`) →
   FAILED → `charge_attempts++`, повтор через 1 → 3 → 7 дней от `period_end`; между попытками
   статус `PAST_DUE` (грейс), DM «Не удалось списать: обновите карту или оплатите вручную» с кнопкой.
5. `period_end + 7 дней` без успеха → `ENDED` (грейс исчерпан) → DM → «новое нельзя, начатое
   доживает». Повторная оплата = новый чекаут (§ 4.2), материнский платёж заново.

### 4.4 Продление без автосписания (ползунок выкл. или `!autopay_possible`)
1. DM за 3 и 1 день с deep link на шит; в шите «Продлить на месяц — 199 ₽» (тот же чекаут; после
   оплаты картой `autopay_possible` снова `true`, ползунок можно включить).
2. `period_end` без оплаты → `PAST_DUE`, DM «Подписка закончилась, 7 дней грейса»; `+7 дней` →
   `ENDED`.

### 4.5 Ползунок
- Выключить: `PATCH /api/clubs/{id}/billing/autopay {autopay:false}` — подписка остаётся ACTIVE до
  конца периода; никаких «отмен» и звонков.
- Включить: тот же вызов с `true`; если `!autopay_possible` → `409` с текстом «Автопродление
  работает только для карт — оплатите следующий месяц картой».

### 4.6 Неоплата и «начатое доживает»
- Гейт только на `createEvent` (R4). Отмена, перенос, Этап 2, отметка явки, закреп, складчина,
  дверь, теги, напоминания — работают при любом статусе.
- Единственное, что закрывается после грейса, — создание новой встречи (и в будущем — AI-опрос).

### 4.7 Переезд группы в супергруппу, отвязка, удаление клуба
- `chat_id` меняется → признак бесплатной встречи переезжает вместе с привязкой (§ 5.3).
- Отвязка/удаление клуба: подписка остаётся у клуба (ENDED по истечении, дочерних списаний нет —
  шедулер не списывает за клуб без чата); признак бесплатной встречи **не удаляется** (R6).
- Повторное подключение того же чата новым клубом: подписки нет, признак есть → сразу стена на
  первой встрече. Это намеренно.

## 5. Модель данных

Две миграции (новое значение enum нельзя использовать в той же транзакции — как V37).

### 5.1 `V87__platform_billing_chat.sql`
```sql
ALTER TYPE subscription_plan ADD VALUE IF NOT EXISTS 'CHAT';

ALTER TABLE service_subscription
    ADD COLUMN autopay          BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN autopay_possible BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN charge_attempts  INT     NOT NULL DEFAULT 0,
    ADD COLUMN last_charge_at   TIMESTAMPTZ;
-- COMMENT ON: autopay — ползунок владельца; autopay_possible — материнский платёж прошёл картой
-- (Robokassa рекуррент только по картам); charge_attempts/last_charge_at — ретраи 1/3/7.

DROP INDEX uq_service_subscription_active_org;
-- Одна живая подписка на клуб независимо от плательщика (владелец может смениться).
CREATE UNIQUE INDEX uq_service_subscription_live_club
    ON service_subscription (subject_club_id)
    WHERE payer_role = 'ORGANIZER' AND status <> 'ENDED';
-- Организаторские строки теперь всегда club-scoped.
ALTER TABLE service_subscription
    ADD CONSTRAINT chk_service_subscription_org_club
    CHECK (payer_role <> 'ORGANIZER' OR subject_club_id IS NOT NULL);
-- provider_token = InvId материнского платежа Robokassa (PreviousInvoiceID для дочерних).

-- Платежи платформы: аудит-след денег, привязка InvId → подписка ДО прихода ResultURL,
-- статус дочерних списаний. Не путать с transactions (замороженный Stars-леджер, V8).
CREATE SEQUENCE platform_payment_inv_seq START 100000;  -- InvId Robokassa: 1..2^63-1, уникален
CREATE TABLE platform_payment (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id  UUID NOT NULL REFERENCES service_subscription(id),
    inv_id           BIGINT NOT NULL UNIQUE DEFAULT nextval('platform_payment_inv_seq'),
    kind             VARCHAR(16) NOT NULL,      -- MOTHER | RECURRING
    previous_inv_id  BIGINT,                    -- для RECURRING: InvId материнского
    amount_kopecks   INT NOT NULL CHECK (amount_kopecks > 0),
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING | SUCCEEDED | FAILED
    payment_method   VARCHAR(64),               -- PaymentMethod из ResultURL
    provider_fee     NUMERIC(10,2),             -- Fee из ResultURL
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    paid_at          TIMESTAMPTZ
);
CREATE INDEX idx_platform_payment_pending ON platform_payment (created_at) WHERE status = 'PENDING';

-- Бесплатная первая встреча — по chat_id, переживает отвязку и удаление клуба (R6).
CREATE TABLE chat_free_meeting (
    chat_id      BIGINT PRIMARY KEY,
    club_id      UUID NOT NULL,                 -- без FK: клуб может быть удалён, признак живёт
    event_id     UUID NOT NULL,
    used_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    released_at  TIMESTAMPTZ                    -- встреча отменена до старта → бесплатная возвращена
);

-- События воронки (День 4,5 переиспользует таблицу; campaign заполняет парсер /start ad_<…>).
CREATE TABLE funnel_event (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID REFERENCES users(id),
    club_id    UUID,
    kind       VARCHAR(48) NOT NULL,
    campaign   VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_funnel_event_kind_created ON funnel_event (kind, created_at);
```
Все `COMMENT ON` — по-русски (конвенция). Планы `FREE/TRIO/UNLIMITED` в enum остаются (значения
enum в PostgreSQL не удаляются), в коде не используются.

### 5.2 `V88__platform_billing_pricing.sql`
```sql
INSERT INTO subscription_pricing (plan, price_kopecks, effective_from) VALUES ('CHAT', 19900, NOW());
```

### 5.3 Переезд `chat_id`
`ChatLinkService.adoptMigratedChat` публикует `ChatIdMigratedEvent(oldChatId, newChatId)`;
`subscription` слушает и делает `UPDATE chat_free_meeting SET chat_id = new WHERE chat_id = old`.
Без прямой зависимости `chatlink → subscription`.

## 6. Бэкенд

Пакеты: движок и гейт — `subscription/`; адаптер провайдера — `payment/`. Новых пакетов нет.

### 6.1 Сеам `PaymentProvider` (новый контракт)
```kotlin
interface PaymentProvider {
    /** Ссылка на страницу оплаты материнского платежа. recurring=true разрешает дочерние списания. */
    fun createCheckout(payment: CheckoutRequest): CheckoutUrl
    /** Дочернее списание по сохранённой карте. Возвращает факт ПРИЁМА заявки, не факт списания. */
    fun charge(payment: RecurringChargeRequest): ChargeAccepted
    /** ResultURL: проверка подписи (и IP — в контроллере), маппинг в результат. */
    fun parseResultNotification(params: Map<String, String>): ResultNotification
    /** Опрос статуса операции (для дочерних платежей без ResultURL). */
    fun queryState(invId: Long): PaymentState  // PENDING | SUCCEEDED | FAILED
}
data class CheckoutRequest(val invId: Long, val amountKopecks: Int, val description: String,
                           val recurring: Boolean, val email: String?, val successUrl: String, val failUrl: String)
data class ResultNotification(val invId: Long, val amountKopecks: Int, val paymentMethod: String?, val fee: BigDecimal?)
```
`StubPaymentProvider` — для dev/тестов: `createCheckout` отдаёт локальный URL-заглушку, `charge`
всегда accepted, `queryState` = SUCCEEDED через N секунд (конфиг), чтобы staging без ключей
проходил весь цикл.

### 6.2 `RobokassaPaymentProvider` (`@Component`, `@ConditionalOnProperty("billing.provider=robokassa")`)
По документации docs.robokassa.ru (проверено 2026-09-07):
- **Чекаут:** `https://auth.robokassa.ru/Merchant/Index.aspx` с `MerchantLogin`, `OutSum` (`199.00`),
  `InvId`, `Description` (≤100 символов, «Clubs: подписка за чат „…“ на 30 дней»), `Culture=ru`,
  `Email` (если есть), `Recurring=true` при `recurring`, `SuccessUrl2`/`FailUrl2` (+`…Method=GET`),
  `IsTest=1` на staging, `Shp_club=<clubId>`. **`Receipt` не передаём** — чек НПД формирует
  «Робочеки СМЗ» автоматически.
- **Подпись запроса:** `MerchantLogin:OutSum:InvId[:SuccessUrl2:SuccessUrl2Method:FailUrl2:FailUrl2Method]:Password#1:Shp_club=<clubId>`
  — модификаторы в порядке из документации (Receipt, StepByStep, ResultUrl2, SuccessUrl2,
  SuccessUrl2Method, FailUrl2, FailUrl2Method, Token), только присутствующие; `Shp_*` по алфавиту.
  Алгоритм — **SHA256** (выставить в настройках магазина; MD5 по умолчанию не использовать).
- **ResultURL** (`POST /api/billing/robokassa/result`, form-параметры): проверить
  `SignatureValue == hash("OutSum:InvId:Password#2:Shp_club=<clubId>")` (сравнение constant-time,
  регистр хэша не важен), IP ∈ {185.59.216.65, 185.59.217.65} (клиентский IP — через те же
  доверенные прокси, что в `RateLimitFilter`), `OutSum == amount_kopecks/100` платежа. Ответ —
  `text/plain` `OK<InvId>`; при ошибке подписи — 403 и WARN в лог **без** значений паролей.
- **Дочернее списание:** `POST https://auth.robokassa.ru/Merchant/Recurring` с `MerchantLogin`,
  `InvoiceID` (новый), `PreviousInvoiceID` (материнский), `OutSum`, `Description`, `SignatureValue`
  = `hash("MerchantLogin:OutSum:InvoiceID:Password#1:Shp_club=…")` — **`PreviousInvoiceID` в подпись
  не входит**. Ответ `OK<InvoiceID>` = принято. Результат — ResultURL или `queryState`.
- **`queryState`:** XML-интерфейс `OpStateExt` (`MerchantLogin`, `InvoiceID`, подпись
  `MerchantLogin:InvoiceID:Password#2`) — точные URL и коды состояний взять из OpenAPI-спеки
  docs.robokassa.ru при реализации; маппинг: «оплачено/зачислено» → SUCCEEDED, «отменено/ошибка» →
  FAILED, иначе PENDING.
- **Ограничения, зафиксированные документацией:** рекуррент — только банковские карты; услуга
  «по предварительному согласованию» (PO запрашивает у поддержки при заключении договора);
  **тестового режима для рекуррента нет** — дочерние списания проверяются только боевыми ключами
  (§ 11).

### 6.3 Сервис `BillingService` (переименованный `SubscriptionService`)
- `status(clubId, userId)` → `BillingStatusDto` (владелец/со-организатор с `MANAGE_EVENTS`; иначе 403).
- `checkout(clubId, userId, autopay)`: только владелец (`ClubRoleGuard`, капабилити владельца);
  клуб обязан иметь привязку; берёт живую подписку или создаёт строку `PAST_DUE` с
  `current_period_end = now` (ждём первый платёж — семантика «счёт выставлен»; после успеха →
  ACTIVE); создаёт `platform_payment(MOTHER)`; `funnel_event(checkout_started)`; отдаёт URL.
  Второй чекаут при живом PENDING < 30 мин — вернуть тот же URL (идемпотентность), не плодить InvId.
- `onResult(notification)`: транзакция; `platform_payment` по `inv_id` (нет → WARN, ответ OK, чтобы
  Robokassa не ретраила); `recordEventIfNew("robokassa:paid:<InvId>")` (повтор → OK без действий);
  сумма совпадает; → SUCCEEDED, `paid_at`, `payment_method`, `fee`; подписка:
  `extendPeriod(max(now, period_end) + 30d)`, `transitionStatus(PAST_DUE|ENDED → ACTIVE)` (ENDED →
  ACTIVE разрешён только здесь, как «переподписка»), для MOTHER — `provider_token = InvId`,
  `autopay_possible = isCard(payment_method)`, `charge_attempts = 0`; `funnel_event(payment_succeeded)`;
  DM владельцу.
- `setAutopay(clubId, userId, value)`: владелец; `true` при `!autopay_possible` → 409.
- `requireBillable(club)` — § 6.4.

### 6.4 Гейт `BillingGate.requireBillable(club, eventIdSupplier)` — единственная точка
Вызывается из `EventService.createEvent` **после** `requireCapability` и **после** вставки события,
в той же транзакции (нужен `event.id`):
1. `club_chat_links` нет → пропустить (R2).
2. Живая подписка `ACTIVE` → пропустить. `PAST_DUE` и `now < period_end + 7d` → пропустить (грейс).
3. Иначе попытка взять бесплатную встречу атомарно:
   `INSERT INTO chat_free_meeting(chat_id, club_id, event_id) … ON CONFLICT (chat_id) DO UPDATE SET
   club_id=EXCLUDED.club_id, event_id=EXCLUDED.event_id, used_at=NOW(), released_at=NULL
   WHERE chat_free_meeting.released_at IS NOT NULL` → 1 строка = встреча бесплатна
   (`funnel_event(free_meeting_used)`); 0 строк → `PaymentRequiredException(reason=FREE_MEETING_USED)`
   → транзакция откатывается, событие не создаётся.
   Для подписки `ENDED` после грейса — `reason=SUBSCRIPTION_EXPIRED`.
4. `EventService.cancelEvent` после успешной отмены: `billingGate.releaseFreeMeeting(eventId)` —
   `UPDATE chat_free_meeting SET released_at = NOW() WHERE event_id = ? AND released_at IS NULL` (R5).

`PaymentRequiredException` и `PaywallResponse` получают поля `reason` и `clubId`; `currentPlan/
requiredPlan` удаляются (фронт `paywallFromError` переписывается).

### 6.5 Шедулер `ServiceSubscriptionScheduler` (крон `subscription.lifecycle-cron`, ежедневно; плюс
почасовой тик для PENDING)
Ежедневно, для каждой живой подписки клуба **с привязанным чатом**:
- `period_end − 3d` и `−1d`, `autopay=false` или `!autopay_possible` → DM-напоминание с deep link
  (`?billing=1`); дедуп по `subscription_event("reminder:<period_end>:<3|1>")` — без новой колонки.
- `period_end − 1d`, `autopay && autopay_possible` → DM «завтра спишем».
- `period_end ≤ now`, `autopay && autopay_possible`, `charge_attempts < 3`, следующий слот по
  расписанию `[0, +1d, +3d]` от `period_end` (4-я попытка на +7d не делается — грейс кончился) →
  `platform_payment(RECURRING)` + `provider.charge(...)`; `ACTIVE → PAST_DUE` при первой неудаче.
- `period_end ≤ now` без автосписания → `ACTIVE → PAST_DUE` + DM «7 дней грейса».
- `period_end + 7d ≤ now` и статус `PAST_DUE` → `ENDED` + DM + `funnel_event(subscription_ended)`.
Ежечасно: `platform_payment` PENDING старше 6 ч → `provider.queryState` → SUCCEEDED (обработать как
ResultURL) / FAILED (`charge_attempts++`, DM при первом фейле).
Клуб без чата (отвязали) — подписка доживает период, списаний нет, по истечении → ENDED тихо.

### 6.6 API
| Метод | Путь | Кто | Что |
|---|---|---|---|
| GET | `/api/clubs/{id}/billing` | владелец, со-орг с `MANAGE_EVENTS` | `BillingStatusDto` |
| POST | `/api/clubs/{id}/billing/checkout` | владелец | `{autopay:boolean}` → `{paymentUrl, invId}` |
| PATCH | `/api/clubs/{id}/billing/autopay` | владелец | `{autopay:boolean}` → `BillingStatusDto` |
| POST | `/api/billing/robokassa/result` | Robokassa (permitAll + IP + подпись) | form-параметры → `OK<InvId>` |

```kotlin
data class BillingStatusDto(
    val state: String,            // FREE_MEETING_AVAILABLE | FREE_MEETING_USED | ACTIVE | GRACE | ENDED | NO_CHAT
    val priceKopecks: Int,        // 19900
    val currentPeriodEnd: OffsetDateTime?,
    val graceUntil: OffsetDateTime?,
    val autopay: Boolean,
    val autopayPossible: Boolean,
    val pendingCheckout: Boolean, // есть PENDING MOTHER < 30 мин
)
```
`SecurityConfig`: `/api/billing/robokassa/result` permitAll (вместо `/api/subscriptions/webhook`).
`RateLimitFilter`: бакет `billing` для `/api/clubs/*/billing/checkout` — 5/мин на пользователя.

### 6.7 Конфиг (`application.yml` + **оба** compose-файла, правило CLAUDE.md п. 3)
```yaml
billing:
  provider: ${BILLING_PROVIDER:stub}             # stub | robokassa
  autopay-default: ${BILLING_AUTOPAY_DEFAULT:true}
  grace-days: ${BILLING_GRACE_DAYS:7}
  retry-days: ${BILLING_RETRY_DAYS:0,1,3}         # слоты дочерних списаний от period_end
  pending-timeout-hours: ${BILLING_PENDING_TIMEOUT_HOURS:6}
  success-url: ${BILLING_SUCCESS_URL:${telegram.webapp-base-url}/pay/return}
  robokassa:
    merchant-login: ${ROBOKASSA_MERCHANT_LOGIN:}
    password1: ${ROBOKASSA_PASSWORD_1:}
    password2: ${ROBOKASSA_PASSWORD_2:}
    test-mode: ${ROBOKASSA_TEST_MODE:true}
    hash: ${ROBOKASSA_HASH:SHA256}
    allowed-ips: ${ROBOKASSA_ALLOWED_IPS:185.59.216.65,185.59.217.65}
subscription:
  period-days: ${SUBSCRIPTION_PERIOD_DAYS:30}     # остаётся
  lifecycle-cron: ...                             # остаётся
```
Пароли — только через env; в логах не появляются никогда (тест на маскирование).

## 7. Фронтенд

- `api/billing.ts` (`getBilling`, `startCheckout`, `setAutopay`, `paywallFromError` → `{reason,
  clubId, priceKopecks}`), `queries/billing.ts` (`useBillingQuery(clubId)`, мутации, инвалидация
  `billing` + `events`).
- `components/billing/BillingSheet.tsx` — донор `DuesPaymentSheet` (портал, шапка, `.rd-dues-amount`,
  `.rd-step`, `.rd-btn-primary`), без загрузки скриншота и trust-карточки. Состояния: «к оплате»
  (ползунок + кнопка), «ждём подтверждения» (спиннер + опрос), «оплачено до…», «грейс до…».
  Открывает URL через `openLink` из `@telegram-apps/sdk-react` (не `window.open` — в Mini App это
  разное поведение на iOS).
- `components/billing/BillingStatusStrip.tsx` на `OrganizerClubManage` (владелец) — состояния из
  `BillingStatusDto`; ползунок автопродления здесь же.
- Форма создания встречи: `catch 402` → `paywallFromError` → `BillingSheet`; после успешной оплаты
  форма остаётся заполненной, кнопка «Создать» активна.
- `ClubPage`/`OrganizerClubManage`: `?billing=1` (из DM) → открыть шит; `?billing=done` /
  `startapp=billing_<clubId>` (возврат из браузера) → шит в состоянии «ждём подтверждения».
  Разбор `startapp` уже есть — `components/DeepLinkHandler.tsx` (паттерны `club_`, `event_`,
  `skladchina_`, `invite_`); добавить `billing_<uuid>` → `/clubs/{id}/manage?billing=done`.
- Страница `/pay/return` (обычный роут фронта, работает вне Telegram): «Оплата принята,
  возвращайтесь в Telegram» + кнопка `https://t.me/<bot>/<app>?startapp=billing_<clubId>`;
  `/pay/fail` — «Оплата не прошла» + та же кнопка. Обе без запросов к API (нет JWT вне Mini App).
- Мастер `ClubSetupWizard` и `ConnectChatScreen`: одна строка «Первая встреча бесплатно, дальше
  199 ₽ в месяц за чат».
- Удалить: `components/subscription/*`, `api/subscription.ts`, `queries/subscription.ts`,
  `SubscriptionCard` из `ProfilePage`, `PaywallModal` из `CreateClubModal`, ключи
  `queryKeys.subscription`.

## 8. Уведомления (DM владельцу, через `NotificationService`)
| Когда | Текст (суть) | Кнопка |
|---|---|---|
| Оплата прошла (MOTHER) | «Оплачено до дд.мм · автопродление вкл./выкл.» | «Открыть клуб» |
| −3д, −1д без автосписания | «Подписка за чат „…“ заканчивается дд.мм. Продлить — 199 ₽» | `?billing=1` |
| −1д с автосписанием | «Завтра спишем 199 ₽ за чат „…“. Отключить можно на странице клуба» | «Открыть клуб» |
| Дочернее списание прошло | «Продлено до дд.мм» | — |
| Списание не прошло (первый фейл) | «Не удалось списать 199 ₽. Обновите карту или оплатите вручную — 7 дней грейса» | `?billing=1` |
| period_end без оплаты | «Подписка закончилась. 7 дней всё работает, потом новые встречи нельзя» | `?billing=1` |
| Грейс исчерпан | «Новые встречи недоступны до оплаты. Начатое доживёт» | `?billing=1` |
В чат — ничего. Бюджет «1 закреп + 2 поста» не тратится.

## 9. Безопасность (HARD-гейт из `payment-v2.md` § 7.1, перенесён сюда)
1. ResultURL: IP-allowlist + подпись Password#2 (constant-time) + совпадение суммы + идемпотентность
   по `InvId`. Ответ `OK<InvId>` только после коммита транзакции.
2. Чекаут: только владелец; rate limit 5/мин; идемпотентность PENDING < 30 мин; сумма считается на
   сервере из `subscription_pricing`, клиент цену не передаёт.
3. Пароли Robokassa — env, не логируются; тест «маскирование в логах».
4. Все переходы статусов forward-only через `transitionStatus` с проверкой затронутых строк;
   `ENDED → ACTIVE` только в `onResult`.
5. Bypass `POST /api/subscriptions` исчезает вместе с контроллером.
6. `/pay/return` и `/pay/fail` не делают ничего, кроме показа текста, — статус оплаты по ним не
   меняется.

## 10. Критерии приёмки
1. Клуб из чата: первая встреча создаётся без оплаты; полоска «Первая встреча бесплатно».
2. Вторая встреча → шит оплаты; форма не теряется; после оплаты (staging: `IsTest=1`) статус
   `ACTIVE`, `current_period_end = +30д`, DM «Оплачено до…», встреча создаётся.
3. Отмена первой встречи до старта → следующая снова бесплатна; отмена после старта невозможна.
4. Отвязать чат → удалить клуб → подключить тот же чат заново → первая встреча **платная**.
5. Ползунок выкл. → напоминания за 3 и 1 день (staging: `SUBSCRIPTION_PERIOD_DAYS=1`, крон
   каждые 5 мин) → без оплаты `PAST_DUE` → через `BILLING_GRACE_DAYS` → `ENDED` → 402 на создании
   встречи с `reason=SUBSCRIPTION_EXPIRED`; отмена/явка/складчина работают.
6. Ползунок вкл. + материнский платёж картой → `autopay_possible=true`; DM «завтра спишем»; тик
   шедулера создаёт `platform_payment(RECURRING)` и вызывает `charge` (со стабом — SUCCEEDED через
   N секунд → продление).
7. Материнский платёж по СБП → `autopay_possible=false`, ползунок с пояснением, напоминания как при
   выкл.
8. Повторный ResultURL с тем же `InvId` — без второго продления; ResultURL с неверной подписью или
   чужого IP — 403, состояние не меняется.
9. Клуб без чата: гейт не срабатывает, `state=NO_CHAT`.
10. `funnel_event` содержит `free_meeting_used`, `paywall_seen`, `checkout_started`,
    `payment_succeeded`, `subscription_ended` для прогона.
11. Backend `./gradlew test` и frontend `npm test` + чистый `tsc` зелёные; старые тесты ёмкости удалены.

## 11. Ловушки и календарные зависимости
- **Рекуррент без тестового режима.** Дочернее списание нельзя проверить с `IsTest=1`. План:
  на staging весь цикл (напоминания, PAST_DUE, грейс, ENDED, ретраи) гоняется на
  `StubPaymentProvider` с `SUBSCRIPTION_PERIOD_DAYS=1`; материнский платёж на staging — Robokassa
  в тестовом режиме. Первое настоящее дочернее списание проверяется на проде на собственном чате
  PO: период там глобальный (30 дней), поэтому нужен служебный триггер «списать сейчас» для одной
  подписки — `POST /api/clubs/{id}/billing/charge-now`, доступный только при
  `BILLING_MANUAL_CHARGE_ENABLED=true` и только владельцу клуба из env-списка
  `PLATFORM_ADMIN_TELEGRAM_IDS`. После проверки флаг выключается. Записать в тест-план.
- **«По предварительному согласованию»**: рекуррент включает поддержка Robokassa по заявке —
  PO подаёт заявку в день договора, иначе `Recurring=true` молча не сработает.
- **IP Robokassa за Traefik/nginx**: клиентский IP брать через доверенные прокси, как в
  `RateLimitFilter`, иначе allowlist отвергнет всё.
- **SuccessURL ≠ подтверждение.** Никогда не активировать по возврату в приложение.
- **`Description` ≤ 100 символов** — обрезать название чата.
- **Хэш-алгоритм** задаётся в настройках магазина Robokassa — SHA256 должен совпадать с
  `ROBOKASSA_HASH`, иначе все подписи невалидны и чекаут отдаёт ошибку у провайдера.
- **Два дефолта**: `subscription.period-days` живёт в `application.yml` и в compose — грепать.
- **`ALTER TYPE … ADD VALUE`** — отдельная миграция от `INSERT` со значением `CHAT` (как V37).
- **Возврат в Mini App из внешнего браузера** через `t.me/<bot>/<app>?startapp=…` — проверить на
  iOS и Android до staging-прогона; известная слепая зона (конспект кода 2026-09-06).
- **Ответ ResultURL** — `text/plain`, ровно `OK<InvId>`, без JSON и кавычек.
- **Календарь PO до выхода на staging:** самозанятость в «Мой налог» → договор Robokassa на
  самозанятого + «Робочеки СМЗ» (доступ к «Мой налог») + заявка на рекуррент → тестовые пароли и
  SHA256 в настройках магазина → оферта (час юриста) → env в Coolify (staging: test-mode=true).

## 12. Docs alignment (обязательные правки в этой же ветке)
`docs/INDEX.md` § 1 (`subscription`, `payment` → `platform-billing.md`; `common/security` +
`RateLimitFilter` бакет), § 2 (новая строка); `docs/modules/payment-v2.md` (уже superseded);
`docs/modules/club-chat-link.md` § «Ещё не сделано» (уже указывает сюда); `docs/modules/events.md`
(гейт на `createEvent`); `docs/modules/telegram-bot.md` (DM биллинга); `docs/modules/infrastructure.md`
(env Robokassa, permitAll путь); `docs/design/sprint-1.0-chat-pivot.md` § 3 финмодель — пересчёт из
`monetization-v3-research-2026-09.md` § 6.4; PRD § 4.7 — одним заходом в конце спринта.
