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

## Статус

Все четыре gap'а **зафиксированы в `docs/modules/payment.md`** (раздел «Риски и открытые вопросы») как known. После того как каждая фича будет реализована — запись переносится из этого файла в git history (через удаление + описание в PR).
