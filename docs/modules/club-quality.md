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
| `coreSize: Int` | Сплочённость (ядро) | distinct **НЕ-owner** юзеров с ≥3 явками («attended») по НЕ-cancelled событиям клуба, all-time |
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
  этого клуба, без ограничения по времени, **исключая `clubs.owner_id`**). Организатор исключён: он сам
  отмечает свою явку, поэтому его учёт раздувал «основу клуба» (она никогда не падала бы ниже 1) и смешивал
  организатора с членским ядром; owner-исключение согласовано с правилом L3 «Сплочённость» (gamification §2).
  Cancelled-событие исключается, т.к. club-delete cascade может
  отменить уже отмеченное по явке событие — его строки `attended` не должны раздувать ядро (как и в
  held-фактах cancelled не считается).
- **ageMonths** = `Period.between(created_at::date, now::date).toTotalMonths()`, не меньше 0.
- **totalMeetings** = `count(held-событий all-time)` (past, non-cancelled), без окна — майлстон «N встреч».
- **successfulSkladchinas** = `count(skladchinas WHERE club_id=? AND status='closed_success')` — майлстон «первый сбор».

**Анти-фарм (важно для ревью):** в этом срезе факты — слой **L1/L2** (показываем, считаем щедро,
owner-усилие в событиях допускается). Distinct-account / абсолюты / decay / min-K — защиты слоя **L3**
(скрытый ранг). Self-marked явка в фактах допустима по дизайну (§3: owner-данные → L1/L2). `coreSize`
использует distinct-юзеров и абсолют (≥3) — честная форма факта. **Но самого организатора `coreSize`
исключает** (он не «член ядра»; иначе кольцо никогда не ниже 1) — owner-exclusion применяется к
ПЕРСОНЕ-владельцу, а не к owner-driven событиям.

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

- Структурная перестройка карточки/страницы (направления, новости, аватар-от-баннера, постоянное место) — future-слой, свои схемы.
- **L3 v2:** co-occurrence-граф (включить под атакой), складчина-финансы в L3 (когда складчина пойдёт через Stars), transfer-probation (вторичный рынок владения), калибровка весов на выросшей прод-выборке.

**Зашиплено после этой спеки:**
- Фикс мёртвого `activity_rating` (V30 — колонка ретайрнута, сортировка дискавери + тег «Популярный»
  пересобраны как derived). См. `docs/modules/clubs.md` § «GET /api/clubs».
- **Карточка Discovery (§11.1):** batch-эндпоинт `GET /api/clubs/quality/batch` + метрики (встреч/мес ·
  вовлечённость% + возраст/достижения) на карточке клуба в ленте. См. §4, §6 выше. Только данные качества;
  структурная перестройка карточки и L3 soft-ранг — отдельными срезами.
- **owner-«Статистика»** (рычаги/нуджи/зона внимания, owner-only) — см. §9 ниже.
- **Скрытый L3-ранг + soft-ранг «★ Топ-5 в категории»** (за фиче-флагом, default off) — см. §10 ниже.

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
- **Win-back ростер (`/churned-members`)** показывает display-identity ушедших (имя/аватар) — **только
  владельцу** клуба, в окне 30д. Это не новая утечка: владелец уже видел этих людей участниками своего клуба,
  и карточка — та же, что в «Участники». PII минимизирован: DTO отдаёт только id/имя/аватар/leftAt (без
  telegram_id/username/контактов).

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
- **churnedThisPeriod** (оба): **distinct** участников, которые `left`/`expired` за 30д **И сейчас НЕ
  active/grace** (ушли и не вернулись — left-then-rejoined исключаются). Лейбл: paid → «Не продлили», free →
  «Ушли». **= длине ростера win-back** (`GET …/churned-members`) by construction (общий предикат). ⚠️ Это НЕ
  то же, что churn-events в retention-знаменателе (`renewals/(renewals+churnEvents)` использует **поток**
  событий left/expired, отдельный счёт) — разные числа для разных целей, см. код-комменты.
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
2. `win_back` — если `churnedThisPeriod > 0`. **Раскрывающийся:** тап разворачивает ростер ушедших
   (`GET …/churned-members`, лениво) — строки участников с тапом на **ту же карточку профиля**, что в табе
   «Участники» (`MemberProfileModal` + `GET …/members/{userId}`). Будущие действия (пригласить на встречу) — backlog.
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

```
GET /api/clubs/{clubId}/churned-members
  Auth: JWT + @RequiresOrganizer(clubIdParam = "clubId") — только владелец
  200: List<ChurnedMemberDto>  // win-back ростер для нуджа «Верните N ушедших»
  403/404: как у /stats
```
```jsonc
// ChurnedMemberDto — лёгкая строка списка (карточку профиля грузит существующий /members/{userId})
{ "userId": "uuid", "firstName": "Анна", "lastName": "К.", "avatarUrl": null, "leftAt": "ISO datetime" }
```
Ростер = distinct участников `left`/`expired` за 30д, **сейчас не active/grace**, по `leftAt` (самый поздний
уход) DESC. Литерал `/{clubId}/churned-members` не коллизит с `/{id}/members` (`MemberController`).

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
- `components/manage/WinBackNudge.tsx`: нудж «Верните N ушедших» — раскрывающийся (`useChurnedMembersQuery`,
  ленивая загрузка по `enabled`), строки ростера в стиле `rd-rep-row`; тап → **переиспользуемая** `MemberProfileModal`
  (через `MemberListItemDto`-stub: id/имя/аватар, остальное модалка дотягивает по userId). Новых карточек/DTO
  профиля НЕ заводим — только лёгкий `ChurnedMemberDto` для строки списка.

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
13. `GET …/churned-members` владельцем → ростер distinct currently-gone (left/expired 30д, не active/grace), newest-first; rejoined-обратно и вне окна исключены; **длина ростера = `churnedThisPeriod`**. Не владелец → 403.
14. Фронт: нудж «Верните N ушедших» раскрывается в список; тап по участнику → та же `MemberProfileModal`, что в «Участники»; ленивая загрузка (запрос только при раскрытии).

---

## 10. L3 — скрытый ранг + soft-ранг «★ Топ-5 в категории»

L3 — **внутренний** композитный ранг клуба (субъект = МЕСТО). Наружу не выходит ни число, ни разложение —
**единственный** видимый дериватив это **boolean «★ Топ-5 в категории»** на карточке Discovery. Дизайн-контракт:
`docs/backlog/club-quality-gamification.md` §1–8. Реализация v1 — гейт-тяжёлая и консервативная (на ~10-клубном
проде статистической калибровки нет; пороги — **принципиальные дефолты**, помечены PROVISIONAL, калибруются
одним файлом `ClubRankPolicy` без SQL).

### 10.1 Фиче-флаг (видимость бейджа)
- `club.rank.badge-enabled` (env `CLUB_RANK_BADGE_ENABLED`, default **`false`**). Машинерия и пересчёт работают
  всегда; флаг управляет **только** показом бейджа. На ~10 клубах бейдж = шум, поэтому он подавлен, пока
  категории не наполнятся. Флипается env-переменной в Coolify без редеплоя.
- Дополнительный in-code `GLOBAL_RANK_FLOOR=8`: пока по проду < 8 RANKED-клубов, бейдж не показывается даже при `true`.

### 10.2 Существование ранга (гейт)
`is_ranked` ⟺ **`Σ credibility(credibleCore) ≥ EFFECTIVE_K (8.0)`** — гейт **credibility-взвешенный**, НЕ head-count
(кольцо дешёвых аккаунтов не «пробивает» порог числом). Аккаунт с raw-credibility < `CRED_MIN (0.2)` не считается
вовсе. Ниже гейта → **UNRANKED** (не «низкий ранг», бейджа нет). `credibleCore` = distinct-аккаунты из §10.4 Diversity.

### 10.3 Credibility аккаунта (анти-Sybil вес), `[0,1]`
`credibility = ageW × signalW × footprintW`, считается из `users` + кросс-владельческого следа (read-port), БЕЗ
отдельной таблицы. Owner-концентрированный аккаунт (≥`OWNER_CONCENTRATION_THRESHOLD 0.6` следа в клубах ВЛАДЕЛЬЦА
этого клуба) придавливается к `CRED_MIN` — не независимое свидетельство (лёгкая co-occurrence-проверка без графа).

| Множитель | Бакеты |
|---|---|
| `ageW` (`users.created_at`) | ≥180д→1.0 · 90–180→0.8 · 30–90→0.6 · <30→0.4 |
| `signalW` | 0.6 + username?0.2 + avatar?0.2 → 0.6…1.0 |
| `footprintW` (distinct **OWNERS** клубов с kept-исходом, не сырые клубы) | ≥3 owners→1.0 · 2→0.85 · 0/1→0.6 |

Footprint берётся через `LedgerReadPort.footprintByUser` (модуль `reputation`); kept-kinds = **`ironclad`/`spontaneous`
ТОЛЬКО** — `skladchina_paid` owner-авторизуем (organizer-mark-paid, нет `charge_id`) и **исключён** из всех входов L3.

### 10.4 Оси (абсолюты, decay, owner-устойчивы)
Все оси — `Σ credibility(distinct аккаунт) × decay`, абсолют (НЕ доля), owner исключён. `decay = 0.5^(ageDays/HL)`,
`HALF_LIFE_POS=120д` (позитивы) / `HALF_LIFE_NEG=90д` (негативы), future-dated→1.0. Hard-cutoff: сигналы старше
`HARD_CUTOFF_DAYS=365` не читаются. Нормировка `norm(x)=1−0.5^(x/anchor)`.

| Ось | Вес (paid) | Сигнал (источник) | Floor / anchor |
|---|---|---|---|
| **Diversity** (Сплочённость) | 0.35 | distinct core: ≥`CORE_MIN_EVENTS 2` attended на разных событиях + **member-голос до события** (`stage_1_timestamp < event_datetime`) + temporal-spread ≥`MIN_EVENT_GAP_DAYS 7` (`event_responses`) | gate / anchor 12 |
| **Paying** (Финансы, Stars only) | 0.30 | distinct payers (`transactions.completed`, type `subscription`, **`charge_id` not null**) + disjoint `renewal`-бонус; scam-payer (left ≤14д после оплаты) ×0.5 | `PAY_MIN 3`, free→ось off / anchor 8 |
| **Demand** (Отклик) | 0.20 | distinct member-голосующие (`stage_1_timestamp`), 90д | `VOTE_MIN 8` / anchor 12 |
| **Activity** | 0.15 | события с ≥`EVENT_MIN_ATTENDEES 4` distinct **квалифицирующих** attended (тот же member-голос-до-события) | `EVENT_MIN 2` / anchor 6 |

**Free-клуб** (`subscription_price=0`): Paying off, вес 0.30 перераспределён → Diversity 0.50 / Demand ≈0.286 / Activity ≈0.214.

### 10.5 Негативы и множители
Негативы читаются **напрямую** из `event_responses`/`events`/`applications`/`skladchina_participants` (НЕ из ledger —
в нём их нет как отдельного kind), identity спорщика **не селектится**. Все capped + decay (90д), вычитаются из base ∈ [0,1]:
- Dispute (`disputed`∨`dispute_terminal`∨`dispute_note`) ·0.05, cap 0.30 · Ghosting (`finalized ∧ ¬marked`) ·0.07, cap 0.40 ·
  AutoReject (closed-клубы) + SkladchinaGhost (`expired_no_response`) по ·0.03, совместный cap 0.20.
- `anomalyMultiplier ∈ [0.7,1.0]`: «слишком чисто при объёме» (core ≥10 И ноль споров/ghosting/churn) → −0.1.
- `tenureFactor = min(1, clubAgeDays/90)`. `CoOccurrenceCollapse`/`TransferProbation` = stub 1.0 (v2).
- `rank_score = max(0, (base − penalties) × anomaly × tenure)` (clamp ≥0).

### 10.6 «★ Топ-5 в категории» (единственный внешний выход)
Партиция — `clubs.category` (immutable, set-once at create — это **предусловие L3**, cooldown не нужен). Бейдж выдаётся,
если ВСЕ условия (`ClubRankPolicy.topInCategory`): флаг on · всего RANKED ≥ `GLOBAL_RANK_FLOOR 8` · **per-owner cap**
(один владелец = max 1 клуб в категории — убивает «производство категории») · ранкед-клубов в категории ≥
`MIN_CATEGORY_SIZE 6` · позиция ≤5 · `rank_score ≥ BADGE_SCORE_FLOOR 0.20` · отрыв от 6-го ≥ `SELECTIVITY_EPS 0.05`.

### 10.7 Хранение и пересчёт
- Таблица `club_rank` (V32): `club_id, owner_id, category, rank_score, is_ranked, effective_k, computed_at`. **INTERNAL** —
  `rank_score`/`effective_k` НИКОГДА не сериализуются (enforce: `ClubRankContractTest`).
- `ClubRankScheduler` `@Scheduled(fixedDelay=6ч, initialDelay=6ч)` → `ClubRankService.recomputeAll()`: грузит сигналы →
  `ClubRankPolicy.computeRank` → upsert `ON CONFLICT (club_id) DO UPDATE` + `pg_advisory_xact_lock`. Лог — только counts.
- Бейдж едет существующим `GET /api/clubs/quality/batch` → `ClubCardFactsDto.topInCategory: Boolean` (0 нового фетча).

### 10.8 Границы модуля
- `clubquality` читает ledger ТОЛЬКО через `LedgerReadPort` (не `JooqReputationRepository`), порт возвращает counts, не Trust.
- **Инвариант:** club-L3 ≠ среднее member-Trust — машинно: композит складывает distinct-account абсолюты, нет `AVG(trust)`.

### 10.9 Слои
`ClubQualityController → ClubRankService(@Transactional) → ClubRankRepository / JooqClubRankRepository → ClubRankPolicy`
(pure object) + domain `ClubRank`/`ClubRankSignals`/`CredibilityInput`/`AccountOutcome`/`RankedClub`.
Read-port: `reputation/{LedgerReadPort, JooqLedgerReadAdapter}`.

### 10.10 Acceptance Criteria
1. `Σcredibility(core) < 8.0` → `is_ranked=false`, `rank_score=0`; кольцо из 8 дешёвых одноклубных аккаунтов (Σcred≈1.9) → UNRANKED.
2. 8 полностью-credible аккаунтов → `is_ranked=true`, `rank_score>0`, `effective_k=8`.
3. attended без member-голоса-до-события (owner-marked) НЕ квалифицирует core → клуб не ранкается.
4. `skladchina_paid` исключён из footprint read-port; складчина не попадает в Paying (она не пишет в `transactions`).
5. Free-клуб: Paying off, веса 0.50/0.286/0.214, сумма 1.0.
6. Бейдж: флаг off → пусто; < `GLOBAL_RANK_FLOOR` → пусто; 6 клубов одного владельца в категории → per-owner cap → бейджа нет;
   категория из 6 разных владельцев → топ-5 (отрыв от 6-го ≥ eps, score ≥ floor) получают бейдж, 6-й — нет.
7. `rank_score`/`effective_k`/разложение НЕ в любом `*Dto` (`ClubRankContractTest`).
8. Backend `./gradlew test` зелёный (`ClubRankPolicyTest` pure + `ClubRankIntegrationTest` real-Postgres + `ClubRankContractTest`).
   Frontend: бейдж рендерится только при `topInCategory=true` (`ClubCard.test.tsx`).
