# Bot не доставляет DM участникам при создании события

**Статус:** root cause identified · **Создано:** 2026-05-12 · **Обновлено:** 2026-05-12 (post-flight `feature/refactor-bot`) · **Origin:** ручной staging-тест после `feature/refactor-membership`
**Severity:** Medium (engagement-критично для core-loop клубов)

## Root cause (выявлено 2026-05-12)

После post-flight аналитики `feature/refactor-bot` корень бага установлен:

**`NotificationService.sendEventCreated` — orphan-метод.** `EventService.createEvent` не импортирует `NotificationService` и не зовёт `sendEventCreated`. Метод существует и работает (SQL валиден, текст корректен, inline-кнопка есть), но его никто не вызывает. DM не приходят, потому что они **не отправляются**, а не потому что Telegram их теряет.

`grep -r "sendEventCreated" backend/src/main/kotlin` → только определение в `NotificationService.kt:34`.

**Решение:** см. `docs/backlog/telegram-bot-prd-gaps.md` § GAP-003 + § GAP-010 (фильтр по `membership.status` — обязательное условие, иначе DM пойдут отписавшимся).

Гипотезы про webhook config / staging bot config ниже **сохранены для истории**, но не являются причиной. После закрытия GAP-003+GAP-010 они могут стать актуальными отдельно (например, для prod webhook), но не для текущего бага.

## Симптом

Organizer создаёт событие через `POST /api/clubs/{id}/events`. Ожидаемо: бот @clubs_admin_bot шлёт DM с уведомлением каждому участнику клуба. Реально: DM не приходят.

## Что точно НЕ является причиной

**Рефакторинг membership-модуля 2026-05-12.** Reviewer провёл line-by-line сверку `NotificationService.getMemberTelegramIds` старого vs нового (`findMemberTelegramIds`) — SQL-запрос и список telegram_id'шников идентичны master. Если баг есть — он pre-existing.

**Рефакторинг bot-модуля 2026-05-12 (`feature/refactor-bot`).** Метод `sendEventCreated` функционально не изменился (SQL ушёл в `MembershipRepository`, текст и кнопка остались идентичны). До рефакторинга метод тоже был orphan.

## Подозреваемые причины (исторический список — НЕ актуальны после идентификации root cause)

1. **Staging bot config** — `TELEGRAM_BOT_TOKEN` на staging Coolify может быть пустым или указывать на prod-бота. Если staging и prod используют одного бота, webhook прописан на prod-URL, и staging-инстанс молча не получает callback'и.
2. **Webhook URL** — Telegram Bot API в production-режиме требует webhook (long polling запрещён, см. `.claude/rules/backend.md` § Telegram Bot API). Если webhook у бота прописан только на prod URL — staging бот вообще не активен.
3. **Async ошибка** — `NotificationService` может использовать `@Async` для отправки. Если executor пустой/упал — DM в очереди не уходят. Логи backend на staging покажут.
4. **Telegram API rate limit** — 30 сообщений/сек на бота. Если перегружен — DM ставится в очередь или дропается.
5. **Юзер не нажал /start у бота** — если получатель ни разу не запустил @clubs_admin_bot, Telegram заблокирует отправку DM (это политика Telegram). Проверь: участник клуба, который тестируется, должен сначала открыть бота и нажать /start.

## Воспроизведение

1. Staging: `https://staging.77-42-23-177.sslip.io`
2. Создай два testing-аккаунта в TG, оба нажми /start у @clubs_admin_bot
3. Аккаунт A — создаёт клуб, аккаунт B — вступает
4. A создаёт событие через UI
5. B ожидаемо получает DM, реально — нет

## Investigation план

1. Проверить логи backend на staging: `docker logs <backend-container> | grep -i "notification\|telegram\|dm"` сразу после создания события
2. Проверить env vars staging: `TELEGRAM_BOT_TOKEN`, webhook URL
3. Проверить `getWebhookInfo` у Telegram Bot API для @clubs_admin_bot — куда указывает
4. Если webhook на prod — нужно либо отдельный staging-бот, либо запустить long polling в staging-профиле (dev-only)

## Когда фиксить

Pre-existing, не блокер для merge membership-рефакторинга. Но **до public release** — обязательно, event-DM это базовый engagement-крючок (если organizer не уверен что участники узнают о событии — снижается частота создания).

Кандидат на ветку `bugfix/bot-event-dm` или devops-ветку `devops/staging-bot-config` (если корень — конфиг, а не код).

## Связанные

- `backend/src/main/kotlin/com/clubs/bot/NotificationService.kt` — `sendEventCreated`
- `JooqMembershipRepository.findMemberTelegramIds` — источник списка получателей (рефакторинг сохранил master-поведение: возвращает ВСЕХ membership-rows без фильтра по status; в backlog отдельно стоит вопрос фильтра по active)
- `.claude/rules/backend.md` § Telegram Bot API
