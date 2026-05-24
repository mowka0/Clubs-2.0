# Auth — детализация error-кодов для membership/organizer checks

**Статус:** open · **Создано:** 2026-05-24 · **Origin:** post-flight alignment
`feature/unified-activity-creation` (Reviewer + Tester заметили, что спека
говорила `403 NOT_MEMBER`, а реальный `@RequiresMembership` aspect отдаёт
общий `FORBIDDEN`).

## Контекст

`backend/src/main/kotlin/com/clubs/common/auth/AuthorizationAspect.kt` бросает
`ForbiddenException` для обоих случаев:
- user не active member клуба (`@RequiresMembership`)
- user не organizer клуба (`@RequiresOrganizer`)

`GlobalExceptionHandler.handleForbidden` маппит **любой** `ForbiddenException`
в response `{ "code": "FORBIDDEN", "message": <ex.message> }`. Сообщение
человеко-читаемое и различает причину («You must be an active member...» vs
«Only the club organizer can perform this action»), но **machine-readable
`code`** одинаковый.

## Почему это (потенциально) важно

OWASP A09 (Security Logging and Monitoring Failures) — лог и алертинг
ориентируются на коды, не на свободный текст. С единым `FORBIDDEN`:
- Невозможно поднять отдельный алерт «слишком много отказов по membership»
  vs «слишком много отказов по organizer» (последнее может быть симптомом
  попытки IDOR-эскалации).
- Frontend не может тонко отличить «ты в принципе не в клубе» от «ты не
  организатор» без парсинга свободного текста (что хрупко при i18n).

## Что сделать (если возьмём)

1. Ввести два специфичных кода (например `NOT_MEMBER`, `NOT_ORGANIZER`) либо
   ввести специфичные exception-классы (`NotMemberException`,
   `NotOrganizerException`) с отдельными handler-методами в
   `GlobalExceptionHandler`.
2. Обновить все спецификации, где упоминается `403 FORBIDDEN` от этих
   aspect'ов — на конкретный код.
3. Обновить frontend error-handling если хотим показывать разные UX
   placeholders (now: один общий «Нет доступа»).
4. Логирование `WARN` с кодом — централизовать в aspect, не в handler.

## Почему **не** делаем сейчас

- Не блокирует ни одну текущую фичу.
- Требует синхронного обновления ~10+ спек, где упомянут `403 FORBIDDEN`.
- Сообщение в `message` уже различает причину для DevOps debug; frontend
  показывает generic «Нет доступа» — нет user-facing деградации.
- Алертинг по auth-failures сейчас отсутствует в принципе — error-code
  granularity без алертов = ложное чувство безопасности.

## Связанное

- `docs/modules/unified-activity-creation.md` § Errors (AC-16: `403 FORBIDDEN`)
- `.claude/rules/security.md` § OWASP A09
- `backend/src/main/kotlin/com/clubs/common/auth/AuthorizationAspect.kt`
- `backend/src/main/kotlin/com/clubs/common/exception/GlobalExceptionHandler.kt`
