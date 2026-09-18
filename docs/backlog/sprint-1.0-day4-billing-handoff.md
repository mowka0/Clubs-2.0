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
| `subscription/StubCheckoutController.kt` | страница «оплаты» стаба с выбором исхода (карта · СБП · отказ), только при `billing.provider=stub` |
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

## 2a. ⟳ 2026-09-15: бесплатный период вместо бесплатной встречи

Модель сменилась до staging-прогона (решение PO, обоснование —
`monetization-v3-research-2026-09.md` § 8.2): первая созданная встреча запускает бесплатный период
чата **15 дней** (`BILLING_TRIAL_DAYS`), дальше подписка. DM о конце — за **7 дней** и за **1 день**.

| Что | Где |
|---|---|
| `V99__chat_trial.sql` | `chat_free_meeting` → `chat_trial`: `used_at → started_at`, `event_id → first_event_id`, `released_at` удалена, добавлена `reminder_days_left` (дедуп DM, как V77) |
| `ChatTrialRepository` / `JooqChatTrialRepository` | `startOrGet` (идемпотентная вставка + `justStarted` для воронки), `findStartedAt`, `findTrialsEndingBefore`, `markReminded`, `migrateChatId` |
| `BillingGate` | стена только после `started_at + trial-days`; первая встреча проходит всегда; `releaseFreeMeeting` удалён вместе с вызовами в `cancelEvent`/`cancelBySystem` |
| `BillingLifecycleService.remindEndingTrials` | новый проход тика: DM за 7 и 1 день по чатам без подписки |
| `BillingNotifier.trialEndingSoon` | два текста; говорят, что бот продолжит делать за эти деньги |
| Статус и фронт | состояния `TRIAL_NOT_STARTED` / `TRIAL` / `TRIAL_ENDED`, поля `trialUntil` и `trialDays`, полоска «Бесплатно до …», `PaywallReason.TRIAL_ENDED`, `CHAT_PRICE_LINE` |

Воронка: шаг `free_meeting_used` заменён на `trial_started`.

**2026-09-16, найдено PO на прогоне:** кик бота из чата не удаляет привязку (так и задумано), но
биллинг смотрел только на наличие строки — клуб с выгнанным ботом получал стену и автосписание за
чат, где бота нет. Починено: `botStatus.isInChat` в гейте, статусе (`BOT_REMOVED`, полоска «подписка
на паузе»), календаре, выборке напоминаний и `charge-now`. Сообщения бота при кике остаются —
ограничение Telegram.

## 2b. 2026-09-16: домен `clubsapp.ru` и публичная страница

Для модерации Robokassa нужен сайт с описанием услуги, ценой, продавцом и офертой; sslip.io-адрес
с пустым Mini App снаружи её бы не прошёл. Сделано: домен куплен (Timeweb, A-записи на VPS),
`TELEGRAM_WEBAPP_BASE_URL` по умолчанию `https://clubsapp.ru`, лендинг `/about` + корень вне
Telegram (`entry.ts`). **Мержить в master только когда `https://clubsapp.ru` уже открывается** —
иначе WebAppInfo-кнопки бота на проде уведут на мёртвый адрес.

Осталось руками (PO): реквизиты в `pages/landingContent.ts` (ФИО, ИНН, e-mail), домен в Coolify
у прод-приложения (frontend-сервис → Domains → `https://clubsapp.ru,https://www.clubsapp.ru`,
redeploy), `TELEGRAM_WEBAPP_BASE_URL` в Coolify (прод — clubsapp.ru, staging — sslip staging),
URL Mini App в BotFather → `https://clubsapp.ru`, `clubsapp.ru` в белый список Referer обоих
ключей Яндекс.Карт, адреса в кабинете Robokassa: ResultURL
`https://clubsapp.ru/api/billing/robokassa/result`, SuccessURL `https://clubsapp.ru/pay/return`,
FailURL `https://clubsapp.ru/pay/fail` (метод GET у обоих возвратов).

## 2c. Состояние на 2026-09-16 (конец дня): прогон отложен до Robokassa

**Решение PO:** staging-прогон на заглушке — не полноценный тест, ждём подключения Robokassa.
Ручной прогон по `platform-billing-testplan.md` **не проведён**; вручную проверены только первые
шаги (TC-01…TC-05 частично: бесплатный период стартует, старый клуб получает полный срок).
Всё остальное держится на автотестах: бэкенд 1162, фронт 732, оба зелёные.

**Сделано за день (ветка `feature/sprint-1.0-day4-payment-model`, запушена):**

| Коммит | Что |
|---|---|
| `e1ab588` | мерж master (сборы v3, одноэтапная открытая встреча), миграции → V97/V98, долги ревью 1–4 |
| `66400675` | понятная причина падения без `BILLING_PROVIDER` (`BillingProviderCheck`) |
| `717e55ad` | страница заглушки с выбором исхода: карта · СБП · отказ |
| `82aaeaf4` | **модель сменена: бесплатный период 15 дней вместо одной встречи (V99)** |
| `d12cca14` `55efeab5` | публичная страница `/about` + корень вне Telegram, домен `clubsapp.ru`, реквизиты продавца |
| `7f040186` | служебное «списать сейчас» (§ 11) |
| `2c0c1d08` | **пауза биллинга, когда бота выгнали из чата** + тест-план |
| `67f6006c` `928472eb` | полоска на странице клуба (владелец + со-орги, без ползунка), `trialPassedLabel`, `useClubPageUnderneath` |

**Staging сейчас:** `BILLING_PROVIDER=stub`, `BILLING_TRIAL_DAYS=1`, `SUBSCRIPTION_PERIOD_DAYS=1`,
`SUBSCRIPTION_LIFECYCLE_CRON=0 */5 * * * *`, `BILLING_PENDING_TIMEOUT_HOURS=0`,
`BILLING_RECIPIENT_NAME` задан. **`BILLING_RECONCILE_CRON` остался часовым** — для TC-14
поставить `0 */5 * * * *`. У клубов «Корги 🫶» (старт сдвинут на 14.09, период истёк) и
«тест_оплаты_полный» строки `chat_trial` уже есть — перед новым прогоном их либо удалить, либо
учитывать состояние.

**Когда вернёмся — порядок:**
1. PO: Robokassa — договор, тестовые пароли, SHA256 в кабинете, **заявка на рекуррент**; домен
   `clubsapp.ru` в Coolify у прод-приложения (DNS уже резолвится) + `TELEGRAM_WEBAPP_BASE_URL`;
   `clubsapp.ru` в Referer обоих ключей Яндекс.Карт.
2. Прогон по `platform-billing-testplan.md`: блоки А–Г на заглушке + материнский платёж
   Robokassa с `ROBOKASSA_TEST_MODE=true`.
3. «Готово, запушь» → PR → master → прод. **Мерж только когда `https://clubsapp.ru` открывается.**
4. На проде: боевые `ROBOKASSA_*`, `BILLING_PROVIDER=robokassa`, первый живой платёж на чате PO,
   затем `charge-now` под флагом (§ 11) — проверка первого автосписания, после чего флаг выключить.
5. Robokassa на модерацию отправлять **после** мержа: модератор должен увидеть лендинг, а не
   пустой Mini App.

## 2d. Состояние на 2026-09-18 (конец сессии): биллинг В ПРОДЕ, ждём Robokassa, сайт режет РКН

**Читать первым.** § 2c ниже устарел в части «прогон отложен, ветка не влита»: 16–17.09 всё
влито в master и выкачено на прод осознанным решением PO — лендинг нужен для модерации
Robokassa, а без её аппрува денежный путь не проверить (тупик разорван выкаткой).

### Что в проде (master `c61c0ca5`)

| PR | Что |
|---|---|
| #168 `b6b4b418` | весь биллинг: V97–V99, гейт, провайдеры, календарь, DM, шит/полоска, лендинг, `charge-now`, пауза при кике бота |
| #169 `da2c8e69` | `/privacy` (политика 152-ФЗ), почта поддержки `clubs.techsupport@gmail.com` |
| #170 `24a9e3f9` | фирменный логотип (`public/brand/logo-wordmark.jpg` шапка, `logo-mark.jpg` фавикон, og:image) |
| #171 `c61c0ca5` | публичные страницы всегда в тёмной теме, логотип на `/pay/return`, заголовок «Clubs — бот…» |

Схема прода — **99**. Подписок в проде нет; у 5 клубов с чатами строк `chat_trial` нет → каждый
получит 15 дней с первой новой встречи (амнистия, PO). Домен **`clubsapp.ru`** (Timeweb, A-записи
на 77.42.23.177), Let's Encrypt через Coolify, **старый `77-42-23-177.sslip.io` оставлен в Domains
намеренно** — уже разосланные кнопки в чатах ведут на него. Mini App из Telegram открывается
приложением (подтвердил PO 17.09). BotFather **не переключён** на clubsapp.ru — старый URL
работает, менять после проверок, последним шагом.

**Env прода (выставлено PO):** `BILLING_PROVIDER=robokassa`, `BILLING_RECIPIENT_NAME=Варламов Иван
Михайлович`, `ROBOKASSA_TEST_MODE=true`, `TELEGRAM_WEBAPP_BASE_URL=https://clubsapp.ru`,
`YANDEX_GEOCODER_REFERER=https://clubsapp.ru`; **`ROBOKASSA_MERCHANT_LOGIN/PASSWORD_1/PASSWORD_2` —
ЗАГЛУШКИ** (`clubs` / `placeholder-until-robokassa`): адаптер требует непустые значения на старте,
прод с пустыми не поднялся и Coolify снёс стек (16.09, восстановлено). До чекаута в ближайшие
15 дней никто не дойдёт; менять на настоящие пароли из кабинета Robokassa + Deploy. Не проверено
PO: `clubsapp.ru` в Referer обоих ключей Яндекс.Карт — без этого карта в пикере места молча ляжет.

### Robokassa — шаги PO

1. Магазин с адресом `https://clubsapp.ru` (главная, не подстраница). Технастройки:
   Result `https://clubsapp.ru/api/billing/robokassa/result` POST, Success `/pay/return` GET,
   Fail `/pay/fail` GET, хеш **SHA256**. Отправить на модерацию; **отдельно заявка на рекуррент**.
2. Спросить поддержку: кто плательщик в чеке НПД — покупатель-физлицо (4 %) или Robokassa (6 %);
   куда и как часто выплаты самозанятому, есть ли комиссия за вывод. Комиссии подтверждены:
   карты 3,4 %, СБП 3 %, абонплата 0.
3. Тестовые пароли → в Coolify вместо заглушек → Deploy → прогон `platform-billing-testplan.md`
   (21 кейс; на staging всё ещё стаб + `BILLING_TRIAL_DAYS=1`, `SUBSCRIPTION_PERIOD_DAYS=1`, крон
   5 мин, `BILLING_PENDING_TIMEOUT_HOURS=0`; `BILLING_RECONCILE_CRON` там часовой — для автосписания
   ставить `0 */5 * * * *`; у «Корги 🫶» и «тест_оплаты_полный» строки `chat_trial` уже есть —
   чистить перед прогоном).
4. Аппрув → боевые пароли, `ROBOKASSA_TEST_MODE=false`, первый живой платёж на чате PO, потом
   `charge-now` под флагом (`BILLING_MANUAL_CHARGE_ENABLED=true` + `PLATFORM_ADMIN_TELEGRAM_IDS`)
   один раз, флаг выключить.

### Банк для выплат (исследование 17.09, сайты банков с машины недоступны — данные из обзоров)

Расчётный счёт самозанятому не нужен — карта физлица. Зарплата PO на Сбере → **не на зарплатную
карту** (115-ФЗ, учёт, налог): отдельная СберКарта + «Своё дело», либо Альфа (0 ₽ без условий,
снятие до 1 млн/мес) как второй банк. Т-Банк — 0 ₽ только при остатке от 50 тыс., иначе 99 ₽/мес.
Комиссию за входящие от юрлиц не называет ни один источник — платит отправитель.
**Ловушка двойного чека:** чеки пробивает Robokassa («Робочеки СМЗ»), банковское автоформирование
чеков при поступлении **выключить**.

### ⚠️ Открытая проблема: сайт не открывается из РФ без VPN

PO: `clubsapp.ru` без VPN не открывается. Причина — не мы: РКН режет подсети Hetzner на домашних и
мобильных провайдерах (DPI рвёт HTTPS; с 11.2024, летом 2025 у МТС/Yota полная блокировка).
check-host 18.09: с российских ДЦ-узлов сайт отдаёт 200, TCP 443 за 19 мс — блок на «последней
миле». Следствия: пользователи Mini App не страдают (Telegram у них через VPN — слова PO);
**модератор Robokassa открывает сайт обычным браузером — может не открыться → отказ**;
ResultURL идёт из ДЦ — по данным check-host доходит.

**Диагностика 2026-09-18 (PO, без VPN): не открываются ни `77-42-23-177.sslip.io`, ни
`http://77.42.23.177`** → режут подсеть, DNS ни при чём, фикс нужен. Фикс подготовлен той же
сессией (ветка `devops/ru-reverse-proxy`): `infra/ru-proxy/` — nginx `stream` (TCP passthrough
80/443 + PROXY protocol), `install.sh`, README-runbook с порядком шагов **Traefik
(`proxyProtocol.trustedIPs` в Coolify) → установка на RU VPS → `curl --resolve` до DNS →
CAA → A-записи → BotFather**. Reviewer и Security пройдены (блокеров нет). Бэкенд не меняется: `ClientIpResolver` прокси не видит
(`infrastructure.md` § «Российский reverse proxy перед Hetzner»). Лог фронта переведён на формат с
`X-Forwarded-For` — по нему проверяется, что до бэкенда доходит IP клиента. **Включено 18.09:** RU VPS
`clubs-ru-proxy` 147.45.189.189 (Timeweb SPB-3), Traefik trustedIPs, A-записи переключены, без VPN
из РФ сайт открывается. **Но с VPN, чьи диапазоны фильтрует российский аплинк, через прокси сайт
недоступен** (SYN-ACK не возвращается) — Mini App переведён на `app.clubsapp.ru` (A-запись напрямую
на Hetzner, минуя прокси; `TELEGRAM_WEBAPP_BASE_URL` тоже), GeoDNS отложен до появления аудитории
без VPN (`infrastructure.md` § «Российский reverse proxy перед Hetzner»). CAA у Timeweb нет.

**Форма фикса — российский reverse proxy перед Hetzner, НЕ переезд стека в РФ**: бэкенд обязан
ходить в api.telegram.org, из российского ДЦ это может быть заблокировано. Ловушка про
`ClientIpResolver` снята выбором L4 + PROXY protocol: Traefik сам видит IP клиента, доверенные
прокси в бэкенде не меняются; опасен только `proxyProtocol.insecure` (подделка IP) — не ставить.

### Долги, не блокирующие

Хэндофф § 6 п. 5–9 (напоминание «за день» по календарным дням МСК; `reconcilePending` не
проверяет сумму; гонка двух первых оплат → 500 вместо 409; `findLatestByClub` без индекса;
дубли форматтеров, `isCard` по подстроке, `CHAT_PRICE_LINE` хардкодит 199 ₽ и 15 дней в бандле).
Плюс: режим `BILLING_PROVIDER=none` («биллинг выключен», чтобы прод не зависел от платёжных
ключей) — обещан, не сделан; юрист не смотрел оферту, политику и текст про возврат.

## 3. Решения PO, которые нельзя переоткрывать

Из `monetization-v3-research-2026-09.md` § 8 (деньги) и правок по мокапам 2026-09-07 (тексты и UI):

1. ⟳ **Заменено 2026-09-15** (см. § 2a): бесплатный период чата 15 дней от первой встречи; отмена
   встречи срок не возвращает.
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

**Выставить в Coolify** (без `BILLING_PROVIDER` приложение не стартует — так задумано после
ревью; расчёт на падение при разборе compose не оправдался: **Coolify игнорирует `${VAR:?…}`** и
подставляет пустую строку, проверено на staging 2026-09-15, поэтому обязательность переменной
держит `payment/BillingProviderCheck` на старте):

| Переменная | Staging | Прод |
|---|---|---|
| `BILLING_PROVIDER` | `stub` | `robokassa` |
| `BILLING_RECIPIENT_NAME` | ФИО самозанятого целиком | то же |
| `ROBOKASSA_MERCHANT_LOGIN` / `_PASSWORD_1` / `_PASSWORD_2` | тестовые | боевые |
| `ROBOKASSA_TEST_MODE` | `true` | `false` |
| `SUBSCRIPTION_PERIOD_DAYS` | `1` на время прогона | `30` |
| `SUBSCRIPTION_LIFECYCLE_CRON` | частый на время прогона | `0 30 9 * * *` |
| `BILLING_TRIAL_DAYS` | `1` на время прогона | `15` |
| `BILLING_MANUAL_CHARGE_ENABLED` / `PLATFORM_ADMIN_TELEGRAM_IDS` | не нужны | `true` + Telegram id PO **только на время** проверки первого автосписания (§ 11), потом `false` |

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

> «продолжи: биллинг за клуб. Хэндофф — `docs/backlog/sprint-1.0-day4-billing-handoff.md`,
> читать **§ 2d** (состояние на 2026-09-18): всё в проде на `clubsapp.ru`, ждём Robokassa
> (пароли-заглушки в Coolify); доступ из РФ: диагностика подтвердила блок подсети Hetzner,
> runbook прокси `infra/ru-proxy/README.md` ждёт VPS от PO.»

Если PO принёс пароли Robokassa — п. 3 раздела «Robokassa — шаги PO». Если принёс IP
российского VPS — `infra/ru-proxy/README.md`, шаги 1–4, затем обновить статус в
`infrastructure.md` § «Российский reverse proxy перед Hetzner» и здесь. Тест-план — `docs/modules/platform-billing-testplan.md`.
