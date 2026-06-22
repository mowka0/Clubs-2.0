# Cleanup: удалить вестигиальную колонку `clubs.member_count`

**Создано:** 2026-06-22 (после bugfix `bugfix/member-count`)

## Контекст
`clubs.member_count` была денормализованным счётчиком участников, который **дрейфовал** (правился в
разрозненных, неполных местах: `MembershipService.cancelMembership` не уменьшал; leave→rejoin→leave
рассинхронизировал) и показывал, например, `0` для клуба с 2 живыми участниками. Bugfix перевёл **весь
показ** на живой счёт из `memberships` (active+grace, включая организатора), вычисляемый на лету в
`JooqClubRepository` (display + сортировка дискавери + теги «Популярный»/«Свободные места»). Колонка
больше **нигде не читается**.

## Что доделать (отдельным PR)
1. Удалить вызовы `ClubRepository.incrementMemberCount` / `decrementMemberCountSafely` и сами методы
   (call-sites: `FreeMembershipActivator`, `PaymentService`, `MembershipService.leaveFreeClub`,
   `SubscriptionLifecycleService`) — они поддерживают write-only колонку.
2. Дропнуть колонку `clubs.member_count` миграцией + `generateJooq`.
3. Убрать `member_count` из `JooqClubRepository.create` и из тестов, где он сидится.

## Почему НЕ сейчас
Миграция-дроп требует следующего свободного номера `V{N}`. На момент багфикса в полёте ветка
`feature/club-quality-l3-rank` со своей `V32` (ещё не в master). Чтобы не словить коллизию номера /
out-of-order Flyway — дроп делаем **после мержа L3** (тогда max = V32, cleanup = V33, без конфликта).
