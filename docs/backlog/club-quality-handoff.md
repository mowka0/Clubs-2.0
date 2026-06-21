# Клуб-трек (качество клуба) — хэндофф для новой сессии

**Обновлено:** 2026-06-21 · **Статус:** L1/L2-показ + карточка Discovery + `membership_history` + **owner-«Статистика»** в проде. Дальше: **L3 скрытый ранг + «★ Топ-5 в категории»**.
**Спека модуля:** `docs/modules/club-quality.md` (§9 — owner-«Статистика») · **Дизайн-контракт (locked):** `docs/backlog/club-quality-gamification.md` (§0–11) · **Карточка/страница/управление — мокап:** `docs/design/club-quality-redesign/mockups/final.html`
**Память:** [[project_club_quality_track]], [[project_work_queue]]

> **С чего начать новую сессию:** следующий срез — **L3 скрытый ранг (§Б ниже)** + soft-ранг «★ Топ-5 в категории» на карточке/странице. Режим **xhigh + ultracode**. ПЕРЕД стартом — снять открытые вопросы §8 дизайн-дока (веса/пороги/калибровка). PO явно хочет «Топ-5» как ранний стимул для организаторов (на 10 клубах гейт-based v1 честен).

---

## 1. Что уже в проде

### Модуль `com.clubs.clubquality` (пир рядом с `reputation`, субъект — МЕСТО, якорь `club_id`)
Два эндпоинта (JWT; факты `others`-видимы, без ownership-check; 404 через общий `NotFoundException`):

- **`GET /api/clubs/{clubId}/quality`** → `ClubFactsDto` (6 фактов, derive read-only, НОЛЬ новой схемы): `meetingsPerMonth` (held-события 90д ÷3) · `avgAttendance` (Σ явок ÷ финализ. встреч 90д) · `coreSize` (distinct ≥3 «attended», non-cancelled, all-time) · `ageMonths` · `totalMeetings` (held all-time) · `successfulSkladchinas` (`closed_success`).
- **`GET /api/clubs/quality/batch?ids=...`** → `List<ClubCardFactsDto>` (#73) для ленты Discovery: `{clubId, ageDays, engagementPercent}`. Batch = grouped-queries (no N+1); dedupe + cap 50. Путь не коллизит с `/{clubId}/quality`. «вовлечённость» = distinct откликнувшихся за 90д ÷ живые участники (active+grace), clamp 0..100.

Слои: `ClubQualityController → ClubQualityService(@Transactional readOnly) → ClubQualityRepository / JooqClubQualityRepository → ClubQualityMapper` + domain `ClubFacts`/`ClubCardFacts`. Читает `clubs, events, event_responses, skladchinas, memberships`. **`reputation_ledger` пока НЕ читаем** (нужен L3 для негативов — споры/ghosting; через read-port `reputation`, не напрямую).

### Фронт
- **Страница клуба** — `components/club/ClubQualityFacts.tsx`: ОДИН блок (между «О клубе» и табами, виден всем), 3 кольца `QualityRing` (**основа клуба · частота встреч · обычно приходит**, центр = distinct-абсолют) + строка-капшн (🎂 возраст-бейдж + счётчики «N встреч»/«N сборов»). Хелперы: `qualityLevels.ts` (пороги колец — щедрая косметика, не L3), `clubMilestones.ts`.
- **Карточка Discovery** (#73) — `components/ClubCard.tsx`: сегментированное трио (`.mrow`) **возраст (дни) · участники · вовлечённость%** (зелёная) + ценник-чип на баннере + затемняющий градиент-скрим (книзу темнее — задел под будущую начинку поверх баннера). `useClubCardFacts(pages)` фетчит **один batch на страницу** (`useQueries`, кэш на страницу). Деградирует до имени+мета пока факты грузятся.
  - ⚠️ **Светлая тема** (#75): цвета ячеек/ценника тему-зависимы (navy для тёмной, override для светлой; текст — токены `--text`/`--live`/`--text-faint`). Не возвращать хардкод тёмных цветов.

### `membership_history` (V31, #74) — фундамент retention/churn
Append-only лог `(user_id, club_id, event{joined/left/rejoined/expired}, occurred_at)`. Пишется из **единственного чокпоинта** `JooqMembershipRepository` (та же транзакция). **Без UI и без чтения** — reads строятся в следующих срезах. Семантика событий — `docs/modules/membership.md` § «membership_history». Решения: организатор НЕ логируется (member-only); `left` = churn-intent (момент отмены, не потери доступа); продление активного ≠ rejoined.

### Discovery-сортировка/теги (#72 — `activity_rating` ретайрнут, V30)
`GET /api/clubs` сортирует по derived recent-activity (non-cancelled события 90д+предстоящие DESC → member_count DESC → created_at DESC). Тег «Популярный» = member_count top-10% выборки + гард `threshold > 0`. Мёртвая колонка `activity_rating` дропнута. Спека: `docs/modules/clubs.md` § «GET /api/clubs».

**Сессия 2026-06-21 — смерджено:** #72 (activity_rating) · #73 (карточка Discovery) · #74 (membership_history) · #75 (светлая тема карточки).

---

## 2. Инварианты и решения (НЕ нарушать)

- **3-слойная модель:** L1 факты · L2 кольца-косметика · L3 скрытый ранг. В проде **только L1/L2 (показ)**. Анти-фарм-защиты (distinct-account / абсолюты / decay / min-K / co-occurrence) — это **L3**, которого ЕЩЁ НЕТ.
- **L1/L2 могут использовать owner-данные** — бан только из L3 (§3 дизайн-дока).
- **Карточка Discovery = только ДАННЫЕ качества** на текущей карточке. Метрики не дублируют страницу (встреч/мес и ядро — кольца страницы, на карточку не выносятся). Структурная перестройка (аватар-от-баннера / направления / постоянное место/гео / роли / новости) = **future-слой со своими схемами**, не сейчас.
- **Счётчики/майлстоны — плоские живые числа, БЕЗ порогов/замков** (PO отверг лестницу 5/10/25).
- **coreSize исключает cancelled**; **clubquality читает чужие таблицы через jOOQ** (прецедент — `reputation`); **club-L3 ≠ среднее Trust участников** (иначе фарм через накрутку участников).
- **`membership_history` — без backfill** (выдуманная история = мусор; слепота на 1 churn-цикл). Append-only, чокпоинт — только `JooqMembershipRepository`. GDPR: при будущем user-erasure включить таблицу в очистку.

---

## 3. Дальше по треку

### А. owner-«Статистика» (§11.3) — ✅ ЗАШИПЛЕНО (`feature/owner-statistics`)
Подблок «Статистика» в управлении клубом (таб рядом с Участники/Финансы/Настройки; мокап «Обзор» — будущая
перестройка), **только владельцу** (`GET /api/clubs/{clubId}/stats`, `@RequiresOrganizer`). Три блока:
1. **Рычаги роста** (с трендом ↑↓): удержание (продлевают, paid), не продлили/ушли, вернулись (free), вовлечённость, оплата складчин, заявки ждут ответа.
2. **Что сделать сейчас** — нуджи из whitelist (ответить заявки / вернуть ушедших / напомнить о встрече).
3. **Зона внимания** 👁 *только владельцу*: споры по явке, авто-отклонения, отменённые встречи.

**Реализовано (спека — `club-quality.md` §9):**
- Полная логика трендов: engagement/skladchina-тренды доступны сразу (историчные таблицы), retention-тренд — по мере накопления `membership_history` (suppression до появления базы сравнения).
- Панель адаптируется paid/free: free → удержание по membership_history (ушли/вернулись) вместо платного renewal; складчинный рычаг скрыт без складчин; заявки/авто-отклонения — только у `closed`-клубов.
- Ownership через `@RequiresOrganizer(clubIdParam="clubId")`; «Надёжность организатора» — только владельцу, наружу не выводится; dispute-identities не раскрываются (только counts); reputation_ledger НЕ читается (споры — напрямую из `event_responses`). Выручка НЕ дублируется (она в «Финансах»).

### Б. L3 — скрытый ранг (§4) — **xhigh + ultracode**, СНЯТЬ §8-вопросы до старта
См. §4 ниже. Разблокирует soft-ранг «★ Топ-5 в категории» на карточке/странице.

### В. Структурная перестройка карточки/страницы — future-слой (свои схемы)
Направления (осн.+суб) · постоянное место (venue+город) · аватар-отдельно-от-баннера · роли участников · новости клуба · «Атмосфера встреч». Каждая — отдельная фича + схема + продуктовые решения. Затемняющий градиент карточки (#73) — уже задел под наложение текста/аватара на баннер. НЕ начинать без решения по схемам.

---

## 4. L3 — скрытый ранг (§4 дизайн-дока): открытые вопросы §8 — СНЯТЬ ДО реализации

L3 = композит 4 осей (`0.35·ParticipantDiversity + 0.30·PayingRetention + 0.20·DemandResponsiveness + 0.15·LiveActivity − штрафы × множители`), internal, двигает выдачу + категорийный рейтинг. **НЕ сумма баров, читает ledger напрямую.**

**Открытые вопросы (блокируют L3):**
- **Веса w1–w4 и пороги** (K=8/10? K_event=4? период decay? размеры штрафов) — **калибровка на прод-выборке клубов**. Есть ли выборка?
- **account-credibility scoring** (age/username/avatar/cross-club) — отдельный сервис/таблица или on-the-fly из `users`?
- **co-occurrence / collusion-collapse** — на запуске НЕ полный граф (min-K + credibility достаточно для v1; граф под реальной атакой). Зафиксировать порог включения.
- **whitelist owner-нуджей** — какой набор подсказок безопасен (не раскрывает формулу L3).
- **Инвариант «член-Trust ≠ среднее по клубу»** — закрепить (read-port из `reputation`, не агрегат member-Trust).
- **Гейты ДО ранга:** min-distinct-K (иначе UNRANKED) · absolute-volume floor · recency-decay на каждом сигнале.
- **`membership_history` теперь даёт** retention/churn/tenure как вход в L3 (PayingRetention + диверсификация) — самый Sybil-устойчивый сигнал.

---

## 5. Режимы: где нужен ultracode / xhigh

| Срез | Режим | Статус |
|---|---|---|
| Карточка Discovery | обычный | ✅ #73 |
| фикс `activity_rating` | обычный | ✅ #72 |
| `membership_history` | обычный | ✅ #74 |
| **owner-«Статистика»** | **обычный** | ✅ `feature/owner-statistics` (агрегации + UI + ownership) |
| **L3 скрытый ранг** | **xhigh + ultracode** | анти-фарм-математика, Sybil-устойчивость, калибровка §8, co-occurrence — единственный срез, где режимы окупаются |
| структурная перестройка карточки | обычный | future (нужны схемы) |

---

## 6. Инфра-заметки (важно для деплоя)

- **Реальный прод-URL:** `https://u342nbeig0rv71urf2n5s7wk.77.42.23.177.sslip.io` (Coolify-сабдомен). Апекс `https://77-42-23-177.sslip.io` — **битый вторичный роут** (503 + дефолтный Traefik-серт), НЕ индикатор прода.
- **Staging:** `https://staging.77-42-23-177.sslip.io` (одно приложение — деплоит ПОСЛЕДНЮЮ запушенную ветку feature/bugfix/devops).
- **Бокс CPX22 4 ГБ тесен** (staging+prod+Coolify+PG+Redis): был OOM при деплое #69, уронил прод (чинилось ребутом + redeploy в Coolify). **Девопс-задача:** апгрейд CPX31/8ГБ или memory-лимиты / гасить staging вне тестов.
- Верификация деплоя: смена хеша фронт-бандла (`index-XXXX.js`) = новый билд встал; короткий 503 при свапе контейнера — норма. **Бэкенд-only срезы** (как `membership_history`): хеш бандла НЕ меняется — проверять, что приложение поднялось (не 5xx) + миграция применилась.
- **Локальная БД для jOOQ-codegen:** `docker compose up -d postgres` (creds `clubs`/`clubs_secret`/5432); миграции на неё накатывать `psql` по нумерации (V*.sql в `sort -V`), затем `./gradlew generateJooq`. Flyway-gradle-таски нет (Flyway гоняется Spring-ом на старте).

---

## 7. Рабочий процесс (напоминание)

- Срезы идут через флоу CLAUDE.md (фича/bugfix): Developer → Reviewer → Security → (Tester) → Analyst docs-alignment → push в ветку → staging → «готово, запушь» → PR + squash-merge (без `--delete-branch`).
- **Schema change** = миграция V{N} (next = max+1, на 2026-06-21 max = **V31**) + `./gradlew generateJooq` + коммит генеренного `backend/src/generated/jooq/**`.
- **Docs-alignment — обязательный гейт** перед коммитом: grep по всем `docs/` + PRD + ARCHITECTURE, не только тронутый модуль. PRD правится только с согласия пользователя.
