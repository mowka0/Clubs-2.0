# Handoff: Monetization v2 — Billing-Model Design Session

> **Тип сессии:** design-only (LOCK модели). НИ СТРОЧКИ кода в этой сессии — только закрепление продуктовых развилок и запись залоченной спеки.
> **Self-contained:** этот документ достаточен для старта новой сессии без иного контекста.
> **Source of truth:** `docs/design/payment-monetization-v2.md` (полный decision record). Этот handoff операционализирует §10 «следующий шаг».

---

## 1. TL;DR + решение

Проект выбрал **трек монетизации**. После анализа PO принял решение: **сначала залочить модель (design-сессия), потом код**. Полный стратегический разбор/decision record уже существует — `docs/design/payment-monetization-v2.md`.

**Что делает ЭТА сессия:** LOCK биллинг-**модели** — закрепляет открытые продуктовые развилки (`§6.3`, `§7`, `§11.2` дизайн-дока) в одну решённую спеку.

**Чего ЭТА сессия НЕ делает (критично, §10):**
- НЕ запускает биллинг и НЕ ставит дату пэйвола.
- НЕ выбирает финальные цены (это tuning-knob, зависит от пересчёта НДС — см. §5 этого handoff).
- НЕ пишет миграции, эндпоинты, acquirer-интеграцию.

Цель — решить **что мы построим, когда будем монетизировать**, а не включить это. Декаплинг-слайс (раздел 7) и сервисный сбор строятся ПОСЛЕ, и сбор gated на (а) юридику и (б) доказанную активность бесплатного цикла.

---

## 2. Стратегическая рамка (§10) — не спешить с пэйволом

Прямой вывод дизайн-дока (§10), который держим интактным всю сессию:

- **§10.1 — не брать деньги рано.** Сначала доказать цикл на бесплатной базе. Пэйвол поверх недоказанного value-цикла убьёт рост и не проверит гипотезу «платить за бывшее бесплатным» (§1.4).
- **§10.2 — привязать монетизацию к активности.** Платить имеет смысл там, где уже есть живой клуб/событийный поток, а не на пустом месте.
- **§10.3 — серьёзно рассмотреть сбор с организатора**, а не плоский сбор с участника. Организатор — сторона с очевидным ROI; участник получает ценность косвенно.

Из этого следует уклон всей сессии: **organizer-pays** как базовая v1-модель, member-pays — отложенный рычаг (не построенный, не удалённый).

Retention-движок, на котором держится §10 («доказать цикл»), — репутация v2 (`docs/modules/reputation-v2.md`). Монетизация не должна его подменять; она к нему пристёгивается.

---

## 3. ЗАЛОЧЕНО — не переоткрывать

Это уже решено в дизайн-доке (§1/§5/§7/§8/§9). В design-сессии **НЕ переоткрывать**, только опираться:

### 3.1 Платформа ВНЕ потока денег (§1, §5)
- Деньги участника за членство/складчину идут **напрямую организатору**, мимо платформы (P2P-out). Платформа их не держит, не сплитит, не подтверждает.
- Stars **отвергнуты** для РФ (§5) — ни `XTR`-инвойсов, ни charge-id, ни 20/80-сплита в проде.

### 3.2 Сервисный сбор = плата за СОБСТВЕННЫЙ продукт платформы (§5, §6.1)
- Платформа берёт **отдельный рекуррентный сервисный сбор за свой продукт** (доступ к инструментам/ёмкости), как зарегистрированный merchant на **УСН 6%**.
- Это НЕ комиссия с цены организатора. Рекуррентность здесь **чистая именно потому**, что платформа биллит **только свой собственный сбор** (§5.2) — проблема «нет P2P авто-списания» (§3.5/§3.6) сюда НЕ применяется.

### 3.3 Capacity-plans frame (§6.2)
- Монетизация = **планы-ёмкости** (сколько клубов может вести организатор), а не процент с оборота.
- Цена за +1 клуб = **volume discount** (строго убывает с ростом тарифа); честная строка «сервисный сбор», не «комиссия».
- **Анти-паттерн (отвергнут §6.2):** лестница, где 2-й клуб — штраф (отвергнутая сетка 1→30 / до3→99). Мульти-клубовый штраф налогает discovery-движок — запрещено.

### 3.4 Billing golden rules (§7.1, §7.3, §7.4)
- **No access ⇒ no charge** (§7.1) — заряд следует за статусом доступа атомарно.
- **Pause = заморозка ЧАСОВ, не даты** (§7.3) — при заморозке остаток оплаченных часов банкуется; при возобновлении доигрывается; следующий заряд — только когда остаток дошёл до нуля. **Без мгновенного перезаряда при resume.**
- **Кто может завершить** (§7.4) — и организатор, и участник могут закончить; «уйти из клуба» = отмена + потеря доступа одним атомарным действием.

### 3.5 Две формы платежа за ОДНИМ PaymentProvider (§8, §9)
- **P2P-OUT (база):** деньги мимо платформы; приложение только трекает статус (honor-system, как складчина).
- **SPLIT (опция):** опциональный сплит-эквайринг — только если/когда понадобится; тянет за собой 54-ФЗ/НПД/лимиты (см. §5 handoff).
- Обе — за **одним `PaymentProvider`-сеамом** (§9). Сервисный сбор платформы биллится через тот же seam, отдельно от P2P-денег.

---

## 4. РЕШИТЬ В ЭТОЙ СЕССИИ

**Метод:** прогнать развилки 1→2→3→4 через `AskUserQuestion` (по одному решению за раз, опции предзаготовлены). После ответов PO — Analyst пишет залоченную спеку в **`docs/modules/payment-v2.md`** (НЕ перезаписывать `payment.md` — это as-built v1) и флипает маркеры `§6.3 / §7 / §11.2` дизайн-дока ОТКРЫТО → РЕШЕНО.

**Порядок гейтит:** Решение 1 гейтит модель данных; 2 и 3 независимы; 4 — в основном подтверждение + единственная развилка 4d.

---

### РЕШЕНИЕ 1 — КТО ПЛАТИТ (организатор / участник / гибрид)

**Вопрос:** Кто основной плательщик сервисного сбора платформы?

**Опции:**
- **A. Organizer-pays** — платят только организаторы (по тарифу-ёмкости); членство бесплатно для участников.
- **B. Member-pays** — каждый участник платит мелкий сбор за членство в платном клубе.
- **C. Hybrid** — мелкий member-сбор + платный «Pro»-тариф для организаторов.
- **D. Organizer-pays сейчас, member-pays потом** — лочим organizer-pays как v1, member-pays держим отложенным рычагом (не строим).

**Уклон дока:** organizer-pays (§6.3: «ценность достаётся в основном организатору, ROI очевиден»; §10.3).

**Рекомендация: D** (organizer-pays как залоченная v1; member-pays явно отложен, не удалён).
*Почему:* организатор — сторона с очевидным ROI и это **один** плательщик на клуб вместо N → кратно меньше биллинг-отношений и churn-поверхности; обходит непроверенную гипотезу «платить за бывшее бесплатным» (§1.4/§10) на самой ценочувствительной стороне (участники). Hybrid (C) умножает сложность до того, как цикл доказан — нарушает YAGNI против §10. D (а не просто A) держит member-рычаг задокументированным, чтобы не релитигировать, но v1 — один биллинг-путь.

**Импликация модели данных:**
- Биллинг-сущность ключуется на **организатор (user) + plan**, НЕ на membership. План — platform-wide на организатора (прямо в §7.5 aggregate flag).
- Member-записи в v1 **без биллинг-полей**. НЕ добавлять `member.subscription_id`. Member-таблица чистая и реверсивная, если member-pays когда-нибудь введут (за новой сущностью).
- Стейт-машина §7.2 крепится к **plan-подписке организатора**, не к каждому членству.

---

### РЕШЕНИЕ 2 — СТРУКТУРА ПЛАНОВ (планы-ёмкости + ценовое правило)

**Вопрос:** Какова лестница тарифов и **правило**, управляющее ценами между тарифами? (Точные числа tunable-later, НЕ блокер — §6.2.)

**Опции (лестница тарифов):**
- **A. Три тарифа** — «1 клуб / до 3 / безлимит» (пример §6.2).
- **B. Два тарифа** — «1 клуб / безлимит» (проще, меньше proration-краёв).
- **C. Бесплатная база + платные тарифы** — бесплатно до X клубов, дальше планы-ёмкости (в линию с §10 «сначала бесплатная база»).

**Ценовое ПРАВИЛО (вот несущее решение, не числа):**
- Каждый выше тариф = **volume discount**: цена за клуб строго убывает с ростом тарифа.
- Маржинальная цена «+1 клуб ёмкости» **никогда не больше** базовой single-club ставки.
- **Жёсткий анти-паттерн (отвергнут §6.2):** любая лестница, где 2-й клуб — штраф (отвергнутая сетка 1→30 / до3→99). Запрещено.
- «Безлимит» должен включаться **рано/дёшево**, чтобы активные мульти-клуб организаторы не наказывались.

**Уклон дока:** три тарифа с volume-discount (§6.2). Числа (30 / ~59 / ~99₽) — иллюстративны.

**Рекомендация: A** (три тарифа как *структура*; лочим *ценовое правило*, не цены).
*Почему:* три тарифа («1 / до 3 / безлимит») покрывают реальное распределение организаторов без proration-ада, о котором предупреждает §6.2. Реальный deliverable здесь — **инвариант монотонного volume-discount** (постоянное продуктовое правило); рубли — launch-time tuning-knob. Явно зафиксировать: точные цены отложены в legal/unit-economics сессию (зависят от пересчёта 22% НДС эквайринга — см. §5 handoff).

**Импликация модели данных:**
- `plan` = **enum/lookup** (`SINGLE`, `TRIO`, `UNLIMITED`) с `max_clubs`-ёмкостью, НЕ свободное число. Ёмкость = ceiling-чек при создании/активации клуба.
- Цена — в **config/pricing-таблице, keyed by plan + effective-date**, никогда не хардкод, никогда не от клиента (сервер считает — §9). Effective-date позволяет тюнить цены без миграции и поддерживает отложенный НДС-пересчёт.
- Инвариант ценообразования enforced **тестом**, не только доком: unit-тест утверждает, что цена-за-клуб монотонно невозрастающая через тарифы (навсегда сторожит анти-паттерн).
- **Без авто-proration** (§6.2): смена тарифа = plan-swap на следующей биллинг-границе, не mid-cycle перерасчёт.

---

### РЕШЕНИЕ 3 — ТРИГГЕР (разовый вход / месячная подписка)

**Вопрос:** Сервисный сбор — разовый заряд или рекуррентная (месячная) подписка?

**Опции:**
- **A. Месячная подписка** — рекуррентное авто-списание собственного сбора платформы (merchant-tokenized card / СБП-подписка).
- **B. Разовый вход** — платёж один раз за клуб/за join, без рекуррентности.
- **C. Годовая** — рекуррентная, но раз в год (меньше биллинг-событий, выше commitment-friction).

**Уклон дока:** подписка (§6.3 «склоняемся к подписке»). Критично: §5.2 — рекуррентность здесь **чистая именно потому, что платформа биллит только СВОЙ сбор** как зарегистрированный merchant; проблема «нет P2P авто-списания» (§3.5/§3.6) сюда НЕ применяется.

**Рекомендация: A** (месячная подписка).
*Почему:* вся §7 стейт-машина (freeze-by-hours, lapsed, aggregate flag) имеет смысл только для рекуррентной модели — и §5.2 специально расчищает подписку как юридически/технически чистую для собственного сбора платформы. Разовый (B) выбрасывает retention/dunning-механику, спроектированную в §7. Месячная > годовой, т.к. §10 хочет low-commitment тайминг и лёгкий выход, пока value-гипотеза не доказана.

**Импликация модели данных:**
- Требует merchant-tokenization (saved card token / СБП-subscription) — это ОК, т.к. это **собственный** сбор платформы, не P2P club-взнос (§5.2 vs §3.5/§3.6).
- Биллинг-цикл меряется в **subscription-hours remaining** (заводит Решение 4 / §7.3 freeze-семантику).
- Эндпоинты из §9: `create-subscription`, `webhook` (signature verify + idempotency `provider_event_id UNIQUE`), `cancel/leave`, `status`.
- **Вне scope этой сессии:** какой acquirer даёт tokenization (ЮKassa vs Т-Банк) — legal/ops блок (§5 handoff).

---

### РЕШЕНИЕ 4 — БИЛЛИНГ-СТЕЙТ-МАШИНА (§7.2–7.5)

**Вопрос:** Подтвердить четыре состояния, freeze-by-hours семантику, кто может завершить, и plan-level aggregate flag — и залочить **инварианты**, чтобы спека была buildable.

**В основном подтверждение, не развилка — но четыре под-пункта требуют явного LOCK:**

**4a. Четыре состояния (§7.2):** `Active` / `Frozen-by-organizer` / `Lapsed` / `Ended`.
→ **Lock as-is.** Добавить §7.1 golden rule как enforced инвариант: *no access ⇒ no charge.* Заряд следует за статусом доступа атомарно.

**4b. Pause = заморозка ЧАСОВ, не даты (§7.3):**
→ **Lock freeze-by-hours.** При заморозке остаток оплаченных часов банкуется; при resume доигрывается; следующий заряд — только когда остаток дошёл до нуля. **Без мгновенного перезаряда при resume** (предотвращает двойной заряд в одном месяце). Это самое баг-склонное правило — вынести в спеку с проработанным примером.

**4c. Кто может завершить (§7.4):**
→ **Lock: и организатор, И участник** могут завершить. «Участник leave club» = отмена сбора + потеря доступа **одним атомарным действием** (биллинг не заложник дисциплины организатора). При Решении 1 = organizer-pays, «member leave» влияет на §7.5 aggregate flag, не на member-level заряд.

**4d. Plan-level aggregate flag (§7.5):**
→ **Lock aggregate-модель.** Для планов-ёмкостей план биллится, пока у организатора **≥1 активное членство/клуб**; падает до 0 ⇒ **pause** (не Ended). Биллинг decoupled от индивидуальных freeze'ов клубов — платформа смотрит на **один aggregate boolean на организатора**, не на per-club state.

**Развилка для PO на 4d:** когда aggregate flag падает до 0 — это **Pause** (resumable, часы банкуются) или **Lapsed** (надо переподписаться)? Док §7.5 говорит pause — рекомендую подтвердить **Pause**, чтобы совпадало с freeze-hours философией и не наказывать временный спад.

**Импликация модели данных:**
- `subscription`-таблица: `status` enum (4 состояния), `hours_remaining` (или `paid_through`, пересчитываемый из банкованных часов), `frozen_at` nullable, `provider_token`, `provider_event_id UNIQUE` (идемпотентность §9).
- Переходы состояний **forward-only** и **`@Transactional`** вместе с грантом доступа (§9: защита от out-of-order webhooks).
- Aggregate flag = **derived query** (`count(active memberships/clubs) ≥ 1`) или поддерживаемый counter на подписке организатора, пересчитываемый на каждое изменение membership/freeze. Рекомендую derived-query first (KISS), кэш-counter — только если станет hotspot.
- Dunning (retry при failed charge → Lapsed) — часть стейт-машины, но его *расписание* (сколько ретраев, как долго) — tuning-knob. Лочим **состояния**, откладываем **тайминги**.

---

## 5. ВНЕ SCOPE — юрист/человек (§11). Сессия НЕ должна это решать.

Требуют юриста/бухгалтера, не продуктового решения. Сессия может **отметить зависимости**, но НЕ решать:

1. **Юрлицо и налоговый режим** — ИП vs ООО на УСН 6%, против прогноза оборота (§11.1).
2. **Выбор acquirer** — ЮKassa vs Т-Банк (vs split-acquirer для опциональной SPLIT-формы) (§9, §11.5). Tokenization Решения 3 зависит от этого, но не решает.
3. **Формулировка оферты сервисного сбора** — составить так, чтобы «сервисный сбор» НЕ переклассифицировался в агентскую/комиссионную долю цены организатора (§6.1, §11.3) — остро, если SPLIT-форма ship'нется.
4. **289-ФЗ «О платформенной экономике»** (в силе 2026-10-01) — новые обязательства платформы; может ограничить модель, но это legal review item (§11.4).
5. **289-ФЗ / split-option compliance** — 54-ФЗ чековая схема, НПД-статус check перед payout, лимит 2.4 млн₽/год, минимальный оборот провайдера (§11.5). Релевантно только если/когда строят SPLIT opt-in форму.
6. **Пересчёт 22% НДС эквайринга** (в силе 2026-01-01) — unit-экономика сбора; это **почему точные цены в Решении 2 отложены** (§4.4, §11.6). Продуктовая сессия лочит *ценовое правило*; *числа* ждут этот пересчёт.

---

## 6. Текущий код v1 (мигрируем прочь)

Существующий Stars-путь (нейтрализуется monetization-v2). Инвентарь — для контекста и для декаплинг-слайса (раздел 7). **В design-сессии не трогать код.**

### 6.1 Модуль payment — `backend/src/main/kotlin/com/clubs/payment/`

| Файл | Назначение |
|---|---|
| `PaymentService.kt` | Ядро. `createInvoice()` строит Telegram Stars `SendInvoice` (валюта `XTR`); `handleSuccessfulPayment()` идемпотентно активирует/продлевает членство + пишет `Transaction` со сплитом 20/80. |
| `Transaction.kt` | Доменная запись одного Stars-платежа: `amount`, `platformFee`, `organizerRevenue`, `telegramPaymentChargeId`, type (subscription/renewal). |
| `TransactionMapper.kt` | jOOQ `TransactionsRecord` → `Transaction`. |
| `TransactionRepository.kt` | Интерфейс: `existsByTelegramChargeId`, `save`, `sumCompletedSubscriptionRevenueSince`. |
| `JooqTransactionRepository.kt` | jOOQ-impl (charge-id dedup, insert, monthly revenue sum). |
| `PaymentConfirmedEvent.kt` | Spring app-event (`telegramId`, `clubName`), publish в tx, consume after commit. |
| `PaymentNotificationHandler.kt` | `@TransactionalEventListener(AFTER_COMMIT)` — шлёт welcome-DM «Оплата принята». |
| `SubscriptionScheduler.kt` | `@Scheduled(cron "0 0 9 * * *")` daily — DM expiring-soon + grace, затем триггерит expiry. |
| `SubscriptionLifecycleService.kt` | Tx-обёртка: `processExpiry()` active→grace_period (at expiry) → grace_period→expired (после 3-дн grace). |

### 6.2 Схема / миграции платежей
- **`V8__create_transactions.sql`** — таблица `transactions`. Сплит-колонки: `platform_fee` (`:15`), `organizer_revenue` (`:16`), `amount` (`:14`), `telegram_payment_charge_id` (`:17`). Enums: `transaction_type ('subscription','renewal')`, `transaction_status ('completed','failed','refunded')`.
- **`V12__transactions_charge_id_unique.sql`** — partial UNIQUE на `telegram_payment_charge_id WHERE NOT NULL`; идемпотентность `successful_payment`.
- **`V3__create_memberships.sql`** — enum `membership_status ('active','grace_period','cancelled','expired')`; колонка `subscription_expires_at`.
- **`V2__create_clubs.sql`** — `clubs.subscription_price INT DEFAULT 0 CHECK (>=0)` — месячная Stars-цена клуба; `0` = free.
- **`V26__clear_phantom_subscription_on_free_clubs.sql`** — data-fix (контекст).

> Процент сплита **не в БД** — хардкод `PLATFORM_FEE_PERCENT = 20`, **дублирован** в `PaymentService.kt:20` и `club/FinancesService.kt:12`.

### 6.3 Call-sites paid-пути
- **Webhook (ClubsBot):** `bot/ClubsBot.kt:43-46` (`pre_checkout_query`), `:52-62` (`successful_payment`), `:85-103` (`handlePreCheckoutQuery`), `:27` (PaymentService inject).
- **Invoice (3 вызова `createInvoice`):** `application/ApplicationService.kt:152-154` (approve платного клуба), `:382-411` (`resendInvoice`, cooldown 60s) → `ApplicationController.kt:99-107` (`POST /api/applications/{id}/resend-invoice`); `membership/MembershipService.kt:228-235` (`joinOrRequestPayment`: `price>0` → invoice + `JoinResult.PendingPayment`).
- **Membership persistence:** `membership/JooqMembershipRepository.kt:297-306` `activateSubscription`, `:318-326` `renewSubscription`, `:368-373` `moveActiveToGracePeriod`, `:377-383` `moveGracePeriodToExpired`, `:180-187` `findExpiryRefByUserAndClub`. `MembershipController.kt:58` (`PendingPayment`→202+`PendingPaymentDto`). Gate: `MembershipService.kt:109-110` `hasActivePaidAccess()`.
- **Finances:** `club/FinancesService.kt:12-13,29-40` (пересчёт 20/80 из `sumCompletedSubscriptionRevenueSince`) → `/api/clubs/{id}/finances`.
- **Quality/rank:** `clubquality/JooqClubRankRepository.kt:158` фильтр по `TRANSACTIONS.TELEGRAM_PAYMENT_CHARGE_ID.isNotNull` (real-payer сигнал для L3).

### 6.4 Frontend payment-flow
- `pages/ClubPage.tsx` — `isPendingPayment` (`:153`), «Ожидаем оплату — {priceStars} Stars» (`:263-307`), state `:93`.
- `pages/MyClubsPage.tsx` — awaiting-payment inbox; `useResendInvoiceMutation` (`:15`,`:255`), «Цена: {price}⭐» (`:288`), pending (`:179-180`,`:365`).
- `api/membership.ts` — `resendApplicationInvoice()` → `POST …/resend-invoice` (`:172-173`), `getMyPendingApplications` (`:128`).
- `queries/applications.ts` — `resendApplicationInvoice` mutation (`:11`,`:132`).
- `types/api.ts` — `PendingPaymentDto`/`isPendingPayment`, `PendingApplicationDto` (`:249-263`).
- `components/CreateClubModal.tsx:234,252` — «Цена подписки (Stars/мес)» + «зарабатывать N Stars (80% от дохода)».
- `pages/InvitePage.tsx:118` — «{subscriptionPrice} Stars / мес».
- `components/club/ClubMembersTab.tsx:74,105` — «Ожидают оплаты».
- `api/events.ts:90-91` — `getFinances()`. `ClubCard.tsx:20` уже комментит бренд ₽, не Stars-символ.

### 6.5 Что нейтрализовать (выход в P2P-out)
1. **Убить Stars I/O** — снять `createInvoice` (`SendInvoice`/`XTR`/`LabeledPrice`) и webhook-хендлеры `ClubsBot.kt:43-62,85-103`. `handleSuccessfulPayment` — единственное, что сейчас активирует платное членство, его замена — crux.
2. **Перемаршрутизировать активацию** — 3 вызова `createInvoice` гейтят на `subscription_price>0`. В P2P-out платформа не подтверждает платёж → (a) организатор вручную «paid» → `activateSubscription`, либо (b) join становится free-style. Путь `PendingPayment`/202 и `resend-invoice` мертвеют.
3. **Нейтрализовать сплит** — колонки `platform_fee`/`organizer_revenue` (V8) и хардкод `PLATFORM_FEE_PERCENT=20` (`PaymentService.kt:20` + `FinancesService.kt:12`) больше не отражают реальность. `FinancesService`/`/finances`/`CreateClubModal` «80% от дохода» — переписать/удалить.
4. **Сохранить, но переисточник lifecycle** — `subscription_expires_at` + `membership_status` + `SubscriptionScheduler`/`SubscriptionLifecycleService` нужны для expiry доступа, но expiry driven организатором (или новой capacity/plan моделью). Grace-DM «пополнить баланс Stars» (`SubscriptionScheduler.kt:33`) — снять Stars-формулировку.
5. **Снять Stars-rank сигнал** — `JooqClubRankRepository.kt:158` keys L3 на `telegram_payment_charge_id IS NOT NULL`; без charge-id сигнал пустеет → заменить v2 paid-confirmation маркером или убрать из rank-gate.
6. **Frontend de-Stars** — каждый «Stars/⭐» и `priceStars`/invoice-resend UX → v2 P2P-out copy; `ClubCard.tsx:20` уже сигналит ₽-направление.

> Design-reference цели: `docs/design/payment-monetization-v2.md` (платформа вне потока денег + рекуррентный сервисный сбор; Stars отвергнуты для РФ).

---

## 7. Первый код ПОСЛЕ залока модели — decoupling-слайс

> **Независим от who-pays/pricing** (Решения 1–3). Декаплинг заменяет **Stars-событие** как драйвер доступа на **организатор-гейт** — это не зависит от того, кто в итоге платит сервисный сбор. **Может стартовать параллельно** с/сразу после design-сессии; не ждёт решения по биллингу. Сервисный сбор пристёгивается позже через `PaymentProvider`-seam.

**Цель слайса:** доступ платного клуба = организатор-контролируемый гейт (статус `active`/`frozen`), взносы member→organizer мимо платформы (механика складчины), Stars-инвойс нейтрализован, `transactions` сохранены как frozen ledger, тонкий `PaymentProvider`-seam застаблен под будущий service-fee acquirer. **Решения who-pays/pricing не нужны.**

**Переиспользование складчины:** org-гейт «mark dues paid / unmark» — почти точный клон skladchina A-2 org-toggle (`SkladchinaPaymentService` org-mark/unmark: `pending→paid`/`paid→pending`, confirm-dialog, owner-check в сервисе, идемпотентно, `WHERE status=…` + rows-affected guard). Взносы остаются «деньги через организатора мимо платформы, app трекает статус» — идентично складчине.

### Backend
- **B1. Миграция `V35__membership_access_gate.sql` (~M).** Добавить `frozen` в enum `membership_status` (`ALTER TYPE … ADD VALUE`). Колонки в `memberships`: `access_frozen_at TIMESTAMPTZ NULL`, `dues_marked_paid_at TIMESTAMPTZ NULL`, `dues_marked_by UUID NULL REFERENCES users(id)`. Затем `./gradlew generateJooq`. **Risk:** `ADD VALUE` не работает в txn-блоке на старых PG и новое значение не usable в той же txn — держать в отдельной миграции, изолированно от любого `UPDATE` с ним.
- **B2. Миграция `V36__neutralize_platform_split.sql` (~S).** **НЕ дропать** `transactions` — историю держать как immutable audit ledger (§9). Дефолты `platform_fee DEFAULT 0`, `organizer_revenue DEFAULT 0`, закомментить колонки как deprecated/frozen. Бэкфилл не нужен. **Risk:** не `DROP COLUMN` — `FinancesService` (B7) и jOOQ их биндят; дроп — отдельный поздний cleanup.
- **B3. `PaymentProvider`-seam (§9) (~S).** Интерфейс `payment/PaymentProvider.kt` (`createServiceFeeCharge`, `handleWebhook(event)` → sealed result) + `NoopPaymentProvider @Component` (logs + no-op, DI-проводка есть, будущий ЮKassa/Т-Банк дропается вторым `@Component`). Единственная вводимая сейчас абстракция — тонкая (YAGNI: без rates/split/DTO). Retired Stars-код НЕ форсить за неё.
- **B4. Access-gate service + endpoints (~L) — ядро, reuse skladchina A-2.** `membership/AccessGateService.kt` + repo-методы: `freezeAccess` (`active→frozen`, set `access_frozen_at`, owner-check, `WHERE status='active'`+rows-affected), `unfreezeAccess` (`frozen→active`), `markDuesPaid` (set `dues_marked_paid_at`/`dues_marked_by`; если `frozen` — unfreeze; идемпотентно), `unmarkDues`. Эндпоинты на `MembershipController` (owner-check, → `MembershipDto`): `POST /api/clubs/{clubId}/members/{userId}/{freeze|unfreeze|dues-paid|dues-unpaid}`. Errors: 400/403/404/409.
- **B5. Access-предикат driven гейтом, не Stars-expiry (~M) — семантический pivot.** `membership/MembershipAccess.kt` `hasAccess()`: access = `status = active`, **исключить `frozen`** (drop `cancelled AND subscription_expires_at>now` как paid-driver; оставить только для legacy grandfathering). Единый source of truth — все call-sites следуют автоматически. **Highest risk слайса:** load-bearing через events feed, DM eligibility, skladchina participant lists, club-quality denominators. Верифицировать `find_referencing_symbols` на `MembershipAccess.hasAccess` + focused-тест: `frozen`-member теряет feed/vote/DM.
- **B6. Retire Stars-invoice + join (~M) — убирает pay-to-join.** `MembershipService.joinOrRequestPayment`: для платных клубов не звать `createInvoice` — активировать членство сразу в `frozen` (гейт = явный unlock; рекомендуется). Убрать `JoinResult.PendingPayment`/`PendingPaymentDto` из live-пути. `ApplicationService.approveApplication`+`resendInvoice`: approve создаёт (frozen) членство напрямую; deprecate/410 `resend-invoice`. `ClubsBot.kt`: нейтрализовать `handlePreCheckoutQuery` (ok=false) и `successful_payment` (log-and-ignore), чтобы stray Stars-событие не активировало доступ. **Risk:** «awaiting-payment» surfaces мертвеют → координировать с B8.
- **B7. Decommission split-finances readout (~S).** `FinancesService`: убрать 20/80-расчёт (raw amounts / скрыть панель) или repoint на frozen ledger как historical-only. Никаких новых `platform_fee`/`organizer_revenue`.
- **B8. Decommission «awaiting payment» queries (~S).** Кластер `awaiting-payment-applicants` существует только из-за неоплаченного Stars-инвойса после approve. С B6 такого состояния нет. Нейтрализовать эндпоинты (empty/remove), убрать `organizerAwaitingPaymentCount`/`awaitingPaymentCount` из `applications-pending-count`.

### Frontend
- **B9. Organizer gate UI в member-list (~M) — reuse skladchina org-toggle UX.** Per-member: «Заморозить доступ»/«Разморозить» + «Взнос получен»/«Снять отметку», confirm-dialog (клон skladchina A-2). Badge: `Активен`/`Заморожен`/`Взнос не получен`. Мутации `freezeMember`/`unfreezeMember`/`markDuesPaid`/`unmarkDues` в `queries/members.ts`+`api/membership.ts`; invalidate member-list. Файлы: `pages/OrganizerClubManage.tsx` (или members-tab), `api/membership.ts`, `queries/members.ts`, `types/api.ts`.
- **B10. Убрать Stars pay-to-join из join UX (~M).** `ClubPage.tsx`: снять `isPendingPayment`/«Ожидаем оплату — N Stars»/«Счёт отправлен в Telegram»/`completeFreeMembership`. Платный join → «Вступить — доступ откроет организатор после взноса». Drop `isPendingPayment`/`PendingPaymentDto`. Также `MyClubsPage.tsx`/`InvitePage.tsx`. Тесты `frontend/src/test/pages/*`.
- **B11. Cleanup finances-панели фронта (~S).** Убрать/relabel 20%-fee display (`platformFeePct`, `platformFee`, `organizerRevenue`). Файлы: `pages/OrganizerClubManage.tsx`, `api/clubs.ts`, `types/api.ts`.

### Effort & risk
- **Total:** ~2 BE-дня + ~1 FE-день (8 BE + 3 FE). Ядро — B4+B5+B6; остальное — механический decommission.
- **Highest risk — B5** (`MembershipAccess.hasAccess` — единый cross-cutting предикат: feed, DM, skladchina participants, club-quality denominators). Одно неверное условие — silent grant/revoke по всему app. Митигация: `find_referencing_symbols` impact-pass + тест (`frozen` теряет feed/vote/DM, `paid→active` сохраняет).
- **Migration risk — B1** (enum `ADD VALUE` семантика) и **B2** (НЕ дропать transaction-колонки — frozen ledger; дроп — поздний cleanup как V33 для `member_count`).
- **Coordination risk:** B6 strands весь «awaiting-payment» applications-inbox → B8 в том же слайсе, иначе 500/stale zeros.
- **Вне scope (корректно):** who-pays/pricing, реальная service-fee acquirer интеграция, рекуррентная биллинг-стейт-машина §7 (сейчас вводится только `frozen`-state; subscription-hours pause отложен). `PaymentProvider`-seam (B3) — только стаб.
- **Про `grace_period`/`expired` + `SubscriptionScheduler`:** с retired Stars нет драйвера `subscription_expires_at`. Решить в B5/B6: оставить scheduler дремлющим (никакие строки не входят в time-based lifecycle) или отключить. Рекомендую оставить дремлющим этот слайс (YAGNI), отметить для §7 billing-machine.

### Ключевые файлы (абсолютные)
- `/Users/ivanvarlamov/Desktop/Clubs 2.0/backend/src/main/kotlin/com/clubs/payment/PaymentService.kt`
- `/Users/ivanvarlamov/Desktop/Clubs 2.0/backend/src/main/kotlin/com/clubs/membership/MembershipAccess.kt`
- `/Users/ivanvarlamov/Desktop/Clubs 2.0/backend/src/main/kotlin/com/clubs/membership/MembershipService.kt`
- `/Users/ivanvarlamov/Desktop/Clubs 2.0/backend/src/main/kotlin/com/clubs/application/ApplicationService.kt`
- `/Users/ivanvarlamov/Desktop/Clubs 2.0/backend/src/main/kotlin/com/clubs/bot/ClubsBot.kt`
- `/Users/ivanvarlamov/Desktop/Clubs 2.0/backend/src/main/kotlin/com/clubs/club/FinancesService.kt`
- `/Users/ivanvarlamov/Desktop/Clubs 2.0/backend/src/main/kotlin/com/clubs/skladchina/SkladchinaPaymentService.kt` (паттерн org-toggle для клона)
- `/Users/ivanvarlamov/Desktop/Clubs 2.0/backend/src/main/resources/db/migration/V8__create_transactions.sql`
- `/Users/ivanvarlamov/Desktop/Clubs 2.0/frontend/src/pages/ClubPage.tsx`
- `/Users/ivanvarlamov/Desktop/Clubs 2.0/frontend/src/api/membership.ts`

---

## 8. Как вести сессию

1. **Открыть** `docs/design/payment-monetization-v2.md` (decision record) — это контекст. Serena-онбординг НЕ нужен (чистая планировка, без code-navigation).
2. **Прогнать `AskUserQuestion`** на форках в порядке **1→2→3→4** (раздел 4): Решение 1 гейтит модель данных → 2 и 3 независимы → 4 в основном подтверждение + единственная развилка 4d (Pause vs Lapsed). По одному решению за раз, опции предзаготовлены, с рекомендацией.
3. **Analyst пишет залоченную спеку** в **`docs/modules/payment-v2.md`** (НЕ перезаписывать `payment.md` — он документирует v1 as-built). Записать: who-pays, plan-структуру + ценовой инвариант, триггер, 4-state машину + freeze-hours + aggregate flag.
4. **Флипнуть маркеры** `§6.3 / §7 / §11.2` в дизайн-доке ОТКРЫТО → РЕШЕНО.
5. **Записать отложенные** как явные зависимости, указывающие на legal-блок (раздел 5): точные цены, acquirer, оферта, юрлицо, НДС-пересчёт.
6. **Потом (отдельные сессии, в порядке):**
   - **Decoupling-слайс** (раздел 7) — может стартовать параллельно, независим от who-pays.
   - **Сервисный сбор / биллинг** (стейт-машина §7, `PaymentProvider`-acquirer) — **gated на (а) юридику и (б) доказанную активность** бесплатного/P2P цикла (§10). Не строить раньше.
7. **Запреты сессии:** никакого кода, миграций, флипа пэйвола. §10 держится — «не брать деньги рано».

---

## 9. Ссылки

- **`docs/design/payment-monetization-v2.md`** — decision record (источник; §6.3/§7/§11 — форки, которые лочит эта сессия; §10 — стратегическая рамка).
- **`docs/modules/payment.md`** — v1 as-built (Stars-путь; **НЕ перезаписывать**, новая спека идёт в `payment-v2.md`).
- **`docs/modules/skladchina.md`** — механика P2P-out (org-toggle, honor-system markPaid — паттерн для декаплинг-гейта B4/B9).
- **`docs/modules/reputation-v2.md`** — retention-движок, на котором держится §10 «доказать цикл первым»; монетизация к нему пристёгивается, не подменяет.