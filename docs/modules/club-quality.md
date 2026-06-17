# Модуль «Качество клуба» (clubquality)

**Статус:** Фундамент в проде (#69) · L2-кольца — `feature/club-quality-rings` · **Создано:** 2026-06-17
**Дизайн-контракт:** `docs/backlog/club-quality-gamification.md` (§0–11, design locked).
Память: [[project_club_quality_track]].

> Спека покрывает срезы **«Фундамент»** (модуль `com.clubs.clubquality` + L1-факты, derive read-only) и
> **«Кольца L2»** (Сплочённость·Активность·Приходит на странице клуба). Owner-«Статистика», скрытый L3-ранг,
> `membership_history`, фикс `activity_rating`, возраст-бейдж/майлстоны — **следующими PR**.

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
| `ageMonths: Int` | Возраст (бейдж в «Достижениях») | полные месяцы от `clubs.created_at` до now |
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
  "ageMonths": 14
}
```

Эндпоинт живёт в **новом** `ClubQualityController` (`@RequestMapping("/api/clubs")`,
метод `@GetMapping("/{clubId}/quality")`) внутри модуля `clubquality` — НЕ в `ClubController`
(модульная граница).

---

## 5. Структура модуля (по образцу reputation)

```
backend/src/main/kotlin/com/clubs/clubquality/
├── ClubQualityController.kt   # GET /api/clubs/{clubId}/quality
├── ClubQualityService.kt      # @Transactional(readOnly=true); проверка существования клуба → 404; маппинг
├── ClubQualityRepository.kt   # интерфейс: findClubFacts(clubId): ClubFacts?  (null = клуб не найден)
├── JooqClubQualityRepository.kt # read-only агрегации по events/event_responses/clubs
├── ClubFacts.kt               # domain: ClubFacts(meetingsPerMonth, avgAttendance, coreSize, ageMonths)
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
- `components/club/ClubQualityFacts.tsx` — блок «Качество клуба»: **три L2-кольца** (`QualityRing`).
  Оси (дизайн §11.2) и их **видимые подписи / центр**:
  - Сплочённость → подпись **«основа клуба»**, центр = `coreSize` «чел.», зелёная `--live`
  - Активность → подпись **«частота встреч»**, центр = `meetingsPerMonth` «/мес», `--accent`
  - Приходит → подпись **«обычно приходит»**, центр = `avgAttendance` «из M», `--accent`
  - M = `memberCount` (проп из `ClubPage`, знаменатель «N из M»). Возраст и майлстоны — в отдельном блоке
  «Достижения» (см. ниже). Клуб без событий (`hasActivity=false`) → честный empty-state
  «Пока нет данных о встречах — появятся после первых встреч с отметками».
- `components/club/QualityRing.tsx` — презентационный донат на **4 равных сектора** (скруглённые края,
  `rotate(-126.2)`); `level` (0..4) секторов закрашено `color`, центр — distinct-абсолют. Стиль согласован
  с `DonutRing` (тот же viewBox/радиус/overlay).
- `components/club/qualityLevels.ts` — чистые пороги `level` (0..4) по осям. **Косметика, не L3-калибровка:**
  пороги намеренно щедрые и легко настраиваются; уровень лишь красит сектора, центр всегда = абсолют (§3).
  - Сплочённость: 0 · <4 · <8 · <20 · 20+
  - Активность (встреч/мес): 0 · <2 · <4 · <8 · 8+
  - Приходит (доля `avgAttendance / memberCount`): 0 · <20% · <40% · <60% · 60%+
- `components/club/ClubAchievements.tsx` — блок **«Достижения»**: возраст-бейдж (всегда) + живые счётчики-чипы.
  Логика в `components/club/clubMilestones.ts` (чистая): `ageBadge` (<12 «Клубу N мес» / 12–23 «Год клубу» /
  24+ «Клубу N лет») + `counters` — **плоские живые итоги, без порогов/замков**: «N встреч» (`totalMeetings`) +
  «N сборов» (`successfulSkladchinas`), каждый с плюрализацией, показ только при `>0`. «Преданные» не
  дублируем — это кольцо «основа клуба». Всё fact-backed, **без очков**. Тот же `useClubQualityQuery`.
- Встраивание в `pages/ClubPage.tsx`: блоки **«Качество клуба»** → **«Достижения»** после «О клубе», **до**
  табов/lock — видны всем зрителям. Fail-soft: при загрузке/ошибке блоки не рендерятся.

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
11. Блок «Достижения» всегда показывает возраст-бейдж; счётчики «N встреч»/«N сборов» — живые числа, показ при >0.

---

## 8. Вне среза (следующими PR)

- Карточка Discovery: участники · встреч/мес · вовлечённость% + майлстоны.
- owner-«Статистика» (рычаги/нуджи/зона внимания) — нужны ownership-проверки.
- Скрытый L3-ранг (композит 4 осей) — нужна калибровка §8, читает ledger через read-port.
- `membership_history` (фундамент retention) — строить чисто, без backfill.
- Фикс мёртвого `activity_rating` (живой баг дискавери) — отдельным PR.
</content>
</invoke>
