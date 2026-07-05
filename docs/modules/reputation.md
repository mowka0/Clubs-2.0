# Module: Reputation

Система репутации участников в рамках клуба. Соответствует PRD §4.4.4 (по значениям начислений).

> **Модель v2 (ledger) — актуальна.** Полный имплементируемый контракт и обоснование:
> [`reputation-v2.md`](./reputation-v2.md). Источник истины расчёта — **append-only
> `reputation_ledger`**; `user_club_reputation` — производный кэш, пересчитываемый из ledger
> (`recompute`). Старая аддитивная модель (`@Scheduled` → инкрементальный `save`,
> `computeDeltas`/`addReliabilityDelta`, новичок = COALESCE→100) **удалена** в P1a
> (ветка `feature/reputation-ledger`). Этот файл — краткий обзор текущей реальности + контракты
> отображения; формулы и фазы — в `reputation-v2.md`.

## Назначение
Считает и хранит per-club метрики поведения участника на событиях и сборах. Пользователю показывается
**Trust 0-100** (P1b) — байесовская recency-взвешенная доля сдержанных слов, выводимая on-read из
ledger по kind; сырой `reliability_index = Σpoints` остаётся **internal**-сырьём, наружу не идёт. Плюс
% выполнения обещаний, счётчики подтверждений / посещений / спонтанности. Сегодня репутация
**только отображается** (профиль, карточка участника, инбокс заявок), ничего не гейтит.

## Модель (кратко; детали — reputation-v2.md)

- **`reputation_ledger`** (append-only, V17) — источник истины. Одна строка на (user, source);
  идемпотентность через `ON CONFLICT (user_id, source_type, source_id) DO NOTHING` — структурно
  убивает прежний баг почасовой инфляции.
- **`user_club_reputation`** — кэш; единственный писатель = `recompute(user, club)` (атомарный upsert,
  агрегат из ledger через `COUNT(*) FILTER`; сериализация per (user, club) через
  `pg_advisory_xact_lock`).
- **Оси:** `attendance` (явка, 5 kind'ов: ironclad +100 / no_show −200 / spontaneous +100 /
  spectator −200 / confirmed_unresolved 0; с 2026-06-11 очки привязаны только к этапу-2 × явке —
  stage-1 голос различает kind для черты «спонтанность», но не очки) и `finance` (сборы,
  веса 2026-06-12: paid +10 / expired −40; declined и released — строк нет; kind
  `skladchina_declined` остался в enum ради исторических −5-строк).
  `reliability_index` = Σ points по **всем** осям; счётчики (`total_confirmations/attendances`,
  `spontaneity_count`) — только по оси `attendance`.
- **Событийная связь:** `AttendanceFinalizedEvent` (`@TransactionalEventListener(AFTER_COMMIT)`,
  low-latency) + атомарный клейм `events.reputation_processed` + почасовой poll-backstop
  (`ReputationScheduler`). Складчина пишет finance-строки на закрытии (`SkladchinaService.closeInternal`,
  `@Transactional`).
- **Анти-фарм правило 1:** владелец **не копит** репутацию в своём клубе (ledger-строки не пишутся;
  в live и в бэкфилле). Применяется ретроактивно в V18.
- **Новичок / «право на ошибку»:** реальное число показывается только при `outcome_count >= 3`
  (`ReputationPolicy.MIN_OUTCOMES_FOR_DISPLAY`); иначе UI показывает **«Новичок»** (без числа).
  Владельцу в своём клубе — **организаторская рамка** («репутация за организаторские качества»).
  Показываемое `trust` (P1b) nullable **только на DTO-границе** (новичок/подавлено); домен `Reputation`
  и кэш-колонки (`reliability_index`, `kept/broke/neutral_count`) — NOT NULL.
  Полный «буфер прощения» (Trust 0–100 с оптимистичным приором) реализован в P1b
  (`TrustPolicy`/`TrustService`, on-read decay; счётчики V25).

### Файлы
| Файл | Роль |
|---|---|
| `reputation/ReputationPolicy.kt` | Чистый маппинг исход → (kind, points) + порог показа (single source of truth) |
| `reputation/LedgerEntry.kt` | Доменные типы (LedgerEntry, EventReputationContext, ConfirmedResponse) |
| `reputation/ReputationService.kt` | `processFinalizedEvent` (claim + ledger + recompute, REQUIRES_NEW) + `appendAndRecompute` |
| `reputation/ReputationScheduler.kt` | Почасовой poll-backstop |
| `reputation/AttendanceFinalizedListener.kt` | AFTER_COMMIT listener (low-latency) |
| `reputation/{ReputationRepository,JooqReputationRepository,ReputationMapper}.kt` | Persistence: ledger + кэш + reads |

### Хранение
`reputation_ledger` (V17), `user_club_reputation` (V7 + `outcome_count` в V17). Бэкфилл из истории +
rebuild кэша — V18 (с форензик-снапшотом `user_club_reputation_pre_v18`).

---

## Per-user reputation overview (отображение)

Per-club метрики выведены агрегатом в глобальный «Профиль» (`/profile` → секция «Моя репутация»),
плюс доступны через `MemberProfileModal` в табе «Участники» (peer-view = self-view).

### `GET /api/users/me/reputation` (P1b)

JWT-protected. Возвращает **обёртку** `MyReputationDto` (P1b): global-агрегат «надёжен в N из M
клубов» по **всей истории** + per-club списки, разбитые на активные клубы и «История» (покинутые
клубы с сохранившимся track record). Заменяет прежний плоский active-only `List<UserClubReputationDto>`.

```kotlin
data class MyReputationDto(
    val global: GlobalTrustDto,
    val activeClubs: List<UserClubReputationDto>,
    val historyClubs: List<UserClubReputationDto>
)
data class GlobalTrustDto(val reliableClubs: Int, val trackRecordClubs: Int, val score: Int?)

data class UserClubReputationDto(
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val category: String,
    val role: String,                      // "organizer" = владелец → организаторская рамка
    val joinedAt: OffsetDateTime?,
    val trust: Int?,                       // P1b Trust 0-100; null = «Новичок»/подавлено (outcome_count < 3, или владелец)
    val promiseFulfillmentPct: BigDecimal?,
    val totalConfirmations: Int?,
    val totalAttendances: Int?
)
```

Per-club `trust` и global считаются **on-read** из ledger (`TrustService`), не из кэша (decay
зависит от времени). Список клубов теперь включает покинутые (active-only фильтр снят — дыра A);
порог `outcome_count >= 3` подавляет `trust`/сиблинги в null для новичка. **Семантика новичка:** не
«100 (benefit of the doubt)», а честный «Новичок» — не судим, пока нет ≥3 исходов («право на ошибку»,
см. reputation-v2.md § Новичок). Полный контракт — `reputation-v2.md` § «API-контракты (PR-a)».

### Где отображается

| Локация | Что показывает | Источник |
|---|---|---|
| `/profile` → «Моя репутация» | global «надёжен в N из M клубов» (вся история) + per-club Trust в двух секциях «Репутация» (активные) / «История» (покинутые с track record); новичок → «Новичок»; свой клуб → организаторская рамка | `GET /api/users/me/reputation` |
| `/clubs/:id` → «Участники» → `MemberProfileModal` | полные метрики; null → «Новичок»; владелец → рамка | `GET /api/clubs/:id/members/:userId` (`MemberProfileDto`, теперь с `role` + nullable полями) |
| `/my-clubs` → «Заявки» + `ApplicationReviewModal` | **Peer-signal** заявителя: «В N клубах · посетил X из Y» | `GET /api/users/me/applications-pending` → `peerStats` |

В списке участников per-member Trust считается одним batch-запросом (`TrustService.trustForClubMembers`,
без N+1). **UPDATED 2026-07-05 (асимметричная видимость, reputation-path-back.md):** чужой Trust видит
только организатор; рядовой участник получает числа только в собственной строке (остальные — null,
неотличимо от «Новичка»). Сортировка: организатору — по отображаемому Trust DESC (как раньше);
участнику — нейтрально по joinedAt ASC (организатор клуба в обоих случаях первым).

**Подавление attendance-метрик для finance-only участника (F5-08, 2026-06-25).** Участник с
репутацией только от складчин (`outcome_count ≥ 3`, но `total_confirmations = 0`) имеет `trust ≠ null`,
но нулевой event-трек. Чтобы не показывать вводящее в заблуждение «Обещания 0%», `MemberListItemDto`
несёт `totalConfirmations`; строка списка (`ClubMembersTab`) скрывает «Обещания X%» при
`totalConfirmations === 0`, а `MemberProfileModal` скрывает «Спонтанных визитов» при
`confirmations === 0`. Это паритет с `ProfilePage` (`hasActivity`-гард), где такие нули уже прятались.

### Peer-signal в Applications Inbox

Агрегат `ReputationRepository.aggregateByUserIds` (batch): `memberClubCount` =
`COUNT(user_club_reputation.club_id)`, `total_confirmations/attendances` = SUM. Семантика
`memberClubCount` = **«клубы с track-record»** (где есть строка в `user_club_reputation`). После
анти-фарм правила 1 владелец **не входит** в счёт по своему клубу (нет строки); новичок без исходов
тоже не входит → фронт показывает «Новый пользователь». Это намеренно (надёжность зарабатывается
в **чужих** клубах). Подробно — [`applications-inbox.md`](./applications-inbox.md).

## Non-functional

- `recompute` — единственный писатель кэша; атомарный upsert + advisory-lock сериализация per (user, club).
- Listener (`AFTER_COMMIT` + `REQUIRES_NEW`) — best-effort; poll (`@Scheduled`, 1ч) — durable backstop.
  Ошибка обработки одного события логируется и не роняет финализацию.
- Партиал-индекс на poll-предикат; индексы ledger на (user_id, club_id) и (source_type, source_id).
