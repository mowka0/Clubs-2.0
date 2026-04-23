# Module: Reputation

## Refactor: add Repository and Mapper layers

**Дата:** 2026-04-23
**Тип:** Архитектурный рефакторинг
**Агент:** Backend Developer

### Цель
Привести модуль `reputation/` к слоистой архитектуре из `.claude/rules/backend.md`. Сейчас `ReputationService` напрямую использует `DSLContext`, минуя Repository слой.

### Scope

**Входит:**
- Создать `ReputationRepository` (интерфейс) + `JooqReputationRepository` (impl)
- Создать `ReputationMapper` для маппинга jOOQ Record → domain
- Создать domain-класс `Reputation` (data class)
- Переписать `ReputationService` на Repository + Mapper
- Поведение (подсчёт репутации) **не меняется** — только структура

**НЕ входит:**
- Использование `USER_CLUB_REPUTATION` в `bot/ClubsBot.kt` — будет в рефакторинге модуля `bot`
- Использование в `membership/MemberController.kt` — в рефакторинге `membership`
- Изменение бизнес-правил подсчёта репутации

### Файлы для создания/изменения
| Файл | Действие |
|---|---|
| `reputation/domain/Reputation.kt` | **Создать** — domain data class |
| `reputation/ReputationRepository.kt` | **Создать** — интерфейс |
| `reputation/JooqReputationRepository.kt` | **Создать** — реализация с jOOQ |
| `reputation/ReputationMapper.kt` | **Создать** — маппинг Record ↔ domain |
| `reputation/ReputationService.kt` | **Изменить** — убрать прямой `DSLContext`, работать через Repository |

### API Контракт
Без изменений (Service не имеет REST endpoints, только `@Scheduled`).

### Repository Contract

```kotlin
interface ReputationRepository {
    fun findByUserAndClub(userId: UUID, clubId: UUID): Reputation?
    fun save(reputation: Reputation)
    fun findFinalizedEventsForReputation(): List<EventSummary>  // id, clubId
    fun findResponsesByEvent(eventId: UUID): List<EventResponseSummary>
}
```

**Примечание:** `EventSummary` и `EventResponseSummary` — минимальные DTO только для нужд Reputation (нужны id, clubId, userId, stage1Vote, finalStatus, attendance). НЕ доставать полные сущности.

### Domain model

```kotlin
data class Reputation(
    val userId: UUID,
    val clubId: UUID,
    val reliabilityIndex: Int,
    val promiseFulfillmentPct: BigDecimal,
    val totalConfirmations: Int,
    val totalAttendances: Int,
    val spontaneityCount: Int,
    val updatedAt: OffsetDateTime
)
```

### Бизнес-логика (не меняется)

Логика подсчёта — как в текущем `ReputationService.processReputationForFinalizedEvents()` / `calculateReputation()`:
- Деltas по таблице 4.4.4 из PRD (+100/-50/+30/-20/+10)
- `reliabilityIndex` clamp в [0, 200]
- `promiseFulfillmentPct` = totalAttendances / totalConfirmations × 100

Перенос логики из Service должен сохранить эти правила **бит в бит**.

### Acceptance Criteria (Given/When/Then)

**AC-1: Структура слоёв**
```
GIVEN рефакторинг завершён
WHEN открыт `ReputationService.kt`
THEN нет импортов `DSLContext`, `org.jooq.impl.DSL`, jOOQ-generated классов
AND есть инжект `ReputationRepository` (не реализация)
AND есть инжект `ReputationMapper`
```

**AC-2: Repository чистый**
```
GIVEN открыт `JooqReputationRepository.kt`
WHEN смотрим содержимое
THEN есть только jOOQ-запросы и вызовы Mapper
AND НЕТ бизнес-логики (никаких if со значениями delta, clamp, подсчёта процентов)
```

**AC-3: Mapper чистый**
```
GIVEN открыт `ReputationMapper.kt`
WHEN смотрим содержимое
THEN только преобразования полей Record ↔ Reputation
AND нет бизнес-логики
```

**AC-4: Service содержит только бизнес-логику**
```
GIVEN открыт `ReputationService.kt`
WHEN смотрим содержимое
THEN метод `processReputationForFinalizedEvents()` — оркестрация (запросы через Repository, цикл, обновление)
AND метод `calculateReputation()` — вычисление deltas (по правилам PRD) + save через Repository
AND приватная функция расчёта reliability clamp и fulfillmentPct — есть, остаётся pure-функция
```

**AC-5: Поведение не изменилось**
```
GIVEN тестовый event с responses (going→confirmed→attended):
  user1: stage1Vote=going, finalStatus=confirmed, attendance=attended
  user2: stage1Vote=maybe, finalStatus=confirmed, attendance=absent
  user3: stage1Vote=going, finalStatus=declined
WHEN запускается `calculateReputation(eventId, clubId)`
THEN user1.reliability_index += 100 (или стартует с 100 → 200)
AND user2.reliability_index -= 20
AND user3.reliability_index += 10
AND промисы/посещения/спонтанность подсчитаны как раньше
```

**AC-6: Билд и тесты**
```
GIVEN рефакторинг завершён
WHEN `./gradlew build`
THEN BUILD SUCCESSFUL без warnings
AND все существующие тесты проходят (если они есть для reputation)
```

### Non-functional requirements
- Производительность не деградирует — те же 2 запроса на event (fetch responses, fetch existing reputation), те же update/insert
- `@Transactional` сохраняется на `processReputationForFinalizedEvents()`
- Логирование остаётся (errors при фейле отдельного event)

### Риски
- **Средний:** регрессия в логике подсчёта при переносе. Митигация: построчное сравнение до/после + тест `AC-5` вручную
- **Низкий:** Repository methods для event/response минимальны, но могут быть избыточны (over-engineering) — следить за YAGNI
