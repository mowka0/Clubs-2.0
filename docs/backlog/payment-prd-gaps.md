# Payment: known PRD ↔ code gaps

Расхождения между PRD и текущей реализацией модуля `payment` (после ветки `feature/refactor-payment`, 2026-04-24). Все — **существующие** gaps, выявленные pre-flight аналитикой. Не являются багами в обычном смысле — это нереализованные фичи. Зафиксированы здесь, чтобы не потерялись и чтобы спека модуля (`docs/modules/payment.md`) не выглядела «полной», когда она таковой не является.

Источник: сверка `PRD-Clubs.md` §4.2, §4.7 ↔ `backend/src/main/kotlin/com/clubs/payment/`.

---

## GAP-1: Рекуррентные Stars-платежи (автосписание каждые 30 дней)

**PRD §4.7.2:** «Telegram Stars API поддерживает рекуррентные платежи (подписки).»
**PRD §4.7.3.2:** «Продление: Автоматическое списание каждые 30 дней. Пользователь получает уведомление за 3 дня до списания.»

**Реальность:** `PaymentService.createInvoice` создаёт одноразовый `SendInvoice`. Никакого механизма автосписания нет. `SubscriptionScheduler` только переводит подписки в `grace_period → expired` после истечения. Уведомление «за 3 дня» приходит, но означает «истекает», а не «скоро будет автосписание».

**Impact:** пользователь вынужден вручную переплачивать каждый месяц (UX flow для этого в PRD §4.2 не описан). Де-факто поведение такое, будто автосписание всегда fail'ится.

**Что нужно:**
- Отдельный flow в `ClubsBot` / `PaymentService` для создания recurring invoice'ов через Telegram Stars API.
- Scheduler — прогон попыток автосписания за N дней до `expires_at`, с откатом в `grace_period` при неудаче.
- Отдельная колонка/таблица для отслеживания recurring subscription'ов Telegram.

**Scope:** отдельная фича.

---

## GAP-2: Отмена подписки пользователем (`cancelled`)

**PRD §4.7.3.4:** «Отмена: Пользователь может отменить подписку вручную. Доступ сохраняется до конца оплаченного периода. Статус: `cancelled`.»

**Реальность:** enum `membership_status.cancelled` существует в V3, но нет endpoint / сервиса, который переводит подписку в этот статус. В текущей логике renewal (`PaymentService.handleSuccessfulPayment`) оплата membership со статусом `cancelled` вернёт его в `active` без подтверждения — т.е. сейчас cancelled де-факто недостижим и потенциально «хрупок».

**Что нужно:**
- HTTP endpoint для пользовательской отмены (`POST /api/memberships/{id}/cancel`) + UI в Mini App.
- Логика: после `cancelled` membership остаётся активным до `subscription_expires_at`, потом → `expired` без прохождения `grace_period`.
- Scheduler: перевод `cancelled → expired` по истечении `expires_at`.
- Pre-check в `handleSuccessfulPayment`: оплата `cancelled`-membership должна требовать явного согласия (или запрещаться в рамках текущего инвойса).

**Scope:** отдельная фича.

---

## GAP-3: Grandfathered цена подписки

**PRD §4.7.4 AC:** «При смене цены подписки действующие участники продолжают платить по старой цене до отмены.»

**Реальность:** `PaymentService.createInvoice` всегда читает актуальную `clubs.subscription_price`. Ни в `memberships`, ни в `transactions` не фиксируется «цена на момент вступления». При смене цены следующий renewal-инвойс уйдёт уже по новой цене.

**Impact:** нарушение обещания PRD, потенциальная претензия со стороны участников.

**Что нужно:**
- Новая колонка `memberships.subscription_price_at_join` (снапшот цены при активации).
- `createInvoice` для существующего membership использует этот снапшот, а не текущую `clubs.subscription_price`.
- Миграция для исторических memberships — заполнить из `clubs.subscription_price` на момент миграции (с документированным допущением).

**Scope:** отдельная фича (требует миграции БД + изменений в payment flow).

---

## GAP-5: Flow вступления не зовёт createInvoice (payment отключён от join)

**PRD §4.2.1.3:** «Переход к оплате через Telegram Stars» — шаг flow вступления в открытый клуб.
**PRD §4.2.1.4:** «После успешной оплаты: пользователь автоматически добавляется в Telegram-группу клуба».

**Реальность:** `MembershipController.POST /api/clubs/{id}/join` → `MembershipService.joinOpenClub` создаёт membership напрямую **без проверки цены и без invoice**. `PaymentService.createInvoice` существует, но не вызывается нигде в кодовой базе (`grep -r 'createInvoice' backend/src/main/kotlin` даёт только определение). Аналогично `MembershipService.joinByInviteCode`.

**Impact:** платные клубы де-факто работают как бесплатные. Invoice никогда не приходит, `handleSuccessfulPayment` — dead code в текущем состоянии прода.

**Что нужно:**
- `MembershipService.joinOpenClub` (и `joinByInviteCode`): если `club.subscription_price > 0` → **не создавать** membership, вместо этого зовём `paymentService.createInvoice(userId, clubId)` и возвращаем HTTP 202 / DTO со статусом «pending_payment». Если `== 0` (free club) — текущее поведение (создаём `active` membership сразу).
- `handleSuccessfulPayment` уже корректно создаёт membership при активации (рефакторинг `feature/refactor-payment` это закрыл) — т.е. вторая половина flow уже работает, надо только подключить первую.
- Frontend: на ответ «pending_payment» показывать пользователю подсказку «ждём оплату в боте».

**Scope:** отдельная bugfix-ветка `bugfix/wire-payment-to-join`. Меньше фич чем GAP-1/2/3/4, но критичнее — блокирует всю монетизацию MVP.

**Связанные GAP:**
- После закрытия GAP-5 появится реальный flow оплаты → станет видно отсутствие GAP-4 (авто-invite в TG-группу после `handleSuccessfulPayment`).

---

## GAP-4: Автоматическое добавление в Telegram-группу после оплаты

**PRD §4.2.1.4:** «После успешной оплаты: пользователь автоматически добавляется в Telegram-группу клуба и получает доступ к календарю событий в Mini App.»
**PRD §4.2.1 AC:** «После оплаты пользователь автоматически добавляется в группу (через Telegram Bot API invite link).»

**Реальность:** `PaymentService.handleSuccessfulPayment` создаёт membership и transaction, но **не шлёт invite link** и не вызывает никакого Telegram API для добавления в группу. В `clubs.telegram_group_id` группа может быть привязана (V10), но invite-flow не реализован.

**Impact:** ключевое обещание воронки MVP не выполняется — пользователь заплатил, но в группу не попал.

**Что нужно:**
- После успешной обработки платежа: через `TelegramClient` создать одноразовый invite link (`createChatInviteLink` с `member_limit=1`), отправить его в DM пользователю.
- Обработка ошибок: группа удалена, бот исключён из группы, group_id не задан.
- Возможно — отдельный сервис `ClubMembershipInviteService` в `bot/` или в `membership/`.

**Scope:** отдельная фича, пересекается с `bot` модулем. Критична для MVP, но не для рефакторинга payment-слоёв.

---

## GAP-6: UX закрытого клуба — две кнопки (запрос + оплата), разблокировка после approve

**Текущий PRD §4.2.2 (устарел):** единая кнопка «Хочу вступить» → форма с ответом → организатор approve → автоматически запускается оплата.

**Новое требование (согласовано 2026-04-24):** у пользователя на карточке закрытого клуба **две кнопки рядом**:
1. «Отправить запрос» — активна сразу. Открывает форму, отправляет заявку организатору.
2. «Вступить» — **заблокирована** до одобрения заявки. После approve организатора: бот шлёт DM «Ваша заявка одобрена, вступайте → [ссылка на клуб]», в Mini App кнопка «Вступить» разблокируется. Нажатие → формирует Stars invoice → после оплаты → membership активен.

**Impact:** UX и back-end flow закрытых клубов меняются.

**Что нужно:**
- Изменение модели: статус «заявка одобрена, ожидаем оплату» (возможно, новое значение enum `application_status` или `membership_status = pending_payment`).
- `ApplicationService.approveApplication` после GAP-5 фикса не создаёт membership сразу — только помечает заявку как approved и шлёт DM.
- Отдельный endpoint «нажатие на Вступить в закрытом клубе» → вызывает `createInvoice`. Frontend проверяет `application.status == approved` перед разблокировкой.
- Бот шлёт DM с deep-link на `/club/:id` после approve.
- UI: две кнопки, одна dimmed пока нет approved-application.

**Scope:** отдельная фича. Требует обновления **PRD §4.2.2** — текущий текст закрепляет устаревшую односекундную логику.

**Зависимости:** нужна после GAP-5 (иначе при approve membership создаётся сразу, что делает GAP-6 бессмысленным).

---

## GAP-8: Invoice spam protection (deduplication per user+club)

**Источник:** Security review bugfix/wire-payment-to-join (Medium). CWE-799 / CWE-837.

**Реальность:** `MembershipService.joinOpenClub` / `joinByInviteCode` и `ApplicationService.approveApplication` на каждом вызове зовут `PaymentService.createInvoice`, который шлёт новый `SendInvoice` в Telegram DM пользователя. Повторные клики «Вступить» → сервер отправляет повторные DM. Глобальный rate-limit (`RateLimitFilter`, 60/min per user) ограничивает до 60 invoice/min на одного юзера — не DoS, но UX-спам в DM и расходование квоты Telegram Bot API.

**Impact:** неприятный UX (10 DM при нервных кликах), потенциальная нагрузка на Telegram Bot API (30 msg/sec лимит).

**Что нужно:**
- Дедупликация: перед `createInvoice` проверить, был ли отправлен invoice этому юзеру для этого клуба в окне (например, 60-120 сек). Если да — вернуть тот же `PendingPaymentDto` без повторной отправки.
- Варианты хранения:
  - Redis ключ `invoice:pending:{userId}:{clubId}` с TTL 120s (у нас Redis уже используется для rate-limit — минимальная инфраструктурная правка).
  - Новая колонка `applications.invoice_sent_at` / `memberships.last_invoice_at` (инвазивнее).
- Альтернатива: endpoint-specific rate-limit через Bucket4j с ключом `join:{userId}:{clubId}` вместо per-user global.

**Scope:** отдельная задача. В текущем состоянии (global 60/min) это низкий, но видимый риск — не блокер для GAP-5 merge.

---

## GAP-7: Free-клуб lifecycle по вовлечённости (вместо простого `expires_at + 30d`)

**Текущее поведение:** при вступлении в бесплатный клуб `memberships.subscription_expires_at = now + 30d`. Renewal в коде не реализован — после истечения scheduler переводит в `grace_period` → `expired`. Фактически бесплатный клуб умирает через 33 дня для каждого юзера. PRD этого сценария явно не описывает.

**Идея (черновик, требует проработки):**
- Если юзер **вовлечён** в клубе (критерии tbd — например, участие в ≥1 событии за 30 дней, positive reputation delta) → автопродление `expires_at += 30d`, renewal продолжается без оплаты.
- Если юзер **не вовлечён** за последние 30 дней → авто-исключение (`status = expired`), без grace-period или с укороченным.

**Open questions:**
- Какие метрики вовлечённости использовать? (event responses, attendance, chat activity?)
- Пороги: «≥ 1 событие» достаточно или нужна формула?
- Уведомление юзеру о приближающемся auto-kick?
- Совпадает ли это поведение с PRD-смыслом «подписка» для бесплатных клубов (вероятно, PRD писался только для платных — надо переосмыслить).

**Scope:** отдельная фича. Требует дизайна + обновления PRD (§4.3 или новая секция «Lifecycle бесплатного клуба»).

**Зависимости:** пока отсутствуют, можно проектировать параллельно с другими GAP.

---

## GAP-9: Deep-link в конкретный клуб из DM (поверх welcome-DM)

**Реальность:** после закрытия GAP-5+DM-welcome пользователь получает DM «Добро пожаловать в клуб «X»!» с кнопкой «📱 Открыть Clubs» (existing inline button в `NotificationService.sendDm`). Кнопка открывает **главную** страницу Mini App, а не страницу конкретного клуба. Пользователю нужно самому найти клуб в «Мои клубы».

**Что нужно:**
- Формировать deep-link `https://t.me/<bot>/app?startapp=club_<clubId>` для конкретного клуба.
- Во фронте добавить обработчик `startParam` (через `retrieveLaunchParams()`) в корневом компоненте или отдельном `DeepLinkHandler`: парсит `startParam`, редиректит на `/clubs/<id>` / `/invite/<code>` и т. д.
- `NotificationService` получает перегрузку `sendDirectMessage(telegramId, text, startParam?)`, которая собирает URL с нужным параметром.

**Связанные GAP:** GAP-4 (автоинвайт в TG-группу после оплаты) концептуально близок — оба о "что происходит post-payment". Можно проектировать и реализовывать вместе: one PR = welcome DM со ссылкой + создание invite-link в Telegram-группу + shared deep-link handler.

**Scope:** отдельная ветка, frontend + backend + возможно расширение `NotificationService`.

---

## Статус

Все gap'и **зафиксированы в `docs/modules/payment.md`** (раздел «Риски и открытые вопросы») как known. После того как каждая фича будет реализована — запись переносится из этого файла в git history (через удаление + описание в PR).
