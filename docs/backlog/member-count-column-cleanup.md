# Cleanup: удалить вестигиальную колонку `clubs.member_count` — ✅ DONE (V33)

**Создано:** 2026-06-22 (после bugfix `bugfix/member-count`)
**Закрыто:** 2026-06-25 (bugfix `bugfix/member-count-column-cleanup`). Колонка дропнута миграцией `V33__drop_clubs_member_count.sql`, вся inc/dec-машинерия удалена, тесты зелёные. Файл оставлен как запись.

## Контекст
`clubs.member_count` была денормализованным счётчиком участников, который **дрейфовал** (правился в
разрозненных, неполных местах: `MembershipService.cancelMembership` не уменьшал; leave→rejoin→leave
рассинхронизировал) и показывал, например, `0` для клуба с 2 живыми участниками. Bugfix перевёл **весь
показ** на живой счёт из `memberships` (active+grace, включая организатора), вычисляемый на лету в
`JooqClubRepository` (display + сортировка дискавери + теги «Популярный»/«Свободные места»). Колонка
больше **нигде не читается**.

## Что было сделано (✅ выполнено)
1. ✅ Удалены вызовы `ClubRepository.incrementMemberCount` / `decrementMemberCountSafely` и сами методы
   (call-sites: `FreeMembershipActivator.activate`, `PaymentService.handleSuccessfulPayment`,
   `MembershipService.leaveFreeClub`, `SubscriptionLifecycleService.processExpiry`). Заодно убрана
   вестигиальная цепочка `findGracePeriodExpiredGroupedByClub` / `ClubMembershipExpiredCount`, которая
   питала только scheduler-декремент. `processExpiry` теперь делает только `moveActiveToGracePeriod` +
   `moveGracePeriodToExpired`.
2. ✅ Колонка `clubs.member_count` дропнута миграцией `V33__drop_clubs_member_count.sql` + `generateJooq`.
3. ✅ Убран `member_count` из `JooqClubRepository.create` и из тестов, где он сидился.

Счётчик участников полностью derived из `memberships` (см. `JooqClubRepository.countLiveMembers` /
`countLiveMembersByClub`), драйф невозможен по построению. Миграция `V33` (max был `V32` после L3 PR #81).
