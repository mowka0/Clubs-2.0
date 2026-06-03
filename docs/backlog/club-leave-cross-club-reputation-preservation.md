# Backlog: ручная проверка сохранения сквозной репутации после leave

## Контекст
PR `feature/club-leave-member` (см. [docs/modules/club-leave.md](../modules/club-leave.md) § «Неприкосновенное»).

В спеке зафиксировано инвариант: cascade при leave **никогда** не трогает `user_club_reputation` и `event_responses` для **завершённых** событий. Это обеспечивает, что:
- Per-club метрики (`reliability_index`, `promise_fulfillment_pct`, `total_confirmations`, `total_attendances`) переживут leave и реактивируются при повторном вступлении.
- Cross-club aggregate из `Reputation.kt` (`Cross-club aggregate of one user's reputation rows`) — основа репутации в Профиле — продолжит считаться по всем клубам, включая те, из которых пользователь вышел.

Код корректен (cascade-фильтры скриптуют только `upcoming/stage_1/stage_2` события и `active` сборы), но end-to-end проверка через реальную БД + UI Профиля **не выполнялась**.

## Сценарий для проверки

### Setup
- Свободный клуб X. Test-юзер — active member.
- Провести событие до `completed`, где test-юзер: stage_1=going → stage_2=confirmed → attendance=attended.
- Дождаться ReputationService scheduler-цикла или дёрнуть `calculateReputation` вручную — в `user_club_reputation` (test-юзер, X) должна появиться строка с `reliability_index = 200` (старт 100 + Железобетонный +100), `total_confirmations = 1`, `total_attendances = 1`.
- Запомнить значения в Профиле test-юзера ДО leave: «Сквозная репутация» / «Мероприятий посещено».

### Действие
- POST `/api/clubs/X/leave` от лица test-юзера.

### Ожидания (verify в БД и в UI)
1. `user_club_reputation` (test-юзер, X) — **строка осталась, значения не изменились**.
2. `event_responses` для completed event — **сохранена** (attendance=attended).
3. Профиль test-юзера → «Сквозная репутация» и счётчик «Мероприятий посещено» — **те же значения**, что были до leave.
4. (Опционально) test-юзер повторно вступает в X через `joinOpenClub` → `FreeMembershipActivator` reactivate → membership active → в Профиле и в клубе видны прежние метрики, репутация не сбросилась до 100.

## Как проверить

1. Достроить test-данные на staging (см. setup выше — нужна имитация stage_2 голосования + attendance, что требует наличия двух аккаунтов или DB-инъекций).
2. Снимок Профиля + клуба ДО leave (скриншот).
3. Leave → снимок ПОСЛЕ.
4. SSH в БД:
   ```sql
   SELECT * FROM user_club_reputation WHERE user_id = '<test>' AND club_id = '<X>';
   SELECT event_id, final_status, attendance FROM event_responses
    WHERE user_id = '<test>'
      AND event_id IN (SELECT id FROM events WHERE club_id = '<X>' AND status = 'completed');
   ```
   → строки на месте, значения те же.
5. Опционально: rejoin → проверить что `user_club_reputation` подтянулась (а не пересоздалась с дефолтами).

## Когда делать
Перед мержом PR-3 «delete club» — там надо будет принимать решение, что делать с репутацией при **удалении самого клуба** (cross-club aggregate включает строки для is_active=false клубов? или нет?). Подтверждённое поведение текущего PR-1 (репутация неприкосновенна при выходе ИЗ клуба) — отправная точка для дизайна PR-3.

Если ручная проверка покажет, что репутация всё-таки теряется (например, скрытый кэш в Профиле или левый JOIN с `clubs.is_active=true`) — это блокер для следующих PR и требует фикс в этом PR-1 до мержа.
