# Roadmap: PR-2 (transfer ownership) + PR-3 (delete club)

Продолжение эпика «Выход из клуба». PR-1 (leave для участника) — смержен в `master`. Этот документ фиксирует всё, что уже обсуждено и согласовано с пользователем, чтобы следующая сессия смогла подхватить работу без повторного сбора требований.

## Контекст эпика (что обсудили с пользователем)

Полный диалог, который привёл к decompose'у на 3 PR:

1. Кнопка «Выйти из клуба» доступна **только участнику**. У организатора её нет.
2. У организатора вместо «Выйти» — **два** действия:
   - **Передать права** другому участнику.
   - **Удалить клуб** (не выйти).
3. При удалении клуба:
   - **Бесплатный** клуб — удаляется в моменте (soft-delete сейчас уже работает, надо только убедиться, что cascade-правила консистентны с leave).
   - **Платный** клуб:
     - Каждому участнику в личное сообщение бота: «в течение 30 дней клуб будет удалён, все вопросы — организатору (ссылка) или в саппорт @varlamov_ivan».
     - **Деньги** организатора заблокированы на 30 дней до удаления клуба.
     - Через 30 дней клуб реально удаляется, в этот же момент удаляются все связанные сущности (события / сборы / membership-rows).
4. Transfer ownership — **мгновенный**, без согласия нового owner (выбрал → сразу передал).
5. Refund для участников при удалении платного клуба:
   - Сейчас отдельной refund-кнопки нет.
   - Если будет **много запросов в саппорт** — спроектируем automated refund-flow.
   - Зафиксировано в [refund-on-paid-club-delete.md](#нужен-будет-отдельный-backlog-файл) (создать в PR-3, когда станет hot).

---

## PR-2: Transfer ownership

### Цель
Дать организатору возможность передать клуб другому активному участнику клуба. Мгновенно, без подтверждения от нового owner.

### Scope
**Входит:**
- Backend: endpoint `POST /api/clubs/{id}/transfer-ownership` с body `{ newOwnerUserId: UUID }`.
- Backend: атомарная транзакция:
  1. Проверки auth/business.
  2. `clubs.owner_id` → newOwnerUserId.
  3. `memberships.role` старого owner → `member`.
  4. `memberships.role` нового owner → `organizer`.
- Frontend: UI на `OrganizerClubManage` (уже есть «Удалить клуб» рядом — туда же) — кнопка «Передать права», модал с поиском участника, confirm с warning'ом.
- Tests: unit + integration.

**НЕ входит:**
- Множественные организаторы (один owner на клуб).
- Подтверждение от нового owner (по решению пользователя — мгновенный).
- Аудит-история передач (можно в отдельный backlog при необходимости).

### Бизнес-правила

`POST /api/clubs/{id}/transfer-ownership`

| Условие | Ответ |
|---|---|
| Caller не owner клуба | 403 «Forbidden» |
| `newOwnerUserId` == caller | 400 «Cannot transfer ownership to yourself» |
| `newOwnerUserId` не существует | 404 «User not found» |
| `newOwnerUserId` не active member клуба (включая cancelled-в-периоде, по логике расширенного `isMember`) | 400 «New owner must be an active member» |
| Клуб не существует / `is_active=false` | 404 «Club not found» |
| Успех | 200 OK + обновлённый `ClubDto` |

### Транзакция
```kotlin
@Transactional
fun transferOwnership(clubId: UUID, callerUserId: UUID, newOwnerUserId: UUID): ClubDto {
    val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
    if (club.ownerId != callerUserId) throw ForbiddenException("Forbidden")
    if (newOwnerUserId == callerUserId) throw ValidationException("Cannot transfer ownership to yourself")
    val targetMembership = membershipRepository.findActiveByUserAndClub(newOwnerUserId, clubId)
        ?: throw ValidationException("New owner must be an active member")
    val callerMembership = membershipRepository.findActiveByUserAndClub(callerUserId, clubId)
        ?: throw IllegalStateException("Owner has no membership row") // edge — defensive

    clubRepository.updateOwner(clubId, newOwnerUserId)
    membershipRepository.updateRole(callerMembership.id, MembershipRole.member)
    membershipRepository.updateRole(targetMembership.id, MembershipRole.organizer)
    log.info("Ownership transferred: clubId={} from={} to={}", clubId, callerUserId, newOwnerUserId)
    return clubMapper.toDto(club.copy(ownerId = newOwnerUserId))
}
```

Новые методы репозиториев (которых пока нет):
- `ClubRepository.updateOwner(clubId, newOwnerId)`
- `MembershipRepository.updateRole(membershipId, role)`

### UI

**`OrganizerClubManage.tsx`** — рядом с «Удалить клуб»:
- Кнопка «Передать права» (mode="outline", secondary danger).
- При нажатии — модал `TransferOwnershipModal.tsx`:
  - Список членов клуба (через существующий `useClubMembersQuery(clubId)`).
  - Search-input (фильтр по имени).
  - Tap по участнику → confirm: «Передать клуб «{name}» пользователю {firstName lastName / @username}? Вы потеряете права организатора, отменить нельзя.»
  - Кнопки: «Отмена» + «Передать» (destructive).
- На успех: toast «Права переданы», navigate на `/clubs/{id}` (теперь как обычный участник, без табы «Управление»).

### Tests
- Unit `MembershipServiceTest` или новый `OwnershipServiceTest`:
  - AC-1 success: owner → role=member, target → role=organizer, clubs.owner_id=target.
  - AC-2: caller не owner → 403.
  - AC-3: target = caller → 400.
  - AC-4: target не member → 400.
  - AC-5: club не найден → 404.

### Спека
Создать `docs/modules/club-ownership.md` или дополнить `docs/modules/club-leave.md` отдельной секцией. Лучше отдельный файл — это разные lifecycle.

### Размер PR
Ожидаемо: backend ~150 строк (endpoint + service + 2 repo-метода + tests), frontend ~200 строк (UI модал + mutation + invalidations). Полный PR ~400 строк — в пределах reviewer.md.

---

## PR-3: Delete club

### Цель
Дать организатору возможность удалить клуб. Бесплатный — instant soft-delete (уже работает, дополнить cascade). Платный — 30-дневное отложенное удаление с DM-уведомлениями участникам и hold денег.

### Scope
**Входит:**
- Backend: расширить существующий `ClubService.deleteClub` для free-клубов — добавить cascade (consistent c PR-1 leave-cascade): events → cancelled, skladchinas → cancelled, applications → удаление pending+approved.
- Backend: для **платных** клубов — новое поведение:
  - Поле `clubs.scheduled_deletion_at` (Flyway-миграция).
  - `deleteClub` для paid: ставит `scheduled_deletion_at = now + 30 days`, оставляет `is_active=true`, рассылает DM всем active members.
  - Поле `transactions.funds_locked_until` или флаг `clubs.funds_locked` — детально решить в спеке.
  - Scheduled job `ClubDeletionProcessor` (как `MembershipExpireProcessor`): раз в час проверяет клубы, у которых `scheduled_deletion_at <= now`, и выполняет реальный soft-delete + cascade.
  - Endpoint `POST /api/clubs/{id}/cancel-deletion` — пока ещё `scheduled_deletion_at > now`, организатор может отменить удаление.
- Bot: новый шаблон DM:
  > «Клуб «{name}» будет удалён через 30 дней. Если у вас остались вопросы — обратитесь к организатору ({@telegramUsername}) или в саппорт @varlamov_ivan.»
- Frontend:
  - В `OrganizerClubManage` для платного клуба — изменить existing «Удалить клуб»: модал предупреждает про 30-дневный период + funds hold.
  - Баннер на `OrganizerClubManage` / `ClubPage` для всех (members + organizer) клубов с `scheduled_deletion_at`: «Клуб будет удалён DD MMMM YYYY».
  - Для organizer — кнопка «Отменить удаление» в баннере.

**НЕ входит:**
- Automated refund для участников (отдельный backlog-файл создать ниже).
- Расчёт остатка средств организатора после refund'ов.
- UI для саппорта (@varlamov_ivan) — пока ручной процесс.

### Миграция БД

```sql
-- V{N}__add_club_scheduled_deletion.sql
ALTER TABLE clubs
    ADD COLUMN scheduled_deletion_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN deletion_initiated_by UUID REFERENCES users(id);

CREATE INDEX idx_clubs_scheduled_deletion_at ON clubs(scheduled_deletion_at)
    WHERE scheduled_deletion_at IS NOT NULL;
```

Funds hold — отдельная таблица или поле? Решить в спеке. Простейший вариант:
```sql
ALTER TABLE transactions
    ADD COLUMN funds_locked_until TIMESTAMP WITH TIME ZONE;
```
И при scheduled deletion: `UPDATE transactions SET funds_locked_until = clubs.scheduled_deletion_at WHERE club_id = ?`.

### Бизнес-правила (paid delete)

`POST /api/clubs/{id}` (DELETE) — расширение существующего:

| Условие | Действие |
|---|---|
| Caller не owner | 403 |
| Клуб free (`subscription_price=0`) | Старое поведение: instant `is_active=false` + cascade всех связанных |
| Клуб paid, `scheduled_deletion_at = null` | `scheduled_deletion_at = now+30d`, DM всем active members, funds_locked_until для transactions |
| Клуб paid, `scheduled_deletion_at != null` | 400 «Deletion already scheduled» |

`POST /api/clubs/{id}/cancel-deletion`:
- Caller = owner ИЛИ admin (опционально).
- `scheduled_deletion_at = null`, `deletion_initiated_by = null`, `transactions.funds_locked_until = null`.
- DM всем участникам: «Удаление клуба отменено».

### Scheduled job
`ClubDeletionProcessor` (`@Scheduled(fixedDelay = 3_600_000)`):
1. SELECT clubs WHERE scheduled_deletion_at <= now() AND is_active = true.
2. Для каждого: `is_active = false`, cascade (events → cancelled, skladchinas → cancelled, memberships → expired).
3. Лог `INFO "Club hard-deleted after 30d window: clubId={}, members={}"`.

### Frontend
**Модал удаления (paid)** — переделать существующий:
> «Удалить клуб «{name}»?  
> Клуб будет удалён через **30 дней** (DD MMMM YYYY). До этой даты:  
> • участники сохраняют доступ;  
> • новые подписки не списываются;  
> • ваш баланс заблокирован.  
> Все вопросы участники могут задать вам или саппорту @varlamov_ivan.  
> [Отменить] [Удалить]»

**Баннер про scheduled deletion** — компонент `ClubDeletionBanner.tsx`:
- Сверху ClubPage и OrganizerClubManage.
- Цвет: brass-warning (как `cp-cancelled-note`).
- Текст: «Клуб будет удалён DD MMMM YYYY». Для owner добавлена кнопка «Отменить удаление».

### Спека
Создать `docs/modules/club-deletion.md`. Включить sequence diagram для 30-дневного flow.

### Backlog-файлы, которые нужно создать при старте PR-3
- `docs/backlog/refund-on-paid-club-delete.md` — automated refund-flow. Тригерится при росте запросов в саппорт. Дизайн: возврат через Telegram Stars refund API, частичный пропорционально оставшимся дням подписки.

### Размер PR
Ожидаемо: backend ~500 строк (миграция + endpoint + processor + cascade + bot template + tests), frontend ~300 строк (banner + modal + mutation + invalidations). Полный ~800 строк — может потребоваться разбить на PR-3a (free delete + cascade consistency) + PR-3b (paid 30d flow). Решить при старте — посмотреть на реальный размер MR-1 (transfer) чтобы откалибровать.

---

## Готовые куски, на которые можно опираться

- **Cascade-логика leave** (free + paid): `MembershipService.leaveClub` + `JooqEventResponseRepository.deleteByUserAndClubAndActiveEvents` + `JooqSkladchinaRepository.deleteParticipantFromActiveSkladchinasInClub` + `JooqApplicationRepository.deleteActiveByUserAndClub`. PR-3 cascade для free delete и для scheduled paid hard-delete должны переиспользовать эти методы (вынести в `ClubLifecycleService` если будет три call-site'а).
- **isMember расширенный** (active OR cancelled-в-периоде): уже в master после PR-1. Transfer ownership должен принимать таких users как валидных получателей.
- **FreeMembershipActivator inc member_count на reactivate**: тоже уже в master. Для PR-3 cascade при scheduled delete для paid — нужно решить, что делать со всеми `memberships` (предложение: hard-delete или mark expired без cascade member_count, так как сам клуб удаляется).
- **DM через бота**: использовать `NotificationService.sendDm` (уже используется для applications). Шаблоны — в `bot/`.

---

## Известные backlog-айтемы из PR-1, которые могут пригодиться в PR-3
- [club-leave-cascade-verification.md](club-leave-cascade-verification.md) — те же SQL-выборки актуальны для проверки free delete и для paid scheduled delete.
- [club-leave-cross-club-reputation-preservation.md](club-leave-cross-club-reputation-preservation.md) — при hard-delete клуба что делать с `user_club_reputation`? Сохранить как archived (не удалять)? Вопрос на спеку PR-3.

---

## Как начать следующую сессию

```
Привет! Продолжаем эпик «Выход из клуба». PR-1 уже в master.
Делаем PR-2 transfer ownership по плану из docs/backlog/club-leave-pr2-pr3-roadmap.md § «PR-2: Transfer ownership».
Создай ветку feature/club-transfer-ownership и поехали.
```

Все требования и решения уже зафиксированы — повторно собирать со мной не нужно.
