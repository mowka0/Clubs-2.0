# Обработка ошибок

## Fail Fast

Падать рано и громко, не глотать ошибки.
- Ошибку, которую нельзя обработать осмысленно — не ловить, пусть падает.
- Глобальный handler превратит её в 500 с логом и request-id.
- Глотание ошибок (`catch { }` без логики) — запрещено.

```kotlin
// ❌ плохо — ошибка проглочена
try {
    riskyOperation()
} catch (e: Exception) {
    // silent
}

// ❌ плохо — проглочено с бесполезным логом
try {
    riskyOperation()
} catch (e: Exception) {
    log.error("Error", e)  // и что дальше?
}

// ✅ хорошо — либо обработать, либо пробросить
try {
    riskyOperation()
} catch (e: SpecificException) {
    return fallbackValue()  // конкретная обработка
}
// остальное летит вверх
```

## Специфичные exceptions

Ловить самое конкретное, кидать самое информативное.
```kotlin
// ❌
throw RuntimeException("not found")

// ✅
throw UserNotFoundException(userId)
```

В проекте для бэкенда есть `GlobalExceptionHandler` — он мапит конкретные исключения в HTTP-ответы. Создавать новые исключения для новых бизнес-ошибок.

## Валидация на границах

Валидируй ТОЛЬКО на границах системы:
- HTTP вход → DTO с `@Valid`
- Внешние API → проверка формата ответа
- Вход от юзера в UI → форма валидации

**НЕ** валидируй в внутренних слоях то, что гарантировано типами или контрактом:
```kotlin
// ❌ лишняя проверка — уже гарантировано типом
fun createUser(userId: UUID) {
    if (userId == null) throw IllegalArgumentException()
    // ...
}

// ✅ Kotlin тип `UUID` (non-nullable) гарантирует что не null
fun createUser(userId: UUID) {
    // ...
}
```

## Анализ типа ошибки

Ошибки бывают разные — разная стратегия:
- **Transient** (сеть, timeout) → retry с exponential backoff
- **Permanent** (not found, validation) → вернуть клиенту 4xx, не retry
- **Programmer error** (NPE, assertion) → упасть громко, не маскировать

## Exception Safety

При любой ошибке — система в консистентном состоянии.
- `@Transactional` для БД-операций: всё или ничего
- `try-finally` или `use` для ресурсов (соединения, файлы)
- Откат изменений в памяти при partial failure

```kotlin
@Transactional
fun transferCredits(from: UserId, to: UserId, amount: Int) {
    subtractCredits(from, amount)
    addCredits(to, amount)
    // если вторая операция упадёт — первая откатится
}
```

## Логирование ошибок

Логируем с контекстом:
```kotlin
// ❌
log.error("Error")

// ✅
log.error("Failed to process payment: userId={} amount={} reason={}",
    userId, amount, e.message, e)
```

Уровни:
- **ERROR** — неожиданная ошибка, нужно разбираться
- **WARN** — ожидаемая но подозрительная ситуация (rate limit hit, invalid JWT)
- **INFO** — нормальный ход событий (user logged in, payment processed)
- **DEBUG** — детали для отладки (только локально или с `LOGGING_LEVEL_COM_CLUBS=DEBUG`)

## Чего НЕ делать

- **Defensive programming мания** — валидация на каждом шагу без причины. Доверяй типам и контрактам внутри системы.
- **Exception для control flow** — исключения для исключительных ситуаций, не для нормального хода программы.
- **Голые `catch (e: Exception)`** — ловить конкретные типы.
- **Re-throw без контекста** — если перебрасываешь, добавляй контекст: `throw new PaymentError("...", cause = e)`.
