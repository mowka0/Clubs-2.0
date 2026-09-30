# Хэндофф: День 4,5 спринта 1.0 — разметка воронки и воскресный отчёт (2026-09-30)

## Где мы

- **Биллинг за клуб — в проде целиком**, ветки влиты: #168–#171 (биллинг, лендинг, политика),
  #173 (RU-прокси), #174, #175, #176 (Mini App на `clubsmeet.com`), #177 (обязательная
  информация для Robokassa в боте: `/start`, `/terms`, оферта и политика «шторкой»), #178
  (оферта на шаблоне Robokassa «Оказание услуг», канон `docs/legal/oferta.md`, генератор
  `scripts/gen-oferta.py`, CI `oferta-sync.yml`). master = `319e5975`.
- **Robokassa: магазин отправлен на активацию 2026-09-24**, ждём ответа. Адрес магазина —
  `https://t.me/clubs_v2_bot`, тип бизнеса «Оказание услуг», в проде `ROBOKASSA_TEST_MODE=true`
  и пароли-заглушки. Когда ответят — п. 3–4 раздела «Robokassa — шаги PO» в
  `sprint-1.0-day4-billing-handoff.md` § 2d: тестовые пароли → Coolify → прогон
  `platform-billing-testplan.md` (21 кейс) → боевые пароли, `ROBOKASSA_TEST_MODE=false`.
  Это делается в **новой ветке от master**, старые биллинговые ветки не реанимировать.
- **Следующий этап — День 4,5** (план `docs/design/sprint-1.0-chat-pivot.md` § 4, § 6):
  разметка воронки `?start=ad_<кампания>` + шаг «чат подключён» + воскресный DM-отчёт себе.
  PO: «предварительно начал бы с него, но обсудим» — сначала обсуждение, потом код.
  Ветка **`feature/sprint-1.0-day4-5-funnel`** уже создана от master, в ней только спека-черновик
  `docs/modules/funnel.md` (123 строки: шаги, миграция V100 `telegram_id`, отчёт с
  определениями метрик, конфиг, 9 AC, тест-план staging, безопасность). Код не начат.
- Дни 5–6 (экран «подключите чат», `ConnectChatScreen`) — в проде. Открытый хвост этапа 2
  концепции: тур по странице клуба к «Пригласить» (`sprint-1.0-day2-3-handoff.md` § «Что дальше»).
- AI-опрос v0 (дни 7–11, план § 5) — не начат, идёт после Дня 4,5.

## Что обсудить с PO перед кодом Дня 4,5

1. Состав воскресного отчёта: какие цифры хочет видеть (черновик — § 3.3 спеки: чаты, платят и
   MRR, встреч состоялось, чаты без встреч 30 дней, воронка за неделю, кампании).
2. Определение «чат без встреч 30 дней» (в спеке: привязан ≥ 30 дней, нет встреч кроме
   отменённых в окне ±30 дней).
3. Нужна ли атрибуция оплат к кампаниям (в спеке — нет, при 4 оплатах в неделю считается руками).
4. Крон отчёта: воскресенье 10:00 МСК по умолчанию.

## Что уже выяснено по коду (чтобы не искать заново)

- `funnel_event` (V97) есть: `user_id, club_id, kind, campaign, created_at`; пишут
  `BillingGate` (`trial_started`, `paywall_seen` через `recordDetached`), `BillingService`
  (`checkout_started`, `payment_succeeded`), `BillingLifecycleService` (`subscription_ended`).
  `campaign` никем не заполняется. Enum `FunnelStep` — `subscription/FunnelEventRepository.kt`.
- `/start` в личке: `ClubsBot.consume` → `handleStart(chatId)`, payload сейчас игнорируется;
  текст доступен как `update.message.text` («/start ad_x»). В группе — `handleGroupStart`
  (payload `new` или UUID клуба).
- Привязка чата: `ChatLinkBotService.linkChatToClub(chatId, chatTitle, fromTelegramId, club,
  announceInChat)` — единственная точка, куда сходятся `?startgroup=new` и привязка из
  «Управления»; `club.ownerId` — владелец, `fromTelegramId` — его telegram id.
- Пользователь по telegram id: `UserRepository.findByTelegramId(telegramId): UsersRecord?`.
- Админы платформы: `ManualChargeAccess` парсит `billing.manual-charge.admin-telegram-ids`
  (env `PLATFORM_ADMIN_TELEGRAM_IDS`) — в спеке вынос в `common/config/PlatformAdmins`,
  `ManualChargeAccessTest` есть (3 теста), конструктор `(enabled, adminTelegramIds)`.
- DM админу: `NotificationService.sendDirectMessage(telegramId: Long, text: String)` (best-effort)
  или `trySendDirectMessage` (с результатом). Шедулеры по крону — образец
  `payment/SubscriptionScheduler.kt` (`@Scheduled(cron = "\${membership.expiry-cron}")`).
- Схема для агрегатов: `club_chat_links(club_id PK, chat_id, linked_at, …)` — отвязка = физическое
  удаление строки, истории нет; `service_subscription(status ∈ ACTIVE|CANCELLED_PENDING_END|
  PAST_DUE|ENDED, plan, subject_club_id)`, цена — `subscription_pricing` через
  `SubscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT)`; `events(club_id,
  event_datetime, status ∈ upcoming|completed|cancelled)`.
- Образец интеграционного теста на Testcontainers — `subscription/BillingRepositoryTest.kt`
  (`@SpringBootTest` с `spring.data.redis.port=0`, `telegram.bot-token=test-bot-token`).
- Трек — **L**: правка `subscription/` и миграция V100 (триггеры из CLAUDE.md).

## Хвосты PO (не блокируют)

- BotFather `@clubs_v2_bot`: Description / About / Commands — тексты в `telegram-bot.md`
  § «BotFather» (≤ 512 / ≤ 120 знаков, уложены).
- Реквизиты выплат в Robokassa — с отдельной СберКарты («Реквизиты и выписки» → счёт 40817…,
  БИК, корр. счёт, наименование; автоформирование чеков в Сбере выключить — чеки пробивает Robokassa).
- Сброс root-пароля RU VPS в панели Timeweb (пароль засветился в чате 18.09).
- Backlog кода: `recipientInn` в `BillingStatusDto` (шит берёт ИНН из бандла); политика ПД
  дублируется руками (`PrivacySections.kt` ↔ `privacyText.ts`); юрист оферту не смотрел.

## Стартовая команда новой сессии

> «продолжи: День 4,5 спринта 1.0 — разметка воронки и воскресный отчёт. Хэндофф —
> `docs/backlog/sprint-1.0-day4-5-funnel-handoff.md`, ветка `feature/sprint-1.0-day4-5-funnel`
> (от master, в ней только спека `docs/modules/funnel.md`). Сначала обсудить с PO состав отчёта
> и определения (§ «Что обсудить»), потом код.»
