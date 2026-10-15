# Журнал продуктовых событий (основа аналитики, W4)

> Спека 2026-10-15 по хэндоффу `docs/backlog/session-handoff-2026-10-15.md` § 3 W4. Решения PO
> 2026-10-14: журнал нужен с первого дня («что делали в первый месяц» потом не восстановить),
> данные только у нас (ПДн участников), третьих сервисов аналитики нет. Grafana (W5) и вопросы
> с SQL (W6) строятся поверх этой таблицы.
>
> Код: `backend/.../analytics/` (таблица, виды событий, `POST /api/track`), точки записи в
> сервисах фич, `frontend/src/analytics/` (очередь и отправка пачками). Воронка привлечения и
> недельный отчёт — по-прежнему `funnel.md`; здесь только то, что добавилось.
>
> **Статус:** список событий и решения § 9 утверждены PO 2026-10-15 (все три — по рекомендации); в разработке.

## 1. Цель

Отвечать на вопросы про активацию, удержание, adoption и drop-off цифрами, а не ощущениями:
где новичок отваливается, доходит ли клуб до первой встречи, кто возвращается через неделю,
какими функциями вообще пользуются. Сегодня в базе есть только факты бизнес-сущностей (клубы,
встречи, оплаты) и восемь шагов воронки — по ним не видно, что человек открывал, куда нажимал
и на чём остановился.

Принцип: **событие ради вопроса, а не «логируем всё»**. Каждое событие в § 3 привязано к вопросу
из § 7; событие без вопроса не заводится.

## 2. Хранение

### 2.1. Таблица `product_event` (миграция V105, переименование `funnel_event`)

`funnel_event` переименовывается в `product_event` и становится общим журналом. Переименование
сейчас, а не «потом»: таблица маленькая (на проде 26 строк), а Grafana и SQL из W6 будут
написаны против имени, которое останется на годы. Имя «воронка» для таблицы, где 90 % строк —
открытия экранов, врало бы.

```sql
ALTER TABLE funnel_event RENAME TO product_event;
ALTER INDEX idx_funnel_event_kind_created RENAME TO idx_product_event_kind_created;
ALTER TABLE product_event RENAME CONSTRAINT funnel_event_pkey TO product_event_pkey;
-- FK на users пересоздаётся с ON DELETE SET NULL: журнал переживает удаление пользователя, автор обезличивается
ALTER TABLE product_event DROP CONSTRAINT funnel_event_user_id_fkey;
ALTER TABLE product_event ADD CONSTRAINT product_event_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE product_event
    ADD COLUMN source VARCHAR(8),
    ADD COLUMN props  JSONB;
UPDATE product_event SET source = CASE
    WHEN kind IN ('bot_started', 'chat_connected', 'chat_disconnected') THEN 'bot'
    ELSE 'server' END;
ALTER TABLE product_event ALTER COLUMN source SET NOT NULL;
CREATE INDEX idx_product_event_user_created ON product_event (user_id, created_at);
-- + COMMENT ON таблицы и всех колонок (русский), в т.ч. обновлённый перечень kind
```

| Колонка | Тип | Что |
|---|---|---|
| `id` | UUID PK | как было |
| `user_id` | UUID FK users `ON DELETE SET NULL`, NULL | кто; NULL у `bot_started` до первого входа, у событий шедулера без актора и у событий удалённого пользователя (журнал переживает удаление, автор обезличивается — иначе удалить человека из `users` нельзя, пока за ним есть хоть одно событие) |
| `telegram_id` | BIGINT, NULL | как было (V100): шаги привлечения до появления `users` |
| `club_id` | UUID, NULL, без FK | клуб, к которому относится событие; у экранов без клуба — NULL |
| `kind` | VARCHAR(48) | вид события строкой (§ 3); без enum в БД — набор меняется чаще схемы |
| `source` | VARCHAR(8) NOT NULL | `app` — Mini App через `POST /api/track`; `bot` — апдейт Telegram-бота; `server` — бизнес-логика бэкенда (биллинг, сервисы, шедулеры) |
| `campaign` | VARCHAR(64), NULL | как было: метка `/start ad_<slug>`, только у `bot_started` |
| `props` | JSONB, NULL | детали события по § 3; NULL, если деталей нет. Плоский объект: ≤ 10 ключей, значения — строка ≤ 100 знаков, число или boolean |
| `created_at` | TIMESTAMPTZ | время записи **сервером** (клиентское время не принимается — пачка уходит с задержкой ≤ 3 с, точность достаточная, а сдвиг часов на телефонах и подделка исключаются) |

Второй индекс `(user_id, created_at)` — под вопросы удержания и активации («что делал человек
после первого входа»); первый `(kind, created_at)` остаётся под воронку и счётчики по видам.
Больше индексов не заводим: до ~1 млн строк Postgres считает агрегаты за миллисекунды.

JSONB в схеме появляется впервые. В jOOQ колонка — `org.jooq.JSONB`; сериализация props —
Jackson в репозитории (не в сервисах).

### 2.2. Объём и срок жизни

Оценка: 20 активных пользователей × ~50 событий в день ≈ 1 000 строк/день, 365 тыс. в год;
при 500 пользователях — ~9 млн в год. Чистка **не входит** в эту итерацию: вернуться при
1 млн строк (агрегировать `screen_opened` старше 180 дней в суточные счётчики, сырые удалять).
Записано в § 8.

## 3. События

Правило источника: **всё, что является мутацией на сервере, пишет сервер** в той же транзакции,
что и действие (`source = server`); нажатие inline-кнопки бота пишет бот (`source = bot`); Mini App
шлёт **только то, чего сервер не видит** — открытия экранов и шторок, слайды интро, факт запуска
(`source = app`). Так клиентская поверхность минимальна, а факты действий не теряются из-за
закрытого приложения или сбоя сети.

### 3.1. Уже есть (funnel.md, platform-billing.md) — без изменений

| kind | source | Когда |
|---|---|---|
| `bot_started` | bot | `/start` в личке; `campaign`, `telegram_id` |
| `chat_connected` | bot | чат привязан к клубу |
| `chat_disconnected` | bot | отвязка или бота выгнали |
| `trial_started` | server | первая встреча чата |
| `paywall_seen` | server | 402 на создании встречи (REQUIRES_NEW) |
| `checkout_started` | server | счёт у провайдера создан |
| `payment_succeeded` | server | оплата подтверждена ResultURL |
| `subscription_ended` | server | подписка закончилась |

Их `source` проставляется миграцией и в коде через новое перечисление; в логике — ничего.

### 3.2. Новые события от Mini App (`source = app`, через `POST /api/track`)

| kind | props | club_id | Когда пишется (фронт) | Вопрос (§ 7) |
|---|---|---|---|---|
| `app_opened` | `entry`: `none` \| `club` \| `event` \| `skladchina` \| `billing` \| `invite` (вид `startapp`-параметра, см. `DeepLinkHandler.resolveDeepLink`); `platform`: `tgWebAppPlatform` как есть (`ios`, `android`, `tdesktop`, `weba`, …, ≤ 32 знаков) | — | один раз за запуск, сразу после успешной авторизации (`Layout`, когда `isAuthenticated` стал true) | В1, В2, В5 |
| `screen_opened` | `screen`: имя экрана из таблицы § 3.4; для `/events/:id` и `/skladchina/:id` дополнительно `eventId` / `skladchinaId` (UUID строкой) | из `/clubs/:id/*`, иначе NULL | на каждую смену `location.pathname` внутри `Layout` (включая первый экран после авторизации); смена только `search`/`hash` не считается | В2, В3, В4 |
| `onboarding_step` | `step`: 1..3 (номер показанного слайда интро) | — | при показе слайда (`index` изменился), первый слайд — при монтировании `OnboardingFlow` | В1 |
| `onboarding_completed` | — | — | тап «Погнали!» на последнем слайде, **до** запроса `completeTour` (факт намерения, не успех запроса) | В1 |
| `pay_sheet_opened` | `reason`: `PaywallReason` из 402 или `none`; `mode`: `pay` \| `waiting` | клуб | монтирование `BillingSheet` | В6 |

Открытия, перечисленные в хэндоффе отдельными видами (`club_opened`, `event_form_opened`,
`skladchina_opened`), — это `screen_opened` с `screen = club | event_new | skladchina`. Один
вид вместо трёх плюс все остальные экраны бесплатно: одна точка в `Layout`, а не хук в каждой
странице.

### 3.3. Новые события сервера и бота

| kind | source | props | club_id | Где пишется | Вопрос |
|---|---|---|---|---|---|
| `club_joined` | server | `via`: `open` (`POST /api/clubs/{id}/join`) \| `invite` (`POST /api/invite/{code}/join`) \| `application` (одобрение заявки, актор — вступивший) | клуб | `MembershipService` / `InviteService` / `ApplicationService` в транзакции вступления; повторное вступление после выхода — тоже событие | В2, В3 |
| `event_created` | server | `format`: `normal` \| `open`; `eventId` | клуб | `EventService.create` после вставки, той же транзакцией; при 402 пейволла события нет (транзакция откатывается — уже есть `paywall_seen`) | В3, В4 |
| `vote_cast` | server | `vote`: `going` \| `maybe` \| `not_going`; `eventId` | клуб встречи | `POST /api/events/{id}/vote`, каждое голосование, включая смену голоса | В3, В4 |
| `skladchina_created` | server | `kind`: `shared` \| `per_head` \| `voluntary`; `skladchinaId` | клуб | `SkladchinaCreationService` | В4 |
| `debt_claimed` | server | `action`: `promise` \| `claim` \| `confirm` \| `reject` \| `forgive`; `debtId` | клуб долга (NULL, если долг без клуба) | `DebtController` → сервис, по одному событию на действие; те же действия из inline-кнопок бота пишутся **здесь же** в сервисе (источник тогда `bot`, см. ниже) | В4 |
| `feedback_sent` | server | — (текст **не** пишется) | — | `FeedbackService` после успешной доставки DM | В5 |
| `bot_button_clicked` | bot | `button`: имя кнопки из закрытого списка (сам `callback_data` в журнал не попадает — его присылает клиент): `chatlink:unlink`, `roster:proceed`, `roster:remind`, `debt:confirm`, `debt:reject`, `settle:confirm`, `settle:reject`, `skladchina:enroll`, `skladchina:take`, `debt:forgive`, `skladchina:close`, `legal:info` / `legal:offer` / `legal:privacy` (литералы констант `*_PREFIX` в `bot/`) | NULL: сервисы кнопок возвращают только текст алерта, клуб из них не достать, а действия по долгам и составу и так видны по своим событиям | `ClubsBot.handleCallbackQuery` **после** ответа Telegram (сбой журнала не должен оставить спиннер); `user_id` — по `query.from.id` через `users`, `telegram_id` всегда; неизвестный префикс не пишется | В5 |

Для `debt_claimed` из бота: сервис долга один на REST и бота, `source` передаётся параметром
(`server` из контроллера, `bot` из `SkladchinaCallbackService`), чтобы не плодить два вида на
одно действие. Остальные кнопки бота дают только `bot_button_clicked` — что именно случилось,
видно по уже существующим сущностям.

Кнопки-ссылки бота (`url` / `startapp=…`) Telegram боту не сообщает — их нажатие видно как
`app_opened` с `entry ≠ none`. Это и есть ответ на «сколько кликают по кнопкам в чат-постах».

### 3.4. Имена экранов (`screen_opened.screen`)

| Маршрут | screen | club_id |
|---|---|---|
| `/` | не экран: `HomeRoute` только перенаправляет в клуб или «Мои клубы»; в профиле с каталогом на главной — `discovery` | — |
| `/discovery` | `discovery` | — |
| `/connect-chat` | `connect_chat` | — |
| `/my-clubs` | `my_clubs` | — |
| `/activities`, `/events`, `/skladchina` | `activities` | — |
| `/profile` | `profile` | — |
| `/clubs/:id` | `club` | id |
| `/clubs/:id/setup` | `club_setup` | id |
| `/clubs/:id/manage` | `club_manage` | id |
| `/clubs/:id/events/new` | `event_new` | id |
| `/clubs/:id/event-templates/:templateId/edit` | `event_template_edit` | id |
| `/clubs/:id/skladchina/new` | `skladchina_new` | id |
| `/clubs/:id/join` | `club_join` | id |
| `/events/:id` | `event` | — (`props.eventId`) |
| `/skladchina/:id` | `skladchina` | — (`props.skladchinaId`) |
| `/debts` | `debts` | — |
| `/debts/with/:userId` | `debt_pair` | — (чужой `userId` в props **не** пишется) |
| `/invite/:code` | `invite` | — (код **не** пишется) |
| `/feedback` | `feedback` | — |
| редиректы (`/organizer`, `/clubs/:id/interior`), `/pay/*`, `/about`, `/privacy` | не пишутся: редиректы мгновенны, остальное — вне `Layout` и без авторизации |

Неизвестный путь (404 внутри `Layout`) → `screen = unknown`, без пути (путь мог содержать что
угодно). Таблица живёт в `frontend/src/analytics/screens.ts` рядом с роутером; при добавлении
маршрута — дописать строку (проверяется тестом: каждый путь роутера, кроме исключений, даёт имя).

## 4. API

### `POST /api/track` — пачка событий от Mini App

Авторизация: JWT (любой пользователь). `user_id` — **только из токена**, в теле его нет.

Запрос:
```json
{
  "events": [
    { "kind": "screen_opened", "clubId": "uuid-или-null", "props": { "screen": "club" } },
    { "kind": "app_opened", "props": { "entry": "none", "platform": "ios" } }
  ]
}
```

Правила (Bean Validation на DTO + проверка видов в сервисе):

- `events`: 1..20 элементов;
- `kind`: один из пяти app-видов § 3.2 (перечисление `AppEventKind` на сервере — whitelist);
  любой другой, включая серверные виды вроде `payment_succeeded`, — 400 на всю пачку;
- `clubId`: UUID или отсутствует; существование клуба и членство **не** проверяются (см. § 6);
- `props`: отсутствует или объект ≤ 10 ключей; ключ — `^[a-zA-Z]{1,32}$`; значение — строка
  ≤ 100 знаков без нулевого байта, число в пределах ±2^53 (BigInteger/BigDecimal, NaN — 400) или
  boolean; вложенные объекты и массивы — 400; ключи, не описанные для этого `kind` в § 3.2, — 400
  (whitelist по виду, таблица в `AppEventKind`). Размер деталей ≤ 1 КБ следует из потолков:
  у вида не больше трёх ключей по 100 знаков;
- тело больше 32 КБ по `Content-Length` — 413 ещё до разбора JSON (`RateLimitFilter`); общий
  потолок nginx — 6 МБ.

Ответы:
- `204 No Content` — пачка записана (одной транзакцией, одним batch-insert);
- `400 VALIDATION_ERROR` — нарушение любого правила выше, пачка не записана целиком
  (fail-closed: частичная запись скрыла бы ошибку клиента);
- `401` — без токена;
- `429` — отдельный бакет `RateLimitFilter` с двумя пределами: **30 запросов/мин** (пачка до 20
  событий, клиент шлёт раз в 3 с → максимум 20/мин при живой навигации; 30 оставляет запас на
  ретрай) и **500 запросов/сутки** (живая навигация — десятки в день; потолок против скрипта,
  который сутками пишет по 30 пачек в минуту). Ключ — пользователь из JWT (фильтр стоит после
  `JwtAuthenticationFilter`), без токена — IP.

Эндпоинт ничего не возвращает и ничего не читает — только пишет.

### Фронт: `track(kind, props?, clubId?)` в `frontend/src/analytics/track.ts`

- Кладёт событие в очередь в памяти; **никогда** не бросает исключений и не возвращает
  промис — вызывающий код не ждёт и не узнаёт о сбое (console.warn в dev).
- Отправка: таймер 3 с после первого события в очереди, либо сразу при 20 событиях, либо при
  `visibilitychange → hidden` (сворачивание/закрытие Mini App) — последняя через
  `fetch(..., { keepalive: true })`, потому что `sendBeacon` не умеет заголовок `Authorization`.
- Сбой сети: пачка возвращается в очередь и уходит со следующей — **один** повтор, дальше
  выбрасывается; ответ 4xx — выбрасывается сразу (чинить надо код, а не ретраить); очередь
  не длиннее 100 событий, старые вытесняются.
- Пока нет токена (`apiClient.getToken() === null`) — копим, не шлём; `apiClient` сам обновит
  токен на 401 и повторит, как для любого запроса.
- Выключатель: `VITE_ANALYTICS_DISABLED=true` → `track` — no-op (локальная разработка и тесты
  по умолчанию, чтобы vitest не слал пачки; в тестах мок на уровне модуля).

## 5. Реализация

### Backend — новый пакет `com.clubs.analytics`

| Файл | Что |
|---|---|
| `ProductEventKind.kt` | перечисление всех видов с `kind` и `source` по умолчанию; туда переезжает содержимое `FunnelStep` (сам `FunnelStep` удаляется, ссылки в `subscription` переводятся — 7 файлов) |
| `AppEventKind.kt` | whitelist видов, принимаемых `/api/track`, с допустимыми ключами props |
| `ProductEventRepository.kt` + `JooqProductEventRepository.kt` | `record(kind, source, userId, clubId, props: Map<String, Any?>?, telegramId?, campaign?)`, `recordDetached(...)` (REQUIRES_NEW, как сейчас), `recordAll(batch)`; замена `FunnelEventRepository` (удаляется) |
| `TrackController.kt`, `TrackService.kt`, `dto/TrackRequest.kt` | `POST /api/track` |
| `RateLimitFilter` | бакет `/api/track`, константа `TRACK_LIMIT_PER_MIN = 30` с русским пояснением |

`FunnelTracker`, `FunnelReportScheduler`, `JooqFunnelReportRepository` остаются в
`subscription` (меняется только имя таблицы и репозитория). Зависимость `subscription → analytics`
допустима: `analytics` — лист, импортирует только `user` (резолв `user_id` по telegram id) и
`common`. Сервисы фич (`event`, `membership`, `club`, `skladchina`, `debt`, `feedback`, `bot`)
инжектят `ProductEventRepository` напрямую — без событий Spring: запись должна быть в той же
транзакции, а цикла здесь нет.

Маппинг `Map → JSONB`: один `ObjectMapper` в репозитории; `null`-значения репозиторий выбрасывает, пустой объект пишется как `NULL`.

### Frontend — `frontend/src/analytics/`

| Файл | Что |
|---|---|
| `track.ts` | очередь, таймер, flush, keepalive, выключатель |
| `screens.ts` | `resolveScreen(pathname): { screen, clubId?, props? }` по таблице § 3.4 |
| `ScreenTracker.tsx` | компонент без разметки в `Layout`: `screen_opened` на смену `pathname` |
| `launch.ts` | `trackAppOpened()` — `app_opened` один раз за запуск, вызывается эффектом `Layout` по `isAuthenticated` (до гейта интро) |
| `telegram/deepLink.ts` | `parseDeepLink` / `deepLinkKind` — общий разбор ссылок запуска для `DeepLinkHandler` и `app_opened.entry` |
| точки | `OnboardingFlow` (`onboarding_step`, `onboarding_completed`), `BillingSheet` (`pay_sheet_opened`) |
| `api/track.ts` | `postTrack(batch)` через `apiClient` с `keepalive` |

### Миграция

`V105__product_event.sql` по § 2.1. После миграции — `./gradlew generateJooq`, Serena-индекс.

## 6. Безопасность (обязательный Security-проход)

- **Актор только из JWT.** `userId`/`telegramId` в теле нет; подделать автора нельзя.
- **`clubId` не проверяется** на существование и членство: это метка для группировки, а не
  доступ. Худшее, что может сделать злоумышленник, — замусорить статистику чужого клуба
  строками `screen_opened`. Проверка членства стоила бы запрос на каждую пачку и ломала бы
  легитимный случай (экран приглашения у не-участника). Эндпоинт ничего не возвращает —
  утечки данных нет. Решение: принять риск, зафиксировать здесь.
- **Whitelist видов и ключей props** — чужие виды (`payment_succeeded` от клиента) невозможны:
  серверные виды пишет только сервер. Размеры ограничены (пачка 20, props 10×100, 1 КБ).
- **Rate limit** 30/мин и 500/сутки на пользователя — отдельный бакет, чтобы трекинг не выжирал
  общий лимит 120/мин у живой навигации и не давал раздувать таблицу сутками; тело больше 32 КБ —
  413 до разбора JSON (Spring тело не ограничивает, Jackson строит граф раньше валидации).
- **Нажатия кнопок бота.** `callback_data` присылает клиент и он может быть любым; в журнал
  попадает имя кнопки из закрытого списка префиксов, чужие данные не пишутся.
- **ПДн в props нет по контракту:** ни имён, ни username, ни телефонов, ни текста
  (`feedback_sent` без текста, `debt_pair` без чужого userId, `invite` без кода). Reviewer
  проверяет каждую новую точку записи по этому правилу.
- **Вывод props.** Строки в props — пользовательский ввод (клиент может прислать любой текст
  ≤ 100 знаков в разрешённом ключе). В DM-отчёте они не печатаются; в Grafana (W5) таблицы
  экранируют HTML по умолчанию — не включать «raw HTML» в панелях.
- **Логи.** Пачка в лог не пишется; при 400 — только счётчик нарушений и `kind`, без props.

## 7. Вопросы, на которые отвечает журнал (основа W6)

| № | Вопрос | События |
|---|---|---|
| В1 | Где новичок отваливается в интро; сколько доходят до «Погнали!» | `app_opened`, `onboarding_step`, `onboarding_completed` |
| В2 | Откуда приходят запуски (кнопка в чате, приглашение, напрямую) и на какой платформе; WAU по пользователям и по клубам | `app_opened.entry/platform`, `screen_opened` (club_id), `club_joined` |
| В3 | Доходит ли клуб до первой встречи и первого голоса; сколько дней от `chat_connected` до `event_created` | `chat_connected`, `event_created`, `vote_cast` |
| В4 | Drop-off на формах и adoption функций: `event_new` → `event_created`, `skladchina_new` → `skladchina_created`, кто пользуется долгами | `screen_opened`, `event_created`, `skladchina_created`, `debt_claimed` |
| В5 | Работают ли кнопки бота (нажимают ли «Напомнить», «Получил»); сколько пишут в поддержку | `bot_button_clicked`, `feedback_sent` |
| В6 | Где теряем на оплате: шит открыт → чекаут → оплата | `pay_sheet_opened`, `checkout_started`, `payment_succeeded` |
| В7 | Удержание: кто из открывших приложение на неделе N открыл на неделе N+1/N+4 | `app_opened` |

## 8. НЕ входит

- Чистка/агрегация старых строк (вернуться при 1 млн строк, § 2.2).
- Идентификатор сессии, длительность экрана, скроллы, клики по кнопкам внутри Mini App
  (кроме перечисленных шторок) — не под вопрос.
- События `club_created` (клуб из чата = `chat_connected`; клубов «из приложения» на этапе 1
  нет), `event_cancelled`, `attendance_marked`, `member_left` — есть в таблицах сущностей со
  временем, Grafana возьмёт оттуда.
- Клиентское время события, оффлайн-буфер в localStorage, ретраи дольше одного.
- Просмотр событий в приложении (есть только SQL и Grafana).
- Расширение недельного DM-отчёта (W6) и вопросы с SQL (`docs/analytics/questions.md`, W6).

## 9. Решения PO (приняты 2026-10-15)

1. **Переименование таблицы в `product_event`** — да (§ 2.1).
2. **`screen_opened` на каждый экран** вместо трёх отдельных видов из хэндоффа — да (§ 3.2):
   один вид, одна точка в коде, все экраны.
3. **`bot_button_clicked` для всех callback-кнопок**, не только «важных» — да: одна строка в
   `handleCallbackQuery`, лишних событий мало (кнопки жмут редко).

## 10. Критерии приёмки

- **AC-1.** Миграция V105 на базе с существующими строками `funnel_event`: таблица `product_event`
  с теми же строками, `source = bot` у `bot_started`/`chat_connected`/`chat_disconnected`,
  `server` у остальных, `props IS NULL`; недельный отчёт (`JooqFunnelReportRepository`, его
  Testcontainers-тесты) даёт те же числа, что до миграции.
- **AC-2.** `POST /api/track` с пачкой из двух валидных событий → 204, две строки с `user_id`
  из токена, `source = app`, `created_at` сервера, `props` ровно как прислано; `clubId` → `club_id`.
- **AC-3.** 400 и ни одной строки при: 0 или 21 событии; `kind = payment_succeeded`;
  `kind = nope`; props с 11 ключами; ключ `scr-een`; строковое значение из 101 знака; вложенный
  объект; ключ, не разрешённый для вида (`screen_opened` с `entry`); `clubId = "abc"`.
- **AC-4.** 31-й запрос за минуту от одного ключа → 429 с `Retry-After: 60`; бакет `/api/track`
  не расходует общий бакет API (`resetBuckets` чистит и его).
- **AC-5.** Без токена → 401.
- **AC-6.** Серверные события: `POST /api/clubs/{id}/join` → `club_joined{via=open}`;
  `POST /api/invite/{code}/join` → `club_joined{via=invite}`; одобрение заявки →
  `club_joined{via=application}` с `user_id` вступившего; `POST /api/clubs/{id}/events` →
  `event_created{format, eventId}` с `club_id`; то же при 402 пейволла → строки нет;
  `POST /api/events/{id}/vote` дважды (`going`, затем `not_going`) → две строки `vote_cast`;
  создание сбора → `skladchina_created{kind}`; каждое из пяти действий по долгу → `debt_claimed{action}`
  с `source = server` из REST и `source = bot` из callback; успешный `POST /api/feedback` →
  `feedback_sent` без props; неуспешный (503) → строки нет.
- **AC-7.** Бот: callback `roster:remind:<uuid>` → `bot_button_clicked{button="roster:remind"}`,
  `legal:offer:1` → `{button="legal:offer"}`, с `telegram_id` и `user_id` (если пользователь известен);
  неизвестный `callback_data` → строки нет.
- **AC-8.** Фронт (vitest): `track` не бросает при сбое сети и при 400; 20 событий уходят одной
  пачкой сразу; 3 события уходят одной пачкой через 3 с; `hidden` → немедленная отправка с
  `keepalive: true`; после сетевого сбоя пачка уходит повторно один раз и при втором сбое
  выбрасывается; при 400 не повторяется; очередь ограничена 100; при `VITE_ANALYTICS_DISABLED`
  ничего не уходит.
- **AC-9.** `resolveScreen` покрывает каждый путь из `router.tsx`, кроме исключений § 3.4
  (тест перебирает маршруты роутера); `/clubs/<uuid>/manage` → `club_manage` + `clubId`;
  `/events/<uuid>` → `event` + `props.eventId`; `/debts/with/<uuid>` → `debt_pair` без props;
  `/что-то` → `unknown` без props.
- **AC-10.** `Layout`: после авторизации ровно один `app_opened` за запуск с `entry` по
  `startapp` (`club_<uuid>` → `club`, без параметра → `none`) и `platform`; переход `/my-clubs`
  → `/clubs/<id>` даёт два `screen_opened`; изменение только `?tab=` — ничего.
- **AC-11.** `OnboardingFlow`: показ трёх слайдов → `onboarding_step` 1..3 по одному на слайд
  (возврат назад — повторный step с тем же номером); «Погнали!» → `onboarding_completed`.
- **AC-12.** `BillingSheet` при монтировании → `pay_sheet_opened{reason, mode}` с `clubId`.
- **AC-13.** `./gradlew test` и `npm test` зелёные; чистый `tsc`; Serena-индекс обновлён после
  codegen.

## 11. Тест-план на staging

Проверка — по строкам в базе (`~/clubs-db.sh staging` или SSH + `psql`), запрос:
`select created_at, source, kind, club_id, props from product_event order by created_at desc limit 30;`

1. Открыть Mini App с телефона напрямую → `app_opened{entry=none, platform=ios|android}` и
   `screen_opened{screen=home}` (или `club`, если клуб один).
2. Пройти интро новым аккаунтом (или сбросить `user_onboarding_tours`) → три `onboarding_step`,
   `onboarding_completed`.
3. Пройти: «Мои клубы» → клуб → «Управление» → назад → профиль → «Долги» → строки `screen_opened`
   в этом порядке, у клубных — `club_id`.
4. В клубе открыть «+» → «Встреча» → создать → `screen_opened{event_new}`, затем
   `event_created{format}` (`source = server`).
5. Проголосовать «Пойду», затем «Не пойду» → два `vote_cast`.
6. Создать сбор → `skladchina_created{kind}`; отметить долг «Отдал» → `debt_claimed{action=claim}`.
7. В чате нажать inline-кнопку бота «Напомнить» → `bot_button_clicked{button=roster:remind}`
   (`source = bot`).
8. Открыть приложение кнопкой из чат-поста встречи → `app_opened{entry=event}`.
9. Открыть шит оплаты из полоски статуса → `pay_sheet_opened{reason=none, mode=pay}`.
10. «Сообщить о проблеме» → `feedback_sent`.
11. Свернуть приложение сразу после перехода на экран → строка всё равно есть (keepalive).
12. Недельный отчёт (крон на staging раз в 5 минут, как в funnel.md § 6) приходит и числа не
    поменялись.
