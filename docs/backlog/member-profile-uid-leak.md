# Backend: `GET /api/clubs/{id}/members/{userId}` не проверяет членство `userId`

**Статус:** open · **Создано:** 2026-05-12 · **Origin:** post-flight alignment `feature/refactor-membership`
**Severity:** Low

## Что не так

`MemberController.getMemberProfile` (`backend/src/main/kotlin/com/clubs/membership/MemberController.kt`, делегирует в `MemberService.getMemberProfile`) проверяет только что **caller** — active member клуба. Целевой `userId` (чей профиль смотрят) **не проверяется** на membership в этом клубе.

Code path:
1. Проверка caller: `if (!membershipRepository.isMember(callerId, clubId)) throw ForbiddenException`
2. `val user = userRepository.findById(userId) ?: throw NotFoundException`
3. `val reputation = reputationRepository.findByUserAndClub(userId, clubId)` — если записи нет, defaults (100/0/0/0)
4. Возврат `MemberProfileDto` — `firstName`, `username` (telegramUsername), `avatarUrl`, дефолтные значения репутации

Поэтому **любой active member клуба** может, зная UUID произвольного юзера, получить его `firstName`, `telegramUsername`, `avatarUrl` через этот endpoint — даже если этот юзер не имеет отношения к клубу.

## Severity: Low

- **Discoverability**: UUID v4 имеет 122 бита энтропии — перебор `userId` практически нереален. Получить чужой UUID можно только если он уже известен по другому источнику.
- **Leak volume**: 3 поля — `firstName`, `telegramUsername`, `avatarUrl`. Чувствительные данные (телефон, email, raw initData) не утекают.
- **Audience**: только authenticated active members клуба (не публичный leak).
- **Дефолтная репутация** (100/0/0/0) может создать ложное впечатление что юзер — member клуба, хотя это не так. UX confusion, но не security.

## Воспроизведение

1. User A авторизован, является active member клуба X
2. User A получает UUID юзера B (например, увидел на странице другого клуба, или знает из внешнего источника)
3. `curl -H "Authorization: Bearer <A_jwt>" https://staging../api/clubs/X/members/<B_uuid>`
4. Получит 200 OK с `firstName`/`username`/`avatarUrl` юзера B + дефолтной репутацией, даже если B не member клуба X

Ожидаемое поведение: 404 (юзер B не существует в этом клубе) или 403.

## Решение

В `MemberService.getMemberProfile`:
```kotlin
if (!membershipRepository.isMember(callerId, clubId))
    throw ForbiddenException("Not a member of this club")

if (!membershipRepository.isMember(userId, clubId))
    throw NotFoundException("Member not found")   // 404, не 403 — не раскрываем существование юзера
```

Альтернатива: `findClubMembersWithUserInfo(clubId)` уже умеет фильтровать по членству — можно использовать `.singleOrNull { it.userId == userId }` и кинуть 404 если null. Один SELECT вместо двух.

## Когда фиксить

**Не блокер для текущего рефакторинга** (паритет с master — баг существовал и в старом коде). До public release — желательно, но не критично. Кандидат на отдельный bugfix-PR: `bugfix/member-profile-membership-check`. ~20мин с тестом.

## Связанные

- `MemberController.getMemberProfile` — endpoint
- `MemberService.getMemberProfile` — бизнес-логика
- `MembershipRepository.isMember` — предикат, который надо добавить вторым вызовом
- `docs/backlog/club-events-membership-check.md` — резолюция аналогичного gap'а для events
