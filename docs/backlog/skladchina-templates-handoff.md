# Складчина-шаблоны — хэндофф для новой сессии

> Снимок на конец сессии 2026-06-16. Цель файла — чтобы новая сессия продолжила работу
> на той же ветке без потери контекста. Дизайн-контракт: `skladchina-templates-architecture.md`.
> Направление: `skladchina-2.0-roadmap.md`.

## Где мы сейчас

- **Ветка:** `feature/skladchina-templates` (стек поверх `feature/skladchina-phase-a`).
  **Ни одна не влита в master/prod** — строим весь трек шаблонов на ветке, вольём вместе в конце
  (решение PO). Ветка запушена → **развёрнута на staging** (https://staging.77-42-23-177.sslip.io,
  бот `@clubs_v2_test_bot`).
- **Коммиты ветки (снизу вверх):**
  - `0fbd5e6`, `0fc1487` — **Фаза A** (упрощение ядра): один тап в fixed, орг-отметка оплаты
    (toggle), «не хватает X ₽»/перераспределение, убрано автозакрытие по цели, прогресс в людях.
    Спека: `docs/modules/skladchina.md` § «Фаза A».
  - `d093fc7` — **split_bill backend**: движок Strategy+Registry (`SkladchinaTemplateStrategy` +
    `SkladchinaTemplateRegistry`), `CustomTemplate`/`SplitBillTemplate`, миграция V27
    (`skladchinas.template` enum + `event_id`).
  - `ae1f69d` — **split_bill frontend**: «+» → шаг шаблона, `CreateSplitBillPage` (вход из события
    или выбор события), контекстная кнопка «🧾 Разделить счёт» на событии.
  - `ac81964` — **отказ-с-одобрением (V28)**: `declinePolicy` в стратегии (FREE/REQUIRES_APPROVAL),
    заявка на отказ с причиной → орг одобряет/отклоняет, миграция V28 (3 колонки на участнике).

## Что готово (и работает в тестах)

- **Фаза A** — мержибельна как чистое упрощение ядра. UI транзитный (часть переедет в шаблоны).
- **split_bill** — первый шаблон: состав авто из верифицированной явки события, доля = чек ÷
  пришедших, `outcomesVerified=true`. Создаётся через тот же `POST /api/clubs/{id}/skladchinas`
  с `template=split_bill` + `eventId`.
- **Отказ-с-одобрением** — только split_bill (`declinePolicy=REQUIRES_APPROVAL`). Эндпоинты
  `request-decline` (участник) / `participants/{userId}/resolve-decline` (орг).
- **Тесты:** backend `SkladchinaControllerTest` 48/48 ✅, frontend 127/127 ✅, оба билда ✅.

## ⚠️ Баги (триаж в начале новой сессии)

Пользователь нашёл баги при тесте на staging — **список будет в начале новой сессии**. Здесь
оставлено место, чтобы их зафиксировать перед фиксами:

- [ ] (вписать баг 1: что, где, как воспроизвести, ожидание/факт)
- [ ] (баг 2)
- [ ] …

## Следующие шаги (по приоритету)

1. **Разобрать баги** (см. выше) — bugfix-проход по split_bill / отказу / Фазе A.
2. **gear/касса** — следующий шаблон: open enrollment (вписался оплатой) + ростер=доступ +
   fulfillment «куплено». Лечит безбилетника без репутации (исключение, не штраф).
3. **booking/резерв** — «социальное эскроу»: pledge → порог → платим; ветка возврата.
4. **«Шаг 2» — репутация из шаблонов.** Сейчас split/отказ репутацию НЕ трогают (важность выкл).
   Заложено: `outcomesVerified` (split=true) + `declinePolicy`. План: одобренный отказ = release
   (нейтрально), отклонённый-и-неоплаченный → штраф; писать ledger-строки с меткой `verified`
   (колонку `reputation_ledger.verified` ещё НЕ добавляли — добавить, когда дойдём).
5. **Финал:** ревью (Reviewer+Security), docs-alignment, влить весь стек в master.

## Отложенные follow-up (мелкие)

- Picker в «+»→split показывает ВСЕ прошедшие события; фильтровать по «явка отмечена».
- `formatRubles` дублируется в 3+ местах (page/card/list/split-page) → вынести в `shared/lib`.
- Скрыть тумблер «важный сбор» до «шага 2» (опц.).

## Гочи разработки (важно для новой сессии)

- **Тесты бэка требуют Docker** (Testcontainers). **CI test-job НЕТ** — `SkladchinaControllerTest`
  гоняется только локально (`./gradlew test --tests "com.clubs.skladchina.SkladchinaControllerTest"`).
  Запусти Docker Desktop перед тестами (`open -a Docker`).
- **jOOQ codegen:** локальная БД `clubs` (localhost:5432) **устарела на V18**. Для codegen создана
  scratch-БД **`clubs_cg27`** в контейнере `clubs2_postgres` с полной цепочкой миграций (сейчас в
  ней V1–V28). После новой миграции: применить её к `clubs_cg27` (psql), затем
  `DB_URL="jdbc:postgresql://localhost:5432/clubs_cg27" DB_USER=clubs DB_PASSWORD=clubs_secret ./gradlew generateJooq --rerun-tasks`.
  Сгенерённые исходники `backend/src/generated/**` — **в git** (коммитить вместе с миграцией).
- **IDE-диагностика отстаёт** на свежесгенерённых jOOQ-энумах — верь `./gradlew compileKotlin`,
  не «Unresolved reference» в редакторе.
- **В тестах `dsl.execute(sql, vararg)` с `?`-биндами + inline-литералами падает** («bad SQL
  grammar») — используй строковую интерполяцию (как остальной тест). `dsl.fetchOne(sql, vararg)`
  с биндами — ок.
- **Rate-limit фильтр стоит ПЕРЕД auth** → в MockMvc все запросы делят один `ip:127.0.0.1` бакет;
  в `@BeforeEach` вызывается `rateLimitFilter.resetBuckets()`, иначе большой класс ловит 429.

## Незакоммиченное (НЕ наше, не трогать)

В рабочем дереве остаются изменения не из этого трека (были до сессии): `.claude/settings.json`,
`docs/backlog/club-quality-gamification.md`, untracked `Что нужно сделать.md`, `.DS_Store`.
Их в коммиты складчины НЕ включали.
