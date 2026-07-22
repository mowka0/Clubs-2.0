# Pre-existing: 2 падения CreateEventRequestValidationTest

**Обнаружено:** 2026-07-22 (сессия profile-quest), воспроизводится на чистом master (`62f5d83`).

- `open flag combined with a limit is rejected` — expected true, was false
- `missing limit without the open flag is rejected` — expected true, was false

Оба — про Bean Validation связки `isOpen`/`participantLimit` (V62, открытые встречи).
Локально `./gradlew test` красный; **в CI незаметно** — GitHub Actions гоняет только
Gitleaks и деплой, бэкенд-тесты не запускаются (отдельный gap: добавить test-workflow?).

Не связано с profile-quest (проверено stash-прогоном на чистом дереве). Требует
отдельного разбирательства: либо валидация реально не работает (баг прода!), либо
тест устарел после какого-то рефакторинга.
