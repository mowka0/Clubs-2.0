# Module: Reputation

Система репутации участников в рамках клуба.
Соответствует PRD §4.4.4. Источник истины для бизнес-правил — PRD.

## Назначение
Считает и хранит per-club метрики поведения участника на событиях: надёжность, процент выполнения обещаний, счётчик спонтанности.

## Архитектура

```
@Scheduled → ReputationService
                   │
                   ▼
            ReputationRepository (interface)
                   │
                   ▼
          JooqReputationRepository ──▶ ReputationMapper ──▶ Reputation (domain)
```

### Файлы
| Файл | Роль |
|---|---|
| `Reputation.kt` | Domain data classes (Reputation, FinalizedEventRef, ResponseForReputation) |
| `ReputationRepository.kt` | Интерфейс |
| `JooqReputationRepository.kt` | Реализация с jOOQ |
| `ReputationMapper.kt` | Record → domain |
| `ReputationService.kt` | Оркестрация + бизнес-правила |

### Хранение
Таблица `user_club_reputation` (см. миграцию V7, V10):
- `reliability_index` INT (default 0, без границ, может быть отрицательным)
- `promise_fulfillment_pct` NUMERIC(5,2) (default 0)
- `total_confirmations`, `total_attendances`, `spontaneity_count` INT
- UNIQUE (user_id, club_id) — одна запись на пару юзер+клуб

## Бизнес-правила (из PRD §4.4.4)

### Начисления надёжности

| Сценарий | Этап 1 | Этап 2 | Приход | Δ reliability | Δ spontaneity |
|---|---|---|---|---|---|
| Железобетонный | going | confirmed | attended | +100 | — |
| Пустозвон | going | confirmed | absent | −50 | — |
| Передумавший | going | declined | — | 0 | — |
| Спонтанный | maybe | confirmed | attended | +30 | +1 |
| Зритель | maybe | confirmed | absent | −20 | — |
| Вечный сомневающийся | maybe | not confirmed | — | 0 | — |
| Молчун | not_going | — | — | 0 | — |

### Формулы

- **reliabilityIndex** = Σ всех начислений за всю историю. Стартует с 0. **Может быть отрицательным.** Не ограничен сверху.
- **promiseFulfillmentPct** = totalAttendances / totalConfirmations × 100, округление HALF_UP до 2 знаков. При totalConfirmations = 0 → 0.
- **spontaneityCount** = число случаев "maybe → confirmed → attended".
- **totalConfirmations** = число случаев finalStatus = confirmed.
- **totalAttendances** = число случаев confirmed + attendance = attended.

### Когда пересчитывается
`@Scheduled(fixedDelay = 1h)` в `ReputationService.processReputationForFinalizedEvents()`:
- Находит события где `attendance_finalized = true AND attendance_marked = true`
- Для каждого прогоняет `calculateReputation(eventId, clubId)`
- Для каждого response: считает deltas → загружает существующую репутацию → применяет deltas → сохраняет

## Acceptance Criteria

**AC-1: deltas по таблице**
```
GIVEN новая запись (существующей репутации нет)
WHEN event response (going, confirmed, attended)
THEN reliability = 0 + 100 = 100, confirmations = 1, attendances = 1, fulfillmentPct = 100
```

**AC-2: отрицательная репутация допустима**
```
GIVEN reliability = 0 (новый юзер)
WHEN event response (going, confirmed, absent) — "Пустозвон" -50
THEN reliability = -50 (не клампится в 0)
```

**AC-3: declined → 0**
```
GIVEN любая существующая репутация
WHEN event response (finalStatus = declined)
THEN reliability не меняется (+0)
```

**AC-4: спонтанность только для "maybe → confirmed → attended"**
```
GIVEN ...
WHEN event response (maybe, confirmed, attended)
THEN reliability += 30 AND spontaneityCount += 1
```

**AC-5: изоляция по клубу**
```
GIVEN у юзера есть репутация в клубе A
WHEN event в клубе B добавляет reliability
THEN меняется только запись user_club_reputation для клуба B, клуб A не тронут
```

## Интеграции

- Таблица `EVENTS` (read): `ATTENDANCE_FINALIZED`, `ATTENDANCE_MARKED`
- Таблица `EVENT_RESPONSES` (read): `STAGE_1_VOTE`, `FINAL_STATUS`, `ATTENDANCE`
- Чтение репутации из других модулей (bot, membership) идёт **напрямую к таблице** — это будет вынесено в `ReputationRepository` при рефакторинге этих модулей.

## Non-functional

- `@Transactional` на `processReputationForFinalizedEvents`
- Ошибки при расчёте одного event логируются, не роняют весь батч
- Пересчёт идёт раз в час (fixed delay)
