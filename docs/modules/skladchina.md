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
   ✓ **Опциональный**, чекбокс «Влияет на репутацию участников»
   при создании сбора (default = выкл). Так фича не токсична
   для casual (подарок), но включается для критичных (бронь).
   ✗ Всегда обязательный — токсично для voluntary случаев.
   ✗ Никогда — теряем сигнал ответственности.

5. **Closure (завершение сбора):**
   ✓ Сбор закрывается при **первом из** условий:
   - `collected_amount ≥ total_goal` (для fixed-режимов) — success
   - `deadline` истёк — закрытие с current статусом
   - Admin вручную закрыл (кнопка «Закрыть сбор»)
   При закрытии: бот шлёт DM organizer'у с итогом, reputation-hook
   применяется (если был включён), pending users → expired_no_response.

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
- `ReputationService.addReliabilityDelta(userId, clubId, amount, reason)`
  — новый public метод для skladchina-hook (без зависимости от events)
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

    total_goal_kopecks  BIGINT,                 -- nullable: для voluntary = NULL
                        -- для fixed_equal: required. для fixed_individual: sum of participant amounts
    payment_link        TEXT NOT NULL,          -- ссылка на банк/СБП
    payment_method_note TEXT,                   -- "Тинькофф / Сбер /..." свободный текст

    deadline            TIMESTAMPTZ NOT NULL,
    affects_reputation  BOOLEAN NOT NULL DEFAULT false,

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
- `voluntary` — `total_goal_kopecks = NULL`, у participants
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
                      -- enum: pending, paid, declined, expired_no_response

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

**`status` enum:**
- `pending` — выбран admin'ом, ещё не действовал
- `paid` — user нажал «оплатил» (с указанием суммы)
- `declined` — user явно отказался
- `expired_no_response` — deadline истёк, статус был `pending` →
  переходит в `expired_no_response` при closure

### Reputation deltas (если `affects_reputation = true`)

При **closure** сбора, для каждого participant:

| Status | Reliability delta | Reason note |
|---|---|---|
| `paid` | **+10** | "skladchina_paid" |
| `declined` | **−5** | "skladchina_declined" (за отказ от обязательного сбора) |
| `expired_no_response` | **−25** | "skladchina_no_response" |

Бинарная модель оплаты: либо оплатил (с любой declared суммой), либо нет.
Сумма `declared_amount` сохраняется для recon-у организатора, но не
влияет на reputation delta — мы не штрафуем за частичную оплату,
потому что honor system и так позволяет фейк, а доплачивать руками
после клика «оплатил» неудобно.

Числа умышленно меньше event-hook'а (+100/−50/etc) — складчина
случается чаще, не должна доминировать в репутации. Подтянутся через
A/B наблюдения за реальной активностью.

`reputation_applied = TRUE` после успешного применения — защита
от double-application если closure пересчитывается (например, admin
переоткрыл сбор и закрыл снова).

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
  totalGoalKopecks: number | null,        // required для fixed_equal; null для voluntary
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
- `voluntary`: `totalGoalKopecks` ignored
- `deadline > now + 1h` И `deadline < now + 90 дней`

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
  myStatus: 'pending' | 'paid' | 'declined' | 'expired_no_response' | null,
  myExpectedAmountKopecks: number | null, // что caller должен заплатить
  myDeclaredAmountKopecks: number | null, // что caller уже заявил
  participants: Array<{                   // ВКЛЮЧАЕТСЯ ТОЛЬКО для organizer (isOrganizerView=true)
    userId: string,
    firstName: string,
    lastName: string | null,
    avatarUrl: string | null,
    expectedAmountKopecks: number | null,
    declaredAmountKopecks: number | null,
    status: 'pending' | 'paid' | 'declined' | 'expired_no_response',
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
  myStatus: 'pending' | 'paid' | 'declined' | null,
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
                                          // для fixed_*: рекомендуется == expected, но может отличаться;
                                          // organizer увидит разницу при recon
                                          // для voluntary: что user указал
}
```

**Response 200:** обновлённый `SkladchinaDetailDto`

**Errors:**
- `400` — declaredAmount ≤ 0; сбор уже не active (status != 'active')
- `403` — caller не participant этого сбора
- `409` — caller уже отметил `paid` ранее (idempotent: вернёт текущий DTO без изменений)

### `POST /api/skladchinas/{id}/decline` — отказаться

Только participant сбора.

**Response 200:** обновлённый `SkladchinaDetailDto`

**Errors:**
- `400` — уже `declined` или `paid`; status != 'active'
- `403` — не participant

### `POST /api/skladchinas/{id}/close` — закрыть сбор вручную

Только creator (`@RequiresOrganizer`-like — owner-check внутри
service'а).

**Response 200:** финальный `SkladchinaDetailDto` со status = `cancelled`
(если closed manually, без достижения goal) или `closed_success`
(если manually закрыт при достигнутом goal).

**Side-effects:**
- Все `pending` → `expired_no_response`
- Reputation hooks применяются если `affects_reputation = true`
- DM organizer'у с итогом

### `POST /api/skladchinas/{id}/auto-close` — internal (scheduler)

Не endpoint, а scheduled job `SkladchinaScheduler` (по аналогии
с `Stage2Service.triggerStage2ForReadyEvents`):
- `@Scheduled(fixedDelay = 600_000)` — каждые 10 мин
- Находит `status='active' AND deadline < now()`
- Закрывает каждый через тот же service-метод что и manual close
- Reputation, DM — те же side-effects

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

### Новый public метод в ReputationService

```kotlin
@Transactional
fun addReliabilityDelta(
    userId: UUID,
    clubId: UUID,
    delta: Int,
    reason: String   // for logging: "skladchina_paid", "skladchina_no_response"
)
```

Логика:
1. Загрузить существующий `Reputation` для (userId, clubId) — если нет,
   создать с reliability=0
2. Применить delta: `newReliability = existing + delta`
3. Сохранить через `repository.save(...)`
4. Log INFO: `userId, clubId, delta, reason, newReliability`

**Не трогает** `totalConfirmations`, `totalAttendances`, `spontaneityCount`
— эти счётчики event-specific. Для складчины при необходимости в v2
добавим `totalSkladchinasPaid` отдельной колонкой.

### Вызов из SkladchinaService

При closure сбора (`closeSkladchina` или scheduled `auto-close`):
```kotlin
if (skladchina.affectsReputation) {
    participants.forEach { p ->
        if (!p.reputationApplied) {
            val delta = computeReputationDelta(p)
            if (delta != 0) {
                reputationService.addReliabilityDelta(
                    p.userId, skladchina.clubId, delta,
                    "skladchina_${p.status.literal}"
                )
            }
            repository.markReputationApplied(skladchina.id, p.userId)
        }
    }
}
```

Idempotency: `reputation_applied = TRUE` после применения. Если admin
переоткроет сбор и снова закроет — delta повторно не применится.

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
**AND** при closure: если affectsReputation, delta=0 (см. таблицу)

### AC-5: Auto-close по deadline
**GIVEN** сбор active с deadline = вчера
**WHEN** scheduler `SkladchinaScheduler` срабатывает
**THEN** статус → `closed_success` (если goal достигнут) или `closed_failed` (если <80% от goal)
**AND** все pending → `expired_no_response`
**AND** если affectsReputation: reputation deltas применены idempotent (см. AC-6)
**AND** DM organizer'у через `sendSkladchinaClosed`

### AC-6: Reputation idempotency
**GIVEN** сбор закрыт, deltas применены, `reputation_applied=true` у каждого participant
**WHEN** admin manually re-close (теоретически если вернётся в active через DB hack)
**THEN** deltas НЕ применяются повторно (`reputation_applied=true` блокирует)

### AC-7: Manual close
**GIVEN** organizer в active сборе
**WHEN** нажимает «Закрыть сбор»
**THEN** status → `cancelled` (если goal не достигнут) или `closed_success` (если достигнут)
**AND** все pending → `expired_no_response`
**AND** reputation применяется если включено
**AND** DM organizer'у

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
- `voluntary` с `totalGoalKopecks` → ignored (или 400 — на выбор разработчика, в спеке = ignored с warning в response)

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
- Failed reputation hook не должен ломать close-операцию — wrap в try/catch с log.error

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
- `docs/modules/reputation.md` — модель репутации, расширяется через новый `addReliabilityDelta`
- `docs/modules/telegram-bot.md` — паттерн DM, расширяется новыми `sendSkladchina*` методами
- `docs/modules/club-page-unified.md` — НЕ затрагивается (Decision 3: складчина не в ClubPage)
- `docs/modules/my-clubs-unified.md` — НЕ затрагивается
- `PRD-Clubs.md` — добавится новый раздел 4.5 «Складчина» после реализации
- `docs/backlog/skladchina.md` — **устаревает**, заменяется этой спекой (нужно удалить после merge MVP)

## План разработки (для последующего фича-флоу)

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
