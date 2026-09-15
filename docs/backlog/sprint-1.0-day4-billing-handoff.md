# Хэндофф Дня 4 — биллинг платформы за клуб

**Дата:** 2026-09-07, обновлено 2026-09-15. **Ветка:** `feature/sprint-1.0-day4-payment-model`.
**Состояние:** код написан целиком, ревью пройдено; 2026-09-15 ветка подтянула master (сборы v3,
одноэтапная открытая встреча), миграции перенумерованы в **V97/V98**, закрыты долги 1–4 ниже.
**Следующий шаг:** push → staging → прогон по `docs/modules/platform-billing.md` § 10.

Спека — `docs/modules/platform-billing.md` (там же зафиксированы отклонения реализации от эскиза).
Решения PO по деньгам — `docs/design/monetization-v3-research-2026-09.md` § 8.
Мокапы экранов — `docs/design/platform-billing/mockups/01-billing-screens.html` (открыть в браузере,
кнопка сверху переключает тему).

---

## 1. Коммиты ветки

| SHA | Что |
|---|---|
| `cad1593` | docs: исследование финмодели v3 + спека `platform-billing.md`, `payment-v2.md` → superseded |
| `cce7eea` | feat: миграции V97/V98, `BillingGate`, снос движка ёмкости платных клубов |
| `d5250eb` | docs: мокапы экранов (шит, полоска, `/pay/return`, тексты DM) |
| `3c2202a` | feat: контракт `PaymentProvider`, Robokassa, `BillingService`, ResultURL, календарь, DM |
| `787d08a` | feat: фронт целиком + правки PO по мокапам + docs alignment |
| `b4f34dc` | fix: 8 находок code review (деньги и fail-open стаба) |
| `8e861a1` | chore: V87/V88 → V94/V95 после мержа сборов v3 |
| _(2026-09-15)_ | merge master + V94/V95 → **V97/V98** (в master приехала V96) + долги 1–4 |

Диф к master: 124 файла, +8 063 / −1 356.

**Проверки после мержа master 2026-09-15:** бэкенд `./gradlew test` — **1146 тестов, 0 падений**
(интеграционные на Testcontainers прогоняют полную цепочку Flyway вместе с V97/V98); фронтенд
`npm test` — **723 теста, 83 файла, 0 падений**, чистые `tsc --noEmit` и `npm run build`.
(На момент исходного хэндоффа 2026-09-07 было 1160 / 686: часть тестов ушла вместе с
двухэтапкой открытой встречи в master.)

---

## 2. Что построено

### Бэкенд

| Файл | Роль |
|---|---|
| `V97__platform_billing_chat.sql` | план `CHAT`, поля автосписания на подписке, индекс «одна живая подписка на клуб», таблицы `platform_payment`, `chat_free_meeting`, `funnel_event` |
| `V98__platform_billing_pricing.sql` | цена 19 900 коп. отдельной миграцией (значение enum нельзя использовать в той же транзакции) |
| `subscription/BillingGate.kt` | **единственная точка биллинга** — в `EventService.createEvent` после вставки события; `releaseFreeMeeting` в `cancelEvent` и `cancelBySystem` |
| `subscription/BillingService.kt` | статус, чекаут, `onResult`, ползунок; подписка рождается здесь с первым успешным платежом |
| `subscription/BillingLifecycleService.kt` | ежедневный календарь (напоминания, списания, `PAST_DUE`, `ENDED`) + почасовой опрос счетов |
| `subscription/BillingNotifier.kt` | шесть DM владельцу; в чат — ничего |
| `subscription/BillingController.kt` | `/api/clubs/{id}/billing[/checkout|/autopay]`, ResultURL |
| `subscription/StubCheckoutController.kt` | страница «оплаты» стаба, только при `billing.provider=stub` |
| `payment/PaymentProvider.kt` | переписанный сеам: чекаут, дочернее списание, разбор ResultURL, опрос состояния |
| `payment/RobokassaPaymentProvider.kt` + `RobokassaSignature.kt` | адаптер по документации: SHA256, `Recurring=true`, `PreviousInvoiceID` вне подписи, allowlist IP, `OpStateExt` |
| `common/security/ClientIpResolver.kt` | общий разбор `X-Forwarded-For` для rate limit и allowlist ResultURL |
| `chatlink/ChatIdMigratedEvent.kt` + `ChatIdMigratedListener.kt` | переезд группы переносит признак бесплатной встречи |

**Удалено:** `SubscriptionService`, `SubscriptionController`, `SubscriptionDto`, `SubscriptionPlanPolicy`,
`PricingInvariant`, `ServiceSubscriptionLifecycleService`, `ClubRepository.countPaidByOwnerId`,
гейты ёмкости в `ClubService` и их тесты. Мина `docs/backlog/subscription-endpoint-free-plan-bypass.md`
закрыта по построению.

### Фронтенд

`api/billing.ts`, `queries/billing.ts`, `components/billing/{BillingSheet,BillingStatusStrip,offerText}`,
`pages/PayReturnPage.tsx` (`/pay/return`, `/pay/fail` — **вне Layout**, без JWT и без вызовов API),
`DeepLinkHandler` понимает `billing_<uuid>`, `EventForm` открывает шит на 402, полоска на
`OrganizerClubManage` под шапкой (только владельцу), строка цены на экране «Выбрать чат» и в мастере.
**Удалено:** `components/subscription/*`, `api/subscription.ts`, `queries/subscription.ts`,
`SubscriptionCard` из профиля, `PaywallModal` из `CreateClubModal`, ключи `queryKeys.subscription`.

---

## 3. Решения PO, которые нельзя переоткрывать

Из `monetization-v3-research-2026-09.md` § 8 (деньги) и правок по мокапам 2026-09-07 (тексты и UI):

1. Первая встреча чата бесплатна, счёт — при создании второй. Отменённая до старта возвращается.
2. **199 ₽/мес для всех**, круга ранних нет. Цена живёт в `subscription_pricing`.
3. **По тексту везде «за клуб»**, хотя единица счёта в коде — чат. Не «за чат».
4. Списание — **в день окончания** оплаченного периода. Отдельного DM «завтра спишем» нет.
5. ФИО самозанятого — целиком, из `billing.recipient-name`.
6. Оферта — **текстом внутри шита** (`components/billing/offerText.ts`), не ссылкой.
7. В состоянии «проверяем оплату» кнопки «подожду в личке» нет — закрыть можно только шапкой.
8. Ползунок автопродления по умолчанию включён; выключение не отменяет текущий период.
9. Грейс 7 дней, потом «новое нельзя, начатое доживает». Гейт только на создании встречи.
10. Текст DM о продлении — мотивирующий, про офлайн-встречи (формулировка PO, менять только с ним).

---

## 4. Отклонения реализации от эскиза спеки (зафиксированы в спеке)

1. **Подписка рождается только с первой оплатой.** Эскиз предлагал строку `PAST_DUE` с
   `current_period_end = now` при выставлении счёта — тогда правило грейса дало бы неделю встреч
   без оплаты. Поэтому у `platform_payment` обязателен `club_id`, а `subscription_id` nullable.
2. `platform_payment.autopay_requested` — положение ползунка на момент чекаута; переносится на
   подписку в `onResult` (в эскизе не было, `autopay-default` из конфига не понадобился).
3. Легаси-строки платформенного плана ёмкости V97 переводит в `ENDED`;
   `chk_service_subscription_org_club` добавлен как `NOT VALID`.
4. `V97` содержит `club_id` и индекс `idx_platform_payment_club` вместо эскизного индекса по подписке.

---

## 5. Code review (medium, 8 углов + верификация) — 8 подтверждённых багов, все починены в `b4f34dc`

| # | Что было | Как починено |
|---|---|---|
| 1 | Стаб включался по `matchIfMissing` и дефолту `stub` в prod-compose: прод без переменной раздавал подписки по угаданному `InvId` без авторизации (две точки — `/api/billing/stub/pay` и ResultURL) | `matchIfMissing` убран у обоих стаб-бинов, `BILLING_PROVIDER` в prod-compose объявлен как `${BILLING_PROVIDER:?…}`, у стаба `trustsResultSource=false` и `parseResultNotification` → 403 |
| 2 | Тик шедулера делал HTTP-списания внутри одной транзакции: откат терял записи об уже отправленных списаниях → двойной дебет | С `runDaily` и `reconcilePending` снята общая `@Transactional`, каждая подписка и счёт в своём `try/catch` с ERROR-логом |
| 3 | Оплата позже 24 ч терялась: `markSucceeded` требовал `PENDING`, счёт уже был закрыт по таймауту | `markSucceeded` принимает любой статус, кроме `SUCCEEDED` |
| 4 | Дочернее списание продлевало `ENDED`-подписку, не оживляя её: оплатил, прочитал «Продлено», получил 402 | `settleRecurring` разрешает переход `PAST_DUE, ENDED → ACTIVE` |
| 5 | `onResult` помечал счёт оплаченным до проверки клуба: у удалённого клуба платёж висел `SUCCEEDED` без подписки и без сигнала | Клуб ищется первым, при отсутствии — `ERROR` «нужен возврат вручную», счёт всё равно фиксируется ради аудита |
| 6 | Зависший дочерний счёт (состояние 5/80 у провайдера) навсегда блокировал ретраи и DM об отказе | Зависший счёт любого вида закрывается, дочерний дополнительно даёт `PAST_DUE` + DM |
| 7 | Повторный чекаут в окне 30 мин игнорировал выключенный ползунок → списание вопреки выбору | `updateAutopayRequested` на переиспользуемом счёте |
| 8 | Шит показывал «Оплачено» при несостоявшемся продлении (`pendingCheckout` считался по окну 30 мин) | `hasPendingMother` — по счетам любого возраста |

---

## 6. Известные пробелы — НЕ починено

Найдено ревью, сознательно оставлено за рамками (порог отчёта или не блокирует staging).
Первые четыре стоит закрыть до боевого включения Robokassa.

1. ✅ **Закрыто 2026-09-15.** Стаб-редирект и `bot` в адресе — одной правкой: параметр `bot`
   из адресов возврата убран совсем, `/pay/return` берёт имя бота из бандла
   (`VITE_TELEGRAM_BOT_USERNAME`, build arg из `TELEGRAM_BOT_USERNAME`), `club` принимается только
   как UUID. Кнопка возврата теперь есть и у стаба, и у Robokassa.
2. ✅ **Закрыто 2026-09-15.** `BillingStatusDto.canPay` (владелец ли смотрящий); шит показывает
   со-организатору «Оплачивает владелец клуба» вместо кнопки и ползунка — 403 он больше не ловит.
3. ✅ **Закрыто 2026-09-15** вместе с п. 1: имя бота из адреса не принимается вовсе.
4. ✅ **Закрыто 2026-09-15.** Недостающие восемь `COMMENT ON` дописаны прямо в `V97`: миграция
   нигде не применена (ветка не деплоилась), отдельная миграция была бы лишней сущностью.
5. **Напоминание «за день» уходит в день окончания**, если период кончается позже времени крона
   (по умолчанию 09:30 UTC): `daysLeft` считается по мгновениям, а не календарным дням МСК. За день
   до конца DM не приходит вовсе.
6. `reconcilePending` строит уведомление из суммы самого счёта, поэтому проверка суммы на этом пути
   не работает (на ResultURL работает).
7. Две одновременные первые оплаты одного клуба упираются в уникальный индекс → 500; провайдер
   ретраит и всё сходится, но старый `SubscriptionService` отдавал на это чистый 409.
8. `findLatestByClub` не покрыт индексом (частичный `uq_service_subscription_live_club` планировщику
   не подходит, так как запрос обязан видеть `ENDED`). Сейчас по строке на клуб — не больно, но
   запрос на горячем пути (каждая встреча, каждый опрос статуса).
9. Качество кода из ревью, не блокирует: форматтер даты и рублей дублирует `NotificationService`;
   пороги 3/1 дня дублируют `ExpiryReminderRules`; `BillingGateTest` дублирует билдеры
   `BillingTestFixtures`; форматтеры лежат в `api/billing.ts`, а не в `utils/formatters.ts`;
   восьмая копия разметки тумблера `rd-cl-tgl`; `HttpClient` дублирует гео-сервисы;
   `BillingService.mapper()` создаёт `@Component` руками; `BillingSheet.tsx` > 200 строк;
   `CheckoutDto.invId` и часть полей `PaywallInfo` не используются; `isCard` — эвристика по подстроке
   (`SberPay`/`Mir Pay` попадут в «не карта»); `CHAT_PRICE_LINE` хардкодит 199 ₽ в бандле, тогда как
   цена живёт в БД; `@Transactional` стоит на репозитории `JooqFunnelEventRepository.recordDetached`.

---

## 7. Перед деплоем на staging

**Выставить в Coolify** (иначе `docker compose` упадёт намеренно — так задумано после ревью):

| Переменная | Staging | Прод |
|---|---|---|
| `BILLING_PROVIDER` | `stub` | `robokassa` |
| `BILLING_RECIPIENT_NAME` | ФИО самозанятого целиком | то же |
| `ROBOKASSA_MERCHANT_LOGIN` / `_PASSWORD_1` / `_PASSWORD_2` | тестовые | боевые |
| `ROBOKASSA_TEST_MODE` | `true` | `false` |
| `SUBSCRIPTION_PERIOD_DAYS` | `1` на время прогона | `30` |
| `SUBSCRIPTION_LIFECYCLE_CRON` | частый на время прогона | `0 30 9 * * *` |

Остальные `BILLING_*` имеют рабочие дефолты в `application.yml` и продублированы в
`docker-compose.prod.yml` (правило CLAUDE.md о двух дефолтах соблюдено).

`TELEGRAM_BOT_USERNAME` — **одна** переменная на оба контейнера: из неё берут и бэкенд
(`telegram.bot-username`), и сборка фронта (build arg `VITE_TELEGRAM_BOT_USERNAME` для кнопок
возврата на `/pay/return`). На staging она уже должна стоять в `clubs_v2_test_bot`, на проде
дефолт `clubs_v2_bot`. Проверить перед прогоном: если на staging её нет, кнопка возврата уведёт
в прод-бота.

**Прогон** — критерии приёмки в `platform-billing.md` § 10 (11 пунктов): первая встреча бесплатно,
стена на второй, отмена возвращает бесплатную, переподключение чата платное, напоминания и грейс,
автосписание со стабом, СБП без автопродления, идемпотентность ResultURL, клуб без чата,
события воронки, зелёные тесты.

**Календарь PO до боевых денег** (не код): самозанятость в «Мой налог» → договор Robokassa на
самозанятого + «Робочеки СМЗ» → **заявка на рекуррент** (без неё `Recurring=true` молча не сработает)
→ SHA256 в настройках магазина → оферта у юриста → env в Coolify.

---

## 8. Грабли сессии

- **Локальная dev-БД отстаёт** (была на V76). jOOQ-кодген гонять по рецепту
  `docs/backlog/branch-handoff-de-stars-member-admin.md` § 7: временный Postgres на 5433, миграции
  через `psql` в порядке `sort -V`, затем `DB_URL=… ./gradlew generateJooq --rerun-tasks`.
  Проверять статус каждой миграции: `psql` без `ON_ERROR_STOP` молча съедает ошибку.
- **`ALTER TYPE … ADD VALUE`** ломает exhaustive `when` по `SubscriptionPlan` — политику ёмкости
  пришлось снести сразу, а не следующим шагом.
- **Serena Kotlin LSP падает** при инициализации — работать grep/Read.
- **Фронт-флака**: отдельные файлы падают на сборке модулей (`path-to-regexp`), изолированно
  проходят. Проверять `npx vitest run <file>` перед тем, как считать падение своим.
- **JUnit создаёт экземпляр на каждый тест** — счётчики id в интеграционных тестах держать в
  `companion object`, иначе второй тест переиспользует те же значения.
- Robokassa: **тестового режима для рекуррента нет**. Дочернее списание проверяется на staging
  только стабом (`BILLING_STUB_SETTLE_SECONDS`), боевое — на проде через служебный триггер (§ 11 спеки).

---

## 9. Стартовая команда новой сессии

> «продолжи: День 4 — биллинг за клуб, ветка `feature/sprint-1.0-day4-payment-model`. Код готов и
> отревьюен, хэндофф — `docs/backlog/sprint-1.0-day4-billing-handoff.md`. Нужен push, staging и
> прогон по `docs/modules/platform-billing.md` § 10.»

Если сначала хочется закрыть долги — п. 1–4 раздела «Известные пробелы» (стаб-редирект,
со-организатор, валидация `bot`, миграция с `COMMENT ON`) — это трек S/M, полдня.
