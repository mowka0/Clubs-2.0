# Именование и code smells

## Именование

### Главное правило
Имя должно заменять комментарий. Если нужен комментарий чтобы объяснить имя — переименуй.

### Функции — глаголы
```kotlin
// ✅
fun getUser(id: UUID): User
fun calculateReputation(events: List<Event>): Int
fun validateInput(data: String): Boolean

// ❌
fun userProcessor(id: UUID): User  // processor — не действие
fun user(id: UUID): User            // существительное для функции
```

### Классы — существительные
```kotlin
// ✅
class UserRepository
class ReputationCalculator
class EventValidator

// ❌
class UserManager    // "Manager" — слово-паразит
class UserHelper     // "Helper" — ни о чём
class DoStuff        // глагол в имени класса
```

### Булевы — вопросы
```kotlin
// ✅
val isActive: Boolean
val hasPermission: Boolean
val canEdit: Boolean

// ❌
val active: Boolean       // неясно, свойство или действие
val permission: Boolean   // не похоже на булево
```

### Длина имени = область видимости
- Локальная переменная — короткая: `i`, `user`, `result`
- Параметр функции — описательная: `userId`, `clubName`
- Публичный класс — полное имя: `MembershipRepository`, `TelegramInitDataValidator`

### Без аббревиатур
Исключения — общепринятые: `id`, `url`, `api`, `dto`, `http`.
```kotlin
// ❌
val usrRepo: UserRepository
val msgSvc: MessageService

// ✅
val userRepository: UserRepository
val messageService: MessageService
```

### Консистентность
Один концепт — одно слово. Не `fetch`, `get`, `retrieve`, `load` — выбрать одно.

---

## Code Smells

### Long Method (> 30-40 строк)
Метод делает слишком много. **Фикс:** разбить на под-методы по смыслу. Каждый под-метод — одна операция с говорящим именем.

### Large Class (> 200-300 строк или > 7 методов)
Класс нарушает Single Responsibility. **Фикс:** разделить по областям ответственности.

### Long Parameter List (4+ параметров)
Сложно читать, легко перепутать порядок. **Фикс:** сгруппировать в объект-параметр (data class / interface).
```kotlin
// ❌
fun createEvent(title: String, date: Date, clubId: UUID, creatorId: UUID, maxParticipants: Int, price: Int)

// ✅
data class CreateEventParams(val title: String, val date: Date, ...)
fun createEvent(params: CreateEventParams)
```

### Feature Envy
Метод больше работает с данными чужого класса чем своего. **Фикс:** перенести метод в тот класс, чьи данные он использует.

### Primitive Obsession
Везде `String`, `UUID`, `Int`. Легко перепутать `userId` с `clubId`. **Фикс:** ввести типы.
```kotlin
// Kotlin
@JvmInline value class UserId(val value: UUID)
@JvmInline value class ClubId(val value: UUID)

// TypeScript
type UserId = string & { __brand: 'UserId' }
type ClubId = string & { __brand: 'ClubId' }
```
Компилятор не даст перепутать.

### Duplicated Code
Одна логика в нескольких местах. **Фикс:** выделить общую функцию. **НО:** см. правило "не путать с случайным дублированием" в `principles.md`.

### Magic Numbers / Strings
```kotlin
// ❌
if (status == 5) { ... }
if (role == "admin") { ... }

// ✅
const val STATUS_CONFIRMED = 5
if (status == STATUS_CONFIRMED) { ... }

enum class Role { ADMIN, MEMBER, GUEST }
if (role == Role.ADMIN) { ... }
```

### Dead Code
Закомментированный или неиспользуемый код — удалять. Git помнит историю.

### Shotgun Surgery
Одно концептуальное изменение требует правок в 10 файлах. Признак нарушения Single Responsibility. **Фикс:** объединить разбросанную логику в один модуль.

### Data Clumps
Одни и те же 3-4 параметра путешествуют вместе по разным методам. **Фикс:** это объект, выделить его.

### God Object
Класс, который "знает всё и делает всё". **Фикс:** разбить по зонам ответственности.

---

## Комментарии

### Что писать
- **WHY** неочевиден: скрытый инвариант, workaround для конкретного бага, бизнес-причина странного поведения.
- **Публичное API** — KDoc/JSDoc для функций, которые используются вне модуля.

### Что НЕ писать
- **WHAT** делает код — это должно быть видно из имён и структуры.
- Ссылки на текущую задачу/тикет/автора — это живёт в PR/git blame, а не в коде.
- Закомментированный код — удалять.
- Мёртвые TODO "from 2023" — либо делать, либо удалять.

### Правило
Если можешь переписать код так, чтобы комментарий стал не нужен — переписывай. Комментарий — последнее средство.
