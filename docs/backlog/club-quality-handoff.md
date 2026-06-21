# Клуб-трек (качество клуба) — хэндофф для новой сессии

**Создано:** 2026-06-20 · **Статус:** срезы 1–3 в проде, фикс `activity_rating` (§3.4) зашиплен, трек продолжается
**Спека модуля:** `docs/modules/club-quality.md` · **Дизайн-контракт (locked):** `docs/backlog/club-quality-gamification.md` (§0–11)
**Память:** [[project_club_quality_track]], [[project_work_queue]]

---

## 1. Что уже в проде (#69 → #70 → #71)

Новый top-level модуль **`com.clubs.clubquality`** (пир рядом с `reputation`, субъект — МЕСТО, якорь `club_id`).
Эндпоинт **`GET /api/clubs/{clubId}/quality`** → `ClubFactsDto` (JWT; факты `others`-видимы, без ownership-check; 404 через общий `NotFoundException`).

**6 фактов (derive read-only, НОЛЬ новой схемы):**
| Поле | Что | Как |
|---|---|---|
| `meetingsPerMonth: Double` | частота | held-события за 90д ÷3, round(1) |
| `avgAttendance: Int` | приходит | Σ явок ÷ финализ. встреч за 90д |
| `coreSize: Int` | ядро | distinct юзеров с ≥3 «attended», non-cancelled, all-time |
| `ageMonths: Int` | возраст | месяцы от `clubs.created_at` |
| `totalMeetings: Int` | счётчик встреч | held-события all-time (без окна) |
| `successfulSkladchinas: Int` | счётчик сборов | складчины `status='closed_success'` |

Слои: `ClubQualityController → ClubQualityService(@Transactional readOnly) → ClubQualityRepository / JooqClubQualityRepository → ClubQualityMapper` + domain `ClubFacts` + `ClubFactsDto`.
Читает таблицы: `clubs`, `events`, `event_responses`, `skladchinas`. **`reputation_ledger` пока НЕ читаем** (нужен L3 для негативов — споры/ghosting; читать через read-port `reputation`, не напрямую).

**Фронт — ОДИН блок** `components/club/ClubQualityFacts.tsx` (на странице клуба, между «О клубе» и табами, виден всем):
- **без заголовков-секций**, всё по центру (дизайн-вариант «кольца + лёгкая строка», выбран PO из 3 мокапов `docs/design/club-quality-redesign/mockups/stats-block-variants.html`)
- 3 кольца `QualityRing` (4 равных сектора, центр = distinct-абсолют; равные flex-колонки → ровные промежутки; подписи в **2 строки** через `\n`+`white-space:pre-line`): **основа клуба · частота встреч · обычно приходит**
- разделитель + **строка-капшн** через точку: 🎂 возраст-бейдж (золотой) + живые счётчики «N встреч»/«N сборов»
- клуб без событий → только строка «🎂 Клубу N мес · пока нет встреч»
- хелперы: `qualityLevels.ts` (пороги колец, **щедрая косметика, не L3**), `clubMilestones.ts` (`ageBadge`+`counters`); `api/clubQuality.ts`, `queries/clubQuality.ts`
- ⚠️ `ClubAchievements.tsx` **удалён** (слит в `ClubQualityFacts`); `.mile`-чипы убраны

**Тесты:** backend `ClubQualityIntegrationTest` (9 кейсов: окна/пороги/пустой/404/cancelled/totalMeetings/skladchinas); фронт `clubMilestones.test`, `qualityLevels.test`, `ClubQualityFacts.test`, `QualityRing` через них. build + 152 фронт-теста + полный backend-прогон зелёные.

---

## 2. Инварианты и решения (НЕ нарушать)

- **3-слойная модель:** L1 факты · L2 кольца-косметика · L3 скрытый ранг. Сейчас в проде **только L1/L2 (показ)**. Анти-фарм-защиты (distinct-account / абсолюты / decay / min-K / co-occurrence) — это **L3**, которого ЕЩЁ НЕТ.
- **L1/L2 могут использовать owner-данные** (self-marked явка и т.п.) — бан только из L3 (§3).
- **Счётчики — плоские живые числа, БЕЗ порогов/замков** (PO отверг лестницу 5/10/25: пороги выдуманы, «71/100» вводит в заблуждение).
- **Кольца центр = distinct-абсолют**, уровень секторов — щедрая косметика.
- **coreSize исключает cancelled** (club-delete cascade может оставить `attended` на отменённом событии).
- **clubquality читает чужие таблицы через jOOQ** — допустимо (прецедент: `reputation` тоже), это НЕ импорт чужих Kotlin-классов.
- **Инвариант границы:** club-L3 ≠ среднее Trust участников (иначе фарм-вектор через накрутку участников).

---

## 3. Дальше по треку (визуальные/механические срезы — обычный режим)

1. ~~**Карточка Discovery (§11.1):**~~ ✅ **ЗАШИПЛЕНО** (`feature/discovery-card-quality`). Сегментированное трио на карточке клуба в ленте: **возраст (дни) · участники · вовлечённость%** (стиль `.mrow` из мокапа). Метрики выбраны минимально-достаточными и **без дублей со страницей** — встреч/мес и ядро остаются кольцами страницы (Активность/Сплочённость), на карточку не выносятся. **N+1 решён batch-эндпоинтом** `GET /api/clubs/quality/batch` (не джойн — держит границу модуля + лёгкий list-запрос); фронт фетчит **один batch на страницу** ленты (`useQueries`, кэш на страницу). Новая метрика «вовлечённость» = distinct откликнувшихся за 90д ÷ живые участники. Структурная перестройка карточки (аватар-от-баннера/направления/гео) и L3 soft-ранг «Топ-5» НЕ входили. Спека: `docs/modules/club-quality.md` §4/§6.
2. **owner-«Статистика» (§11.3):** подблок в управлении клубом — рычаги роста (удержание/вовлечённость/заявки) + actionable-нуджи + приватная «зона внимания» (споры/авто-отклонения).
   ⚠️ Нужны **ownership-проверки** (`@RequiresOrganizer`/проверка владельца). Ось «Надёжность организатора» в проблемной зоне видна **ТОЛЬКО владельцу** (публичный позорный индикатор → dispute-suppression).
3. **`membership_history` (§6, грин-лайт PO):** append-only лог переходов членства `(user_id, club_id, event{joined/left/rejoined/expired}, occurred_at)` из тех же точек, где меняется `membership_status`. **Строить чисто, БЕЗ backfill** (бэкфилл = выдуманная история; принять слепоту на 1 churn-цикл). Разблокирует retention/churn = структурно самый Sybil-устойчивый сигнал. Backend + миграция, без UI.
4. ~~**Фикс мёртвого `activity_rating` (§5):**~~ ✅ **ЗАШИПЛЕНО** (`bugfix/retire-activity-rating`). Колонка ретайрнута миграцией V30 (+ jOOQ регенерён); сортировка дискавери пересобрана как derived (non-cancelled события за 90д + предстоящие DESC → member_count DESC → created_at DESC), тег «Популярный» — по member_count top-10% с гардом `threshold > 0` (пустые клубы не тегаются). `ApplicationScheduler` больше не штрафует (штраф был no-op по мёртвому полю). Спека: `docs/modules/clubs.md` § «GET /api/clubs».

---

## 4. L3 — скрытый ранг (§4): открытые вопросы §8 — СНЯТЬ ДО реализации

L3 = композит 4 осей (`0.35·ParticipantDiversity + 0.30·PayingRetention + 0.20·DemandResponsiveness + 0.15·LiveActivity − штрафы × множители`), internal, двигает выдачу + категорийный рейтинг. **НЕ сумма баров, читает ledger напрямую.**

**Открытые вопросы (блокируют L3):**
- **Веса w1–w4 и пороги** (K=8/10? K_event=4? период decay? размеры штрафов) — **калибровка на прод-выборке клубов**. Есть ли выборка?
- **account-credibility scoring** (age/username/avatar/cross-club) — отдельный сервис/таблица или on-the-fly из `users`? Влияет на сложность.
- **co-occurrence / collusion-collapse** — на запуске НЕ полный граф (min-K + credibility достаточно для v1; граф включить под реальной атакой). Зафиксировать порог включения.
- **whitelist owner-нуджей** — какой набор подсказок безопасен (не раскрывает формулу L3).
- **Инвариант «член-Trust ≠ среднее по клубу»** — явно закрепить в архитектуре (read-port из `reputation`, не агрегат member-Trust).
- **Гейты ДО ранга:** min-distinct-K (иначе UNRANKED, не «низкий») · absolute-volume floor · recency-decay на каждом сигнале.

Из `Что нужно сделать.md` #6 (входы L3 на будущее): premium-% аккаунтов + число спорщиков в скрытый ранг.

---

## 5. Режимы: где нужен ultracode / xhigh

| Срез | Режим | Почему |
|---|---|---|
| Карточка Discovery | **обычный** | UI + batch-эндпоинт, механика |
| owner-«Статистика» | **обычный** | агрегации + UI + ownership-checks |
| `membership_history` | **обычный** | миграция + лог из существующих точек |
| фикс `activity_rating` | **обычный** | точечный bugfix |
| **L3 скрытый ранг** | **xhigh + ultracode** | анти-фарм-математика, Sybil-устойчивость, калибровка §8, co-occurrence — глубокое рассуждение (**xhigh**) + состязательный дизайн (**ultracode**: агенты-«атакующие» ломают каждый сигнал, design-панель, анализ калибровки) |

**Короткий ответ на вопрос:** да — **в L3**. Это единственный срез трека, где и xhigh (думать), и ultracode (состязательно ломать анти-фарм) реально окупаются. Все остальные — обычный режим.

---

## 6. Инфра-заметки (важно для деплоя)

- **Реальный прод-URL:** `https://u342nbeig0rv71urf2n5s7wk.77.42.23.177.sslip.io` (Coolify-сабдомен). Апекс `https://77-42-23-177.sslip.io` — **битый вторичный роут** (503 + дефолтный Traefik-серт), НЕ трогать как индикатор прода.
- **Бокс CPX22 4 ГБ тесен** (staging+prod+Coolify+PG+Redis): был OOM при деплое #69, уронил прод (чинилось ребутом + redeploy в Coolify). Прод-серт после простоя поднимается как `TRAEFIK DEFAULT`, ACME дорегистрирует сам. **Девопс-задача:** апгрейд CPX31/8ГБ или memory-лимиты / гасить staging вне тестов.
- Верификация деплоя: смена хеша фронт-бандла (`index-XXXX.js`) на staging/prod = новый билд встал; короткий 503 при свапе контейнера — норма.
</content>
