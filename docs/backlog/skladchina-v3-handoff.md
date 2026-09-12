# Хэндофф: сборы и долги v3 — от спеки к коду

> Написан 2026-09-12 в конце аналитической сессии на ветке **`feature/skladchina-rethink`**
> (от `master` `c5cf22d`). Следующая сессия — **код по спеке**, только после явного «делаем» от PO
> (PO сказал «давай в новую сессию»). Трек **L**: деньги, миграция, карта прав.

## 0. Что произошло в этой сессии (коротко)

1. Сессия начиналась как «перепройти К1 сверки оплат на staging» (ветка
   `feature/skladchina-payment-confirmation`, V89). PO запутался в граничных условиях модуля и
   решил **переписать книгу правил сборов с нуля, от жизненных сценариев**, а не чинить дальше.
2. Прошли процесс: 20 сценариев → отметки PO → истории и правила → «долг как сущность» →
   мокапы → референсы рынка → экран «Долги» по решениям PO → спека новым файлом.
3. **К1/К2 сверки оплат на паузе**, ветка `feature/skladchina-payment-confirmation` (`a526709`)
   не влита и остаётся целой на случай отката. Если v3 доедет до master, её содержимое
   поглощается v3 и ветка не мержится (решение PO понадобится в момент мержа).

## 1. Читать первым, в этом порядке

1. **`docs/modules/skladchina-v3.md`** — спека целиком: модель, переходы долга (§ 2.2, 15 строк —
   всё, что можно сделать с долгом), жизненные циклы по видам (§ 3), репутация (§ 4), шедулер
   (§ 5), чат и DM (§ 6), API (§ 7), миграция (§ 8), фронт (§ 9), что удаляется (§ 10),
   критерии приёмки AC-1…AC-14 (§ 11), решения по открытым вопросам (§ 12, все закрыты).
2. `docs/design/skladchina-rethink/01-scenarios.md` — 20 сценариев с отметками PO и **шесть
   принципов PO** (выше принципов приложения по приоритету).
3. `docs/design/skladchina-rethink/02-design.md` — истории «как должно быть» и 16 правил.
4. `docs/design/skladchina-rethink/03-debt.md` — долг как сущность, решения PO по экрану.
5. Мокапы **только на диске** (`mockups/` в `.gitignore`), в git их нет:
   `docs/design/skladchina-rethink/mockups/01-story-walkthrough.html` (неделя клуба, четыре сбора),
   `04-debts-personal.html` (экран «Долги»: профиль → люди → пара — **это утверждённый вариант**),
   `02-debts-screen-variants.html` (шесть отвергнутых вариантов, не показывать PO снова),
   `03-references.html` + `refs/` (скриншоты Settle Up, Tricount, Splid, Splitwise, «Должок»).
   Если сессия на другой машине, мокапов не будет; спека самодостаточна.

Старые доки по модулю (`docs/modules/skladchina.md`, `docs/backlog/skladchina-*.md`) описывают
то, что в `master`, и нужны только как карта текущего кода, который предстоит заменить.

## 2. Ключевые решения PO (не переоткрывать)

- **Сбор = обёртка над долгами, долг между двумя людьми, подтверждает получатель.** Три вида:
  `shared` («Скинуться»: назначено, в срок → долг, репутация есть), `per_head` («Кто берёт?»:
  сам жмёт «Беру», при «Заказываю» неоплатившие выбывают без долга), `voluntary` («По желанию»:
  долг рождается как `claimed` при «Перевёл N ₽», тихий режим «скрыть от»).
- **Сбор закрывается сам**, когда нет открытых долгов; итог только `collected` или `cancelled`.
  Порога 80 %, «не собран», «свести», автосведения, запрета платить после срока — нет.
- **Экран «Долги»**: личная книга через все клубы, вход из **профиля**; плашки людей (аватар,
  имя, @username) → пара: «вы должны / вам должны», внутри **ближайший срок сверху**, кнопки
  «Отдал» / «Получил», внизу **сальдо пары** с кнопкой, закрывающей все долги пары разом.
  Сальдо только внутри пары, через третьих ничего не схлопывается.
- **«Отдал» → получателю DM с inline-кнопками «Получил / Не получил»** (в первой версии).
  Запись пропадает из списков только после «Получил». «Не получил» → долг снова открыт, чек.
- **Репутация только у `shared`**: +10 за долг, закрытый до срока; −40 один раз через 3 недели
  просрочки, автоматически; `claimed` часы останавливает; прощено → 0. Кнопки «списать» нет.
- **Владелец клуба чужих долгов не видит.** Может только отменить чужой сбор. Со-организаторы —
  нет (только владелец и создатель). Создать сбор может любой участник клуба.
- **В чате после срока только число** «не оплатили N», имена видят получатель и должник.
  До срока «Ждём: @…» остаётся.
- **«По желанию» закрывается только после разбора всех «Перевёл»** (вариант А).
- **Ручного долга без сбора нет.** Членский взнос — существующий взнос клуба, не сбор.
- Отвергнуто PO: графы со стрелками, «Долги клуба», все шесть вариантов из `02-…variants`,
  переопределение «поровну» как взнос на голову, платные события (отдельная фича, позже),
  абонемент, возвраты, долг один на один.

## 3. Состояние ветки и staging

> **Обновление 2026-09-12 (вторая сессия): код по спеке НАПИСАН целиком — шаги 1–3 ниже сделаны.**
> Бэк: V90 + пакет `debt/` + переписанный `skladchina/` + бот/чат, `./gradlew test` 1064/0.
> Фронт: страница сбора, единая форма `?kind=`, экраны `/debts` и `/debts/with/:userId`, плитка
> «Долги» в профиле, `tsc`/`npm test` (670) / `npm run build` чистые. Решения при реализации —
> `docs/modules/skladchina-v3.md` § 13. Дальше по § 4 «Шаг 4»: ревью → пуш → staging → тест PO.

| Что | Состояние |
|---|---|
| `feature/skladchina-rethink` | от `master`, 4 коммита доков (`cd84284` → `ec4135e`) + коммит(ы) кода v3 |
| `feature/skladchina-payment-confirmation` | `a526709`, запушена, staging сейчас на ней, в master не влита, **на паузе** |
| `master` | `c5cf22d`, миграции до V86 |
| staging БД | на **V89** (сверка оплат) |
| prod | сборов 2 за всё время, оба закрыты — миграция данных формальная |

**Ловушка staging (важно):** staging одно приложение и переезжает на последнюю запушенную ветку.
Пуш `feature/skladchina-rethink` без миграций (код master = V86) на базу с V89 уронит Flyway.
Поэтому **пушить только с V90 внутри** (Flyway допускает пропуски номеров). Альтернатива —
сбросить БД staging, но это отдельное решение PO.

**Нумерация миграций:** V87/V88 заняты локальной веткой биллинга
(`feature/sprint-1.0-day4-payment-model`, не запушена), V89 — веткой сверки. v3 начинает с
**V90**.

## 4. План кода (порядок, с точками входа)

Оценка: 3–5 сессий. После каждого шага — коммит на ветке.

### Шаг 1. Схема и кодоген
- `backend/src/main/resources/db/migration/V90__skladchina_v3.sql` по § 8 спеки: типы
  `skladchina_kind`, `debt_status`, `debt_settlement_status`, новый статус сбора; колонки
  `skladchinas`; таблицы `debts`, `debt_settlements`; перенос `skladchina_participants` → `debts`
  и удаление старой таблицы и её enum; `COMMENT ON` на русском на всё.
- Кодоген jOOQ по рецепту `docs/backlog/branch-handoff-de-stars-member-admin.md` § 7:
  временный PG на 5433 (`docker run … postgres:16-alpine`), миграции по порядку,
  `DB_URL="jdbc:postgresql://localhost:5433/clubs" DB_USER=clubs DB_PASSWORD=clubs_secret
  ./gradlew generateJooq --rerun-tasks`. Локальная dev-БД отстаёт, на неё не накатывать.
- `./gradlew compileKotlin` покажет все места со старыми enum — это карта того, что переписывать.

### Шаг 2. Бэк
- Пакет `backend/src/main/kotlin/com/clubs/skladchina/` переписывается: `SkladchinaCreationService`
  (три вида, `when (kind)`), `SkladchinaLifecycleService` (закрытие «нет открытых долгов»,
  lock/order/close/cancel), новый пакет `debt/` (`DebtService`: переходы § 2.2, `DebtSettlementService`
  § 2.3, `DebtRepository`, `DebtController` § 7, `DebtScheduler` § 5), `SkladchinaController`
  (эндпоинты § 7, старые удалить). Стратегии `template/*` удаляются, `SkladchinaShares.equal`
  остаётся. `SkladchinaConfirmationPolicy`, `SkladchinaScheduler` (автосведение) — удалить.
- `chatlink/SkladchinaChatStatusRenderer` — тексты по видам § 6, после срока только число.
- `bot/SkladchinaBotNotifier` — DM по видам; **callback-кнопки «Получил / Не получил»** по
  образцу `bot/RosterCallbackService` и `chatlink/ChatLinkBotService` (`callback_data` с
  префиксом + UUID, `answerCallbackQuery`).
- `reputation/ReputationPolicy.skladchinaRulesLine()` — фраза под новые правила; начисления
  из `DebtScheduler` по штампам `reputation_plus_at` / `reputation_minus_at`.
- Права: `common/auth/ClubCapability.MANAGE_SKLADCHINA` теряет применение — убрать из карты
  ролей или оставить для списка сборов; отмена чужого сбора только владелец (§ 2.4).
- Тесты: Testcontainers (нужен Docker), не править файлы во время `./gradlew test`.

### Шаг 3. Фронт
- Роуты: `/debts`, `/debts/with/:userId` (новые страницы), плитка в `pages/ProfilePage.tsx`,
  `pages/SkladchinaPage.tsx` переписать (участник / создатель), три формы создания
  (`CreateSkladchinaPage`, `CreateSplitBillPage` → одна форма с `?kind=`), пикер вида в
  `components/manage/CreateActivityPicker.tsx`, карточка `components/feed/SkladchinaCard.tsx`,
  удалить `components/skladchina/OrganizerRequestsPanel.tsx` и таб «Требует оплаты» в
  `components/activities/SkladchinasTab.tsx`.
- Тексты кнопок ровно из § 9 спеки. Стиль — существующий `redesign.css` (`.rd-*`); новые классы
  проверять на коллизии.
- Перед пушем чистый `tsc`: `rm -f node_modules/.tmp/tsconfig.tsbuildinfo && npx tsc --noEmit`,
  `npm test`, `npm run build`.

### Шаг 4. Процесс
Reviewer → Security (деньги, права, `initData` не трогаем) → Analyst по `docs/INDEX.md`
(сделано: `skladchina-v3.md` § 13, `reputation-v2.md`, `club-roles.md`, `club-leave.md`,
`club-chat-link.md`, `club-quality.md`, `events.md`, `unified-activity-creation.md`,
`telegram-bot.md`, `INDEX.md`; после мержа `skladchina.md` → `docs/backlog/`, PRD § 4.8 —
на шаге «готово, запушь») → пуш ветки → staging → тест PO по AC-1…AC-14 → «готово, запушь».

Для теста на staging — env в Coolify (все с дефолтами в `application.yml`, в compose-файлах их
нет): `DEBT_POLL_MS=30000` (тик шедулера), `DEBT_OVERDUE_WEEKS=0` (−40 сразу после срока —
для AC-11), `DEBT_CLAIM_STALE_HOURS=0` (напоминание получателю на первом тике),
`SKLADCHINA_DEADLINE_REMINDER_MINUTES_BEFORE=5`. Старая `SKLADCHINA_REMINDER_POLL_MS` удалена.
Клуб «Партия» с чатом «Опат» и тремя аккаунтами PO (Ivan владелец, Clubs Support, XX).

## 5. Ловушки этой сессии

### Вторая сессия (код, 2026-09-12)

- **Jackson и поля вида `iOwe`:** геттер `getIOwe` сериализуется как `iowe` — фронт не находил
  поле. Именовать DTO-поля без «одна строчная + заглавная» в начале (`owe`/`owed`).
- **`planPerHead`/`planVoluntary` теряли сумму** (передавали `null` вместо `request.amountKopecks`)
  → NPE на «Беру». Ловится только интеграционным тестом через API.
- **Неразрывный пробел в `Money.rub`** ломал сравнения в тестах; в коде обычный пробел.
- **URL чека в тестах** должен начинаться с `s3.base-url` тестового профиля
  (`http://localhost:9000/test-bucket/uploads/...`), root-relative `/uploads/...` там не проходит.
- **BSD `sed` не знает `\b`** — массовые переименования делать через `perl -pi -e 's/\bX\b/Y/g'`.
- **Serena (Kotlin LSP) снова не поднялась** — вся навигация grep/Read; компилятор как оракул.
- **Flyway и соседние ветки:** `out-of-order` в `application.yml` не включён. Если V90 уедет в
  master раньше V87/V88 (биллинг) или V89 (сверка), Flyway на проде откажется применять меньшие
  номера («resolved migration not applied»). Решение PO: либо `spring.flyway.out-of-order: true`
  в prod-профиле, либо перенумеровать отстающие ветки перед их мержем.
- Полный `./gradlew test` ~4,5 мин с Testcontainers; файлы во время прогона не трогать.

- **Хук `safety-guard.py` блокирует Bash-команды, в тексте которых есть слова про удаление
  таблиц**, даже внутри heredoc с документацией. Такие файлы писать инструментом Write.
- **Serena MCP не поднялась** (`CONNECT_TIMEOUT`); фолбэк grep/Read работал нормально.
- **Панель браузера в приложении скрывается**, и тогда скриншоты и прокрутка падают по таймауту;
  DOM-проверки через `javascript_tool` работают. Скриншоты сторов удобнее качать напрямую
  (App Store отдаёт шаблоны `{w}x{h}{c}.{f}`, Google Play — `play-lh.googleusercontent.com/...=w480`).
- **Brave Search MCP** с невалидным ключом; встроенный `WebSearch` работает.
- PO хочет **видеть на примере** (мокап + история из жизни), а не читать правила; варианты
  показывать **по одному**; честная оценка ценится («все варианты плохие» — нормальный ответ).
- Нельзя пушить ветку без миграций (§ 3). Две сборки на VPS одновременно кладут его — не пушить
  в две ветки подряд.

## 6. Команды

```bash
cd backend  && ./gradlew compileKotlin && ./gradlew test      # Docker нужен
cd frontend && rm -f node_modules/.tmp/tsconfig.tsbuildinfo && npx tsc --noEmit && npm test && npm run build
```
