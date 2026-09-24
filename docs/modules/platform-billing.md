# Биллинг платформы за чат — «15 дней бесплатно, дальше 199 ₽/мес» (спека Дня 4)

**Статус:** реализовано в ветке `feature/sprint-1.0-day4-payment-model` (2026-09-07), ждёт
staging-прогона по § 10. Трек **L**: миграции, деньги, новый API, внешний провайдер.
**Правки PO по мокапам 2026-09-07:** по тексту везде «за клуб» (единица счёта — чат, как и было);
списание в **день окончания** оплаченного периода, без DM «завтра спишем»; ФИО самозанятого целиком
(`billing.recipient-name`); оферта — текстом внутри шита; в состоянии «проверяем оплату» нет кнопки
«подожду в личке». Мокап — `docs/design/platform-billing/mockups/01-billing-screens.html`.
**Хэндофф для новой сессии** (состояние ветки, находки ревью, незакрытые долги, env для Coolify,
грабли) — `docs/backlog/sprint-1.0-day4-billing-handoff.md`.
**Дата:** 2026-09-07. **Решения PO:** `docs/design/monetization-v3-research-2026-09.md` § 8
(там же — почему именно так; здесь только «что строим»).
**Заменяет:** `docs/modules/payment-v2.md` (superseded) в части платформенной подписки.
Взносы участник→организатор (`payment.md`, `membership-lifecycle.md`) — **не трогаются**.

---

## 1. Что строим одной строкой

Первая созданная встреча запускает бесплатный период чата — **15 дней** (`BILLING_TRIAL_DAYS`).
Когда он кончается, владелец клуба видит счёт **199 ₽/мес за чат** и платит через Robokassa (деньги приходят самозанятому основателю, чек
НПД формируется сам), подписка идёт 30 дней с оплаты и продлевается автоматически, если ползунок
«Продлевать автоматически» включён (по умолчанию — включён). Неоплата: DM, 7 дней грейса, потом
«новое нельзя, начатое доживает».

**Строка для рекламы и онбординга:** «Первые 15 дней бесплатно. Дальше 199 ₽ в месяц за клуб»
(по тексту — «за клуб», решение PO 2026-09-07; по логике единица счёта остаётся чатом).

> **⟳ 2026-09-15, решение PO: бесплатный период вместо бесплатной встречи.** Было «первая встреча
> бесплатна, счёт при создании второй». Стало: первая созданная встреча запускает **бесплатный
> период чата в `BILLING_TRIAL_DAYS` дней (по умолчанию 15)**, дальше — подписка. Причина: одна
> встреча — один опыт, привычки за него не возникает; счёт должен приходить человеку, который успел
> провести две-три встречи. Срок, а не счётчик встреч: счётчик давал клубу «раз в месяц» квартал
> бесплатно, а ежедневным тренировкам — три дня. Число живёт в конфиге: это ручка, которую
> калибруют по живой воронке, не трогая код. Миграция — `V99__chat_trial.sql`.

## 2. Правила (залочено PO 2026-09-07, R4–R6 переписаны 2026-09-15)

| # | Правило | Уточнение для кода |
|---|---|---|
| R1 | Платит владелец клуба, рождённого из чата; участники — никогда | `payer_user_id = clubs.owner_id`; при передаче владения (не реализована) счёт остаётся на строке подписки |
| R2 | Единица счёта — чат (клуб с `club_chat_links`, 1:1). Клубы без чата — бесплатны | гейт пропускает клуб без привязки. **Бот вне чата = чата нет** (PO 2026-09-16): выгнали бота — привязка остаётся ради оживления, но стены, напоминаний и списаний нет, статус `BOT_REMOVED`; период идёт по календарю; вернули бота — всё оживает |
| R3 | Цена 199 ₽/мес для всех, круга ранних нет | план `CHAT`, `subscription_pricing` 19 900 коп.; изменение цены — новой строкой с `effective_from`, снимка цены на подписке нет |
| R4 | Первая созданная встреча запускает бесплатный период чата; он длится `billing.trial-days` (15) | гейт стоит **только** в `EventService.createEvent`; складчина не гейтится; первая встреча проходит всегда. Отсчёт — от **создания**, не от проведения встречи и не от отметки явки (PO 2026-09-16) |
| R5 | Отмена встречи бесплатный период НЕ возвращает — срок идёт по календарю | отмены биллинга не касаются вовсе (до V99 отменённая встреча возвращала бесплатную) |
| R6 | Один бесплатный период на чат: переживает отвязку, удаление клуба и переезд группы | `chat_trial` по `chat_id` вне `club_chat_links`; повторное подключение того же чата новым клубом второго срока не даёт |
| R6a | О конце периода предупреждают DM за **7 дней** и за **1 день** (PO 2026-09-15) | `BillingLifecycleService.remindEndingTrials`, дедуп через `chat_trial.reminder_days_left` |
| R7 | Только через провайдера; ручных переводов нет | Robokassa, договор на самозанятого, «Робочеки СМЗ» |
| R8 | Часы идут с оплаты: 30 дней от подтверждения ResultURL; повторная оплата продлевает от `max(now, current_period_end)` | правило из `AccessGateService.markDuesPaid` |
| R9 | Ползунок «Продлевать автоматически», по умолчанию вкл. | вкл. = дочернее списание **в день окончания** периода (`current_period_end`, слот 0), DM «завтра спишем» нет — дата названа в DM об оплате (PO 2026-09-07); выкл. = DM за 3 и 1 день с кнопкой «Оплатить» |
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

### 4.1 Бесплатный период чата
1. Чат подключён, встреч ещё не было: полоска «15 дней бесплатно · отсчёт пойдёт с первой встречи»
   (`TRIAL_NOT_STARTED`). Та же строка — в мастере наполнения и на экране подключения чата.
2. Админ жмёт «Создать встречу». Гейт видит: подписки нет, строки `chat_trial` нет → заводит её
   текущим моментом, встреча создаётся, в воронку идёт `trial_started`.
3. Дальше встречи создаются свободно до `started_at + billing.trial-days`. Полоска: «Бесплатно до
   30 сентября» с кнопкой «Оплатить» — заплатить заранее можно, период при этом не прерывается:
   месяц подписки идёт от момента платежа (R8).
4. За **7 дней** и за **1 день** до конца — DM владельцу с кнопкой «Оплатить» (R6a).
5. Отмена встречи срок не возвращает (R5): он идёт по календарю и от числа встреч не зависит.

### 4.2 Стена и первый платёж
1. Админ создаёт встречу после конца периода → `402 PAYMENT_REQUIRED` с `reason = TRIAL_ENDED`,
   `priceKopecks = 19900` → фронт открывает `BillingSheet` поверх формы (форма не теряется).
2. Шит: «199 ₽ / мес · 30 дней с момента оплаты», «за клуб „Название“» · ползунок «Продлевать
   автоматически» (вкл.) с текстом «спишем 199 ₽ с этой же карты в день окончания периода, отключить
   можно в любой момент» · карточка «Страница оплаты Robokassa: карта или СБП; получатель —
   самозанятый <ФИО целиком из `billing.recipient-name`>, чек придёт на e-mail/в Telegram» ·
   подпись под ценой — `trialPassedLabel` («первые 15 дней были бесплатными», для единицы —
  «первый день был бесплатным»: «первые 1 день» не читается ни при каком склонении) ·
  свёрнутый блок «Условия (публичная оферта)» с текстом (`components/billing/offerText.ts`, слова
   правит юрист) · кнопка «Оплатить 199 ₽» · подпись «Оплачивая, вы принимаете условия оферты».
3. Кнопка → `POST /api/clubs/{id}/billing/checkout {autopay}` → сервер создаёт `platform_payment`
   (PENDING, новый `InvId`), считает подпись, возвращает URL оплаты → фронт открывает его через
   `openLink` (внешний браузер / in-app browser Telegram).
4. Оплата на странице Robokassa. `SuccessUrl2` ведёт на нашу страницу `/pay/return?club=<id>`
   (обычный веб, вне Mini App): «Оплата принята, возвращайтесь в Telegram» + кнопка
   `https://t.me/<bot>?startapp=billing_<clubId>`. Имя бота страница берёт **из бандла**
   (`VITE_TELEGRAM_BOT_USERNAME`, дефолт зеркалит `telegram.bot-username`), а `club` принимает
   только как UUID: адрес открыт всем, и параметр `bot` позволил бы показать нашу страницу
   «Оплата принята» с кнопкой в чужого бота (ревью 2026-09-07). **SuccessURL не подтверждает
   оплату** — только ResultURL (правило Robokassa).
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
1. Отдельного DM за день до списания **нет** (снято PO 2026-09-07): дата и сумма названы в DM об
   оплате и на полоске статуса («7 октября спишем 199 ₽ с сохранённой карты»).
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

### 4.6a Бота выгнали из чата (2026-09-16)
Кик руками в Telegram привязку не удаляет (club-chat-link: фичи гаснут, вернули бота — всё
оживает), но для денег выгнанный бот равен отсутствию чата: гейт пропускает встречи, календарь
не списывает и не напоминает, напоминания о конце бесплатного периода не уходят, `charge-now`
отвечает 409. Полоска — «Бот удалён из чата — подписка на паузе» с оплаченной датой; сама дата
не сдвигается: если период кончится, пока бота нет, подписка тихо станет `ENDED` после грейса, и
после возвращения бота следующая встреча упрётся в стену `SUBSCRIPTION_EXPIRED`. Сообщения бота в
чате при кике остаются — это Telegram: вышедший бот удалять ничего не может; при отвязке из
приложения закреп и статусы убираются до выхода.

### 4.7 Переезд группы в супергруппу, отвязка, удаление клуба
- `chat_id` меняется → строка `chat_trial` переезжает вместе с привязкой (§ 5.3), срок не сбрасывается.
- Отвязка/удаление клуба: подписка остаётся у клуба (ENDED по истечении, дочерних списаний нет —
  шедулер не списывает за клуб без чата); строка `chat_trial` **не удаляется** (R6).
- Повторное подключение того же чата новым клубом: подписки нет, строка есть → бесплатный период
  продолжает идти со старой даты, а если он уже вышел — стена на первой же встрече. Это намеренно.

## 5. Модель данных

Две миграции (новое значение enum нельзя использовать в той же транзакции — как V37).

### 5.1 `V97__platform_billing_chat.sql`
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
    club_id          UUID NOT NULL REFERENCES clubs(id),   -- счёт всегда за клуб (чат)
    subscription_id  UUID REFERENCES service_subscription(id),  -- NULL у материнского до оплаты
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
CREATE INDEX idx_platform_payment_club ON platform_payment (club_id, created_at DESC);

-- Бесплатный период чата — по chat_id, переживает отвязку и удаление клуба (R6). Имя таблицы
-- и смысл полей изменены в V99 (§ 5.1a): ниже — вид на момент V97.
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

Как реализовано (отличия от эскиза выше — в самой миграции `V97__platform_billing_chat.sql`):
- легаси-строки платформенного плана ёмкости (`payer_role = 'ORGANIZER' AND subject_club_id IS NULL`)
  переводятся в `ENDED` — в чат-модели у них нет предмета, реальных денег за ними нет
  (стаб-провайдер); `chk_service_subscription_org_club` добавлен как `NOT VALID`, чтобы эти строки
  под правило не подпадали, а новые вставки и обновления проверялись;
- `platform_payment.kind` и `.status` закрыты `CHECK`-списками; `subscription_id` **nullable**, а
  `club_id` обязателен: строка подписки появляется только с первым успешным платежом (§ 6.3), поэтому
  до ResultURL счёт привязан к клубу, а не к подписке;
- у `chat_free_meeting.event_id` (в V99 — `chat_trial.first_event_id`) и `funnel_event.club_id` FK
  нет — признак и факты воронки
  переживают удаление клуба и его встреч.

### 5.1a `V99__chat_trial.sql` (2026-09-15)
Таблица `chat_free_meeting` переименована в `chat_trial` и сменила смысл: `used_at → started_at`
(момент первой встречи, от него идёт срок), `event_id → first_event_id` (ради разбора спорных
случаев), `released_at` удалена (возврата больше нет — R5), добавлена `reminder_days_left` —
порог последнего отправленного DM о конце периода (7 или 1), дедуп тика как у
`memberships.expiry_reminder_days_left` (V77).

### 5.2 `V98__platform_billing_pricing.sql`
```sql
INSERT INTO subscription_pricing (plan, price_kopecks, effective_from) VALUES ('CHAT', 19900, NOW());
```

### 5.3 Переезд `chat_id`
`ChatLinkService.adoptMigratedChat` публикует `ChatIdMigratedEvent(oldChatId, newChatId)`;
`subscription` слушает и делает `UPDATE chat_trial SET chat_id = new WHERE chat_id = old`.
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
Оба провайдера объявлены `@ConditionalOnProperty` **без** `matchIfMissing`: пустое или незнакомое
`billing.provider` не включает стаб молча. Чтобы причина падения читалась с первой строки лога,
`payment/BillingProviderCheck` (`@DependsOn` на `BillingService`) проверяет значение на старте и
называет переменную — Coolify игнорирует `${VAR:?…}` в compose и подставляет пустую строку
(staging 2026-09-15).

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
  `canPay = (club.ownerId == userId)`: со-организатор видит статус, но кнопку оплаты ему не
  показывают — чекаут ответил бы 403.
- `checkout(clubId, userId, autopay)`: только владелец (`ClubRoleGuard`, капабилити владельца);
  клуб обязан иметь привязку; создаёт `platform_payment(MOTHER, club_id, subscription_id = NULL)`;
  **строку подписки не создаёт** — она появляется в `onResult` с первым успешным платежом (строка
  `PAST_DUE` «в ожидании» с `current_period_end = now` дала бы через правило грейса неделю без
  оплаты); `funnel_event(checkout_started)`; отдаёт URL. Второй чекаут при живом PENDING < 30 мин
  по клубу — вернуть тот же URL (идемпотентность), не плодить InvId, **но `autopay_requested` на
  счёте перезаписывается**: между попытками ползунок мог переключиться, и списывать надо по
  последнему решению владельца (ревью 2026-09-07). Значение `autopay` ползунка
  переносится на подписку при её создании/продлении в `onResult`.
- `status(...).pendingCheckout` считается по неоплаченному материнскому счёту **любого возраста**
  (`hasPendingMother`), а не по окну переиспользования: иначе шит после долгой оплаты видел бы
  «счетов нет» и поздравлял с несостоявшимся продлением.
- `onResult(notification)`: транзакция; `platform_payment` по `inv_id` (нет → WARN, ответ OK, чтобы
  Robokassa не ретраила); сумма совпадает; **клуб ищется до отметки об оплате** — удалённый клуб даёт
  `ERROR` «нужен возврат вручную» и счёт всё равно фиксируется SUCCEEDED (аудит денег), но подписка
  не создаётся; `recordEventIfNew("robokassa:paid:<InvId>")` (повтор → OK без действий;
  для MOTHER без подписки ключ пишется после её создания); → SUCCEEDED, `paid_at`,
  `payment_method`, `fee`; подписка: живая по клубу (`findLatestByClub`, статус ≠ ENDED) →
  `extendPeriod(max(now, period_end) + 30d)`, `transitionStatus(PAST_DUE → ACTIVE)`; нет живой
  (первая оплата или переподписка после ENDED) → новая строка `ACTIVE` с `current_period_end =
  now + 30d`, `payer_user_id = clubs.owner_id`; `platform_payment.subscription_id` проставляется;
  для MOTHER — `provider_token = InvId`, `autopay_possible = isCard(payment_method)`,
  `charge_attempts = 0`; `funnel_event(payment_succeeded)`; DM владельцу.
  Дочернее списание оживляет и **ENDED**-подписку (`PAST_DUE, ENDED → ACTIVE`): провайдер может
  подтвердить его уже после конца грейса, и без этого владелец платил бы, читал «Продлено» и
  продолжал получать 402 (ревью 2026-09-07).

**Идемпотентность и поздние оплаты (ревью 2026-09-07).** `markSucceeded` переводит счёт в
SUCCEEDED из любого статуса, кроме самого SUCCEEDED. Повтор ResultURL по-прежнему даёт 0 строк и
ничего не меняет, а счёт, закрытый шедулером по таймауту (FAILED), принимает позднюю оплату: ссылка
оплаты у Robokassa не истекает, и иначе деньги списывались бы без подписки и без следа.
- `setAutopay(clubId, userId, value)`: владелец; `true` при `!autopay_possible` → 409.
- `requireBillable(club)` — § 6.4.

### 6.4 Гейт `BillingGate.requireBillable(club, eventId, actorUserId)` — единственная точка ✅
Вызывается из `EventService.createEvent` **после** `requireCapability` и **после** вставки события,
в той же транзакции (нужен `event.id`):
1. `club_chat_links` нет → пропустить (R2).
2. `findLatestByClub(clubId)` (подписка с самым поздним периодом — живая, если есть) и
   `ServiceSubscription.allowsNewMeetings(now, graceDays)`: `ACTIVE`/`PAST_DUE` и
   `now < period_end + grace` → пропустить. Считается от `period_end`, а не от статуса — шедулер
   переводит `ACTIVE → PAST_DUE` раз в сутки, стена от его тика не зависит. `ENDED` → не пропускает.
3. Иначе — бесплатный период чата (`ChatTrialRepository.startOrGet`):
   `INSERT INTO chat_trial(chat_id, club_id, first_event_id) … ON CONFLICT (chat_id) DO NOTHING
   RETURNING started_at` — вставка идемпотентна, две одновременные первые встречи получают один
   старт. Новая строка ⇒ `funnel_event(trial_started)`. Если `now < started_at + trial-days` —
   встреча проходит; иначе `PaymentRequiredException(reason, clubId,
   priceKopecks)` → транзакция откатывается, событие не создаётся. `reason = TRIAL_ENDED`,
   если подписки у клуба не было никогда; `SUBSCRIPTION_EXPIRED`, если была (любой статус).
   `funnel_event(paywall_seen)` пишется в **отдельной** транзакции (`REQUIRES_NEW`) — основная
   откатывается вместе со вставкой встречи.
4. Отмена встречи биллинга не касается (R5, V99): срок идёт по календарю, возвращать нечего —
   в `cancelEvent` и `cancelBySystem` вызовов биллинга больше нет.
5. Переезд `chat_id` (§ 5.3): `ChatTrialRepository.migrateChatId(old, new)`; если у нового id уже
   есть строка (двойник успел создать встречу), старая отбрасывается — чат один.

`PaymentRequiredException(reason: PaywallReason, clubId, priceKopecks)` и `PaywallResponse
{error, message, reason, clubId, priceKopecks}`; `currentPlan/requiredPlan` удалены (фронт
`paywallFromError` переписывается в § 7). Вместе с гейтом удалены `SubscriptionPlanPolicy`,
`PricingInvariant`, `requirePaidClubCapacity`, гейты ёмкости в `ClubService.createClub/updateClub`,
`ClubRepository.countPaidByOwnerId`, `GET /api/subscriptions/plans` и тесты ёмкости.

### 6.5 Шедулер `ServiceSubscriptionScheduler` (крон `subscription.lifecycle-cron`, ежедневно; плюс
почасовой тик для PENDING)
Ежедневно, для каждой живой подписки клуба **с привязанным чатом**:
- `period_end − 3d` и `−1d`, `autopay=false` или `!autopay_possible` → DM-напоминание с deep link
  (`?billing=1`); дедуп по `subscription_event("reminder:<period_end>:<3|1>")` — без новой колонки.
- `period_end ≤ now`, `autopay && autopay_possible`, `charge_attempts < 3`, следующий слот по
  расписанию `[0, +1d, +3d]` от `period_end` (4-я попытка на +7d не делается — грейс кончился) →
  `platform_payment(RECURRING)` + `provider.charge(...)`; `ACTIVE → PAST_DUE` при первой неудаче.
- `period_end ≤ now` без автосписания → `ACTIVE → PAST_DUE` + DM «7 дней грейса».
- `period_end + 7d ≤ now` и статус `PAST_DUE` → `ENDED` + DM + `funnel_event(subscription_ended)`.
Ежечасно: `platform_payment` PENDING старше 6 ч → `provider.queryState` → SUCCEEDED (обработать как
ResultURL) / FAILED (`charge_attempts++`, DM при первом фейле). Счёт **любого вида**, зависший
дольше `mother-expire-hours`, закрывается как FAILED, а дочерний дополнительно даёт `PAST_DUE` + DM:
иначе непрерывное «в обработке» у провайдера навсегда блокировало бы ретраи через
`hasPendingRecurring`, и владелец не узнал бы о неудачном списании (ревью 2026-09-07).
Клуб без чата (отвязали) — подписка доживает период, списаний нет, по истечении → ENDED тихо.

**Транзакции тика.** Ни `runDaily`, ни `reconcilePending` не оборачиваются в одну транзакцию:
внутри идут внешние вызовы к провайдеру, и общая транзакция означала бы, что ошибка на последней
подписке откатывает записи об уже отправленных списаниях — следующий тик списал бы те же деньги
повторно. Каждая подписка и каждый счёт обрабатываются отдельно, сбой одного логируется как ERROR
и не останавливает остальные; оплату применяет `BillingService.onResult` в своей транзакции.

### 6.6 API
| Метод | Путь | Кто | Что |
|---|---|---|---|
| GET | `/api/clubs/{id}/billing` | владелец, со-орг с `MANAGE_EVENTS` | `BillingStatusDto` |
| POST | `/api/clubs/{id}/billing/checkout` | владелец | `{autopay:boolean}` → `{paymentUrl, invId}` |
| PATCH | `/api/clubs/{id}/billing/autopay` | владелец | `{autopay:boolean}` → `BillingStatusDto` |
| POST | `/api/clubs/{id}/billing/charge-now` | владелец из `PLATFORM_ADMIN_TELEGRAM_IDS`, только при `BILLING_MANUAL_CHARGE_ENABLED=true` (иначе 404) | служебное списание вне календаря (§ 11) → `202 {invId}`; 409 без сохранённой карты или при незакрытом дочернем счёте |
| POST | `/api/billing/robokassa/result` | Robokassa (permitAll + IP + подпись) | form-параметры → `OK<InvId>` |

```kotlin
data class BillingStatusDto(
    val state: String,            // NO_CHAT | BOT_REMOVED | TRIAL_NOT_STARTED | TRIAL | TRIAL_ENDED | ACTIVE | GRACE | ENDED
    val priceKopecks: Int,        // 19900
    val trialUntil: OffsetDateTime?, // до какого момента бесплатно (только в состоянии TRIAL)
    val trialDays: Int,           // billing.trial-days — чтобы тексты не зашивали число
    val currentPeriodEnd: OffsetDateTime?,
    val graceUntil: OffsetDateTime?,
    val autopay: Boolean,
    val autopayPossible: Boolean,
    val pendingCheckout: Boolean, // есть PENDING MOTHER < 30 мин
    val recipientName: String,    // ФИО самозанятого целиком (billing.recipient-name), для шита и оферты
    val canPay: Boolean,          // смотрящий — владелец клуба; со-организатору шит показывает «платит владелец»
)
```
Реализация: `BillingController` (`@RequiresCapability(MANAGE_EVENTS)` на статус, `@RequiresOrganizer`
на чекаут и ползунок), `BillingService`, ResultURL там же; `StubCheckoutController`
(`GET /api/billing/stub/pay?invId=&method=&outcome=`) — только при `billing.provider=stub`. Без
параметров показывает выбор исхода (карта · СБП · отказ) — у настоящей Robokassa этот выбор делает
её собственная страница, и без него staging-прогон не проверил бы ни СБП (автопродление
недоступно), ни неоплату. Карта и СБП подтверждают счёт и уводят на `/pay/return`, отказ оставляет
счёт неоплаченным и уводит на `/pay/fail`. Ответ ResultURL при неверной подписи/чужом IP — 403,
при расхождении суммы — 400, иначе `OK<InvId>` (в том числе для неизвестного InvId — чтобы
провайдер не ретраил).
`SecurityConfig`: `/api/billing/robokassa/result` permitAll (вместо `/api/subscriptions/webhook`).
`RateLimitFilter`: бакет `billing` для `/api/clubs/*/billing/checkout` — 5/мин на пользователя.

### 6.7 Конфиг (`application.yml` + **оба** compose-файла, правило CLAUDE.md п. 3)
```yaml
billing:
  provider: ${BILLING_PROVIDER:stub}             # stub | robokassa; в prod-compose — БЕЗ дефолта (`:?`)
  grace-days: ${BILLING_GRACE_DAYS:7}
  retry-days: ${BILLING_RETRY_DAYS:0,1,3}         # слоты дочерних списаний от period_end
  pending-timeout-hours: ${BILLING_PENDING_TIMEOUT_HOURS:6}   # PENDING старше → опрос OpStateExt
  mother-expire-hours: ${BILLING_MOTHER_EXPIRE_HOURS:24}       # брошенный чекаут → FAILED
  checkout-reuse-minutes: ${BILLING_CHECKOUT_REUSE_MINUTES:30} # идемпотентность чекаута
  reconcile-cron: ${BILLING_RECONCILE_CRON:0 15 * * * *}       # почасовой опрос счетов без ответа
  recipient-name: ${BILLING_RECIPIENT_NAME:}                   # ФИО самозанятого целиком
  success-url: ${BILLING_SUCCESS_URL:${telegram.webapp-base-url}/pay/return}
  fail-url: ${BILLING_FAIL_URL:${telegram.webapp-base-url}/pay/fail}
  stub:
    settle-seconds: ${BILLING_STUB_SETTLE_SECONDS:5}           # стаб: дочернее списание «прошло» через N с
  robokassa:
    merchant-login: ${ROBOKASSA_MERCHANT_LOGIN:}
    password1: ${ROBOKASSA_PASSWORD_1:}
    password2: ${ROBOKASSA_PASSWORD_2:}
    test-mode: ${ROBOKASSA_TEST_MODE:true}
    hash: ${ROBOKASSA_HASH:SHA256}
    allowed-ips: ${ROBOKASSA_ALLOWED_IPS:185.59.216.65,185.59.217.65}
subscription:
  period-days: ${SUBSCRIPTION_PERIOD_DAYS:30}     # остаётся
  lifecycle-cron: ...                             # остаётся, ежедневный тик календаря
```
`autopay-default` из эскиза не нужен: положение ползунка приходит с чекаутом и хранится на счёте
(`platform_payment.autopay_requested`). Все переменные прокинуты через `docker-compose.prod.yml`;
пароли — только через env, в логах не появляются никогда.

## 7. Фронтенд

- `api/billing.ts` (`getBilling`, `startCheckout`, `setAutopay`, `paywallFromError` → `{reason,
  clubId, priceKopecks}`), `queries/billing.ts` (`useBillingQuery(clubId)`, мутации, инвалидация
  `billing` + `events`).
- `components/billing/BillingSheet.tsx` — донор `DuesPaymentSheet` (портал, шапка, `.rd-dues-amount`,
  `.rd-cl-feat`/`.rd-cl-tgl`, `.rd-btn-primary`), без загрузки скриншота и trust-карточки. Состояния:
  «платит владелец» (со-организатору, когда `canPay = false`: кнопки и ползунка нет),
  «к оплате» (ползунок + карточка провайдера с ФИО + свёрнутая оферта `offerText.ts` + кнопка),
  «ждём подтверждения» (спиннер + опрос 3 с до 60 с, **без** кнопки «подожду в личке» — закрыть
  можно только шапкой), «оплачено до…» (кнопка «Вернуться к встрече» / «Готово»), «пока не видим
  оплату» (повтор). Открывает URL через `openExternalLink` (`openLink` из `@telegram-apps/sdk-react`,
  не `window.open` — в Mini App это разное поведение на iOS). «Оплачено» определяется по сдвигу
  `currentPeriodEnd` относительно снимка на момент чекаута и погашенному `pendingCheckout`.
- `components/billing/BillingStatusStrip.tsx` в двух местах:
  **`ClubPage` над блоком «О клубе»** (PO 2026-09-16) — владельцу и со-организаторам (`isManager`),
  во всех состояниях, но с `withAutopayToggle={false}`: переключать автопродление — действие
  управления, и со-организатор всё равно получил бы 403. Это главный экран клуба, и сроки
  бесплатного периода не должны зависеть от того, зашёл ли человек в «Управление».
  Кнопка открывает тот же `BillingSheet`; со-организатору он покажет «Оплачивает владелец клуба».
  **`OrganizerClubManage`** (владелец) — все состояния, включая «Оплачено до …», и ползунок
  автопродления.
- Форма создания встречи: `catch 402` → `paywallFromError` → `BillingSheet`; после успешной оплаты
  форма остаётся заполненной, кнопка «Создать» активна.
- `ClubPage`/`OrganizerClubManage`: `?billing=1` (из DM) → открыть шит; `?billing=done` /
  `startapp=billing_<clubId>` (возврат из браузера) → шит в состоянии «ждём подтверждения».
  Разбор `startapp` уже есть — `components/DeepLinkHandler.tsx` (паттерны `club_`, `event_`,
  `skladchina_`, `invite_`); добавить `billing_<uuid>` → `/clubs/{id}/manage?billing=done`.
- Страница `/pay/return` (обычный роут фронта, работает вне Telegram): «Оплата принята,
  возвращайтесь в Telegram» + кнопка `https://t.me/<bot>?startapp=billing_<clubId>`;
  `/pay/fail` — «Оплата не прошла» + та же кнопка. Обе без запросов к API (нет JWT вне Mini App).
  Имя бота — из бандла (`VITE_TELEGRAM_BOT_USERNAME`), из адреса берётся только `club` и только
  в формате UUID.
- **Политика обработки персональных данных** `pages/PrivacyPage.tsx` + `privacyText.ts`, адрес
  `/privacy` (2026-09-17): требование 152-ФЗ и типовое требование модерации провайдера — модератор
  ищет на сайте ссылку. Реквизиты оператора берутся из того же `landingContent.ts`, что и оферта;
  ссылки — в подвале лендинга и в блоке «Продавец и контакты».
- **Магазин для модерации Robokassa = бот** (звонок PO 2026-09-19, реализовано 2026-09-20): адрес
  магазина `https://t.me/clubs_v2_bot`; в `/start` и `/terms` бота — выгоды, цена, ФИО + ИНН, e-mail и
  Telegram поддержки; условия получения/отказа — в оферте, оферта и политика раскрываются в том же
  сообщении по кнопкам (`bot/LegalSheet`,
  `telegram-bot.md` § `/start`). Тексты дублируют `offerText.ts`/`privacyText.ts` (общий файл между
  Docker-контекстами невозможен). Env: `BILLING_RECIPIENT_INN` (ИНН), `TELEGRAM_SUPPORT_EMAIL`.
  Телефон не указан: для формата «магазин в Telegram» Robokassa требует «контактные данные» без
  уточнения (robokassa.com/how-works). Лендинг и `/privacy` на сайте остаются для веба.
- **Оферта на базе шаблона Robokassa «Оказание услуг»** (2026-09-24): канонический текст —
  `docs/legal/oferta.md`; обе копии генерирует `scripts/gen-oferta.py` (`offerSections.generated.ts`
  для шита и `/about`, `bot/OfferSections.kt` для бота) — руками их не правят, после правки md
  перегенерировать. Разделы 1, 2, 4–10 — шаблон почти дословно; раздел 3 — наши условия (услуга,
  бесплатный период, 199 ₽/30 дней, автопродление, 7 дней грейса, момент оказания, отказ и возврат
  пропорционально неиспользованным полным дням в течение 10 рабочих дней тем же способом,
  обращения — 3 рабочих дня); раздел 11 — реквизиты самозанятого без телефона (публичные контакты
  поддержки). В шите и на `/about` — разделами с заголовками, в боте — постранично (`legal:offer:<n>`).
  ФИО/ИНН/цена/дни/контакты поддержки подставляются из настроек (фронт — константы `SELLER`/`SUPPORT`
  в `api/billing.ts`). CI `oferta-sync.yml` перегенерирует копии и падает, если они отстали от md.
  Абзац «Возврат» на `/about` повторяет § 3.7. Backlog: `recipientInn` в `BillingStatusDto`, чтобы
  шит не брал ИНН из бандла. Юрист текст не смотрел.
- **Публичная страница сервиса** `pages/LandingPage.tsx` (2026-09-16, для модерации Robokassa и
  ссылки на оферту): описание, «что делает бот», цена и условия, возврат, полный текст оферты
  (тот же `offerText.ts`, что в шите), продавец с ИНН и контакты. Роут `/about` вне Layout; корень
  домена показывает её сам, когда нет `initData` (`entry.ts::shouldShowLanding` в `main.tsx`), из
  Telegram — приложение как раньше. Реквизиты продавца — `pages/landingContent.ts` (ФИО должно
  совпадать с `BILLING_RECIPIENT_NAME`); числа цены и периода — `TRIAL_DAYS_DEFAULT` и
  `CHAT_PRICE_LABEL` в `api/billing.ts`, одно место на все публичные тексты.
- Мастер `ClubSetupWizard` и `ConnectChatScreen`: одна строка «Первые 15 дней бесплатно. Дальше
  199 ₽ в месяц за клуб» (`CHAT_PRICE_LINE`).
- Удалить: `components/subscription/*`, `api/subscription.ts`, `queries/subscription.ts`,
  `SubscriptionCard` из `ProfilePage`, `PaywallModal` из `CreateClubModal`, ключи
  `queryKeys.subscription`.

## 8. Уведомления (DM владельцу, через `NotificationService`)
| Когда | Метод `BillingNotifier` / текст (суть) | Кнопка |
|---|---|---|
| Оплата прошла (MOTHER) | `paid` — «Оплачено до дд.мм.гггг: клуб „…“ ведём дальше. Автопродление включено — дд.мм.гггг спишем 199 ₽ с этой же карты» / «выключено — напомним за 3 дня и за день» | «Открыть клуб» |
| −3д, −1д без автосписания | `expiringSoon` — «Подписка за клуб „…“ заканчивается дд.мм.гггг. Продлить на месяц — 199 ₽» / «Завтра заканчивается…» | `?billing=1` |
| Дочернее списание прошло | `renewed` — «Продлено до дд.мм.гггг. Спасибо, что пользуетесь Clubs! …больше живого общения и встреч офлайн… со временем втягиваются все :)» (текст PO) | «Открыть клуб» |
| Списание не прошло (первый фейл) | `chargeFailed` — «Не удалось списать 199 ₽ за клуб „…“. Обновите карту или оплатите вручную — до дд.мм.гггг всё работает как раньше» | `?billing=1` |
| period_end без оплаты | `periodEnded` — «Подписка за клуб „…“ закончилась. До дд.мм.гггг всё работает, потом новые встречи — после оплаты» | `?billing=1` |
| Грейс исчерпан | `graceExhausted` — «Новые встречи в клубе „…“ недоступны до оплаты. Начатое доживёт, бот из чата не уходит» | `?billing=1` |
DM «завтра спишем» перед автосписанием **нет** (снято PO 2026-09-07). В чат — ничего. Бюджет
«1 закреп + 2 поста» не тратится. Даты — `dd.MM.yyyy` по Москве, как в остальных DM бота.

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
7. **Стаб не включается молча (ревью 2026-09-07).** `@ConditionalOnProperty` у `StubPaymentProvider`
   и `StubCheckoutController` — без `matchIfMissing`: пустое или незнакомое `billing.provider` не
   даёт бина провайдера вовсе, и приложение падает на старте вместо тихой раздачи подписок.
   В `docker-compose.prod.yml` переменная объявлена как `${BILLING_PROVIDER:?…}` — незаданная роняет
   деплой. Публичный ResultURL под стабом закрыт: `trustsResultSource` = false,
   `parseResultNotification` бросает 403, счёт подтверждается только своей страницей
   `/api/billing/stub/pay`. Иначе угаданный `InvId` (последовательность от 100000) выдавал бы
   тридцать дней подписки без денег.

## 10. Критерии приёмки
1. Чат подключён, встреч нет: полоска «15 дней бесплатно · отсчёт пойдёт с первой встречи», кнопки нет.
2. Первая встреча создаётся без оплаты; полоска сменилась на «Бесплатно до <дата>» с кнопкой
   «Оплатить»; в `funnel_event` появился `trial_started`. Вторая и третья встречи внутри периода
   тоже создаются свободно.
3. Отмена встречи бесплатный период НЕ возвращает: полоска показывает ту же дату (R5).
4. Оплата внутри периода: шит поясняет, что период не прерывается, после оплаты `ACTIVE`,
   `current_period_end = оплата + 30д`, DM «Оплачено до…».
5. Конец периода (staging: `BILLING_TRIAL_DAYS=1`, частый крон): DM о конце (за неделю — только при
   длинном периоде, за день — всегда), затем создание встречи даёт шит с `reason=TRIAL_ENDED`,
   полоска «Бесплатный период закончился»; отмена, явка, голосование и сборы работают.
6. Отвязать чат → удалить клуб → подключить тот же чат заново → бесплатный период **не** начинается
   заново: он продолжает идти со старой даты, а если вышел — стена на первой же встрече.
7. Оплата после стены: `ACTIVE`, встреча создаётся; ползунок автопродления включён.
8. Ползунок выкл. → напоминания за 3 и 1 день до конца оплаченного периода (`SUBSCRIPTION_PERIOD_DAYS=1`)
   → без оплаты `PAST_DUE` → через `BILLING_GRACE_DAYS` → `ENDED` → 402 с `reason=SUBSCRIPTION_EXPIRED`.
9. Ползунок вкл. + материнский платёж картой → `autopay_possible=true`; тик шедулера создаёт
   `platform_payment(RECURRING)` и вызывает `charge` (со стабом — SUCCEEDED через N секунд → продление).
   Материнский по СБП → `autopay_possible=false`, ползунок заблокирован с пояснением.
10. Повторный ResultURL с тем же `InvId` — без второго продления; неверная подпись или чужой IP — 403.
    Клуб без чата: гейт не срабатывает, `state=NO_CHAT`. Со-организатор видит «Оплачивает владелец клуба».
10a. **Бота выгнали из чата** (руками в Telegram): полоска «подписка на паузе», встречи создаются
    без стены, тик шедулера не списывает и не шлёт DM; вернули бота — полоска и гейт как раньше.
11. `funnel_event` содержит `trial_started`, `paywall_seen`, `checkout_started`, `payment_succeeded`,
    `subscription_ended`; backend `./gradlew test` и frontend `npm test` + чистый `tsc` зелёные.

## 11. Ловушки и календарные зависимости
- **Рекуррент без тестового режима.** Дочернее списание нельзя проверить с `IsTest=1`. План:
  на staging весь цикл (напоминания, PAST_DUE, грейс, ENDED, ретраи) гоняется на
  `StubPaymentProvider` с `SUBSCRIPTION_PERIOD_DAYS=1`; материнский платёж на staging — Robokassa
  в тестовом режиме. Первое настоящее дочернее списание проверяется на проде на собственном чате
  PO: период там глобальный (30 дней), поэтому нужен служебный триггер «списать сейчас» для одной
  подписки — `POST /api/clubs/{id}/billing/charge-now`, доступный только при
  `BILLING_MANUAL_CHARGE_ENABLED=true` и только владельцу клуба из env-списка
  `PLATFORM_ADMIN_TELEGRAM_IDS`. После проверки флаг выключается. **Реализовано 2026-09-16:**
  `ManualChargeAccess` (флаг → 404, чужой id → 403) + `BillingLifecycleService.chargeNow` — тот же
  `sendRecurringCharge`, что у календарного тика, без проверки слота. Деньги уходят раньше срока, но
  период продлевается от его конца (`settleRecurring`), оплаченное время не теряется. Порядок на
  проде: включить флаг и id PO в Coolify → `curl -X POST … -H 'Authorization: Bearer <JWT>'` (JWT —
  из DevTools Mini App) → дождаться ResultURL/опроса → DM «Продлено до …» → выключить флаг.
- **«По предварительному согласованию»**: рекуррент включает поддержка Robokassa по заявке —
  PO подаёт заявку в день договора, иначе `Recurring=true` молча не сработает.
- **IP Robokassa за Traefik/nginx**: клиентский IP брать через доверенные прокси, как в
  `RateLimitFilter`, иначе allowlist отвергнет всё. Российский прокси перед Hetzner (`infra/ru-proxy/`)
  в цепочку не входит: он L4 и отдаёт адрес клиента Traefik по PROXY protocol.
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
