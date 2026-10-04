# Хэндофф: День 4,5 спринта 1.0 — разметка воронки и воскресный отчёт (обновлён 2026-10-04, влито)

## Где мы

- **Биллинг за клуб — в проде целиком**, ветки влиты: #168–#171 (биллинг, лендинг, политика),
  #173 (RU-прокси), #174, #175, #176 (Mini App на `clubsmeet.com`), #177 (обязательная
  информация для Robokassa в боте), #178 (оферта на шаблоне Robokassa, канон `docs/legal/oferta.md`).
  master = `319e5975`.
- **Robokassa АКТИВИРОВАНА 2026-10-02, живые оплаты и чеки в «Мой налог» работают** (PR #179 оплата без
  рекуррента, PR #180 `Receipt`; прод на `aa4f0865` проверен с VPS 2026-10-04). Остался **рекуррент**
  (заявка подана, PO: «скоро подключат») — порядок включения в `sprint-1.0-day4-billing-handoff.md`
  § 2d п. 7 и § 11 спеки. `autopayAvailable` (шит обещает списание без рекуррента) сделан и
  **запаркован** локальной веткой `feature/billing-autopay-available` (коммит `2f178870`, не пушить) —
  решение PO 2026-10-04 «пока не трогай».
- **День 4,5 — ГОТОВ: ветка `feature/sprint-1.0-day4-5-funnel` протестирована PO на staging
  2026-10-04 («окей, вроде работает, готово запушь») и влита в master PR #181 (squash) → прод.** Состав отчёта и определения
  согласованы с PO 2026-09-30 (плюс метрика «клубов / с чатом, цифры и проценты» по его просьбе).
  Спека — `docs/modules/funnel.md` (финальная, не черновик). Трек L: миграция V100 + `subscription/`.
  Бэк: 1198 тестов зелёные (полный прогон), Reviewer — «мерж можно», Security — Critical/High нет;
  Medium (потолок кампаний в DM + честный лог доставки) и три Low закрыты в ветке.
- Дни 5–6 (экран «подключите чат») — в проде. Открытый хвост этапа 2 концепции: тур по странице
  клуба к «Пригласить» (`sprint-1.0-day2-3-handoff.md` § «Что дальше»).
- AI-опрос v0 (дни 7–11, план § 5) — не начат, идёт после Дня 4,5.

## Что сделано в ветке (кратко; подробно — `docs/modules/funnel.md`)

- `funnel_event`: V100 `telegram_id`; шаги `bot_started` (`/start` в личке, `campaign` из `ad_<slug>`,
  регистр не важен), `chat_connected` (все входы привязки через `linkChatToClub`),
  `chat_disconnected` (отвязка любым входом + кик бота).
- `chatlink` → `subscription` без прямого импорта: Spring-события `ChatLinkedEvent` /
  `ChatDisconnectedEvent`, слушатель `subscription/FunnelTracker` (синхронно, в транзакции публикующего).
- `common/config/PlatformAdmins` — общий парсер `PLATFORM_ADMIN_TELEGRAM_IDS` (ключ yaml переехал в
  `platform.admin-telegram-ids`, env тот же); `ManualChargeAccess` на нём.
- Отчёт: `JooqFunnelReportRepository` (все определения в одном классе), `FunnelReportFormatter`
  (текст слово в слово в тесте), `FunnelReportScheduler` — крон `FUNNEL_REPORT_CRON`
  (дефолт понедельник 09:00 МСК), окно — ISO-неделя, в которую входит «вчера».
- Доки выровнены: `docs/INDEX.md`, `platform-billing.md` (§ 10.10, § 11), `infrastructure.md`
  (env-список), `telegram-bot.md` (`/start`), `club-chat-link.md` (события). PRD не трогали:
  проход по PRD — одним заходом в конце спринта (решение PO 2026-08-16).

> **2026-10-04:** master с биллингом Robokassa (PR #179 оплата без рекуррента, PR #180 чек `Receipt`)
> влит в ветку без конфликтов, staging пересобран. Тест по § 6 **всё ещё не проводился** — сессия
> ушла на боевое включение Robokassa (`sprint-1.0-day4-billing-handoff.md` § 2d п. 5–7). Сервер:
> две сборки разом кладут VPS — после мержа в master ветку не пушить, пока прод не поднялся.
> **Вечер 2026-10-04:** тест § 6 пройден PO, ветка влита (PR #181). В Coolify `concurrent_builds`
> уже 1 (сборки встают в очередь), Rescale до 8 ГБ не сделан (3,8 ГБ).

## Что PO проверяет на staging (`docs/modules/funnel.md` § 6)

1. Coolify staging → env: `PLATFORM_ADMIN_TELEGRAM_IDS=<свой telegram id>`,
   `FUNNEL_REPORT_CRON=0 */5 * * * *` → Restart. **Не в понедельник** (иначе окно = прошлая неделя).
2. `https://t.me/clubs_v2_test_bot?start=ad_test1` → «Старт»; ещё раз «Старт» без метки.
3. Подключить тестовый чат через `?startgroup=new`, создать в нём встречу.
4. В течение 5 минут приходит DM-отчёт: воронка «1 старт → 1 подключение → 1 первая встреча»,
   кампании `ad_test1 — 1 старт / 1 подключение / 0 оплат`, «За неделю: +1 клуб, с чатом 1 (100%)».
5. Отвязать чат из «Управления» → в следующем отчёте «отключений 1».
6. Вернуть `FUNNEL_REPORT_CRON` на дефолт (или удалить переменную), иначе DM каждые 5 минут.

## После мержа (PR #181, 2026-10-04)

- ✅ squash-merge в master → прод. **PO: в Coolify prod задать `PLATFORM_ADMIN_TELEGRAM_IDS`** (свой
  telegram id) **до понедельника 09:00 МСК** — без него отчёт в проде никуда не придёт (compose
  переменную уже прокидывает; на 2026-10-04 в прод-контейнере пусто).
- **PO: на staging вернуть `FUNNEL_REPORT_CRON` на дефолт** или удалить переменную — после теста там
  осталось `0 */5 * * * *`, DM будут приходить каждые 5 минут.
- Первый боевой отчёт — понедельник 05.10.2026 09:00 МСК за ISO-неделю 28.09–04.10.

## Что решено и почему (чтобы не переоткрывать)

- День отправки — понедельник 09:00 МСК, полная ISO-неделя (черновик «воскресенье + 7 суток»
  противоречил сам себе: скользящее окно ≠ ISO-номер).
- «С чатом» = привязка + бот в чате, как в биллинге. «−N» — по `chat_disconnected`, мягкое
  удаление `club_chat_links` не нужно.
- «Встреч состоялось» = `completed` (время прошло, не отменена), посещаемость не проверяется;
  только клубы с чатом.
- «Чатов без встреч 30 дней» — с названиями (до 10), запланированная встреча спасает.
- Оплаты атрибутируются к кампаниям (тот же join, что у подключений), first touch.
- Органика грязная (участники тоже жмут «Старт») — читать строку кампаний, не общую конверсию.
- Не взято из ревью: `@Transactional(readOnly)` на отчёт — при READ COMMITTED снапшота не даёт,
  REPEATABLE READ ради недельного DM лишнее.

## Ловушки сессии

- Кодоген jOOQ на временном PG (рецепт `branch-handoff-de-stars-member-admin.md` § 7):
  `pg_isready` отвечает «готов» во время первичной инициализации образа, потом Postgres
  перезапускается и миграции падают. Ждать **второго** «database system is ready to accept
  connections» в `docker logs` + `sleep 2`.
- mockk: цепочечный стаб `every { update.message.from.id }` подменяет уже застабленный
  `update.message` новым моком — ломает `hasText()`. Стабить `from` внутри хелпера сообщения.
- `verify { publisher.publishEvent(any()) }` резолвится в перегрузку `publishEvent(ApplicationEvent)`,
  которую data-классы событий не трогают — проверка пустая. Нужен `ofType<Событие>()`.
  Тот же паттерн сидит в `event/EventServiceTest.kt` (6 мест) — бэклог.
- Kotlin allopen (`@Component`): свойство без инициализатора нельзя назначать в `init` —
  «Property must be initialized, be final, or be abstract». Считать в объявлении.
- Serena `find_referencing_symbols` вернула пусто для `releaseLink` — перепроверять grep-ом.

## Хвосты PO (не блокируют)

- BotFather `@clubs_v2_bot`: Description / About / Commands — тексты в `telegram-bot.md` § «BotFather».
- Реквизиты выплат в Robokassa — с отдельной СберКарты.
- Сброс root-пароля RU VPS в панели Timeweb (пароль засветился в чате 18.09).
- Backlog кода: `recipientInn` в `BillingStatusDto`; политика ПД дублируется руками
  (`PrivacySections.kt` ↔ `privacyText.ts`); юрист оферту не смотрел; `.env.example` без
  `PLATFORM_ADMIN_TELEGRAM_IDS` / `FUNNEL_REPORT_CRON` (там нет ни одной крон-переменной).

## Стартовая команда новой сессии

> «продолжи: День 4,5 (воронка) в проде PR #181, хэндофф — `docs/backlog/sprint-1.0-day4-5-funnel-handoff.md`.
> Проверить, что PO задал `PLATFORM_ADMIN_TELEGRAM_IDS` в Coolify prod и первый отчёт пришёл в
> понедельник; дальше — AI-опрос v0 (план `docs/design/sprint-1.0-chat-pivot.md` § 5), новая ветка
> от master. Рекуррент Robokassa — по `sprint-1.0-day4-billing-handoff.md` § 2d п. 7.»
