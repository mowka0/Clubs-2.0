---
paths:
  - "backend/**/*.kt"
---

# Backend правила (Kotlin / Spring Boot / jOOQ)

## Слои и их ответственность

```
Controller → Service → Repository → jOOQ DSLContext
                ↓
              Mapper (utils)
```

### Controller
- Принимает HTTP, валидирует DTO (`@Valid`)
- Вызывает Service — передаёт DTO или его поля **как есть**
- Возвращает то, что вернул Service
- **НЕ содержит бизнес-логики**
- **НЕ делает маппинг** (ни DTO↔domain, ни domain↔response)
- **НЕ работает с jOOQ напрямую**

```kotlin
@RestController
@RequestMapping("/api/clubs")
class ClubController(private val clubService: ClubService) {
    @PostMapping
    fun create(@Valid @RequestBody request: CreateClubRequest): ResponseEntity<ClubDto> {
        return ResponseEntity.ok(clubService.createClub(request))
    }
}
```

### Service
- Бизнес-логика
- Принимает DTO / простые типы, возвращает DTO / простые типы
- Работает с Repository через доменные объекты
- **Маппинг делает через Mapper-класс** (не inline)
- **НЕ знает про HTTP**
- **НЕ работает с jOOQ напрямую** — только через Repository
- Транзакции: `@Transactional` на уровне Service

```kotlin
@Service
class ClubService(
    private val clubRepository: ClubRepository,
    private val clubMapper: ClubMapper
) {
    @Transactional
    fun createClub(request: CreateClubRequest): ClubDto {
        val domain = clubMapper.toDomain(request)
        val saved = clubRepository.save(domain)
        return clubMapper.toDto(saved)
    }
}
```

### Repository
- Только CRUD через jOOQ
- Принимает/возвращает доменные объекты (или простые типы)
- **НЕ содержит бизнес-логики**
- Маппинг jOOQ Record ↔ domain делает сам (через Mapper или private функции)

```kotlin
interface ClubRepository {
    fun save(club: Club): Club
    fun findById(id: UUID): Club?
}

@Repository
class JooqClubRepository(
    private val dsl: DSLContext,
    private val clubMapper: ClubMapper
) : ClubRepository {
    override fun findById(id: UUID): Club? {
        return dsl.selectFrom(CLUBS).where(CLUBS.ID.eq(id)).fetchOne()
            ?.let(clubMapper::recordToDomain)
    }
}
```

### Mapper / Utils
Вся логика маппинга и универсальных преобразований — в отдельных классах.

**Где размещать:**
- Для конкретного модуля: `backend/src/main/kotlin/com/clubs/<module>/mapper/<Name>Mapper.kt`
- Универсальные утилиты: `backend/src/main/kotlin/com/clubs/common/util/`

**Правила:**
- Один Mapper-класс на одну доменную сущность (ClubMapper, UserMapper)
- Методы называются по смыслу: `toDomain`, `toDto`, `recordToDomain`, `domainToRecord`
- Mapper — Spring `@Component`, инжектится в Service/Repository
- Маппер не содержит бизнес-логики, только преобразование полей

```kotlin
@Component
class ClubMapper {
    fun toDomain(request: CreateClubRequest): Club = Club(
        id = UUID.randomUUID(),
        name = request.name,
        maxMembers = request.maxMembers,
        createdAt = OffsetDateTime.now()
    )

    fun toDto(club: Club): ClubDto = ClubDto(
        id = club.id,
        name = club.name,
        maxMembers = club.maxMembers
    )

    fun recordToDomain(record: ClubsRecord): Club = Club(
        id = record.id!!,
        name = record.name!!,
        maxMembers = record.maxMembers!!,
        createdAt = record.createdAt!!
    )
}
```

---

## DTO vs Record vs Domain

Три разных типа для трёх разных ролей:

| Тип | Где живёт | Для чего |
|---|---|---|
| `*Dto` / `*Request` / `*Response` | `controller/dto/` | HTTP ввод-вывод |
| jOOQ `*Record` | только в Repository | результат запроса к БД |
| Domain class | `<module>/domain/` или рядом с Service | бизнес-модель |

Маппинг между ними — только через Mapper.

**НИКОГДА**: jOOQ Record в Controller или в DTO. DTO в Repository.

---

## Валидация

### На уровне DTO (Bean Validation)
Формат, типы, границы — базовая структура.
```kotlin
data class CreateClubRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 100)
    val name: String,

    @field:Min(1)
    val maxMembers: Int
)
```

### На уровне Service
Бизнес-правила — уникальность, права, инварианты.
```kotlin
fun createClub(request: CreateClubRequest): ClubDto {
    if (clubRepository.existsByName(request.name)) {
        throw ClubAlreadyExistsException(request.name)
    }
    // ...
}
```

---

## Null-safety

- Публичные API не возвращают `T!!` — либо `T`, либо `T?`.
- Repository возвращает `T?` когда результата может не быть (`findById`).
- Service решает: кинуть exception или обработать null.

```kotlin
fun findById(id: UUID): User?                                      // Repository

fun getUser(id: UUID): User {                                       // Service
    return userRepository.findById(id) ?: throw UserNotFoundException(id)
}
```

Не использовать `!!` в продакшн-коде. Каждый `!!` — потенциальный NPE.
Исключение: jOOQ Record getters внутри Mapper (там по контракту non-null, но типы nullable).

---

## Именование

- **Controllers** — `*Controller`: `ClubController`
- **Services** — `*Service`: `ReputationService`, `AuthService`
- **Repositories** — `*Repository`: `UserRepository`
- **Mappers** — `*Mapper`: `ClubMapper`, `UserMapper`
- **DTOs** — `*Dto` / `*Request` / `*Response`: `ClubDto`, `CreateClubRequest`
- **Domain** — существительные без суффикса: `Club`, `User`, `Reputation`
- **Exceptions** — `*Exception`: `UserNotFoundException`
- **Utils** — описательное имя: `DateFormatter`, `SlugGenerator`

---

## Логирование

- Каждый класс: `private val log = LoggerFactory.getLogger(this::class.java)`
- Точки входа в Service (INFO): что началось, ключевые параметры
- Успешные операции (INFO): что произошло, ID созданных сущностей
- Отклонения (WARN): rate limit, invalid auth, бизнес-отказы
- Неожиданные ошибки (ERROR): с stacktrace

Уровни управляются через env vars (`LOGGING_LEVEL_COM_CLUBS`).

---

## Транзакции

- `@Transactional` на уровне Service, не Controller/Repository
- `readOnly = true` для чтения
- `propagation = REQUIRED` (по умолчанию) — норма

---

## Kotlin-специфика

- `data class` для DTO и domain моделей
- `val` по умолчанию, `var` только с обоснованием
- `@JvmInline value class` для ID-типов (предотвращает путаницу UUIDs)
- `sealed class` / `sealed interface` для алгебраических типов
- `when` exhaustive — использовать как expression

---

## Telegram Bot API интеграция

### Bot Token
- Хранить в env var `TELEGRAM_BOT_TOKEN`. Никогда в коде, конфигах или логах

### Webhooks vs Long Polling
- В production — только webhooks, long polling запрещён
- Webhook должен быть защищён (secret token или секретный путь)

### initData валидация
См. `.claude/rules/security.md` § "Для нашего Telegram initData".
Dev-bypass — через Spring profile, не через код-флаг.

### Rate limits
Telegram Bot API: **max 30 сообщений/сек** на бота.
- Массовые уведомления → через очередь (Redis / `@Async`)
- Не отправлять sync из request handler (блокирует HTTP-ответ)

### Stars Payments (специфика)
- `pre_checkout_query` надо подтвердить **в течение 10 секунд** (иначе Telegram отменит оплату)
- `successful_payment` handler — идемпотентный (Telegram может повторить webhook)
- Создание membership + transaction — в одной БД-транзакции

Конкретные шаблоны уведомлений, команды бота, комиссии — в PRD / `docs/modules/telegram-bot.md`, не здесь.

---

## Общие утилиты

Универсальные вспомогательные функции — в `common/util/`:
- `DateFormatter` — форматирование дат
- `SlugGenerator` — генерация slug из названия
- `TokenGenerator` — генерация случайных токенов
- и т.д.

**Правило:** если логика используется в 3+ местах — выносить в util.

---

## Чего избегать

- Service напрямую дёргает `DSLContext` (текущая проблема в проекте)
- Controller делает маппинг DTO↔domain
- Controller возвращает jOOQ Record
- Inline-маппинг в Service (extension functions `toDomain()`, `toDto()` разбросаны по коду — переносить в Mapper-класс)
- Логика в Repository (Repository только CRUD)
- `!!` на публичных API
- `catch (e: Exception)` без обработки
- `RuntimeException` вместо конкретных исключений
- Бизнес-валидация в DTO (это дело Service)
- Универсальная логика продублирована в 3+ местах — выносить в utils
