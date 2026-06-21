# Модуль «Качество клуба» (clubquality)

**Статус:** Фундамент в проде (#69) · L2-кольца — `feature/club-quality-rings` · **Создано:** 2026-06-17
**Дизайн-контракт:** `docs/backlog/club-quality-gamification.md` (§0–11, design locked).
Память: [[project_club_quality_track]].

> Спека покрывает срезы **«Фундамент»** (модуль `com.clubs.clubquality` + L1-факты, derive read-only) и
> **«Кольца L2»** (Сплочённость·Активность·Приходит на странице клуба). Owner-«Статистика», скрытый L3-ранг,
> `membership_history`, возраст-бейдж/майлстоны — **следующими PR**.
> Фикс мёртвого `activity_rating` — **в проде** (V30: колонка ретайрнута, сортировка дискавери и тег
> «Популярный» пересобраны как derived; см. `docs/modules/clubs.md` § «GET /api/clubs»).

---

## 1. Зачем

Клуб = ядро приложения. Выбирающему клуб нужен соц-пруф «живой ли клуб, стоит ли вступать».
Сегодня страница клуба показывает только название/описание/правила — ни одного факта о
реальной жизни клуба. Этот срез добавляет **честные публичные факты**, выводимые из уже
существующих данных, без новой схемы.

Архитектурно — закладываем **отдельный модуль-пир** `com.clubs.clubquality` (якорь `club_id`),
рядом с `com.clubs.reputation` (якорь `user_id`). Это фундамент, на который позже лягут кольца,
owner-статистика и скрытый L3-ранг (§10 дизайн-дока).

---

## 2. Границы модуля (из §10 дизайн-дока)

- `com.clubs.clubquality` — **пир** рядом с `reputation` / `club` / `event`, **не ребёнок** `club`.
- Субъект — **МЕСТО** (`club_id`), не человек. Это НЕ среднее Trust участников (запрещено —
  создало бы фарм-вектор через накрутку участников).
- Модуль **read-only**: считает агрегаты по существующим таблицам (`events`, `event_responses`,
  `clubs`). Своих таблиц в этом срезе НЕ заводит (ноль миграций).
- **Чтение чужих таблиц через jOOQ-агрегации — допустимо** и имеет прецедент: `JooqReputationRepository`
  уже читает `events`/`event_responses`. Правило «фичи не импортируют друг друга» — про импорт
  Kotlin-классов другого модуля (Service/Repository), а не про read-only SQL по общим jOOQ-таблицам.
- `reputation_ledger` в этом срезе **не читаем** (он нужен L3 для негативов — споры/ghosting).
  Когда дойдём до L3 — читать ledger через **read-port** `reputation`, не напрямую из его репозитория.

---

## 3. L1-факты (что считаем)

Все факты — **«now»**-derivable из §11.4 дизайн-дока. Видимость — `others` (публично, видит и
не-участник: это соц-пруф для «вступать ли»).

| Поле DTO | Смысл (ось будущего кольца) | Определение |
|---|---|---|
| `meetingsPerMonth: Double` | Активность | held-события за 90 дней ÷ 3, округление до 1 знака |
| `avgAttendance: Int` | Приходит (среднее на встречу) | Σ явок ÷ число финализированных встреч за 90 дней, округление |
| `coreSize: Int` | Сплочённость (ядро) | distinct юзеров с ≥3 явками («attended») по НЕ-cancelled событиям клуба, all-time |
| `ageMonths: Int` | Возраст (бейдж в строке-капшне) | полные месяцы от `clubs.created_at` до now |
| `totalMeetings: Int` | Счётчик «N встреч» | held-события all-time (past, non-cancelled) |
| `successfulSkladchinas: Int` | Счётчик «N сборов» | складчины со статусом `closed_success` |

**Определения (точные):**
- **held-событие** = `events.event_datetime < now()` AND `events.status <> 'cancelled'`.
- **meetingsPerMonth** = `count(held-событий за [now-90d, now)) / 3.0`, round(1).
- **финализированная встреча** = held-событие с `events.attendance_finalized = true`.
- **avgAttendance** = `count(event_responses.attendance = 'attended' по финализ. встречам за 90д)`
  `÷ count(distinct финализ. встреч за 90д)`, round(0). Если знаменатель 0 → `0`.
  (Встреча, куда никто не пришёл, входит в знаменатель — честно занижает среднее.)
- **coreSize** = `count(distinct user_id, имеющих ≥3 строки attendance='attended'` по НЕ-cancelled событиям
  этого клуба, без ограничения по времени). Cancelled-событие исключается, т.к. club-delete cascade может
  отменить уже отмеченное по явке событие — его строки `attended` не должны раздувать ядро (как и в
  held-фактах cancelled не считается).
- **ageMonths** = `Period.between(created_at::date, now::date).toTotalMonths()`, не меньше 0.
- **totalMeetings** = `count(held-событий all-time)` (past, non-cancelled), без окна — майлстон «N встреч».
- **successfulSkladchinas** = `count(skladchinas WHERE club_id=? AND status='closed_success')` — майлстон «первый сбор».

**Анти-фарм (важно для ревью):** в этом срезе факты — слой **L1/L2** (показываем, считаем щедро,
owner-усилие допускается). Distinct-account / абсолюты / decay / min-K — защиты слоя **L3** (скрытый
ранг), которого в этом срезе НЕТ. Поэтому self-marked явка в фактах — допустима по дизайну (§3:
owner-данные → L1/L2, бан только из L3). `coreSize` уже использует distinct-юзеров и абсолют (≥3) —
это и есть «честная» форма факта, читается приятно и не врёт о масштабе.

---

## 4. API

### `GET /api/clubs/{clubId}/quality`

- **Auth:** JWT (любой аутентифицированный пользователь). Факты `others`-видимы → ownership-проверки нет.
- **404** если клуб не найден (нет строки `clubs` с таким id).
- **Response 200:** `ClubFactsDto`

```jsonc
{
  "meetingsPerMonth": 2.7,
  "avgAttendance": 11,
  "coreSize": 8,
  "ageMonths": 14,
  "totalMeetings": 71,
  "successfulSkladchinas": 3
}
```

### `GET /api/clubs/quality/batch?ids=uuid1,uuid2,...`

Факты для **ленты Discovery** — пачкой по списку клубов (один запрос на страницу карточек, не на каждую → нет N+1).

- **Auth:** JWT. Те же `others`-видимые факты, ownership-проверки нет.
- **Параметр:** `ids` — список UUID (запятыми). Дубликаты схлопываются, размер кэпится на **50** (анти-абьюз).
- **Response 200:** `List<ClubCardFactsDto>` — по элементу на каждый **существующий** клуб (id без строки `clubs` молча пропускаются; 404 нет — частичная страница нормальна). Пустой `ids` → `[]`.
- Путь `/quality/batch` (литерал, 2 сегмента) **не коллизит** с `/{clubId}/quality` и `/{id}`.

```jsonc
// ClubCardFactsDto — трио карточки: возраст · участники · вовлечённость
{
  "clubId": "uuid",
  "ageDays": 425,            // целых дней с создания (возраст)
  "engagementPercent": 64   // distinct откликнувшихся за 90д ÷ живые участники (active+grace), 0..100
}
```
> **«участники»** в фактах НЕТ — карточка берёт `memberCount` из `ClubListItemDto`.
> **Встреч/мес и ядро намеренно НЕ на карточке** — это кольца страницы клуба (Активность / Сплочённость),
> карточка их не дублирует (минимально-достаточный набор для решения «листать или зайти»).

Оба эндпоинта живут в **новом** `ClubQualityController` (`@RequestMapping("/api/clubs")`)
внутри модуля `clubquality` — НЕ в `ClubController` (модульная граница).

---

## 5. Структура модуля (по образцу reputation)

```
backend/src/main/kotlin/com/clubs/clubquality/
├── ClubQualityController.kt   # GET /api/clubs/{clubId}/quality + GET /api/clubs/quality/batch
├── ClubQualityService.kt      # @Transactional(readOnly=true); 404 для single; batch dedupe+cap(50); маппинг
├── ClubQualityRepository.kt   # findClubFacts(clubId): ClubFacts? + findClubCardFacts(ids): List<ClubCardFacts>
├── JooqClubQualityRepository.kt # read-only агрегации; batch = одна grouped-query на метрику (no N+1)
├── ClubFacts.kt               # domain: ClubFacts (страница клуба) + ClubCardFacts (карточка ленты)
├── ClubFactsDto.kt            # HTTP DTO
└── ClubQualityMapper.kt       # ClubFacts -> ClubFactsDto
```

- **Cutoff-окна** считаются в Kotlin (`OffsetDateTime.now().minusDays(90)`) и биндятся как параметры —
  без SQL-interval, детерминированно и тестируемо.
- **Существование клуба:** репозиторий возвращает `ClubFacts?` (null, если строки `clubs` нет);
  сервис кидает общий `NotFoundException("Club not found")` (тот же, что `ClubService`), который
  `GlobalExceptionHandler` мапит в HTTP 404.

---

## 6. Frontend

- `api/clubQuality.ts` → `getClubQuality(clubId): Promise<ClubFactsDto>` (паттерн `api/clubs.ts`).
- `queries/clubQuality.ts` → `useClubQualityQuery(clubId)` (TanStack Query, `enabled: Boolean(clubId)`).
- Тип `ClubFactsDto` в `types/api.ts`.
- `components/club/ClubQualityFacts.tsx` — **единый блок** качества (`rd-glass`, **без заголовков-секций**,
  всё по центру; дизайн-вариант «кольца + лёгкая строка», 2026-06-17). Содержит:
  1. **Три L2-кольца** (`QualityRing`), равные flex-колонки (`flex:1 1 0`) → промежутки одинаковые;
     подписи принудительно в **2 строки** (по слову, `white-space: pre-line`) для ровной высоты:
     - Сплочённость → подпись **«основа клуба»**, центр = `coreSize` «чел.», зелёная `--live`
     - Активность → подпись **«частота встреч»**, центр = `meetingsPerMonth` «/мес», `--accent`
     - Приходит → подпись **«обычно приходит»**, центр = `avgAttendance` «из M» (M = `memberCount`), `--accent`
  2. Разделитель `.q-divider` + **строка-капшн** `.qstat-line` (по центру, через точку): возраст-бейдж
     (золотой) + живые счётчики «N встреч»/«N сборов».
  Клуб без событий (`hasActivity=false`) → колец нет, только строка «🎂 Клубу N мес · пока нет встреч».
- `components/club/QualityRing.tsx` — презентационный донат на **4 равных сектора** (скруглённые края,
  `rotate(-126.2)`); `level` (0..4) секторов закрашено `color`, центр — distinct-абсолют. Стиль согласован
  с `DonutRing` (тот же viewBox/радиус/overlay).
- `components/club/qualityLevels.ts` — чистые пороги `level` (0..4) по осям. **Косметика, не L3-калибровка:**
  пороги намеренно щедрые и легко настраиваются; уровень лишь красит сектора, центр всегда = абсолют (§3).
  - Сплочённость: 0 · <4 · <8 · <20 · 20+
  - Активность (встреч/мес): 0 · <2 · <4 · <8 · 8+
  - Приходит (доля `avgAttendance / memberCount`): 0 · <20% · <40% · <60% · 60%+
- `components/club/clubMilestones.ts` (чистая логика строки-капшна): `ageBadge` (<12 «Клубу N мес» /
  12–23 «Год клубу» / 24+ «Клубу N лет») + `counters` — **плоские живые итоги, без порогов/замков**:
  «N встреч» (`totalMeetings`) + «N сборов» (`successfulSkladchinas`), каждый с плюрализацией, показ при `>0`.
  «Преданные» не дублируем — это кольцо «основа клуба». Всё fact-backed, **без очков**.
- Встраивание в `pages/ClubPage.tsx`: единый блок после «О клубе», **до** табов/lock — виден всем зрителям.
  Fail-soft: при загрузке/ошибке блок не рендерится. (Отдельного `ClubAchievements` больше нет — слит сюда.)

### Карточка Discovery (лента, §11.1)
- `api/clubQuality.ts` → `getClubQualityBatch(ids): Promise<ClubCardFactsDto[]>` (`GET /api/clubs/quality/batch`).
- `queries/clubQuality.ts` → `useClubCardFacts(pages)` — **один batch на страницу** ленты через `useQueries`
  (каждая страница ≤20 id, кэш на страницу: нет ни усечения кэпом, ни перефетча прошлых страниц при скролле);
  возвращает `Map<clubId, ClubCardFactsDto>` для O(1) поиска по карточке.
- `components/ClubCard.tsx` — принимает опциональный `facts`; рендерит **сегментированное трио** (стиль `.mrow`
  из мокапа): **возраст (дни) · участники · вовлечённость%** (последняя зелёная). Ценник — чипом на баннере.
  Без `facts` (ещё грузится) трио не показывается, мета = «город · N участников» — fail-soft, деградация мягкая.
  - **Метрики выбраны минимально-достаточными и БЕЗ дублей со страницей клуба:** встреч/мес и ядро —
    это кольца Активность/Сплочённость на странице; на карточке их нет. Возраст/участники/вовлечённость на
    странице полноценными числами не показаны.
- `pages/DiscoveryPage.tsx`: `useClubCardFacts(data.pages)` → `facts={factsByClub.get(club.id)}` на каждую карточку.
- Тип `ClubCardFactsDto` в `types/api.ts`. **Только данные качества** на существующей карточке — структурная
  перестройка (аватар-от-баннера, направления, постоянное место) и soft-ранг «Топ-5» (L3) — НЕ в этом срезе (§11 MVP).

---

## 7. Критерии приёмки

1. `GET /api/clubs/{id}/quality` возвращает 200 + 6 фактов для существующего клуба.
2. Несуществующий клуб → 404 (не 500).
3. Клуб без событий → `meetingsPerMonth=0.0, avgAttendance=0, coreSize=0`, `ageMonths` корректен.
4. `meetingsPerMonth` считает только held-события (будущие и `cancelled` — не входят).
5. `avgAttendance` считает только финализированные встречи; встреча с 0 явок занижает среднее.
6. `coreSize` = distinct-юзеры с ≥3 «attended», не сумма явок.
7. Блок «Качество клуба» виден на странице клуба всем зрителям; молодой/пустой клуб не выглядит сломанным.
8. Backend: `./gradlew test` зелёный; есть unit-тест на агрегации (позитив + пустой клуб + 404).
9. Frontend: `npm run build` зелёный; `npm test` зелёный.
10. `totalMeetings` = held all-time (без окна); `successfulSkladchinas` = только `closed_success`.
11. Единый блок без заголовков-секций: строка-капшн всегда показывает возраст-бейдж; счётчики «N встреч»/«N сборов» — живые числа, показ при >0.
12. `GET /api/clubs/quality/batch?ids=` возвращает по элементу на существующий клуб; несуществующие id пропускаются (без 404); пустой ввод → `[]`; дубликаты схлопываются; размер кэпится на 50.
13. `engagementPercent` = distinct откликнувшихся за 90д ÷ живые участники (active+grace), 0..100; деление-на-ноль (0 участников) → 0; >100% клампится к 100. `ageDays` = целых дней с создания (дни, не месяцы).
14. Карточка Discovery: при наличии `facts` — сегментированное трио **возраст · участники · вовлечённость**; без фактов мета = «город · N участников» (трио скрыто), деградация мягкая. Встреч/мес и ядро на карточке НЕ показываются (не дублируем кольца страницы).

---

## 8. Вне среза (следующими PR)

- Скрытый L3-ранг (композит 4 осей) + soft-ранг «★ Топ-5 в категории» — нужна калибровка §8, читает ledger через read-port. **Следующий срез после owner-«Статистики»** (xhigh + ultracode).
- Структурная перестройка карточки/страницы (направления, новости, аватар-от-баннера, постоянное место) — future-слой, свои схемы.

**Зашиплено после этой спеки:**
- Фикс мёртвого `activity_rating` (V30 — колонка ретайрнута, сортировка дискавери + тег «Популярный»
  пересобраны как derived). См. `docs/modules/clubs.md` § «GET /api/clubs».
- **Карточка Discovery (§11.1):** batch-эндпоинт `GET /api/clubs/quality/batch` + метрики (встреч/мес ·
  вовлечённость% + возраст/достижения) на карточке клуба в ленте. См. §4, §6 выше. Только данные качества;
  структурная перестройка карточки и L3 soft-ранг — отдельными срезами.
- **owner-«Статистика»** (рычаги/нуджи/зона внимания, owner-only) — см. §9 ниже.

---

## 9. Owner-«Статистика» — приватная панель организатора

**Статус:** `feature/owner-statistics` · **Создано:** 2026-06-21 · Дизайн: `docs/backlog/club-quality-gamification.md` §11.3, §11.4 + мокап `docs/design/club-quality-redesign/mockups/final.html` блок 3.

### 9.1 Зачем
Организатору нужен ответ на вопрос «как улучшить клуб и заработать?». Подблок «Статистика» в управлении
клубом (⚙️) даёт **рычаги роста** (метрики с трендом), **actionable-нуджи** и приватную **«зону внимания»**
(негативы оси «Надёжность организатора»). Это **единственный срез трека с приватными данными** —
ownership-проверки обязательны.

### 9.2 Границы и инварианты
- **Слой L1/L2** (показ, считаем щедро, owner-данные допустимы). Анти-фарм (distinct/абсолюты/decay/min-K) —
  это L3, которого здесь НЕТ.
- **Модуль `clubquality`** (пир): читает `transactions`, `membership_history`, `applications`,
  `event_responses`, `events`, `skladchina_participants`, `skladchinas`, `memberships`, `clubs` —
  read-only jOOQ-агрегации (charter §10 дизайн-дока: clubquality читает события/транзакции/складчину).
- **`reputation_ledger` НЕ читаем** (правило §2 спеки). Поэтому «споры по явке» считаем напрямую из
  `event_responses`. ⚠️ `attendance = 'disputed'` — **временное** состояние: резолв (организатором или по
  истечении окна) возвращает `attended`/`absent` и ставит `dispute_terminal = true`, маркер `disputed`
  стирается. Считать только `disputed` → почти всегда 0. Поэтому считаем **кумулятивно** по персистентным
  маркерам: `attendance='disputed' OR dispute_terminal=true OR dispute_note IS NOT NULL` (открытые + разрешённые
  споры). Сигнал = «член поднял спор», независимо от исхода (design §2 «споры (member-raised)»).
- **Выручка НЕ дублируется** — она во вкладке «Финансы».
- **Надёжность организатора в проблемной зоне — ТОЛЬКО владельцу** (приватный нудж; публично → нельзя,
  иначе dispute-suppression). Наружу (карточка/страница) ничего из §9 не выводится.

### 9.3 Адаптация панели: paid vs free
`clubType` = `paid` если `clubs.subscription_price > 0`, иначе `free`. Набор рычагов адаптируется:

| Рычаг | paid | free |
|---|---|---|
| Удержание (продлевают), % + тренд | ✅ renewal-rate | — (null) |
| Не продлили / Ушли за 30д (count) | ✅ «Не продлили» | ✅ «Ушли» |
| Вернулись за 30д (count) | — | ✅ |
| Вовлечённость, % + тренд | ✅ | ✅ |
| Оплата складчин, % + тренд | ✅ если есть складчины | ✅ если есть складчины |
| Заявки ждут ответа (count) | ✅ если `access_type='closed'` | ✅ если `access_type='closed'` |

Сервер всегда возвращает все поля (nullable там, где неприменимо); фронт собирает список рычагов по
`clubType` + nullability. Поля без данных (`null`) — не рендерятся.

### 9.4 Окна и пороги (константы)
- `RETENTION_WINDOW_DAYS = 30` (удержание/отток/возвраты — «в этом месяце»)
- `ENGAGEMENT_WINDOW_DAYS = 90` (совпадает с вовлечённостью карточки Discovery — один и тот же смысл)
- `SKLADCHINA_WINDOW_DAYS = 90`, `ATTENTION_WINDOW_DAYS = 90` (авто-отклонения, отменённые встречи)
- `STALE_APPLICATION_HOURS = 24` (заявка «висит» — близка к 48ч авто-отклонению)
- `ENGAGEMENT_NUDGE_THRESHOLD = 70` (%) — ниже → нудж «напомните о встрече»

Окна считаются в Kotlin (`OffsetDateTime.now().minusDays(...)`) и биндятся параметрами — детерминированно
и тестируемо (как в `JooqClubQualityRepository`).

### 9.5 Метрики (точные определения)

**Рычаги:**
- **retentionPercent** (paid): `renewals / (renewals + churned) × 100`, округление, clamp 0..100; `null` если
  `(renewals + churned) == 0`.
  - `renewals` = distinct `transactions.user_id` с `type='renewal' AND status='completed'` за окно 30д.
  - `churned` = count `membership_history` строк `event ∈ {left, expired}` за окно 30д (у paid-клуба все
    member-membership'ы платные; организатор в лог не пишется).
- **churnedThisPeriod** (оба): count `membership_history` `event ∈ {left, expired}` за 30д. Лейбл: paid →
  «Не продлили», free → «Ушли».
- **rejoinedThisPeriod** (рендерится для free): count `membership_history` `event='rejoined'` за 30д.
- **engagementPercent** (оба): distinct откликнувшихся (`event_responses`) на non-cancelled события клуба
  за 90д ÷ живые участники (`memberships.status ∈ {active, grace_period}`) × 100, clamp 0..100; 0 если живых нет.
  (То же определение, что `engagementPercent` карточки — консистентно.)
- **skladchinaPaidPercent** (если есть складчины в окне): `paid / terminal × 100`, где по складчинам клуба,
  **закрытым** (`closed_at`) в окне 90д: `paid` = участники `status='paid'`, `terminal` = участники в
  статусах с принятым платёжным решением `{paid, declined, expired_no_response}`. **Исключены:** `pending`
  (ещё не решили) и `released` (сбор закрыт досрочно, обязательства сняты — не должен занижать %).
  `null` если закрытых складчин в окне нет.
- **pendingApplications** (если `access_type='closed'`): count `applications` `status='pending'` клуба.
  `stalePendingApplications` ⊆ — те же, у кого `created_at < now-24ч`. `null` для open/private.

**Тренд** (для retention, engagement, skladchina) — `TrendDto{direction: up|down|flat, delta}`:
- current = метрика за `[now-W, now)`, prior = метрика за `[now-2W, now-W)`.
- `delta = round(current) - round(prior)` (в п.п. для процентов); `direction`: `flat` если `delta==0`,
  иначе `up`/`down` по знаку.
- **Suppression (тренд = `null`):** если в prior-окне НЕТ базы сравнения — нельзя отличить «было плохо» от
  «не было данных». Правила «есть база»:
  - retention: `(renewals_prior + churned_prior) > 0`;
  - engagement: ≥1 non-cancelled событие в prior-окне;
  - skladchina: ≥1 складчина с `closed_at`/`deadline` в prior-окне.
  - **Следствие:** retention-тренд почти у всех `null` сейчас (`membership_history` без backfill, blind ~1
    цикл) — это by design, стрелка появится, как накопится история. engagement/skladchina-тренды доступны
    сразу (их таблицы историчны).

**Зона внимания (owner-only негативы):**
- **attendanceDisputes**: count `event_responses` по non-cancelled событиям клуба, где спор когда-либо
  поднимался — `attendance='disputed' OR dispute_terminal=true OR dispute_note IS NOT NULL` (кумулятивно,
  all-time; см. §9.2 — `disputed` транзиентен, поэтому по персистентным маркерам).
- **totalMeetings**: held-события all-time (past, non-cancelled) — знаменатель-контекст «N из M» (как §3).
- **autoRejectedApplications** (если `access_type='closed'`): count `applications` `status='auto_rejected'`
  за 90д; `null` для open/private.
- **cancelledMeetings**: count `events` `status='cancelled'` клуба за 90д.

**Нуджи «Что сделать сейчас»** — whitelist из 3, вычисляются на фронте из вернувшихся рычагов (чистая
деривация L1/L2 owner-данных, формулу L3 не раскрывают):
1. `answer_applications` — если `pendingApplications > 0` (severity red если `stalePendingApplications > 0`).
2. `win_back` — если `churnedThisPeriod > 0`.
3. `remind_engagement` — если `engagementPercent < 70` и есть живые участники.

### 9.6 API

```
GET /api/clubs/{clubId}/stats
  Auth: JWT + @RequiresOrganizer(clubIdParam = "clubId") — только владелец клуба
  200: ClubStatsDto
  403: "Only the club organizer can perform this action" (не владелец)
  404: "Club not found" (нет клуба) — оба из AuthorizationAspect, ДО тела
```

```jsonc
// ClubStatsDto
{
  "clubType": "paid",                 // "paid" | "free"
  "retentionPercent": 78,             // null для free / нет данных
  "retentionTrend": { "direction": "down", "delta": -4 },  // null если нет базы
  "churnedThisPeriod": 3,
  "rejoinedThisPeriod": 1,
  "engagementPercent": 72,
  "engagementTrend": null,
  "skladchinaPaidPercent": 85,        // null если складчин нет
  "skladchinaPaidTrend": { "direction": "up", "delta": 6 },
  "pendingApplications": 2,           // null если access_type != closed
  "stalePendingApplications": 2,      // null если access_type != closed
  "attendanceDisputes": 1,
  "totalMeetings": 71,
  "autoRejectedApplications": 3,      // null если access_type != closed
  "cancelledMeetings": 0
}
```

Эндпоинт — метод `getStats` в существующем `ClubQualityController` (`@RequestMapping("/api/clubs")`);
делегирует в новый `ClubStatsService`. Литерал `/{clubId}/stats` не коллизит с `/{clubId}/quality`,
`/quality/batch`, `/{id}`.

### 9.7 Структура (рядом с фактами, отдельный набор — не раздуваем ClubQualityRepository)
```
clubquality/
├── ClubStats.kt              # domain: ClubStats, Trend, ClubType
├── ClubStatsDto.kt           # HTTP DTO + TrendDto
├── ClubStatsRepository.kt    # findClubStats(clubId): ClubStats?
├── JooqClubStatsRepository.kt# read-only оконные агрегации + тренды
├── ClubStatsService.kt       # @Transactional(readOnly); маппинг
└── ClubStatsMapper.kt        # ClubStats -> ClubStatsDto
```
Ownership — через `@RequiresOrganizer` (shared `common.auth`), не дублируем проверку в сервисе. Аспект уже
кидает 404 (нет клуба) и 403 (не владелец) до тела, поэтому `findClubStats` вызывается для существующего
клуба владельцем; возвращает `ClubStats` всегда (не `null` на практике, но контракт `?` сохраняем как в
`findClubFacts`).

### 9.8 Frontend
- `types/api.ts`: `ClubStatsDto`, `TrendDto`.
- `api/clubStats.ts` → `getClubStats(clubId)`.
- `queries/clubStats.ts` → `useClubStatsQuery(clubId)` (`enabled: Boolean(clubId)`).
- `OrganizerClubManage.tsx`: новый таб **«Статистика»** в полоске табов (рядом с Участники/Финансы/Настройки;
  мокап «Обзор/Статистика/Финансы» предполагает будущую перестройку управления — «Обзор» пока нет).
- `components/manage/ClubStatsTab.tsx` (+ под-компоненты): 3 блока — **Рычаги роста** (рычаг = лейбл + значение
  + стрелка тренда ↑↓ с тоном), **Что сделать сейчас** (нуджи из whitelist), **Зона внимания** (👁 только вам).
  Маппинг значений → лейблы/нуджи — в `clubStats` mapper-хелпере (не inline в компоненте).

### 9.9 Acceptance Criteria
1. `GET /api/clubs/{id}/stats` владельцем paid-клуба → 200 с `clubType=paid`, заполненными рычагами + зоной внимания.
2. Не владелец → 403; несуществующий клуб → 404 (оба до тела, из `AuthorizationAspect`).
3. Запрос без JWT → 401.
4. free-клуб: `retentionPercent=null`, `retentionTrend=null`; `churnedThisPeriod`/`rejoinedThisPeriod` заполнены.
5. Клуб без складчин в окне → `skladchinaPaidPercent=null`, `skladchinaPaidTrend=null`.
6. Клуб не `closed` → `pendingApplications=null`, `stalePendingApplications=null`, `autoRejectedApplications=null`.
7. Тренд `null`, если в prior-окне нет базы (retention при пустом `membership_history`; engagement при 0 событий prior-окна).
8. Тренд считается, когда база есть: engagement/skladchina — сразу (историчные таблицы); retention — по мере накопления `membership_history`.
9. `attendanceDisputes` = кумулятивно споры, когда-либо поднятые (открытые + разрешённые: `disputed`/`dispute_terminal`/`dispute_note`), all-time; `cancelledMeetings`/`autoRejectedApplications` — за 90д.
10. Выручка/доход в `/stats` НЕ возвращается (только в `/finances`).
11. Backend `./gradlew test` зелёный (paid+free, тренд с базой и без, ownership 403). Frontend `npm run build` + `npm test` зелёные.
12. Фронт: таб «Статистика» виден владельцу; нуджи появляются по условиям §9.5; «зона внимания» помечена 👁 только вам.
