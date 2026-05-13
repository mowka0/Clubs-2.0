# Application — отсутствует `@Size` на `SubmitApplicationRequest.answerText`

**Severity:** Low
**Module:** application
**Найдено:** post-flight docs alignment `feature/refactor-application` (2026-05-12)
**Status:** open

## Контекст

`SubmitApplicationRequest` в текущей реализации:

```kotlin
data class SubmitApplicationRequest(
    val answerText: String? = null
)
```

Файл: `backend/src/main/kotlin/com/clubs/application/ApplicationDto.kt`.

Поле `answerText` приходит в `POST /api/clubs/{id}/apply` от пользователя и сохраняется в `applications.answer_text` (TEXT, без CHECK constraint на длину).

## Risk

- **DoS / disk pressure:** пользователь шлёт многомегабайтную строку → раздувание таблицы `applications`. С лимитом 5 заявок в сутки на юзера за день можно записать до 5 × N MB.
- **Performance:** `SELECT` по `applications` (например `getMyApplications`, `getClubApplications`) грузит большие answerText'ы — клиентский трафик и latency растут.
- **PRD §4.5.1 §«Вопрос для заявки» 200 символов** — про сам вопрос, не про ответ; конкретного лимита на ответ в PRD нет.

PRD пока не фиксирует предел длины ответа. Хороший дефолт — 2000 символов (или 5000): ответы на собеседование редко длиннее.

## Решение

Добавить в `SubmitApplicationRequest`:

```kotlin
data class SubmitApplicationRequest(
    @field:Size(max = 2000)
    val answerText: String? = null
)
```

После согласования с продуктом точного лимита — обновить `docs/modules/application.md` (раздел DTO) и `PRD-Clubs.md` §4.5.1.

## Note

Pre-existing, **не регрессия** рефактора application-модуля. Зафиксировано в post-flight alignment, чтобы не потерять.

## Ref

- OWASP A04 «Insecure Design» / CWE-770 «Allocation of Resources Without Limits»
- `.claude/rules/security.md` § «Input Validation» → правило про `Length min/max`
