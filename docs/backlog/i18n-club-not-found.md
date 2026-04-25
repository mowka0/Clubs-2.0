# i18n drift: «Club not found» на ClubPage

Обнаружено 2026-04-25 при проверке PR #22 на staging.

## Что не так

При загрузке несуществующего клуба `GET /clubs/<uuid>` фронт показывает:
- Заголовок: «Ошибка» (русский ✅)
- Описание: **«Club not found»** (английский ❌)

Текст «Club not found» приходит из бэка как `message` в `ErrorResponse`
(`backend/src/main/kotlin/com/clubs/club/ClubService.kt`):
```kotlin
val club = clubRepository.findById(id) ?: throw NotFoundException("Club not found")
```

Frontend показывает `error.message` напрямую без локализации
(`frontend/src/pages/ClubPage.tsx`).

## Почему это drift

PRD-Clubs.md — русский UI. `docs/design/stack.md §14 Naming vocabulary` —
все user-facing-тексты на русском.

## Варианты фикса

1. **Заменить exception-message на русский** в `ClubService.kt`:
   `throw NotFoundException("Клуб не найден")`. Самый прямой путь, но
   нарушает принцип «error-сообщения backend на английском как technical
   identifier».
2. **Маппинг error.code на UI-сообщения на фронте** —
   правильнее long-term, но требует ввести `error.code` в backend
   `ErrorResponse` (сейчас есть `error: "Not Found"` и `message`,
   но `error` тоже на английском).
3. **Простейший временный фикс**: `ClubPage.tsx` сам подставляет
   локализованный текст для известных кодов (404, 403, 5xx) через
   `errorCode → русский текст` map.

## Связанное

То же самое про:
- 403 «Only the club owner can ...» — эти увидеть на UI обычно нельзя
  (фронт не показывает кнопки которые ведут к 403), но если будут —
  тоже на английском
- 5xx «Internal server error» — тоже стоит локализовать
- Все остальные `NotFoundException("...")` / `ForbiddenException("...")` /
  `ConflictException("...")` сообщения

## Когда фиксить

Cosmetic, не блокирующее. Включить в `feature/pre-design-haptic` или
выделить отдельную ветку `bugfix/i18n-error-messages` параллельно.
