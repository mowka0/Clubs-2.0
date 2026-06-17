# Module: Skladchina (сборы — совместные сбор денег внутри клуба)

> **Status:** ✅ Реализовано и прошло staging — `feature/skladchina-mvp` мержится
> в master 2026-05-23. Заменяет `docs/backlog/skladchina.md` (старый brainstorm
> от 2026-05-17). Post-flight отклонения от исходной спеки + post-staging фиксы
> зафиксированы в § ниже.

> **Update (post `feature/unified-activity-creation`, итерация 4 — 2026-05-24):**
> вкладки `События` и `Сборы` в `OrganizerClubManage` сначала (итерация 1) были
> объединены в один таб `Активности` с unified-feed и picker'ом «+ Создать», а
> затем (итерация 4) **этот таб удалён из manage**. Компоненты `SkladchinaManageTab`
> и `EventsTab` удалены; `ActivitiesManageTab` тоже удалён (итерация 4).
> Унифицированная лента (`ActivityCard` внутри switch по `type`) живёт теперь
> только в member-view `ClubActivitiesTab` (`ClubPage`, read-only). Создание сбора
> переехало в глобальный flow на `ActivitiesPage` (`/events`): hero «+ Создать» →
> `CreateActivityFlow` (тип → клуб) → `/clubs/:id/skladchina/new`
> (`CreateSkladchinaPage`). Endpoint `POST /api/clubs/:id/skladchinas` не
> изменился. Итерация 4 также добавила событиям фото (V15); складчина фото имела
> и раньше — оба теперь видны thumbnail'ом в `ActivityCard` (`ActivityThumb`).
> Упоминания «новый 4-й таб `SkladchinaManageTab`» / «segmented control
> События | Сборы» / «таб Активности в manage» ниже — **исторический контекст**.
> Актуальная UX-картина — [`unified-activity-creation.md`](./unified-activity-creation.md).

> **Update (редизайн репутации складчины, locked design 2026-06-12):** веса и механика
> репутации пересмотрены — paid **+10** (без изменений), declined → **ledger-строки нет**
> (было −5), молчание до дедлайна → **−40** (было −25), досрочное закрытие → новый статус
> участника **`released`** (нейтрально, без строки). Тумблер получил гейты (только
> fixed-режимы, дедлайн ≥ 24 ч, ≤3 реп-сбора на клуб за 7 дней, иммутабелен) и UI-имя
> **«Важный сбор»**. Добавлены reminder-DM и DM о списании (launch-blocker для −40).
> Репутационный hook идёт через **ledger** (`appendAndRecompute`), не через удалённый
> `addReliabilityDelta`. Источник решений — `docs/backlog/skladchina-reputation-redesign.md`;
> упоминания старых весов в датированных hotfix-блоках ниже — исторические.

---

## Фаза A — упрощение ядра (2026-06-15)

> **Статус:** реализовано на `feature/skladchina-phase-a`. Источник направления —
> `docs/backlog/skladchina-2.0-roadmap.md` § «Фаза A». Принцип: **приложение —
> трекер, не платёжка** (деньги ходят офлайн). Деньги = декорация, **прогресс
> считаем в людях**. Эта секция **побеждает** более ранние формулировки ниже там,
> где они противоречат (markPaid-валидации, автозакрытие по цели).

Пять изменений + три продуктовых решения, согласованных с PO 2026-06-15.

### A-1. Один тап «Я оплатил» в fixed-режимах

Поле ввода суммы для `fixed_equal` / `fixed_individual` **убрано** — сервер и так
записывает назначенную долю (server-authoritative, фикс a2fb347), поле было
декоративным. Контракт `markPaid` ослаблен:

- `MarkPaidRequest.declaredAmountKopecks` теперь **nullable** (`Long?`, без `@NotNull`).
- `fixed_equal` / `fixed_individual`: тело можно слать пустым (`{}`) — сервер
  записывает `expected`-долю. Клиентское значение игнорируется (как и было).
- `voluntary`: `declaredAmountKopecks` **обязателен** (`null`/`≤0` → 400),
  cap `≤ 10 000 000` копеек сохраняется.

Фронт (`SkladchinaPage`): fixed → одна кнопка **«Я оплатил {доля} ₽»** без поля;
voluntary → поле суммы + «Я оплатил».

### A-2. Организатор отмечает оплату сам («получил наличкой»)

Новая возможность: **орг** может перевести участника `pending → paid` (и обратно)
из списка участников. Деньги видит только орг — это его право поправить чеклист.

**Решения PO 2026-06-15:**
- **Toggle**: орг может и отметить (`pending → paid`), и **снять** (`paid → pending`).
  Каждое действие фронт защищает диалогом подтверждения; снятие в «важном сборе»
  предупреждает, что участник снова попадает под риск −40 за молчание.
- **Только fixed-режимы** (`fixed_equal` / `fixed_individual`) — доля известна,
  отметка = один тап. Для `voluntary` орг-кнопок нет (участник отмечает сам;
  у voluntary нет назначенной суммы → нечего записать в один тап). Voluntary → 400.
- **Орг-отметка даёт +10** в «важном сборе» (орг ручается, что получил деньги).
  Тот же ledger-путь, что и самостоятельная отметка; фарм ограничен лимитом
  3 реп-сбора/нед/клуб (+30/нед потолок).

Запись `paid` = `expected`-доля участника (`declared = expected`, `paid_at = now`).
Снятие очищает `declared_amount` и `paid_at`, возвращает `pending`. До закрытия
`reputation_applied = false` у всех — toggle безопасен (ledger-строк ещё нет).

### A-3. «Не хватает X ₽» вместо автопересчёта — ❌ УДАЛЕНО (2026-06-17)

> Перераспределение недостачи (`POST /redistribute` + панель «Не хватает X ₽» +
> `setExpectedAmount`) **удалено полностью** по решению PO — фича себя не оправдала.
> Эндпоинт, сервисный метод, репозиторный `setExpectedAmount` и UI-панель убраны;
> тесты сняты. При отказе участника доли по-прежнему не пересчитываются автоматически,
> но и ручного перераспределения больше нет — орг закрывает сбор как есть.

### A-4. Убрать автозакрытие по достижению цели

`maybeAutoCloseAfterStateChange` больше **не закрывает сбор по `collected ≥ goal`**
(источник сложности F5-02). Закрытие осталось по трём путям: **дедлайн** (шедулер) ·
**вручную** (орг) · **все ответили** (нет `pending`). Цель и денежный прогресс-бар —
декорация. `computeFinalStatus` не тронут: финальный бейдж (`closed_success` /
`closed_failed` / `cancelled`) по-прежнему считается по деньгам — это витрина итога,
а не триггер закрытия.

### A-5. Прогресс в людях

Главная метрика на экране сбора и в карточке ленты — **«Скинулись N из M»**
(`paidCount` / `participantCount`), прогресс-бар по людям. Деньги
(`collected [/ goal]`) показываются вторичной приглушённой строкой. DTO уже
содержит `paidCount` / `participantCount` — изменения только во фронте.

### Новые API-эндпоинты (все — только creator сбора, owner-check в сервисе)

| Метод | Назначение | Ошибки |
|---|---|---|
| `POST /api/skladchinas/{id}/participants/{userId}/mark-paid` | Орг отмечает оплату участника (fixed-only). Записывает `expected`-долю. Идемпотентно при уже-`paid`. | 400 (voluntary / не active / участник не `pending`), 403 (не creator), 404, 409 (гонка с закрытием) |
| `POST /api/skladchinas/{id}/participants/{userId}/unmark` | Орг снимает отметку оплаты (`paid → pending`, fixed-only). Идемпотентно при уже-`pending`. **Авто-закрытие не триггерит** (снятие увеличивает `pending`). | 400 (voluntary / не active / участник не `paid`), 403, 404, 409 |

> `POST /redistribute` удалён (см. A-3 выше). Эндпоинты заявок на отказ
> (`request-decline` / `resolve-decline`) и `GET /api/events/{id}/skladchina` —
> см. § «Шаблоны».

`markPaid` (`POST /api/skladchinas/{id}/mark-paid`) — тело `declaredAmountKopecks`
теперь опционально (см. A-1).

### Что **не** менялось в Фазе A

- Схема БД (миграции нет): org-отметка переиспользует `paid`/`pending`, +10 идёт
  тем же ledger-путём — флаг «кто отметил» не нужен.
- Веса/механика репутации, гейты «важного сбора», статусы участника, каскады
  выхода/удаления клуба, шедулеры (auto-close по дедлайну, reminder-DM).
- `computeFinalStatus` и денежный success-порог 80% (теперь это только бейдж).

## Шаблоны (templates) + split_bill (2026-06-17)

Сборы получили слой **шаблонов** поверх одного движка. Полный дизайн-контракт —
`docs/backlog/skladchina-templates-architecture.md`; направление —
`docs/backlog/skladchina-2.0-roadmap.md`. Здесь — фактическое состояние в коде.

### Архитектура движка (рефактор по ответственности)

Бывший god-`SkladchinaService` разбит на 4 когезивных `@Service` (поведение сохранено):

| Сервис | Ответственность |
|---|---|
| `SkladchinaQueryService` | чтения: `getDetail`, `getClubActiveSkladchinas`, `findEventSplitState` |
| `SkladchinaCreationService` | создание + гейты «важного сбора» |
| `SkladchinaPaymentService` | действия участника/орга: mark-paid, decline, request/resolve-decline, org-mark/unmark |
| `SkladchinaLifecycleService` | закрытие + репутация-при-закрытии (close / auto-close / expire / release) |

Граф зависимостей без циклов: `Payment → Lifecycle → Query`, `Creation → Query`. Связка
`maybeAutoCloseAfterStateChange → closeInternal` оставлена внутри `Lifecycle` (один бин) —
участник-действие зовёт её в своей транзакции, `closeInternal` = self-invoke, поэтому
пойманная app-ошибка не метит транзакцию rollback-only (F5-18).

Шаблоны — **Strategy + Registry**: `SkladchinaTemplateStrategy` (поле `template` в
`skladchinas`, enum `custom|split_bill|gear|booking|birthday`) + `SkladchinaTemplateRegistry`
(Spring собирает все бины). Новый шаблон = новый `@Component`, движок не трогаем (Open/Closed).
**Слой-на-шаблон не вводим** (дублировал бы FSM ×5).

### split_bill — «Разделить счёт»

Единственный шаблон с **верифицированным якорем**: состав берётся из отмеченной организатором
**явки** события (`event_responses.attendance = attended`), а не свободного выбора. Поля:
`skladchinas.event_id` (FK, V27), `template = split_bill`.

- **Создание:** `POST /api/clubs/{clubId}/skladchinas` с `template=split_bill`, `eventId`,
  `totalGoalKopecks` (= чек), `paymentMode`. Состав = пришедшие активные члены клуба.
  Валидации: событие клуба, явка отмечена, событие ≤ 30 дней, ≥ 2 пришедших.
- **Два режима:** `fixed_equal` (чек ÷ пришедших, доля сервер-authoritative) или `voluntary`
  (каждый вводит свою сумму, чек = цель прогресс-бара). `fixed_individual` отклоняется (400).
- **«Исключить себя» (`excludeSelf`):** орг, отмеченный пришедшим, исключается из состава
  (доля делится на остальных, панель оплаты ему не показывается). Минимум 2 после исключения.
- **Один сбор на событие:** активный сбор блокирует создание второго (400; кнопка ведёт на него);
  **успешно закрытый** (`closed_success`) тоже блокирует (счёт уже собран). Провальный/отменённый
  не блокирует — можно собрать заново. `repo.findBlockingByEventId` + `GET /api/events/{id}/skladchina`
  (`EventSplitStateDto`) для кнопки на `EventPage`.
- **Всегда влияет на репутацию** (оба режима): verified-шаблон форсит `affects_reputation=true` и
  **минует гейты «важного сбора»** (rate-limit / 24h / voluntary-block) — якорь явки и есть анти-фарм.
  split исключён из rate-limit-счётчика (`countReputationAffectingCreatedSince`). Веса те же
  (оплата +10 / молчание до дедлайна −40), применяются при закрытии.
- **Дедлайн-пресет +48ч**, важность скрыта (форсится).
- **Фото/чек:** опциональное фото в форме (`PhotoAttach` → `/api/upload`), `photoUrl` показывается
  на странице сбора с открытием на весь экран + pinch-зум (`ImageLightbox`).

### Отказ-с-одобрением (V28/V29, только split_bill — `declinePolicy=REQUIRES_APPROVAL`)

Благо уже потреблено (ты был на событии) → мгновенный бесплатный `decline` запрещён (400).

- **Заявка:** `POST /api/skladchinas/{id}/request-decline` `{reason}` (причина обязательна).
  Участник остаётся `pending`, `decline_requested_at` ставится. При <48ч до дедлайна **дедлайн
  продлевается до now+48ч** (`extendDeadline`, только наружу) — оргу гарантировано окно на решение.
  Организатору уходит **DM с причиной + кнопкой** на сбор (`SkladchinaDeclineRequestedEvent`).
- **Решение орга:** `POST /api/skladchinas/{id}/participants/{userId}/resolve-decline`
  `{approve, rejectReason?}`. Одобрить → `declined` (нейтрально). **Отклонить → причина обязательна**
  (`decline_reject_note`, V29); участник остаётся `pending` (должен платить), повторная заявка
  закрыта; участнику уходит **DM с причиной + кнопкой** (`SkladchinaDeclineRejectedEvent`).
  Причина видна отклонённому на странице сбора (`myDeclineRejectNote`).
- Миграции: V28 (`decline_note`/`decline_requested_at`/`decline_rejected`), V29 (`decline_reject_note`).

### UI-заметки

- Режим `voluntary` называется **«Ваша сумма»** (бейдж + плейсхолдер поля).
- Последнему неоплатившему в voluntary-сборе в поле суммы подсказка **«Осталось закрыть X ₽»**
  (`pendingCount` в `SkladchinaDetailDto`).
- После оплаты — зелёная ✅ «Вы оплатили» + «+ к репутации (при закрытии)».
- Прогресс-бар: для `voluntary`+цель — по деньгам (`collected/goal`); иначе — по людям (A-5).

### Что отложено

- Шаблоны **gear / booking / birthday** — не реализованы (дизайн-контракт есть, паркуются).
- Колонка `reputation_ledger.verified` (метка проверенного исхода для «шага 2») **не добавлена** —
  split пишет обычную finance-репутацию. Вводить, если клуб-трек потребует различать verified.

## Post-staging hotfixes round 2 (2026-05-23 вечер)

После первого захода на staging пришёл второй пакет фидбека — все закрыты:

1. **WebApp кнопка в DM** теперь использует прямой frontend URL
   `https://<base>/skladchina/<id>` через `WebAppInfo`, не t.me/<bot>/...?startapp=.
   Причина: Telegram блокирует self-bot ссылки внутри DM с тем же ботом
   (циклическая ссылка). Параметр в `NotificationService.sendDm` называется
   `webAppPath` (готовый path с `/`), env var `TELEGRAM_WEBAPP_BASE_URL`
   контролирует base.
2. **`TELEGRAM_BOT_USERNAME` env var** — bot username вынесен из hardcode
   `clubs_v2_bot`. На staging → `clubs_v2_test_bot`, на prod default.
3. **Reputation: declined → −5** (было 0). По фидбеку «обязан был оплатить и
   отказался — снижаем». Применяется только при `affects_reputation = true`.
   *(Историческое: с 2026-06-12 declined снова бесплатен — ledger-строка не пишется,
   см. § «Reputation deltas».)*
4. **Auto-close при всех ответивших участниках** — `maybeAutoCloseAfterStateChange`
   срабатывает не только при goal-reached, но и когда 0 pending participants
   (все paid или declined). Закрывает voluntary-кейс «все обязанные ответили».
5. **Closed sklаdchinas в истории** — `findMyFeed` возвращает все involvement-
   сборы (не только active). Sort: active первыми (actionRequired → deadline),
   closed следом по `closed_at DESC`.
6. **Photo upload** — переиспользует существующий `POST /api/upload`
   endpoint (тот же что для аватарок клубов) через `AvatarUpload` компонент.
   Отдельный SkladchinaUploadController **не делается** (YAGNI).
7. **DM forматирование** — отступы перед строками исчезли. `buildString`
   вместо `"""trimIndent"""` (последний ломался на interpolated `$description`
   с собственными переводами строк).
8. **Создание сбора — отдельная route-страница**, не bottom-sheet Modal.
   `pages/CreateSkladchinaPage.tsx`, route `/clubs/:id/skladchina/new`.
   vaul Modal не справлялся с длинной формой на iOS Telegram WebView
   (partial snap, обрезание последних полей). Page даёт полный viewport
   и нативную back-кнопку.
9. **`@EnableAsync` + `@TransactionalEventListener`** — `SkladchinaService`
   публикует `SkladchinaCreatedEvent`/`SkladchinaClosedEvent`, бот-listener
   ловит и шлёт DM после commit'а транзакции (тот же паттерн что
   `PaymentNotificationHandler`).
10. **UI fixes**: фото на странице сбора 120×120 слева (не full-width),
    клуб-карточка сверху SkladchinaPage кликабельная, бейдж «⚠️ Репутация»
    на карточке/странице, organizer может быть в списке участников сбора,
    iOS zoom фикс через font-size: 16px на input/textarea внутри страницы
    создания.

## Deploy notes (важно для staging)

В Coolify staging env vars нужны:
```
TELEGRAM_BOT_USERNAME=clubs_v2_test_bot
TELEGRAM_WEBAPP_BASE_URL=https://staging.77-42-23-177.sslip.io
```
Для prod env vars можно не задавать — default из `application.yml`:
```
telegram.bot-username = clubs_v2_bot
telegram.webapp-base-url = https://77-42-23-177.sslip.io
```

---

## Цель

Дать организаторам клубов **инструмент сбора денег** на клубные нужды
(бронь зала, инвентарь, разделение счёта после события, общий подарок)
**без выхода в сторонний чат**. Сейчас этот сценарий живёт в TG-чате:
admin кидает реквизиты → бегает за каждым → ведёт учёт в голове или
таблице. Главная боль — pre-event сбор (надо собрать **ДО** действия,
иначе бронь сорвётся).

Дополнительно даём **сигнал ответственности**: тот кто систематически
«забывает» оплатить — теряет репутацию в клубе (через существующий
`reliability_index`). Это создаёт цену слова.

## Product rationale (PO-gate, 2026-05-18)

- **Какую проблему решаем:** учёт оплат и pre-event сбор сейчас вне
  приложения, ручной, ненадёжный.
- **Для кого:** organizer создаёт, members оплачивают, видят свой
  и общий статус.
- **Почему сейчас:** прямой запрос от друзей-пользователей (vs старый
  trigger «5+ клубов в проде» — пересматриваем под user-pull).
- **Альтернативы:** оставить в TG-чате (текущее), интегрировать
  Telegram Stars для P2P (Stars не поддерживают P2P, не подходит),
  стороннее ПО (теряем встроенность). Складчина выбрана как
  компромисс: appставит learning workflow, не платёжная система.
- **Observable success:** organizer создаёт ≥1 сбор/месяц,
  ≥70% указанных участников нажимают «оплатил» в срок, friends
  reports «удобнее чем чат».

## Post-staging hotfixes (2026-05-23) — корректировки по результатам теста

После прогона на staging пользователь дал 10 пунктов фидбека. Реализованы в этом
же PR. Спека ниже обновлена в местах изменения логики, в этом блоке — итог.

1. **`@EnableAsync`** добавлен в `ClubsApplication` — без него `@Async` на
   `SkladchinaBotNotifier.sendCreated/sendClosed` (и существующие в
   `NotificationService.send*`) **молча игнорировался**. Это правильный фикс
   причины «DM не пришли»; параллельно ловит ту же проблему для events DM.
2. **Reputation `declined → −5`** (вместо 0). Явный отказ от обязательного
   сбора теперь штрафуется. Пользователь пересмотрел: «если человек обязан
   оплатить и отказывается — снижать репутацию». Применяется только при
   `affects_reputation = true`. См. § «Reputation deltas».
   *(Историческое: решение пересмотрено 2026-06-12 — отказ снова бесплатен,
   ledger-строка не эмитится; актуальная таблица в § «Reputation deltas».)*
3. **Auto-close расширен**: закрытие триггерится не только при goal reached,
   но и при **отсутствии pending participants** (все ответили: paid или declined).
   Это закрывает кейс voluntary-режима + общий случай «все обязанные оплатили».
4. **Closed sklаdchinas в истории**: `findMyFeed` больше не фильтрует по
   `status = active`. Возвращает все involvement-сборы; sort: active первыми
   (actionRequired → deadline ASC), closed следом (closed_at DESC). На фронте —
   третья секция «История».
5. **Organizer может быть участником своего сбора** — фильтр на frontend убран.
6. **`affectsReputation` в `MySkladchinaListItemDto`** — для бейджа «Репутация»
   на карточке.
7. **UX-фиксы (frontend):**
   - `<input>`/`<textarea>` глобально `font-size: 16px` — фикс iOS-zoom при тапе
   - Modal `z-index: 1000 !important` — фикс «модалка уходит за основное окно» на desktop Telegram
   - SkladchinaPage: clickable **club-card сверху** с avatar+name+chevron (вместо текст-линка)
   - SkladchinaPage: `useBackButton(true)` — нативная back-кнопка теперь есть
   - SkladchinaCard + SkladchinaPage: бейдж «⚠️ Репутация» когда affects_reputation
   - SkladchinaCard в истории: muted-стиль (`opacity: 0.72`), badge показывает финальный статус сбора (Завершён / Не собран / Отменён) вместо личного myStatus

## Post-flight deltas (2026-05-23) — отличия реализации от исходной спеки

При имплементации MVP выявились упрощения. Зафиксированы здесь, чтобы будущие
читатели спеки не искали несуществующие компоненты.

1. **Photo upload отложен — поле `photoUrl` через URL input.** В спеке был
   `SkladchinaUploadController` с multipart upload. В реализации фронт принимает
   готовый URL текстом (Telegram-storage hosting откладываем до момента когда
   реально понадобится). Backend хранит `photo_url VARCHAR(500)` как и было.
2. **`CreateSkladchinaModal` — single-form**, не 4-step wizard как в спеке.
   Все поля в одной форме (с группами и условным показом total goal для
   `fixed_equal`). Wizard добавим если форма станет переполнена. Сейчас ~13 полей
   в одном scroll'е — приемлемо.
3. **«Сборы» в `OrganizerClubManage` — отдельный TAB**, не секция секцией.
   В спеке было сказано «отдельная секция с listing + кнопкой» — но Organizer-страница
   использует таб-навигацию (Members / Applications / Events / ... / Settings),
   и втиснуть Сборы как inline-секцию ломало бы паттерн. Сделано новым 4-м табом
   `SkladchinaManageTab` рядом с Events.
4. **API: `GET /api/clubs/{clubId}/skladchinas/active`** — endpoint добавлен
   (был неявно в спеке через OrganizerClubManage flow). Отдаёт `List<MySkladchinaListItemDto>`
   ограниченный active-статусом, доступен только `@RequiresOrganizer`.
5. **`UserRepository.findTelegramIds(userIds)`** — добавлен batch-метод для
   notification рассылки (вместо N запросов). Не упомянут в спеке, но полезен
   для перформанса.
6. **Frontend tab «Активности»** — содержит **segmented control «События | Сборы»**
   как и планировалось. URL'ы `/activities`, `/events`, `/skladchina` все ведут
   на `ActivitiesPage` с pre-selected сегментом (deep-link friendly из бота).
7. **`BottomTabBar.isTabBarRoute`** — регекс расширен на `/(clubs|events|skladchina)/:id`
   плюс sub-segments `/events` и `/skladchina` подсвечивают таб «Активности».

## Decisions from pre-flight (2026-05-18)

Зафиксированные продуктовые решения после обсуждения с владельцем
продукта. Где первоначальное обсуждение давало два варианта —
выбранный отмечен «✓», отвергнутый «✗ (причина)».

1. **Verification модель (как исключаем фейковые «оплатил»):**
   ✓ **Honor system** — user просто нажимает «оплатил», без скрина
   чека, без admin-confirm. Просто и быстро. Доработаем при первой
   проблеме (см. R-1).
   ✗ Скрин чека / admin verify — избыточно для v1, добавит трение.

2. **Видимость сумм для organizer'а:**
   ✓ Organizer **всегда** видит **сумму, которую каждый user указал
   при «оплатил»** — даже в fixed-режимах, для сверки с банковской
   выпиской. Это критично: если user перевёл не ту сумму (опечатка
   или хитрость) — organizer должен это видеть.

3. **Где живёт в навигации:**
   ✓ **Только в табе «Активности»** — добавляем segmented control
   сверху страницы «События | Сборы». Aggregated feed по всем клубам
   user'а. Внутри ClubPage **отдельного таба сборов нет** — оставляем
   ClubPage без расширения, чтобы не разбавлять unified-структуру.
   Organizer создаёт сбор из `OrganizerClubManage` (страница
   управления клубом).
   ✗ Таб внутри ClubPage — отвергнуто как раздувание club-page.

4. **Reputation hook:**
   ✓ **Опциональный**, чекбокс при создании сбора (default = выкл).
   Так фича не токсична для casual (подарок), но включается для
   критичных (бронь).
   ✗ Всегда обязательный — токсично для voluntary случаев.
   ✗ Никогда — теряем сигнал ответственности.

   **Гейты тумблера (locked design 2026-06-12,
   `docs/backlog/skladchina-reputation-redesign.md`):**
   - UI-имя — **«Важный сбор»** (чекбокс и бейдж; бывш. «⚠️ Репутация»).
     Хелпер чекбокса = однофразовый прайс: «оплата +10, отказ — без
     штрафа, молчание до дедлайна −40».
   - Доступен **только для fixed-режимов** (`fixed_equal` /
     `fixed_individual`). «Добровольный со штрафом за молчание» —
     оксюморон; для voluntary чекбокс задизейблен с подписью
     «Добровольный сбор не влияет на репутацию».
   - **Дедлайн ≥ 24 ч** от создания (анти-«сбор-засада»; 48 ч
     отклонено — ломает юзкейс «бронь на завтра»).
   - **Rate-limit: ≤ 3 реп-сбора на клуб за скользящие 7 дней** —
     валидация на создании (400), чекбокс дизейблится с объяснением.
     Единственный реальный анти-фарм и анти-грифинг механизм: потолок
     фарма +30/нед/клуб, потолок грифинга −120/нед при двух
     игнорируемых DM.
   - **Иммутабелен после создания** (включение задним числом =
     ретроактивная мобилизация). Ошибся — закрой досрочно: pending →
     `released` без минусов, paid получают +10.
   - Блок для pending на `SkladchinaPage`: «Это важный сбор. Оплатите
     или откажитесь до {дата}: молчание снизит репутацию на 40».
     Плашка для `released`: «Сбор закрыли досрочно — ваш ответ не
     потребовался. Репутация не изменилась».

5. **Closure (завершение сбора):**
   ✓ Сбор закрывается при **первом из** условий:
   - `collected_amount ≥ total_goal` (для fixed-режимов) — success
   - `deadline` истёк — закрытие с current статусом
   - Admin вручную закрыл (кнопка «Закрыть сбор»)
   При закрытии: бот шлёт DM organizer'у с итогом, reputation-hook
   применяется (если был включён). Судьба pending-участников —
   **предикат по времени закрытия** (решение F5-02, 2026-06-12):
   - `closed_at ≥ deadline` → pending → `expired_no_response`
     (молчание до дедлайна, −40 при «важном сборе»);
   - `closed_at < deadline` (досрочно: цель достигнута / все ответили /
     ручное закрытие) → pending → **`released`** (нейтрально, без
     ledger-строки). Обещание — «ответить к дедлайну»; дедлайн не
     наступил → нарушения нет.
   Прежняя формулировка «pending → expired при любом закрытии»
   противоречила глоссарию статусов (:424) — **глоссарий победил**.

6. **Три payment modes** (новое — детализация от user'а):
   - `fixed_equal` — общая сумма / N (все участники платят поровну)
   - `fixed_individual` — admin задаёт сумму на каждого индивидуально
   - `voluntary` — без фиксированной суммы, user сам решает сколько

   В `fixed_*` режимах user видит **prefilled readonly** сумму на
   странице сбора. В `voluntary` user **сам вводит** сумму при
   «оплатил». Organizer всегда видит итоговую заявленную сумму
   (см. Decision 2).

---

## Scope

### Входит (MVP)

**Backend:**
- 2 новые таблицы: `skladchinas`, `skladchina_participants`
- Domain: `Skladchina`, `SkladchinaParticipant`
- `SkladchinaService` — бизнес-логика create / mark-paid / decline / close
- `SkladchinaRepository` (interface + JooqImpl)
- `UserSkladchinasService.getMySkladchinas` — aggregated feed по
  всем клубам user'а
- `NotificationService.sendSkladchinaCreated()` — DM участникам
- `NotificationService.sendSkladchinaClosed()` — DM organizer'у
- ~~`ReputationService.addReliabilityDelta(userId, clubId, amount, reason)`~~
  — *(историческое: метод удалён в reputation-v2 P1a; актуальный путь —
  finance ledger-строки через `appendAndRecompute`, см. § «Reputation hook»)*
- Flyway миграция V14: 2 таблицы + enum'ы + индексы
- REST endpoints (см. § «API контракты»)

**Frontend:**
- `EventsPage` → переименовать таб в просто хранить, добавить
  segmented control сверху: «События | Сборы»
- Новая страница `SkladchinaPage` (`/skladchinas/:id`) — детали сбора:
  у member один layout, у organizer — расширенный (список участников
  с их статусами и суммами)
- `CreateSkladchinaModal` — форма создания (organizer'ом)
- `components/feed/SkladchinaCard.tsx` — карточка в ленте «Сборы»
- В `OrganizerClubManage` — кнопка «Создать сбор» рядом с «Создать
  событие»
- Query hooks: `useMySkladchinasQuery`, `useSkladchinaQuery`,
  `useCreateSkladchinaMutation`, `useMarkPaidMutation`,
  `useDeclineSkladchinaMutation`, `useCloseSkladchinaMutation`

### НЕ входит (v2 / отдельные PR)

- **Скрин чека / admin verify** — Decision 1, oneway honor system в v1
- **Push-нотификации reminder за 24h до deadline** — v2 (требует
  scheduler аналог `Stage2Service.triggerStage2ForReadyEvents`)
- **Standardized payment links** (Tinkoff/Sber/Альфа deep-link
  шаблоны) — v1 свободное текстовое поле, как в backlog R-2
- **Складчина-creation из под-меню клуба внутри ClubPage** —
  только из OrganizerClubManage в v1
- **Multi-currency** — все суммы в рублях, integer копеек
- **Историческая лента закрытых сборов** — v1 показываем только
  активные; закрытые видны 7 дней после закрытия (для recon),
  потом архивируются (показать в profile позже)
- **Edit-ability** — после создания сбор нельзя править. Только
  закрыть и создать новый. (См. R-3)
- **Привязка к событию** — сбор stand-alone, не связан с конкретным
  event'ом. Связка через описание/название.
- **Recovery: что если organizer покинул клуб посреди сбора** —
  edge case, оставляем для v2; для MVP сборы закрываются автоматом
  если organizer теряет права.

---

## User Stories

### US-1 (Critical, organizer): Создаю сбор
**Как** organizer клуба
**Я хочу** создать сбор с описанием, суммой, дедлайном, списком
участников и ссылкой на оплату
**Чтобы** систематизировать сбор денег и переложить рассылку на бота

### US-2 (Critical, member): Узнаю о сборе через бота
**Как** member клуба, выбранный в сбор
**Я хочу** получить DM от бота со ссылкой в приложение
**Чтобы** перейти на страницу сбора одним тапом

### US-3 (Critical, member): Оплачиваю и отмечаю в приложении
**Как** member клуба
**Я хочу** перейти по платёжной ссылке в банк, перевести деньги,
вернуться в приложение и нажать «оплатил» с указанием суммы
**Чтобы** organizer и другие участники видели мой статус

### US-4 (Critical, member): Отказываюсь
**Как** member клуба
**Я хочу** явно отказаться от участия в сборе (если режим не
mandatory, или просто чтобы organizer знал что не жди)
**Чтобы** не ловить repuации-минус за молчание

### US-5 (Important, organizer): Вижу прогресс
**Как** organizer
**Я хочу** видеть progress-bar (% от goal) + список участников
с их статусами и **заявленными суммами**
**Чтобы** сверять с банковской выпиской и понимать когда хватит

### US-6 (Important, member): Вижу свои сборы агрегированно
**Как** member нескольких клубов
**Я хочу** видеть все активные сборы из всех клубов в одной
ленте (таб «Активности» → секция «Сборы»)
**Чтобы** не пропустить и не лазить по клубам

### US-7 (Important, organizer): Закрываю сбор
**Как** organizer
**Я хочу** вручную закрыть сбор (например, набралось достаточно
до дедлайна; или передумали что-то покупать — отменяем)
**Чтобы** не висели зомби-сборы; reputation-hook применяется
в момент закрытия

### US-8 (Edge, all): Сбор завершился — все получают сигнал
**Как** organizer
**Я хочу** получить DM от бота с итогом сбора (собрано X из Y,
оплатили N из M)
**Чтобы** понимать результат не открывая приложение

---

## Модель данных

### Таблица `skladchinas`

```sql
CREATE TABLE skladchinas (
    id                  UUID PRIMARY KEY,
    club_id             UUID NOT NULL REFERENCES clubs(id),
    creator_id          UUID NOT NULL REFERENCES users(id),

    title               VARCHAR(255) NOT NULL,
    description         TEXT,
    rules               TEXT,                   -- отдельное поле "правила", опц.
    photo_url           VARCHAR(500),           -- фото-вложение (чек / референс), опц.

    payment_mode        skladchina_mode NOT NULL,
                        -- enum: fixed_equal, fixed_individual, voluntary

    total_goal_kopecks  BIGINT,                 -- nullable: для voluntary опционально (индикативная цель)
                        -- для fixed_equal: required. для fixed_individual: sum of participant amounts
    payment_link        TEXT NOT NULL,          -- ссылка на банк/СБП
    payment_method_note TEXT,                   -- "Тинькофф / Сбер /..." свободный текст

    deadline            TIMESTAMPTZ NOT NULL,
    affects_reputation  BOOLEAN NOT NULL DEFAULT false,

    reminder_sent_at    TIMESTAMPTZ,            -- когда отправлен reminder-DM pending-участникам
                                                -- (за 24ч до deadline); NULL = ещё не слался.
                                                -- Дедуп-маркер шедулера (добавлено 2026-06-12)

    status              skladchina_status NOT NULL DEFAULT 'active',
                        -- enum: active, closed_success, closed_failed, cancelled

    closed_at           TIMESTAMPTZ,
    closed_by           UUID REFERENCES users(id),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_skladchinas_club_id ON skladchinas(club_id);
CREATE INDEX idx_skladchinas_status_deadline ON skladchinas(status, deadline);
```

**`payment_mode` enum:**
- `fixed_equal` — `total_goal_kopecks` обязателен; каждому participant
  записывается `expected_amount = total_goal / N` (round для остатка
  — последнему берёт +diff)
- `fixed_individual` — admin задаёт `expected_amount` каждому
  участнику в форме; `total_goal_kopecks = sum(expected_amounts)`
- `voluntary` — `total_goal_kopecks` опционален (индикативная цель для прогресс-бара,
  добавлено 2026-06-12 по staging-фидбеку; при наличии работает по общим правилам
  закрытия/статусов), у participants
  `expected_amount = NULL`, user указывает сам при «оплатил»

**`status` enum:**
- `active` — сбор идёт
- `closed_success` — `collected_amount ≥ total_goal` (для fixed-режимов)
  или `voluntary` достигла deadline'а с хотя бы 1 платежом
- `closed_failed` — deadline истёк, не набрали 80% от total_goal
  (для fixed-режимов); voluntary без платежей
- `cancelled` — admin вручную закрыл до результата

### Таблица `skladchina_participants`

```sql
CREATE TABLE skladchina_participants (
    skladchina_id     UUID NOT NULL REFERENCES skladchinas(id) ON DELETE CASCADE,
    user_id           UUID NOT NULL REFERENCES users(id),

    expected_amount_kopecks   BIGINT,
                              -- для fixed_*: required (admin задал или равная доля)
                              -- для voluntary: NULL (user сам решает)

    declared_amount_kopecks   BIGINT,
                              -- что user указал при «оплатил»; NULL пока не оплатил

    status            skladchina_participant_status NOT NULL DEFAULT 'pending',
                      -- enum: pending, paid, declined, expired_no_response, released
                      -- ('released' добавлен 2026-06-12: ALTER TYPE ... ADD VALUE)

    paid_at           TIMESTAMPTZ,
    declined_at       TIMESTAMPTZ,

    reputation_applied BOOLEAN NOT NULL DEFAULT false,
                      -- идемпотентность hook'а: ставится TRUE после применения
                      -- delta к reliability_index, не позволяет double-apply

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (skladchina_id, user_id)
);

CREATE INDEX idx_skladchina_participants_user_id ON skladchina_participants(user_id);
```

**`status` enum (глоссарий — побеждает при конфликте с прозой):**
- `pending` — выбран admin'ом, ещё не действовал
- `paid` — user нажал «оплатил» (с указанием суммы)
- `declined` — user явно отказался
- `expired_no_response` — **deadline истёк И статус был `pending`**
  (`closed_at ≥ deadline`) → переходит в `expired_no_response` при closure
- `released` — ответ участника не потребовался, **репутация не затронута**
  (ledger-строки нет). Достижимо двумя путями: (1) **сбор закрыт досрочно**
  (`closed_at < deadline`: цель достигнута / все остальные ответили / ручное
  закрытие); (2) **клуб удалён** при `active`-сборе (каскад
  `cancelActiveByClub`, минуя `closeInternal` — см. § «Удаление клуба»)

### Reputation deltas (если `affects_reputation = true`)

> Веса — решение 2026-06-12 (`docs/backlog/skladchina-reputation-redesign.md`).
> Эмиссия — finance ledger-строки в момент **closure** (`closeInternal`),
> `occurred_at = closed_at`; механика — § «Reputation hook» ниже.

| Status участника | Ledger kind | Points |
|---|---|---|
| `paid` | `skladchina_paid` | **+10** |
| `declined` | — **строки НЕТ** (kind не эмитится) | 0 |
| `expired_no_response` | `skladchina_expired` | **−40** |
| `released` | — **строки НЕТ** | 0 |

- **`declined` без строки, а не 0-строкой:** отказ — желаемое поведение
  («не можешь — скажи сразу») и бесплатный выход из карательного сбора.
  0-строка инфлировала бы `outcome_count` (три отказа в один тап = выход
  из «Новичка»). Kind `skladchina_declined` остаётся в enum (исторические
  −5-строки на staging), но новых строк не получает:
  `ReputationPolicy.financeKind(declined) → null`. Статус сохраняется в
  `skladchina_participants` — кандидат на черту «доля отказов» (P1b).
- **`released` без строки:** досрочное закрытие нейтрально — обещание
  («ответить к дедлайну») не нарушено.

**Обоснование величин** (относительно актуальных event-весов
**+100 ironclad / −200 no_show**, см. `reputation-v2.md`):
- **+10** = 1/10 от ironclad (+100): явку верифицирует организатор,
  оплата — самодекларация; плюс символический до верификации (P2).
- **−40** = 1/5 от no_show (−200): вред сопоставим (сорванная бронь),
  но обязательство навязано организатором — участник не нажимал
  «подтверждаю», как на этапе-2 событий. Break-even ≈ 80% оплат —
  чуть выше success-метрики «≥70% платят в срок».

Бинарная модель оплаты: либо оплатил (с любой declared суммой), либо нет.
Сумма `declared_amount` сохраняется для recon-а организатора, но не
влияет на points — частичную оплату не штрафуем (honor system и так
позволяет фейк, а доплачивать руками после клика «оплатил» неудобно).
В fixed-режимах сервер записывает назначенную `expected`-долю сам,
игнорируя клиентское значение (см. § API), так что расхождение возможно
только в voluntary.

`reputation_applied = TRUE` после успешного применения — skladchina-side
guard от double-application; структурный backstop — UNIQUE-ключ ledger
`(user_id, source_type, source_id)`.

---

## API контракты

### `POST /api/clubs/{clubId}/skladchinas` — создать сбор

Только organizer клуба (`@RequiresOrganizer`).

**Body:**
```ts
{
  title: string,                          // 1-255
  description: string | null,
  rules: string | null,
  photoUrl: string | null,                // URL загруженного фото (через AvatarUpload pattern)
  paymentMode: 'fixed_equal' | 'fixed_individual' | 'voluntary',
  totalGoalKopecks: number | null,        // required для fixed_equal; optional для voluntary (цель); null для fixed_individual (считается из долей)
  paymentLink: string,                    // required, max 1000
  paymentMethodNote: string | null,       // "Тинькофф" и т.п.
  deadline: string,                       // ISO 8601, must be > now + 1h
  affectsReputation: boolean,             // default false
  participants: Array<{
    userId: string,
    expectedAmountKopecks: number | null  // required для fixed_individual,
                                          // ignored для fixed_equal (берём total/N),
                                          // null для voluntary
  }>
}
```

**Response 201:** `SkladchinaDetailDto` (см. ниже)

**Errors:**
- `400` — payment_mode / amounts / participants невалидны (см. валидации)
- `403` — не organizer клуба, или хотя бы один participant.userId не active member клуба
- `404` — клуб не существует / soft-deleted

**Валидации:**
- `participants.length ≥ 1` И `≤ club.member_count`
- Все participant.userId должны быть active members этого клуба (MembershipStatus.active)
- `fixed_equal`: `totalGoalKopecks > 0` обязателен
- `fixed_individual`: каждому participant.expectedAmountKopecks > 0; `total = sum(amounts)` вычисляется
- `voluntary`: `totalGoalKopecks` опционален; если задан — должен быть > 0 (400 иначе)
- `deadline > now + 1h` И `deadline < now + 90 дней`

**Гейты `affectsReputation = true`** («Важный сбор», 2026-06-12; все нарушения → 400):
- `paymentMode` — только `fixed_equal` / `fixed_individual` (для `voluntary` → 400)
- `deadline ≥ now + 24h`
- Rate-limit: в клубе **≤ 3 реп-сбора за скользящие 7 дней** (по `created_at`
  сборов с `affects_reputation = true`)
- Флаг **иммутабелен после создания** (edit-эндпоинта нет — R-3; гейт фиксирует,
  что иммутабельность сохраняется и при появлении editing в v2)

### `GET /api/skladchinas/{id}` — детали сбора

Только active members клуба-владельца ИЛИ creator (organizer).
Используем существующий `@RequiresMembership`-style check.

**Response 200:** `SkladchinaDetailDto`:
```ts
{
  id: string,
  clubId: string,
  clubName: string,
  clubAvatarUrl: string | null,
  creatorId: string,
  title: string,
  description: string | null,
  rules: string | null,
  photoUrl: string | null,
  paymentMode: 'fixed_equal' | 'fixed_individual' | 'voluntary',
  totalGoalKopecks: number | null,
  collectedKopecks: number,               // sum(declared_amount) для status=paid
  paymentLink: string,
  paymentMethodNote: string | null,
  deadline: string,
  affectsReputation: boolean,
  status: 'active' | 'closed_success' | 'closed_failed' | 'cancelled',
  closedAt: string | null,
  isOrganizerView: boolean,               // computed: caller == creator?
  myStatus: 'pending' | 'paid' | 'declined' | 'expired_no_response' | 'released' | null,
  myExpectedAmountKopecks: number | null, // что caller должен заплатить
  myDeclaredAmountKopecks: number | null, // что caller уже заявил
  participants: Array<{                   // ВКЛЮЧАЕТСЯ ТОЛЬКО для organizer (isOrganizerView=true)
    userId: string,
    firstName: string,
    lastName: string | null,
    avatarUrl: string | null,
    expectedAmountKopecks: number | null,
    declaredAmountKopecks: number | null,
    status: 'pending' | 'paid' | 'declined' | 'expired_no_response' | 'released',
    paidAt: string | null
  }> | null,
  participantCount: number,               // для member-view: общее число участников
  paidCount: number                       // для member-view: сколько уже оплатили
}
```

**Errors:**
- `403` — не member клуба и не creator
- `404` — сбор не существует

### `GET /api/users/me/skladchinas` — мои сборы (aggregated feed)

Активные сборы где caller — participant ИЛИ creator, из всех клубов
где caller — active member. Аналог `/api/users/me/events`.

**Query params:** `page` (default 0), `size` (default 20, max 50)

**Response 200:** `PageResponse<MySkladchinaListItemDto>`:
```ts
{
  id: string,
  title: string,
  clubId: string,
  clubName: string,
  clubAvatarUrl: string | null,
  paymentMode: string,
  totalGoalKopecks: number | null,
  collectedKopecks: number,
  participantCount: number,
  paidCount: number,
  deadline: string,
  status: string,
  isOrganizerView: boolean,               // caller == creator
  myStatus: 'pending' | 'paid' | 'declined' | 'expired_no_response' | 'released' | null,
  actionRequired: boolean                 // myStatus=pending && status=active
}
```

Сортировка: `actionRequired DESC, deadline ASC`.

### `GET /api/users/me/skladchinas/action-required-count` — счётчик неоплаченных (NEW, 2026-05-30)

Числовой сигнал для бейджа на табе «Сборы» и точки на нижнем табе «Активности». Один лёгкий `COUNT`-запрос, та же логика что у `actionRequired` в ленте:

```sql
SELECT COUNT(*) FROM skladchina_participants sp
  JOIN skladchinas s ON s.id = sp.skladchina_id
  JOIN clubs       c ON c.id = s.club_id
 WHERE sp.user_id = :userId
   AND sp.status  = 'pending'
   AND s.status   = 'active'
   AND c.is_active = true
```

**Response 200:** `ActionRequiredCountDto`
```ts
{ count: number }
```

**Frontend (см. `feature/profile-reputation-and-skladchina-badge`):**
- хук `useSkladchinaActionRequiredCountQuery()` — `useQuery`, `staleTime: 60_000`, `select: (data) => data.count`. Инвалидируется в `useMarkPaidMutation` / `useDeclineSkladchinaMutation` / `useCreateSkladchinaMutation`; `useCloseSkladchinaMutation` цепляет его через prefix-match по `queryKeys.skladchinas.all`.
- **Бейдж на сегменте «Сборы»** в `ActivitiesPage` — латунная пилюля с числом (`.activities-segments .segment .seg-badge`), видна когда `unpaidCount > 0`.
- **Точка на нижнем табе «Активности»** в `BottomTabBar` — латунный кружок с свечением (`.brand-tabbar .tab .tab-dot`). Sibling от `.ico`, не child — иначе при `desaturate + opacity 0.62` на неактивном табе точка тускнеет вместе с иконкой.

### `POST /api/skladchinas/{id}/mark-paid` — отметить «оплатил»

Только participant сбора (active member клуба).

**Body:**
```ts
{
  declaredAmountKopecks: number           // required, > 0
                                          // для fixed_*: ДОЛЖЕН == expected (иначе 400, см. валидации)
                                          // для voluntary: что user указал (cap 10_000_000 копеек)
}
```

**Response 200:** обновлённый `SkladchinaDetailDto`

**Валидации (2026-06-12):**
- `fixed_equal` / `fixed_individual`: **сервер записывает назначенную долю
  (server-authoritative)**, клиентское `declaredAmountKopecks` игнорируется.
  UI округляет копейки до рублей (33333 → «333»), поэтому строгая проверка
  равенства резала бы честные оплаты; авторитетность сервера так же убивает
  усилитель F5-02 (заявить ≥ goal нельзя) и «нарисованный» collected.
- `voluntary`: sanity-cap **`declared ≤ 10_000_000` копеек (100 000 ₽)** —
  гигиена статистики, иначе 400.
- Переход только из `pending`: UPDATE с предикатом `WHERE status='pending'`
  + проверка rows-affected (F5-03) — оплата не перетирает уже выставленный
  `expired_no_response`/`released` при гонке с закрытием.

**Errors:**
- `400` — declaredAmount ≤ 0; `declared != expected` (fixed); cap превышен (voluntary); сбор уже не active (status != 'active')
- `403` — caller не participant этого сбора
- `409` — caller уже отметил `paid` ранее (idempotent: вернёт текущий DTO без изменений); либо статус участника уже не `pending` (гонка с закрытием, rows-affected = 0)

### `POST /api/skladchinas/{id}/decline` — отказаться

Только participant сбора. Отказ **всегда бесплатен** для репутации
(ledger-строки нет — см. § Reputation deltas). Переход только из `pending`
(`WHERE status='pending'` + rows-affected, F5-03 — симметрично mark-paid).

**Response 200:** обновлённый `SkladchinaDetailDto`

**Errors:**
- `400` — уже `declined` или `paid`; status != 'active'
- `403` — не participant
- `409` — статус участника уже не `pending` (гонка с закрытием, rows-affected = 0)

### `POST /api/skladchinas/{id}/close` — закрыть сбор вручную

Только creator (`@RequiresOrganizer`-like — owner-check внутри
service'а).

**Response 200:** финальный `SkladchinaDetailDto` со status = `cancelled`
(если closed manually, без достижения goal) или `closed_success`
(если manually закрыт при достигнутом goal).

**Side-effects (предикат — решение F5-02, 2026-06-12):**
- `pending` → **`closed_at ≥ deadline ? expired_no_response : released`**.
  Досрочное закрытие (`closed_at < deadline`) нейтрально: `released`,
  без ledger-строк.
- Reputation hooks применяются если `affects_reputation = true`
  (ledger, см. § Reputation hook); `released` строк не получает.
- DM organizer'у с итогом
- DM каждому `expired_no_response`-участнику о списании −40
  (только «важный сбор»; см. § Bot integration)
- **Атомарный клейм закрытия** (F5-12): `UPDATE skladchinas SET ...
  WHERE id = :id AND status = 'active'` + rows-affected — конкурентный
  `closeInternal` (scheduler × auto-close × ручное) не даёт дублей
  `SkladchinaClosedEvent`/DM и недетерминированного статуса.

### `POST /api/skladchinas/{id}/auto-close` — internal (scheduler)

Не endpoint, а scheduled job `SkladchinaScheduler` (по аналогии
с `Stage2Service.triggerStage2ForReadyEvents`):
- `@Scheduled(fixedDelay = 600_000)` — каждые 10 мин
- Находит `status='active' AND deadline < now()`
- Закрывает каждый через тот же service-метод что и manual close
  (закрытие по deadline ⟹ `closed_at ≥ deadline` ⟹ pending → expired)
- Reputation, DM — те же side-effects
- Окно «оплата после дедлайна, до тика шедулера» (~10 мин): сбор ещё
  `active` → `markPaid` валиден (+10). Осознанное поведение — деньги
  важнее формального дедлайна (решение 2026-06-12).
- **F5-18:** `closeInternal` в auto-close пути (`maybeAutoCloseAfterStateChange`
  из markPaid/decline) обёрнут в try/catch + `log.error` — падение
  закрытия/реп-хука не откатывает действие пользователя.

### Удаление клуба — каскадная отмена складчин (2026-06-13)

При soft-delete клуба (`ClubService.deleteClub` → `DELETE /api/clubs/{id}`)
все его **`active`-складчины каскадно отменяются**, чтобы шедулеры не
обрабатывали зомби-сборы удалённого клуба и их страницы не упирались в
скрытый клуб. Каскад живёт в `SkladchinaRepository.cancelActiveByClub(clubId)`
и **намеренно минует `SkladchinaService.closeInternal`**:

- `active`-складчины клуба → **`cancelled`** (`updated_at = now()`).
  Уже закрытые/отменённые (`closed_success`/`closed_failed`/`cancelled`)
  не трогаются.
- их `pending`-участники → **`released`** (нейтральный терминальный статус,
  тот же, что при досрочном закрытии). **НЕ `expired_no_response`** — удаление
  клуба не должно штрафовать за «молчание»: дедлайн ещё не наступил, обещание
  «ответить к дедлайну» не нарушено. `released` ⇒ `ReputationPolicy.financeKind`
  возвращает `null` ⇒ **ledger-строки нет**.
- `paid`/`declined`/уже-`expired_no_response` участники не трогаются.

**Почему минуя `closeInternal`** — это требование «удаление клуба не трогает
репутацию» (продуктовое решение 2026-06-13): обычный close применил бы
reputation-дельты (paid +10, expired −40) и разослал бы `SkladchinaClosedEvent`-DM.
Каскад делает только статусные UPDATE'ы — **никаких ledger-строк и никаких DM**.
Контекст и остаток (applications) — `docs/backlog/orphan-memberships-cleanup.md`.

### Reminder-DM за 24ч до дедлайна — internal (scheduler, 2026-06-12)

`SkladchinaReminderScheduler` (модуль `bot`, по паттерну `EventReminderScheduler`),
**только для реп-сборов** (`affects_reputation = true`):
- Poll каждые 5 мин (`skladchinas.reminder-poll-ms`, default 300000)
- Находит `status='active' AND affects_reputation AND
  deadline ≤ now + 24h AND reminder_sent_at IS NULL`
  (окно — `skladchinas.deadline-reminder-minutes-before`, default 1440)
- Ставит `skladchinas.reminder_sent_at = now()` **до** отправки
  (дедуп; независимый авто-коммит — повторный poll не дублирует DM,
  упавшая DM логируется и дропается, доставка best-effort)
- Шлёт DM всем ещё-`pending`-участникам (текст — § Bot integration)
- **Launch-blocker для −40:** штраф легитимен, только если система дважды
  предупредила (DM при создании + reminder) и отчиталась (DM о списании).
  Деплоится одним PR с новыми весами.

---

## Frontend implementation план

### Pages
```
frontend/src/pages/
  SkladchinaPage.tsx              — детали (member-view ИЛИ organizer-view)
```

### Components
```
frontend/src/components/feed/
  SkladchinaCard.tsx              — карточка в ленте «Сборы»
                                    (рядом с EventCard, не имеют общего родителя
                                    — generic компоненты FeedSection/FeedSkeleton
                                    их объединяют)

frontend/src/components/skladchina/
  CreateSkladchinaModal.tsx       — форма создания, multi-step
                                    (Step 1: basic info, Step 2: режим + суммы,
                                    Step 3: participants picker, Step 4: review)
  ParticipantPicker.tsx           — выбор member'ов из active members клуба
  PaymentModeSelector.tsx         — radio "fixed_equal / fixed_individual / voluntary"
  ProgressBar.tsx                 — % от goal для fixed-режимов
  OrganizerParticipantList.tsx    — таблица с участниками и их статусами,
                                    видна только organizer'у
```

### Queries
```
frontend/src/queries/skladchinas.ts:
  useMySkladchinasQuery()           — useInfiniteQuery
  useSkladchinaQuery(id)            — useQuery
  useCreateSkladchinaMutation()
  useMarkPaidMutation()
  useDeclineSkladchinaMutation()
  useCloseSkladchinaMutation()
```

### Tab structure update — три route'а на одной странице

Принято: **три разных URL без redirect'ов**, на одном компоненте.
Bottom-nav таб «Активности» подсвечен для всех трёх:

- `/activities` — landing с segmented control. По умолчанию открывает
  сегмент «События»
- `/events` — тот же layout, segmented control с pre-selected «События»
- `/skladchina` — тот же layout, segmented control с pre-selected «Сборы»

Файловая структура:
```
pages/ActivitiesPage.tsx           — wrapper. Считывает текущий route
                                     (useLocation) → выбирает segment.
                                     Тап по segment-button → navigate
                                     на /events или /skladchina.
components/activities/EventsTab.tsx     — лента событий (контент текущей EventsPage)
components/activities/SkladchinaTab.tsx — лента сборов
```

`BottomTabBar.isTabBarRoute` расширяется: tab подсвечивается для
`/activities`, `/events`, `/skladchina`, `/events/:id`,
`/skladchina/:id` (regex обновится).

Router (`router.tsx`):
```tsx
<Route path="/activities" element={<ActivitiesPage />} />
<Route path="/events" element={<ActivitiesPage />} />
<Route path="/skladchina" element={<ActivitiesPage />} />
<Route path="/events/:id" element={<EventPage />} />
<Route path="/skladchina/:id" element={<SkladchinaPage />} />
```

Это даёт deep-link friendly DM от бота: `/skladchina/{id}` или
`/events/{id}` ведут прямо на детали без промежуточных перенаправлений.

### Organizer entry point

В `OrganizerClubManage` добавляем **отдельную секцию «Сборы»** —
по аналогии с существующей секцией «События». Listing активных
сборов клуба + кнопка «+ Создать сбор» в header секции. Listing
рендерит мини-карточки (название, прогресс, deadline, статус),
тап по карточке → `/skladchina/{id}`. Кнопка «+ Создать сбор» →
открывает `CreateSkladchinaModal`.

---

## Bot integration

### `sendSkladchinaCreated(skladchina, participantIds)`

Новый метод в `NotificationService` (аналог `sendEventCreated`).

DM-текст:
```
💰 Новый сбор в клубе «{clubName}»: {title}

{description (первые 200 символов, остальное в приложении)}

💵 Сумма: {expected_amount} ₽ (или "по желанию" для voluntary)
⏰ До: {deadline.format dd.MM HH:mm}

💳 Платёжная ссылка: {paymentLink}

После оплаты — отметьте в приложении, чтобы организатор увидел.
```

Inline button: `📱 Открыть Clubs` (WebApp) — открывает Mini App
на `/skladchinas/{id}` (через TG initData `startParam`).

Отдельный пункт: **ссылка на сбор** идёт **и как текст, и как inline
button**. Текстом — чтобы скопировать в банк-приложение; inline
button (Telegram tg://) — direct deep-link если поддерживается.

**DM-прайс для «важного сбора» (2026-06-12):** если `affects_reputation = true`,
DM при создании дополнительно содержит строку-прайс:
«Это важный сбор: оплата +10 к репутации, отказ — без штрафа,
молчание до дедлайна −40».

### Reminder-DM pending-участникам (за 24ч до дедлайна, 2026-06-12)

**Только для «важного сбора»** (`affects_reputation = true`). Шлётся
шедулером `SkladchinaReminderScheduler` (см. § Reminder-DM) каждому
участнику со статусом `pending`: «⏰ Напоминание: сбор «{title}» в клубе
«{clubName}» закрывается {deadline}. Это важный сбор: оплатите или
откажитесь до дедлайна — молчание снизит репутацию на 40». Inline
button — deep-link на `/skladchina/{id}`.

### DM expired-участникам при закрытии (2026-06-12)

Только для «важного сбора»: каждому, кто получил `expired_no_response`
при закрытии, уходит DM о списании −40 («Сбор «{title}» закрыт, вы не
ответили до дедлайна — репутация снижена на 40»). Отчёт о фактическом
списании — третья нога легитимности штрафа (предупредили дважды →
отчитались).

### `sendSkladchinaClosed(skladchina, summary)`

DM **только organizer'у** с итогом:
```
✅ Сбор закрыт: «{title}»

Собрано: {collected} ₽ из {goal} ₽ ({percentage}%)
Оплатили: {paid_count} из {participant_count}
{если affectsReputation: ⚠️ Репутация пересчитана.}
```

### Throttling

В v1 — DM шлются sequentially (как в `sendEventCreated`). Если будут
проблемы (сбор на 30 человек = 30 sequential DM с возможным rate-limit
от Telegram 30/sec) — добавим batching в v2.

---

## Reputation hook

> **Переписано 2026-06-12.** Прежний путь — прямой инкремент
> `reliability_index` через `ReputationService.addReliabilityDelta` —
> **удалён в reputation-v2 P1a** (ветка `feature/reputation-ledger`).
> Источник истины репутации — append-only `reputation_ledger`;
> `user_club_reputation` — кэш. Контракт перемаршрутизации —
> [`reputation-v2.md`](./reputation-v2.md) § «Складчина» / § «Ось „финансы"».

### Актуальный путь: finance ledger-строки через `appendAndRecompute`

При closure сбора (`closeInternal` — manual close, auto-close по deadline
или auto-close по state-change), если `affects_reputation = true`:

1. `SkladchinaService.applyReputationDeltas` строит **finance
   ledger-строки** для участников: kind через
   `ReputationPolicy.financeKind(status)`:
   - `paid → skladchina_paid (+10)`
   - `expired_no_response → skladchina_expired (−40)`
   - `declined → null` (строка не эмитится)
   - `released → null` (строка не эмитится)
2. **Skip owner** (анти-фарм правило 1): владельцу клуба строки не пишутся.
3. `occurred_at = closed_at` (инвариант `reputation-v2.md` — момент решения).
4. Общий `ReputationService.appendAndRecompute(entries)`: INSERT в ledger
   с `ON CONFLICT (user_id, source_type, source_id) DO NOTHING`
   (`source_type='skladchina'`, `source_id=skladchina.id`) + recompute
   кэша per (user, club) под advisory-локом. **F5-13:** пары (user, club)
   сортируются детерминированно перед захватом локов — иначе конкурентные
   событие × складчина одного клуба ловят deadlock (40P01).

### Идемпотентность

Два слоя:
- `reputation_applied = TRUE` на участнике — skladchina-side guard
  (admin переоткроет сбор через DB hack и закроет снова → дельты не
  повторятся);
- UNIQUE-ключ ledger `(user_id, source_type, source_id)` — структурный
  backstop: вторая строка на тот же сбор невозможна.

### Устойчивость к сбоям (F5-18)

Падение реп-хука **не ломает** close-флоу: в auto-close пути
(`maybeAutoCloseAfterStateChange`) `closeInternal` обёрнут в try/catch +
`log.error` — `markPaid`/`decline` пользователя не откатывается (NFR
§ Транзакции теперь соответствует реальности, ранее KDoc обещал это
без реализации).

---

## Acceptance Criteria

### AC-1: Создание сбора (organizer happy path)
**GIVEN** organizer клуба X
**WHEN** создаёт сбор с `paymentMode=fixed_equal, total_goal=10000, deadline=tomorrow, participants=[A, B, C], affectsReputation=true`
**THEN** в БД появляется `skladchinas` запись + 3 `skladchina_participants` с `expected_amount=3334/3333/3333` (round последнему +1)
**AND** бот шлёт DM каждому из A, B, C с inline-button и текстовой ссылкой
**AND** organizer получает 201 + `SkladchinaDetailDto`

### AC-2: Member видит и оплачивает (voluntary)
**GIVEN** member A в сборе с `paymentMode=voluntary`
**WHEN** открывает `/skladchinas/{id}` и нажимает «Оплатил», вводит `500`
**THEN** `skladchina_participants(A).declared_amount = 50000` (копейки), `status=paid`
**AND** `collected_kopecks` обновляется
**AND** возвращается обновлённый DTO

### AC-3: Organizer видит declared vs expected
**GIVEN** сбор fixed_equal, expected = 3334 копеек на участника. Member B нажал «Оплатил» с `declared = 3000`
**WHEN** organizer открывает страницу сбора
**THEN** видит B в списке с пометкой "ожидалось 33.34, заявлено 30.00" (или эквивалент UI)
**AND** member view B видит только свой статус paid, без сравнения

### AC-4: Decline пользователя
**GIVEN** participant C в active сборе
**WHEN** нажимает «Отказаться»
**THEN** `status=declined, declined_at=now`
**AND** при closure: даже если affectsReputation — **ledger-строка не
создаётся** (отказ бесплатен; `outcome_count` не растёт — см. таблицу
Reputation deltas)

### AC-5: Auto-close по deadline
**GIVEN** сбор active с deadline = вчера
**WHEN** scheduler `SkladchinaScheduler` срабатывает
**THEN** статус → `closed_success` (если goal достигнут) или `closed_failed` (если <80% от goal)
**AND** все pending → `expired_no_response` (`closed_at ≥ deadline`)
**AND** если affectsReputation: ledger-строки `skladchina_expired` (−40) применены idempotent (см. AC-6)
**AND** если affectsReputation: каждому expired-участнику DM о списании −40
**AND** DM organizer'у через `sendSkladchinaClosed`

### AC-6: Reputation idempotency
**GIVEN** сбор закрыт, deltas применены, `reputation_applied=true` у каждого participant
**WHEN** admin manually re-close (теоретически если вернётся в active через DB hack)
**THEN** deltas НЕ применяются повторно (`reputation_applied=true` блокирует)

### AC-7: Manual close (предикат released/expired — 2026-06-12)
**GIVEN** organizer в active сборе
**WHEN** нажимает «Закрыть сбор»
**THEN** status → `cancelled` (если goal не достигнут) или `closed_success` (если достигнут)
**AND** pending → **`released`** если `closed_at < deadline` (досрочно;
без ledger-строк и DM о штрафе) ИЛИ **`expired_no_response`** если
`closed_at ≥ deadline` (ручное закрытие после дедлайна до тика шедулера)
**AND** reputation применяется если включено: paid +10, expired −40,
released/declined — без строк
**AND** DM organizer'у

### AC-7b: Досрочное закрытие нейтрально для pending
**GIVEN** «важный сбор» (affectsReputation=true), участник B в `pending`,
deadline завтра
**WHEN** organizer закрывает сбор сегодня (или сбор авто-закрывается по
goal-reached / «все остальные ответили»)
**THEN** B получает статус `released`
**AND** ledger-строк для B нет, репутация B не изменилась
**AND** UI показывает B плашку «Сбор закрыли досрочно — ваш ответ не
потребовался. Репутация не изменилась» (не «Срок истёк»)

### AC-8: Privacy — список участников только для organizer
**GIVEN** member A открывает `/skladchinas/{id}` где он participant
**WHEN** API call
**THEN** response.participants == null (или undefined)
**AND** response.participantCount, paidCount показаны (counts, не names)

### AC-9: Aggregated feed
**GIVEN** user в 2 клубах с активными сборами в каждом
**WHEN** открывает таб «Активности» → segmented control «Сборы»
**THEN** видит все 2 сбора, отсортированные `actionRequired DESC, deadline ASC`

### AC-10: Empty state «Сборы»
**GIVEN** user не имеет активных сборов
**WHEN** открывает «Активности» → «Сборы»
**THEN** placeholder: «Активных сборов нет. Когда организатор создаст сбор и выберет вас участником — он появится здесь»

### AC-11: Validation
- `participants` пуст → 400
- `participants[i].userId` не active member клуба → 403
- `deadline < now + 1h` → 400
- `fixed_equal` без `totalGoalKopecks` → 400
- `voluntary` с `totalGoalKopecks` → принимается и сохраняется (изменено 2026-06-12; ранее ignored); `<= 0` → 400
- `affectsReputation=true` + `voluntary` → 400
- `affectsReputation=true` + `deadline < now + 24h` → 400
- `affectsReputation=true` + в клубе уже 3 реп-сбора за последние 7 дней → 400
- markPaid в fixed-режиме с `declared != expected` → 400
- markPaid в voluntary с `declared > 10_000_000` копеек → 400

---

## Non-functional

### Производительность
- `GET /api/users/me/skladchinas` < 300ms для user'а в 10 клубах с 50 active сборами (через индексы)
- Создание сбора с 30 participants: API < 1s; DM-рассылка через `@Async` не блокирует ответ
- Auto-close scheduler за 1 проход не дольше 30 сек на 1000 истёкших сборов

### Безопасность
- Privacy: list участников НЕ показывается non-organizer'ам (см. AC-8)
- `paymentLink` — это **публичный URL банка** organizer'а, видим всем participants. Это осознанное решение admin'а (см. R-2 backlog'а)
- All write endpoints через JWT + role checks
- SQL injection защищён jOOQ
- Validation на boundaries (DTO + @Valid)
- Rate limiting на create-endpoint (по аналогии с /api/auth — 5 req/min на user)

### Логирование
- INFO на каждом state-transition: created, mark-paid, decline, close
- WARN на decline сразу после mark-paid (странное поведение)
- ERROR на failed reputation hook (но не fail-close-flow)

### Идемпотентность
- `mark-paid`: повторный call возвращает текущий DTO без изменений (см. API)
- `decline`: повторный call после уже declined → 400
- Reputation: `reputation_applied = true` блокирует double-apply

### Транзакции
- `@Transactional` на: create, mark-paid, decline, close (включает reputation + DB updates)
- Failed reputation hook не должен ломать close-операцию — wrap в try/catch с log.error.
  **Статус: теперь реализовано (F5-18, 2026-06-12)** — до этого требование
  было декларацией (KDoc `maybeAutoCloseAfterStateChange` обещал «errors
  are logged» без try/catch, и падение реп-хука откатывало `markPaid`
  пользователя 500-кой). Try/catch добавлен вокруг `closeInternal` в
  auto-close пути.

---

## Risks / Open Questions

### R-1 (Open): Honor system → потенциальные накрутки
Сейчас user может нажать «оплатил» без реального перевода. Прогресс
будет фейковый, доверие подорвано. Mitigation в v2 — добавить optional
прикрепление скрина чека (доступно всем participants для контроля).

### R-2 (Inherited from backlog): Payment link as public PII of organizer
Ссылка на сбор открывает банк organizer'а (с его телефоном/реквизитами).
Это видят все participants. Должно быть осознанным решением admin'а
при создании — добавить warning в `CreateSkladchinaModal` step 3.

### R-3 (Open): No-edit policy
Решили: сбор нельзя править после создания. Если admin ошибся —
закрывает и создаёт новый. Простая модель, но может бесить.
Mitigation в v2 — позволить editing полей до первой оплаты.

### R-4 (Open): Что если organizer покинул клуб
В v1 — auto-close сбора при потере organizer-роли (потенциально
требует extra trigger в `MembershipService.cancelOrganizer`,
если такой flow существует). Это edge case, но **должен быть
покрыт тестом**.

### R-5 (RESOLVED): UX в `OrganizerClubManage` — отдельная секция «Сборы»
Принято: добавляем **отдельную секцию «Сборы»** (по аналогии с
существующей секцией «События») с listing активных сборов клуба +
кнопкой «Создать сбор» в header секции. Organizer сразу видит свои
активные сборы и может их открыть/закрыть. Закрытые сборы (старше
7 дней — см. v2-scope) не показываются.

### R-6 (Open): Что показывать в Profile/Reputation
Сейчас в Profile видна `reliability_index` per club. После складчины
deltas из складчин попадают туда же — у user'а будет смешанная
картина (events + skladchinas). Нужно ли разделять визуально?
В v1 — нет, общий index. Если будут жалобы — добавим breakdown.

### R-7 (Inherited from backlog): Telegram bot rate limit
Сбор на 30 participants = 30 DM в момент создания. TG limit ~30/sec.
В v1 sequential — может попасть в rate limit. Mitigation в v2 —
batch + throttle через Redis queue.

### R-8 (RESOLVED): Photo upload — отдельный controller
Принято: **новый endpoint** `POST /api/uploads/skladchina-photo` в
новом `SkladchinaUploadController` (или `UploadController` если получится
обобщить позже). НЕ переиспользуем `/api/uploads/avatar` — разделяем
ответственность чтобы можно было независимо менять правила (max
размер, allowed формaты, валидация для скриншотов чеков в будущем).
Логика похожа на avatar upload, но контроллер свой.

---

## Связанное

- `docs/modules/events.md`, `docs/modules/events-feed.md` — паттерн для агрегированного feed'а (events) — копируется на сборы (`/api/users/me/skladchinas`)
- `docs/modules/reputation-v2.md` § «Ось „финансы"» / § «Складчина» — контракт finance ledger-строк (`appendAndRecompute`; `addReliabilityDelta` удалён в P1a)
- `docs/backlog/skladchina-reputation-redesign.md` — locked design репутации складчины (2026-06-12): веса, `released`, гейты, обоснования, anti-abuse
- `docs/modules/telegram-bot.md` — паттерн DM, расширяется новыми `sendSkladchina*` методами
- `docs/modules/club-page-unified.md` — НЕ затрагивается (Decision 3: складчина не в ClubPage)
- `docs/modules/my-clubs-unified.md` — НЕ затрагивается
- `PRD-Clubs.md` §4.8 «Складчина» — добавлен 2026-06-12 (номер 4.5 из ранних планов был занят «Панелью организатора»)
- `docs/backlog/skladchina.md` — **устаревает**, заменяется этой спекой (нужно удалить после merge MVP)

## План разработки (для последующего фича-флоу)

> Исторический план MVP (2026-05); `addReliabilityDelta` ниже устарел —
> репутация теперь через ledger (см. § Reputation hook).

1. Backend: миграция V14 → domain → repository → service → controller → tests
2. Backend: NotificationService extensions + ReputationService.addReliabilityDelta + tests
3. Backend: SkladchinaScheduler для auto-close + tests
4. Frontend: queries hooks + apiClient
5. Frontend: SkladchinaPage (member + organizer views)
6. Frontend: CreateSkladchinaModal
7. Frontend: ActivitiesPage wrapper с segmented control «События | Сборы»
8. Frontend: SkladchinaCard в `components/feed/`
9. Frontend: OrganizerClubManage entry-point кнопка
10. Tests + staging + handoff
