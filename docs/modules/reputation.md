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
Считает и хранит per-club метрики поведения участника на событиях и сборах: индекс надёжности,
% выполнения обещаний, счётчики подтверждений / посещений / спонтанности. Сегодня репутация
**только отображается** (профиль, карточка участника, инбокс заявок), ничего не гейтит.

## Модель (кратко; детали — reputation-v2.md)

- **`reputation_ledger`** (append-only, V17) — источник истины. Одна строка на (user, source);
  идемпотентность через `ON CONFLICT (user_id, source_type, source_id) DO NOTHING` — структурно
  убивает прежний баг почасовой инфляции.
- **`user_club_reputation`** — кэш; единственный писатель = `recompute(user, club)` (атомарный upsert,
  агрегат из ledger через `COUNT(*) FILTER`; сериализация per (user, club) через
  `pg_advisory_xact_lock`).
- **Оси:** `attendance` (явка, 5 kind'ов: ironclad +100 / no_show −50 / spontaneous +30 /
  spectator −20 / confirmed_unresolved 0) и `finance` (сборы: paid +10 / declined −5 / expired −25).
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
  `reliabilityIndex` nullable **только на DTO-границе**; домен `Reputation` и кэш-колонка — NOT NULL.
  Полный «буфер прощения» (Trust 0–100 с оптимистичным приором) — P1b.

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

### `GET /api/users/me/reputation`

JWT-protected. Возвращает `List<UserClubReputationDto>` — по одной записи на клуб, где юзер
`status IN active|grace_period` и `club.is_active = true`. Order: `MEMBERSHIPS.JOINED_AT DESC NULLS LAST`.

```kotlin
data class UserClubReputationDto(
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val category: String,
    val role: String,                      // "organizer" = владелец → организаторская рамка
    val joinedAt: OffsetDateTime?,
    val reliabilityIndex: Int?,            // null = «Новичок»/подавлено (outcome_count < 3, или владелец)
    val promiseFulfillmentPct: BigDecimal?,
    val totalConfirmations: Int?,
    val totalAttendances: Int?
)
```

Под капотом — один SELECT `MEMBERSHIPS ⨝ CLUBS LEFT JOIN USER_CLUB_REPUTATION` (без coalesce-дефолтов);
порог `outcome_count >= 3` применяется маппером (`MembershipMapper`), весь блок подавляется в null для
новичка. **Семантика новичка:** не «100 (benefit of the doubt)», а честный «Новичок» — не судим, пока
нет ≥3 исходов («право на ошибку», см. reputation-v2.md § Новичок).

### Где отображается

| Локация | Что показывает | Источник |
|---|---|---|
| `/profile` → «Моя репутация» | список клубов + индекс; новичок → «Новичок»; свой клуб → организаторская рамка; средняя надёжность **исключает** null-клубы | `GET /api/users/me/reputation` |
| `/clubs/:id` → «Участники» → `MemberProfileModal` | полные метрики; null → «Новичок»; владелец → рамка | `GET /api/clubs/:id/members/:userId` (`MemberProfileDto`, теперь с `role` + nullable полями) |
| `/my-clubs` → «Заявки» + `ApplicationReviewModal` | **Peer-signal** заявителя: «В N клубах · посетил X из Y» | `GET /api/users/me/applications-pending` → `peerStats` |

В списке участников (`findClubMembersWithUserInfo`) сортировка — по отображаемому индексу
`... DESC NULLS LAST`, поэтому новички/sub-threshold/владельцы (null) уходят вниз, а не наверх.

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
