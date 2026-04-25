# Backend: добавить membership check на GET /api/clubs/:id/events

Обнаружено 2026-04-25 Security-агентом при ревью `feature/unified-club-page`.

## Что не так

`EventService.getClubEvents` (`backend/src/main/kotlin/com/clubs/event/EventService.kt:29-36`) проверяет только что клуб существует — НЕ проверяет membership вызывающего. Endpoint `GET /api/clubs/:id/events` отдаёт полный список событий клуба ЛЮБОМУ authenticated user'у с валидным JWT, даже если он не состоит в клубе.

В отличие от:
- `MemberController.getMembers` (line 26-37) — корректно бросает `ForbiddenException("Not a member of this club")` если caller не member
- `MemberController.getMemberProfile` (line 74-86) — такая же проверка

## Почему **сейчас** актуально

Pre-existing с момента создания endpoint'а, но `feature/unified-club-page` сделал гэп **более заметным**:
- Frontend visitor view (PR #27) explicitly показывает placeholder «События доступны участникам клуба» — это **обещание privacy** пользователю
- Любой authenticated user может **bypass'нуть UI**: открыть DevTools → `fetch('/api/clubs/<id>/events', {headers: {Authorization: ...}})` → получить полный JSON со временем + локацией
- Особенно критично для closed-клубов: их events НЕ должны быть индексируемы посторонними («слушай, я подсмотрел в API что у вас там meetup в субботу — приду», = плохой UX для организатора и safety risk)

## Воспроизведение

1. Авторизуйся как user A (не состоит в клубе X)
2. Возьми его JWT (DevTools → Network → headers)
3. `curl -H "Authorization: Bearer <jwt>" https://staging../api/clubs/<X>/events`
4. Получишь 200 OK с полным массивом events:
   ```json
   [{"id":"...", "title":"...", "eventDatetime":"...", "locationText":"...", "goingCount":N, ...}]
   ```

## Решение

В `EventService.getClubEvents`:
1. Получить `currentUserId` из SecurityContext
2. Запросить активный membership: `membershipRepository.findActiveByClubAndUser(clubId, userId)`
3. Если membership null AND club.accessType ≠ 'open' → throw `ForbiddenException("Not a member of this club")`
4. Если accessType == 'open' → пропустить проверку (открытые клубы могут показывать events публично — это product decision)

**Альтернатива (более строгая):** требовать membership всегда, независимо от accessType. Тогда даже open-клубы скрывают events до вступления. Совпадает с frontend variant B (visitor видит placeholder для всех клубов).

**Рекомендую альтернативу** — соответствует frontend-инварианту «events видны только member'ам».

Тест: integration-test bypass-сценарий + happy-path как member.

## Когда фиксить

**До public release**. Защита приватности meetups — base requirement из исходной product-философии (см. `project_clubs_over_events_rationale.md` — clubs-first именно из-за safety от незнакомцев). Если кто-то может узнать когда и где meetup из API без членства — защита нарушена.

Кандидат: `bugfix/club-events-membership-check`. Backend ~1ч + integration test ~30мин.

## Связанные

- `EventController.getClubEvents` — controller, делегирует Service
- `MemberController.getMembers` — образец правильной проверки
- `frontend/src/components/club/ClubEventsTab.tsx` — frontend consumer (мы предполагаем он только member'ом монтируется → backend защита defense-in-depth)
- `project_clubs_over_events_rationale.md` — почему events private = product принцип
