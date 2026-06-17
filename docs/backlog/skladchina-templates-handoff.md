# Складчина-шаблоны — хэндофф для новой сессии

> Снимок на 2026-06-17. **split_bill доделан и влит в master.** Трек шаблонов ПРИОСТАНОВЛЕН
> (решение PO) — дальше репутация клуба. Дизайн-контракт: `skladchina-templates-architecture.md`.
> Направление: `skladchina-2.0-roadmap.md`. Фактическое состояние кода: `docs/modules/skladchina.md` § «Шаблоны».

## Где мы сейчас (влито в master)

- **Фаза A** (кроме A-3) + **split_bill** полностью реализованы и **в master/prod**.
  A-3 «перераспределение» — **удалено** (не оправдало себя).
- **split_bill готов:** два режима (поровну / «ваша сумма»), исключить-себя, один-сбор-на-событие
  (active/closed_success блокируют), всегда-влияет-на-репутацию (минует гейты важного сбора),
  отказ-с-одобрением (V28) + обязательная причина отклонения (V29) + продление дедлайна до 48ч +
  DM-уведомления орг↔участник, фото/чек с pinch-зумом.
- **Движок отрефакторен** по ответственности: `SkladchinaQueryService` / `CreationService` /
  `PaymentService` / `LifecycleService` (был один god-`SkladchinaService`). Шаблоны — Strategy+Registry.
- **Тесты:** backend `SkladchinaControllerTest` 54/54 ✅ (весь набор green), frontend 135 ✅.
- Миграции: V27 (template+event_id), V28 (decline request), V29 (decline_reject_note).

## Что отложено (трек на паузе)

- Шаблоны **gear / booking / birthday** — дизайн-контракт в `architecture.md`, код не начат.
- **Фаза B-1** (карточка сбора в чате клуба) — не начата.
- **«Шаг 2» / колонка `reputation_ledger.verified`** — НЕ добавлена. split пишет обычную
  finance-репутацию. Ввести, если клуб-трек потребует различать verified-исходы.

## Следующий приоритет — репутация клуба

См. `docs/backlog/club-quality-gamification.md` (залоченные решения). split даёт первый
верифицированный финансовый сигнал, но клуб-качество — в основном факты (явка/события/удержание).
Перед кодом — сверить дизайн-док с текущей реальностью + нарезать MVP.

## Отложенные follow-up (мелкие)

- Picker в «+»→split показывает ВСЕ прошедшие события; фильтровать по «явка отмечена».
- `formatRubles` дублируется в 3+ местах (page/card/list/split-page) → вынести в `shared/lib`.

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
