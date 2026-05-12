# Удалить legacy bot-команды `/кто_идет` и `/мой_рейтинг`

**Статус:** open · **Создано:** 2026-05-12 · **Origin:** обсуждение в рамках `feature/refactor-bot`
**Severity:** Low (cleanup, не функциональный баг)

## Что не так

`ClubsBot.handleWhoIsGoing` и `ClubsBot.handleMyRating` дублируют функциональность, которую Mini App уже полностью покрывает:

| Команда бота | Что показывает | Аналог в Mini App |
|---|---|---|
| `/кто_идет` | ближайшее событие любого клуба + going/maybe count | Таб «События» в клубе → детальная страница события (`EventPage`) — список RSVP с counter'ом |
| `/мой_рейтинг` | reliabilityIndex + promiseFulfillmentPct + totalConfirmations (одного случайного клуба) | Профиль участника (`MemberProfileDto`) — те же метрики, плюс per-club разбивка |

Команды — наследие initial design phase, когда бот мыслился как primary surface. После того как Mini App стал основным интерфейсом, эти команды никогда не были интегрированы в актуальный продуктовый experience (в `/start` welcome нет упоминания этих команд, в Mini App нет кнопок «попробовать через бота»).

Дополнительно у `/мой_рейтинг` есть **семантический баг** (GAP-001): показывает репутацию случайного клуба (orderBy updated_at DESC limit 1), не агрегат. Если бы команда была живой — это раздражало бы юзеров с членством в нескольких клубах.

## Что произойдёт после удаления

**Удаляются в коде:**
- `ClubsBot.handleWhoIsGoing` (~30 строк)
- `ClubsBot.handleMyRating` (~30 строк)
- `dispatch`-ветка для этих команд в `consume()`
- `EventRepository.findNextUpcomingEvent` — становится unused
- `ReputationRepository.findLatestByUserId` — становится unused
- `EventResponseRepository.findResponderTelegramIdsByEventId` — **остаётся** (используется в orphan `sendStage2Started`, который сам по себе GAP-004 — отдельный вопрос)
- Тесты `ClubsBotTest.kt` для этих команд (если будут добавлены)
- Зависимости в конструкторе `ClubsBot`: `eventRepository`, `reputationRepository` — становятся не нужны (если только остальные команды их не используют — проверить)

**Обновляются доки:**
- `docs/modules/telegram-bot.md` — удалить секции про команды, оставить только `/start` + payment handlers + нотификации
- `docs/backlog/telegram-bot-prd-gaps.md` — закрыть GAP-001 (теперь N/A) и GAP-008 (кнопка `/мой_рейтинг` исчезнет вместе с командой)
- `PRD-Clubs.md` §4.6 — обновить с согласия пользователя: убрать описание `/кто_идет` и `/мой_рейтинг`, оставить `/start` + автоматические нотификации

**Что НЕ удаляется:**
- `/start` — нужна для onboarding и Mini App кнопки
- Payment handlers (`pre_checkout_query`, `successful_payment`) — критичные
- `NotificationService` — нотификации остаются (отдельная история, см. GAP-003..GAP-005 — orphan-методы нужно подключить, не удалить)

## Почему сейчас не делаем

Текущий рефактор `feature/refactor-bot` — структурный (Controller → Service → Repository). Удаление команд = **feature scope change** (`chore` или `feat(bot)`), не `refactor`. Смешивать в одном PR ломает conventional commits семантику и затрудняет revert если понадобится.

После мержа `feature/refactor-bot` удаление команд — тривиальный delete-PR на 30-40 минут (DSL уже убран, Repository-методы изолированы).

## Открытые вопросы перед удалением

1. **Точно никто не использует команды?** — нет статистики использования бота. Если есть пользователи, привыкшие к командам — они увидят сообщение «Команда не найдена» от Telegram. Можно либо:
   - Удалить молча (если активность ~0%)
   - Заменить на ответ-redirect: «Эта функция теперь в приложении. Откройте Clubs ↓» + WebApp кнопка
2. **Обновлять PRD §4.6** — требует согласия пользователя (правило `rules/analyst.md`).
3. **`/мой_рейтинг` per-club агрегация** (GAP-001) — больше не актуально, закрыть GAP-001 как N/A.

## Когда фиксить

Не блокер. После merge `feature/refactor-bot` в master. Кандидат на ветку `chore/remove-legacy-bot-commands`. ~30-40 минут с обновлением спеки и PRD.

## Связанные

- `backend/src/main/kotlin/com/clubs/bot/ClubsBot.kt` — `handleWhoIsGoing` (lines ~140-195), `handleMyRating` (lines ~197-235)
- `backend/src/main/kotlin/com/clubs/event/EventRepository.kt` — `findNextUpcomingEvent` будет unused
- `backend/src/main/kotlin/com/clubs/reputation/ReputationRepository.kt` — `findLatestByUserId` будет unused
- `docs/modules/telegram-bot.md` § «Команды» — нужно подрезать
- `docs/backlog/telegram-bot-prd-gaps.md` GAP-001, GAP-008 — закрываются с удалением
- `PRD-Clubs.md` §4.6 — нужна правка с согласия пользователя
