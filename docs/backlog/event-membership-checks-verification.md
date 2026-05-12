# QA: проверить is-member-checks на странице события (RSVP / Stage 2 / Attendance)

**Статус:** open · **Создано:** 2026-05-12 · **Origin:** отложенный smoke-test после `feature/refactor-membership`
**Severity:** Low (защитная проверка, не баг)

## Зачем

В рефакторинге membership заменили inline-SQL is-member-check в `VoteService.castVote` и `Stage2Service.confirmParticipation` на единый вызов `membershipRepository.isMember(userId, clubId)`. Reviewer провёл line-by-line сверку и подтвердил паритет с master (status=active, без `clubs.IS_ACTIVE` фильтра — намеренно).

Тесты (unit + integration `EventControllerSecurityTest`) проходят. Но на staging UI-сценарии RSVP/Stage 2/Attendance **не были вручную провалидированы** перед merge — пользователь отложил проверку.

## Что проверить (когда будет тестовое событие)

На staging `https://staging.77-42-23-177.sslip.io`:

### RSVP («Записаться» / «Не приду»)
- **Member клуба, событие в статусе `voting`/`upcoming`** — кнопка работает, голос засчитывается, счётчик going меняется
- **Non-member клуба** (открыть прямую ссылку на событие если есть) — 403 «Not a member of this club»
- **Member, но событие уже прошло (после Stage 2 cutoff)** — кнопка либо disabled, либо 400 «Voting window closed»

### Stage 2 («Подтвердить участие»)
- Открывается за 24ч до события (см. `Stage2Service`)
- **Member, проголосовавший «приду»** — кнопка confirm работает
- **Member, не голосовавший в RSVP** — кнопка либо отсутствует, либо confirmable
- **Non-member** — 403

### Attendance («Отметить присутствие»)
- Доступна только organizer'у клуба (через `@RequiresOrganizer`)
- **Organizer** — может отмечать каждого участника как пришёл / не пришёл
- **Обычный member** — кнопка отсутствует или 403

## Если найдётся регрессия

Сравнить с master через `git checkout master -- backend/src/main/kotlin/com/clubs/event/VoteService.kt` и запустить тот же сценарий — если на master работает, на feature/refactor-membership нет → регрессия рефакторинга, фикс в bugfix-ветке.

## Когда

Не блокер. Когда у тестировщика будет тестовое событие в клубе на staging. ~10-15 минут ручной проверки.

## Связанные

- `backend/src/main/kotlin/com/clubs/event/VoteService.kt:30-38` (старая логика → новая через `isMember`)
- `backend/src/main/kotlin/com/clubs/event/Stage2Service.kt:65-73` (то же)
- `backend/src/test/kotlin/com/clubs/event/EventControllerSecurityTest.kt` — integration test (5 кейсов, все green)
- `feature/refactor-membership` commit — добавил `MembershipRepository.isMember`
