# Backlog: `isMember()` не покрывает cancelled-в-периоде

## Контекст
Введено в PR-1 эпика «Выход из клуба» ([docs/modules/club-leave.md](../modules/club-leave.md)).

`MembershipRepository.isMember(userId, clubId)` возвращает `true` только для `status='active'`. После того как пользователь нажал «Выйти из клуба» в **платном** клубе:
- `memberships.status` → `cancelled`
- `subscription_expires_at` сохраняется (доступ обещан до этой даты)
- Frontend ClubPage показывает табы `«Активности»` и `«Участники»` для cancelled-в-периоде юзера и badge «Подписка отменена. Доступ до DD MMMM YYYY»

Но backend `isMember` вернёт `false`, поэтому реальные запросы (`Stage2Service.confirmParticipation`, `VoteService.vote`, `MemberService.getClubMembers`, …) ответят **403 «Not a member of this club»**.

## Влияние
- Paid cancelled-в-периоде юзер видит UI «у меня ещё есть доступ» — но при попытке проголосовать/посмотреть участников получает 403.
- Противоречит PRD §4.7.3: «Доступ сохраняется до конца оплаченного периода».
- До PR-1 случай в проде практически не встречался: UI на legacy `/cancel` не было.

## Что сделать
Расширить `MembershipRepository.isMember`:

```kotlin
fun isMember(userId: UUID, clubId: UUID): Boolean =
    dsl.fetchExists(
        dsl.selectOne()
            .from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.USER_ID.eq(userId)
                    .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
                    .and(
                        MEMBERSHIPS.STATUS.eq(MembershipStatus.active)
                            .or(
                                MEMBERSHIPS.STATUS.eq(MembershipStatus.cancelled)
                                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.greaterThan(OffsetDateTime.now()))
                            )
                    )
            )
    )
```

Альтернатива: новый метод `hasAccess(userId, clubId)` и точечная замена call-sites — безопаснее по blast radius, но требует обхода всех мест.

## Where used (call-sites)
- `Stage2Service.kt:60` — confirm/decline participation
- `VoteService.kt:27` — stage-1 vote
- `MemberService.kt:21` — `getClubMembers` access guard
- `MemberService.kt:29` — `getMemberProfile` access guard

(grep `isMember\\|isActiveMemberInActiveClub` в `backend/src/main/kotlin/com/clubs/` для свежего списка)

## Когда делать
Рекомендуется до PR-2 (`transfer ownership`) или PR-3 (`delete club`) этого же эпика, чтобы paid пользователи получили обещанный UX. Не блокирует PR-1 в смысле деплоя, но блокирует валидное end-to-end paid leave-сценарий в проде.

## Tests
- `MembershipRepositoryTest`: добавить кейсы cancelled-в-периоде → true; cancelled-после-периода → false; cancelled с null expire (free) → false.
- Интеграционные на `Stage2Service.confirmParticipation` под cancelled-в-периоде юзером — должны проходить.
