# Backlog: ручная проверка cascade при free leave

## Контекст
PR `feature/club-leave-member` (см. [docs/modules/club-leave.md](../modules/club-leave.md) § «Бизнес-правила (free club)»).

Автоматические тесты есть только на verify-уровне (mock'ed repository вызовы в `MembershipServiceTest`):
- `applicationRepository.deleteActiveByUserAndClub` вызывается
- `eventResponseRepository.deleteByUserAndClubAndActiveEvents` вызывается
- `skladchinaRepository.deleteParticipantFromActiveSkladchinasInClub` вызывается
- membership-строка переводится в `status='cancelled'` (отдельной записи счётчика нет — он считается на лету из `memberships`; колонка `member_count` дропнута в V33)

Реальная БД-каскадная семантика (что строки **действительно удаляются** + границы фильтров: `events.status IN (upcoming, stage_1, stage_2)`, `skladchinas.status='active'`, `applications.status IN (pending, approved)`) на staging-инстансе **руками не подтверждалась**. Юзер не проверял этот сценарий перед мержом.

## Сценарий для проверки

GIVEN:
- Свободный клуб X (subscription_price = 0), caller — active member
- У caller'а есть: 1 RSVP на upcoming event клуба X, 1 RSVP на completed event клуба X, 1 pending-участие в active skladchina клуба X, 1 paid-участие в closed_success skladchina клуба X, approved-but-cancelled application в архивный клуб (другой) — не должна затрагиваться

WHEN:
- POST `/api/clubs/X/leave`

THEN:
- 200 OK, response.status = `cancelled`
- В БД: `event_responses` для upcoming event — **удалена**, для completed event — **сохранена**
- В БД: `skladchina_participants` для active sbor — **удалена**, для closed_success — **сохранена**
- В БД: `applications` для (caller, X) с status='approved' — **удалена**; archived club application — **не затронута**
- live-счёт участников X уменьшился на 1 (membership-строка caller'а теперь `cancelled` и выпала из счёта — он считается из `memberships`, колонки `member_count` больше нет)
- `user_club_reputation` для (caller, X) — **не изменена**
- `transactions` — не затронуты

## Как проверить

1. На staging создать минимальный набор данных (можно через UI: создать клуб от owner-аккаунта, вступить test-юзером, создать ивент + сбор, записать test-юзера).
2. Выполнить leave от лица test-юзера.
3. SSH в БД staging (Coolify → postgres console) → выборки:
   ```sql
   SELECT id, status FROM memberships WHERE user_id = '<caller>' AND club_id = '<X>';
   SELECT er.event_id, e.status, er.final_status
     FROM event_responses er JOIN events e ON e.id = er.event_id
    WHERE er.user_id = '<caller>' AND e.club_id = '<X>';
   SELECT sp.skladchina_id, s.status, sp.status
     FROM skladchina_participants sp JOIN skladchinas s ON s.id = sp.skladchina_id
    WHERE sp.user_id = '<caller>' AND s.club_id = '<X>';
   SELECT id, status FROM applications WHERE user_id = '<caller>' AND club_id = '<X>';
   -- live-счёт участников (колонки clubs.member_count больше нет — дропнута в V33):
   SELECT count(*) FROM memberships WHERE club_id = '<X>' AND status IN ('active', 'grace_period');
   SELECT reliability_index, total_attendances FROM user_club_reputation WHERE user_id = '<caller>' AND club_id = '<X>';
   ```
4. Сверить с ожиданиями из секции «THEN» выше.

## Когда делать
Перед мержом следующего PR этого эпика (PR-2 transfer / PR-3 delete) — если по факту окажется что cascade оставляет «висящие» строки, это раздует PR-3 (delete должен будет руками подчищать то, что должен был чистить leave).

Альтернатива — написать `@SpringBootTest` интеграционный тест с реальной jOOQ через testcontainers Postgres. Сейчас у проекта `@SpringBootTest` есть в других модулях, но не для membership-каскада.
