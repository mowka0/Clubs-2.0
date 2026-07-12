# Со-организаторы клуба (co-organizers)

> Ветка: `feature/co-organizers`. Спека написана 2026-07-12 по свежему sweep'у кода
> (обновляет impact-анализ `docs/backlog/member-admin-roles-S3-deferred.md` от 2026-06-27 —
> с тех пор добавились chatlink слайсы 1–5, личные приглашения PR #107, теги V55,
> титулы V54, гео+фото события PR #108, статистика клуба).
> База: `docs/modules/member-admin-profile.md` §4/§8 (S3, R1).
>
> **Статус: реализовано (as-built), post-flight alignment 2026-07-12.** Поведение,
> уточнённое в ходе разработки и security-аудита, внесено в текст ниже; сводка отличий
> от первоначальной редакции — § «As-built уточнения».

## Цель и контекст

Владелец клуба, доросшего до делегирования, даёт доверенным участникам роль
**со-организатора** — практически полные полномочия по ведению клуба, кроме
владельческих действий. Триггер из S3-deferred сработал: реальный спрос на
«со-орг ведёт клуб» есть.

**Как** владелец клуба, **я хочу** назначить участника со-организатором,
**чтобы** он разбирал заявки, вёл события/складчины и управлял участниками без меня.

**Как** со-организатор, **я хочу** видеть те же управляющие экраны, что владелец
(кроме владельческих), **чтобы** вести клуб полноценно.

Роль «модератор» НЕ строим (YAGNI, решение PO). Иерархия:
**organizer (владелец) > co_organizer > member**. Owner проходит любой гейт.

## Решения PO (залочены 2026-07-12)

1. **Одна роль** — `co_organizer` добавляется в enum `membership_role`
   (`'member','organizer'` → + `'co_organizer'`). Модератора нет.
2. **Права со-орга — всё, кроме владельческого.** Со-орг НЕ может:
   удалить клуб; назначать/снимать со-оргов (менять роли вообще);
   менять СБП-реквизиты; привязывать/отвязывать чат клуба;
   трогать биллинг/организаторскую подписку.
   Всё остальное — может: заявки (одобрить/отклонить/расширить-и-принять),
   управление участниками, события (создание/отмена/посещаемость/споры),
   складчины, награды, заметки, настройки клуба, просмотр финансов/аналитики.
3. **Назначение только из участников клуба**: владелец открывает карточку
   участника в ростере → «Сделать со-организатором». Приглашений извне нет.
   Снятие роли — там же, только владельцем.
4. **Видимость**: бейдж «Со-организатор» в ростере и на странице клуба.
   Владелец остаётся «Организатор».

## Ключевые правила

- **Роль действует только при активном членстве (fail-close).** Право со-орга
  = `clubs.owner_id == caller` ИЛИ (membership в этом клубе с `role = 'co_organizer'`
  И `status = 'active'`). `frozen` / `expired` / `cancelled` / легаси `grace_period`
  — прав НЕТ (403). Канон статуса — `MembershipAccess.hasAccess()` (только `active`).
  Внимание: `findActiveByUserAndClub` — misnomer, включает `frozen|expired`;
  для гейта роли использовать НЕ его, а строгую проверку `status = active`.
- **Со-орг управляет только участниками с ролью `member`.** Заморозка / кик /
  взнос / reject-dues / access-until / заметка / награды по владельцу или другому
  со-оргу → 403. Владелец управляет всеми, кроме себя (target `role = 'organizer'`
  остаётся неуправляемым, как сейчас в `loadManageableMember`).
- **Роли меняет только владелец.** Владелец не может сменить роль самому себе;
  нельзя назначить со-орга не-участнику; нельзя разжаловать владельца;
  значение `organizer` в body запрещено (передача владения — вне скоупа,
  club-leave PR-2).
- **Кик/выход со-орга**: роль исчезает вместе с membership. Повторное вступление
  = обычный `member`. Отдельного «снять роль при выходе» кода не нужно — роль
  живёт в строке membership, `cancelled`-строка прав не даёт (fail-close).
  Реализовано: путь реактивации старой строки (`JooqMembershipRepository`,
  reactivate-UPDATE) явно выставляет `role = member` — бывший со-орг, вернувшийся
  по инвайту/заявке, всегда обычный участник.
- **Единый механизм** (по S3-deferred, реализовано): двухслойная авторизация.
  Слой 1 — аннотация `@RequiresClubManager(clubIdParam)` (`common/auth/Annotations.kt`)
  + ветка `checkClubManager` в `AuthorizationAspect`; `@RequiresOrganizer` остался
  только на owner-only точках, включая новый role-эндпоинт. Слой 2 — компонент
  `common/auth/ClubManagerGuard` — ЕДИНСТВЕННАЯ реализация предиката «менеджер клуба»
  (owner-bypass внутри): `isManager(club, userId)` / `isClubManager(clubId, userId)` /
  `requireManager(club, userId)` (403) / `requireClubManager(clubId, userId)` (404+403) /
  `isActiveManagerMembership(membership)` (для мест, где caller-membership уже прочитан) /
  `requireManageableTarget(club, target, callerId)` (per-target матрица, общая для
  AccessGateService и AwardService). Оба слоя ходят в один компонент, копипасты
  предиката по сервисам нет. Тексты ошибок гейта — русские
  («Управлять клубом может владелец или активный со-организатор»,
  «Со-организатор может управлять только обычными участниками»,
  «Нельзя управлять организатором клуба»).

## Матрица прав

| Возможность | member | co_organizer | organizer (владелец) |
|---|---|---|---|
| Контент клуба (лента, события, складчины, чат-дверь) | ✅ | ✅ | ✅ |
| Заявки: список / одобрить / отклонить / расширить-и-принять | — | ✅ | ✅ |
| События: создать / отменить / посещаемость / споры / dispute-note | — | ✅ | ✅ |
| Складчины: создать / отметить оплату / resolve-decline / закрыть (любые сборы клуба) | — | ✅ | ✅ |
| Участники-`member`: freeze/unfreeze, взнос, reject-dues, кик, access-until, заметка, награды | — | ✅ | ✅ |
| Управление владельцем / другим со-оргом | — | — | ✅ (кроме себя) |
| Личные приглашения (invite-share) + regenerate invite-link | — | ✅ | ✅ |
| Настройки клуба (PUT /api/clubs/{id})² | — | ✅ | ✅ |
| Финансы / статистика / churned / awaiting-dues / инбокс заявок и счётчики | — | ✅ | ✅ |
| Смена ролей (PUT .../role) | — | — | ✅ |
| Привязка/отвязка/настройка чата клуба (chat-link) | — | — | ✅ |
| СБП-реквизиты, биллинг/организаторская подписка | — | — | ✅ |
| Удалить клуб | — | — | ✅ |

² Внутри менеджерского PUT есть полевой owner-гейт на СБП-реквизиты
(`paymentLink` / `paymentMethodNote`) — см. § «As-built уточнения» п. 2.

## Полный реестр точек авторизации (sweep 2026-07-12)

Целевая роль: **manager** = owner ИЛИ активный co_organizer; **owner** = только владелец.
Все пути — от `backend/src/main/kotlin/com/clubs/`.

### Слой 1 — аннотация `@RequiresOrganizer` (17 точек → все manager)

| # | Файл | Операция | Целевая роль |
|---|---|---|---|
| 1 | `membership/MemberController.kt:48` | POST `/{clubId}/members/{userId}/freeze` | manager¹ |
| 2 | `membership/MemberController.kt:57` | POST `.../unfreeze` | manager¹ |
| 3 | `membership/MemberController.kt:66` | POST `.../dues-paid` | manager¹ |
| 4 | `membership/MemberController.kt:77` | POST `.../reject-dues` | manager¹ |
| 5 | `membership/MemberController.kt:89` | POST `.../remove` (кик) | manager¹ |
| 6 | `membership/MemberController.kt:99` | POST `.../dues-unpaid` | manager¹ |
| 7 | `membership/MemberController.kt:109` | POST `.../access-until` | manager¹ |
| 8 | `membership/MemberController.kt:119` | PATCH `.../note` | manager¹ |
| 9 | `membership/MemberController.kt:143` | POST `.../awards` (выдать награду) | manager¹ |
| 10 | `membership/MemberController.kt:153` | DELETE `.../awards/{awardId}` | manager¹ |
| 11 | `membership/MemberController.kt:168` | GET `/{clubId}/award-suggestions` | manager |
| 12 | `club/ClubController.kt:92` | GET `/{id}/finances` | manager |
| 13 | `event/EventController.kt:31` | POST `/api/clubs/{id}/events` | manager |
| 14 | `skladchina/SkladchinaController.kt:26` | GET `/api/clubs/{clubId}/skladchinas/active` | manager |
| 15 | `skladchina/SkladchinaController.kt:36` | POST `/api/clubs/{clubId}/skladchinas` | manager |
| 16 | `clubquality/ClubQualityController.kt:43` | GET `/{clubId}/stats` | manager |
| 17 | `clubquality/ClubQualityController.kt:53` | GET `/{clubId}/churned-members` | manager |

¹ Гейт эндпоинта — manager, но per-target ограничение в сервисе (см. #38, #39):
со-орг действует только на `role = 'member'`.

### Слой 2 — service-level проверки

| # | Файл:строка | Операция | Целевая роль |
|---|---|---|---|
| 18 | `application/ApplicationService.kt:150` | approveApplication (`club.ownerId != organizerId`) | manager |
| 19 | `application/ApplicationService.kt:197` | expandAndApproveAll («Расширить клуб и принять всех», PR #107) | manager |
| 20 | `application/ApplicationService.kt:256` | rejectApplication | manager |
| 21 | `application/ApplicationService.kt:304` | getClubApplications (инбокс клуба) | manager |
| 22 | `application/ApplicationService.kt` | getMyClubsActionCounts И getMyPendingApplications (сам кросс-клубовый инбокс, не только счётчик) — скоуп был `findIdsByOwnerId` | managed-клубы: `ClubRepository.findManagedIds` (owned + active co-org) |
| 23 | `event/AttendanceService.kt:38` | markAttendance | manager |
| 24 | `event/AttendanceService.kt:146` | resolveDispute | manager |
| 25 | `event/EventService.kt:30` | createEvent (дублирует аннотацию #13 — заменить синхронно) | manager |
| 26 | `event/EventService.kt:77` | cancelEvent | manager |
| 27 | `event/VoteService.kt:92` | видимость `dispute_note` в getEventResponders (F5-06, сейчас `ownerId == userId`) | manager |
| 28 | `skladchina/SkladchinaCreationService.kt:38` | create (дублирует аннотацию #15) | manager |
| 29 | `skladchina/SkladchinaLifecycleService.kt:81` | closeManually («Only creator can close») | creator ИЛИ manager (У-1) |
| 30 | `skladchina/SkladchinaPaymentService.kt:293` | requireActiveAsCreator — орг-действия: resolve-decline, participants/mark-paid, unmark (3 эндпоинта) | creator ИЛИ manager (У-1) |
| 31 | `skladchina/SkladchinaMapper.kt:58` | `isOrganizerView = creatorId == caller` (гейт орг-действий на фронте) | creator ИЛИ manager (У-1, имя поля не менять — У-7) |
| 32 | `club/ClubService.kt:159` | updateClub (настройки клуба) | manager |
| 33 | `club/ClubService.kt:186` | deleteClub | **owner** (не меняется) |
| 34 | `club/ClubService.kt:119` | regenerateInviteLink | manager (У-4) |
| 35 | `club/InviteShareService.kt:70` | requireOwnerOrOrganizer (личные приглашения PR #107) | owner / role `organizer` / role `co_organizer` — только `status = active` (У-2) |
| 36 | `chatlink/ChatLinkService.kt:51,58,97,134,168,200,234,305` | requireOwner — get/refresh/update/unlink чат-линка | **owner** (не меняется) |
| 37 | `chatlink/ChatLinkBotService.kt:44,185` | привязка `/link` и отвязка из чата (бот-сторона) | **owner** (не меняется) |
| 38 | `membership/AccessGateService.kt:327-334` | loadManageableMember («Нельзя управлять доступом организатора») | расширить target-матрицу: caller-owner → любой target кроме `organizer`; caller-co-org → только target `role = 'member'` (403 иначе) |
| 39 | `award/AwardService.kt` grant/revoke | сейчас без target-ограничения по роли | добавить: caller-co-org → только target `role = 'member'`; owner — как сейчас |
| 40 | `membership/MemberService.kt:39` | getClubMembers: `forOrganizer = role == organizer` (бакеты, canSeeScores, subscriptionExpiresAt) | manager (роль `co_organizer` тоже) |
| 41 | `membership/MemberService.kt:84` | getMemberProfile: `callerIsOrganizer` (note, dues-claim, proof, applicationAnswer) | manager |
| 42 | `membership/MemberService.kt` + `membership/JooqMembershipRepository.kt` | getOrganizerAwaitingDues / awaiting-dues счётчик — скоуп был по `clubs.owner_id` | managed-клубы (У-5); репо-методы переименованы: `findAwaitingDuesMembersByOwner` → `findAwaitingDuesMembersByManager`, `countClaimedAwaitingDuesByOwner` → `countClaimedAwaitingDuesByManager` (общее условие `clubManagedBy`) |
| 43 | `membership/MembershipService.kt:98,264` | «Owner cannot leave the club» | не меняется; co-org МОЖЕТ выйти (роль умирает с membership) |
| 44 | `common/auth/AuthorizationAspect.kt:34-41` | механизм checkOrganizer | добавить manager-ветку (см. «Единый механизм») |
| 45 | **НОВОЕ** | PUT `/api/clubs/{clubId}/members/{userId}/role` | **owner** (`@RequiresOrganizer`) |

Не трогаем (проверено sweep'ом): `activity/ActivityController` (`@RequiresMembership`),
`storage/StorageController` (`/api/upload`, любой авторизованный),
`subscription/SubscriptionController` (user-scoped биллинг владельца),
`membership/OrganizerMembersController` (сам контроллер без гейта — скоуп внутри #42),
`club/ClubService.kt:143` (`hasChatAccess` — со-орг проходит как active-участник),
member-side эндпоинты (`dues-claim`, `apply`, `cancel`, `vote`, `confirm/decline`,
`dispute`, `complete-free-membership`), бот-механики chatlink (теги/строгий
режим/live-pin — работают от статусов и наград, роль не участвует).

## API контракт

### PUT /api/clubs/{clubId}/members/{userId}/role — новый, owner-only

```
Request:  { "role": "member" | "co_organizer" }
Response 200: MembershipDto (существующий; поле role отражает новую роль)
```

Ошибки:
- `400 VALIDATION` — `role` вне enum запроса (в т.ч. `"organizer"`); target —
  владелец («нельзя разжаловать владельца»); target = сам вызывающий;
  промоут не-`active` участника (У-9); превышен лимит со-оргов (У-3).
- `403 FORBIDDEN` — вызывающий не владелец (со-орг тоже 403).
- `404 NOT_FOUND` — клуб не найден/неактивен; membership target в клубе нет,
  **включая `cancelled`** (вышедший/кикнутый — не участник: роль живёт в строке
  membership и умерла вместе с ней; демоут возможен при любом живом статусе —
  active/frozen/expired/grace_period).
- `409 CONFLICT` — параллельное изменение (UPDATE по паттерну
  `WHERE role = <ожидаемая>` + rows-affected guard, как org-toggle складчины).

Идемпотентность: повторный промоут уже-со-орга / демоут уже-member → `200` no-op.
Атомарность лимита (У-3): перед подсчётом со-оргов промоуты клуба сериализуются
транзакционным advisory-локом `pg_advisory_xact_lock(hashtext('club-roles:<clubId>'))`
(`MembershipRepository.lockRoleChanges`) — anti-TOCTOU, два параллельных промоута
не пробивают лимит. Счётчик лимита `countCoOrganizers` считает строки
`role = co_organizer` с любым не-`cancelled` статусом: frozen/expired со-орг прав
не имеет, но слот делегата занимает.
Логирование: INFO `Role changed: clubId={} targetUserId={} role={} by={}`.
DM target'у best-effort (У-6), сбой DM не роняет операцию (WARN).
Реализация: `membership/MemberRoleService.kt` (константа `CO_ORGANIZER_LIMIT = 5`),
эндпоинт в `membership/MemberController.kt` под `@RequiresOrganizer`.

### Существующие DTO — что уже отдаёт role (менять не нужно, только новое значение)

- `MembershipDto.role: String` — отдаётся в `GET /api/users/me/clubs` →
  **это и есть «моя роль в клубе» для гейтинга UI**, ничего добавлять не надо.
- `MemberListItemDto.role`, `MemberProfileDto.role`, `ClubMemberInfo.role` —
  ростер/карточка получают `"co_organizer"` автоматически после codegen.
- `ClubDetailDto.ownerId` — остаётся признаком «я владелец» на фронте.
- Сортировка ростера (`MemberService.kt:61-64`): владелец → со-орги → участники
  (добавить вторичный ключ по `co_organizer`).

## Миграция БД

Максимальная миграция сейчас — **V58** → новая: **V59**.

`V59__membership_role_co_organizer.sql` — изолированно, только ADD VALUE +
COMMENT (урок V37: никакого UPDATE с новым значением в той же миграции;
PG16 позволяет `ADD VALUE` в транзакции Flyway только пока значение не
используется — прецеденты V19/V23/V43):

```sql
-- Со-организатор клуба: владелец делегирует участнику почти все полномочия
-- (кроме владельческих: удаление клуба, роли, СБП, чат-линк, биллинг).
ALTER TYPE membership_role ADD VALUE IF NOT EXISTS 'co_organizer';

COMMENT ON COLUMN memberships.role IS
    'Роль в клубе: member — участник; organizer — владелец (ровно один, зеркалит clubs.owner_id, создаётся при создании клуба); co_organizer — со-организатор, назначается владельцем из активных участников, права действуют только при status = active.';
```

После миграции: `./gradlew generateJooq` (enum `MembershipRole` получит
`co_organizer`). Инвариант данных: в клубе ровно одна строка `role='organizer'`
(владелец), со-оргов — 0..N (лимит У-3).

## Frontend (as-built)

Роль вызывающего фронт уже знает: `GET /api/users/me/clubs` → `MembershipDto.role`.
`types/api.ts`: тип role расширен до `'member' | 'organizer' | 'co_organizer'`.
Хелперы — в одном модуле `utils/membershipRole.ts`:

- `isManagerRole(role)` — чистый предикат роли (organizer|co_organizer). Используется
  ТОЛЬКО для косметики (бейджи/подписи), не для гейтинга.
- `isActiveManagerMembership(membership)` — **основной гейт управляющего UI**:
  владелец — всегда; со-орг — только при `status === 'active'`. Строже исходной
  редакции спеки (там для гейтинга предлагался `isManagerRole`) — принято по nit 6
  ревьюера: fail-close, зеркалит серверный `ClubManagerGuard` — замороженный/
  просроченный со-орг не видит управляющий UI (бейдж роли остаётся), после
  разморозки права возвращаются без повторного назначения.
- `membershipRoleLabel(role)` / `ROLE_LABELS` — русские подписи ролей;
  `isMembershipRole(role)` — type guard.

| Файл | Как реализовано |
|---|---|
| `queries/organizerClubs.ts` | `filter(isActiveManagerMembership)` — «+» создания активностей и клуб-пикер работают у активного со-орга |
| `pages/ClubPage.tsx` | `isManager = isOwner \|\| isActiveManagerMembership(membership)`; `isOwner` остаётся для owner-only UI; шапка со-оргу — «Вы со-организатор» (PO №4) |
| `pages/MyClubsPage.tsx` | организаторская секция / hot actions / awaiting-dues — по `isActiveManagerMembership` (У-5); 👑 оставлена и для со-орга, title «Вы со-организатор»; орг-подача репутации и фолбэк «ваш клуб» — только владельцу (со-орг копит репутацию как участник); секция смены роли в «Ждут оплаты» — только владельцу |
| `pages/EventPage.tsx` | `hostClub.ownerId === userId \|\| isActiveManagerMembership(myHostMembership)` — посещаемость/споры/отмена доступны активному со-оргу |
| `pages/OrganizerClubManage.tsx` | со-оргу: таб «Чат» скрыт (owner-only, У-10); в «Настройках» скрыты **СБП-реквизиты** (владельческая секция, PO №2) и «Опасная зона» с удалением клуба; текст ошибки «включить платность» для со-орга объясняет, что реквизиты задаёт владелец |
| `components/club/ClubMembersTab.tsx` | бейдж роли: `isManagerRole(member.role) ? membershipRoleLabel(...) : null` → «Организатор» / «Со-организатор» (PO №4) |
| `components/club/MemberProfileModal.tsx` | roleLabel через `membershipRoleLabel`; `isManageable`: owner → target ≠ organizer; со-орг → target = member. Секция `RoleGate` «Роль в клубе» — только владельцу: «Сделать со-организатором» (кнопка задизейблена для `accessStatus` frozen/expired — У-9) / «Снять со-организатора», confirm-диалог; 409 закрывает карточку (кэш инвалидирован) |
| `components/subscription/SubscriptionCard.tsx` | НЕ менялся — биллинг owner-only, `co_organizer` сюда не входит |
| `api/membership.ts` | `updateMemberRole(clubId, userId, role)` → PUT `.../role`; тип `AssignableMemberRole = 'member' \| 'co_organizer'` |
| `queries/members.ts` | `useUpdateMemberRoleMutation` — инвалидация ростера + карточки профиля |

Экраны, которые со-орг получил без доработок (после смены гейтов):
инбокс заявок на «Мои клубы», «Ждут оплаты», формы создания события/складчины,
attention-бакеты и admin-панель карточки участника (кроме target owner/co-org),
вкладки Статистика/Финансы/Настройки управления клубом.

## Edge cases

1. Co-org `frozen`/`expired` (должник) → любые manager-эндпоинты 403 (fail-close);
   после `unfreeze`/`dues-paid` права возвращаются сами (роль в строке не трогается).
2. Co-org пытается заморозить/кикнуть/наградить владельца или другого со-орга → 403.
3. Владелец замораживает/кикает со-орга → можно; замороженный со-орг мгновенно
   теряет права; кик = membership `cancelled`, роль умирает.
4. Владелец меняет роль сам себе (`{userId} = owner`) → 400.
5. Промоут пользователя без membership в клубе → 404; промоут `frozen`/`expired`
   участника → 400 (У-9); демоут со-орга в любом живом статусе → 200
   (`cancelled` → 404 — не участник, роль умерла со строкой).
6. Body `{"role": "organizer"}` → 400 (передача владения — вне скоупа).
7. Шестой со-орг (лимит 5, У-3) → 400 с внятным сообщением.
8. Повторный промоут/демоут → 200 no-op (идемпотентно).
9. Co-org вызывает PUT role (даже демоут самого себя) → 403 (роли — только владелец).
10. Co-org вышел из клуба сам («Owner cannot leave» на него не действует) →
    membership `cancelled`, роль исчезла; повторный вход по инвайту/заявке →
    `member` (при реактивации старой строки роль обязана сброситься — см.
    «Ключевые правила»).
11. Гонка: владелец демоутит со-орга, пока тот жмёт «Одобрить заявку» —
    допустимо любое упорядочивание; после демоута следующий запрос со-орга → 403.
    Смена роли конкурентно с другой сменой → 409 (rows-affected guard).
12. Неактивный клуб (`is_active = false`) → 404 из аспекта/репозитория (как сейчас).
13. Заявки: со-орг и владелец одновременно одобряют одну заявку → второй получает
    400 «Application is not pending» (существующее поведение, не меняется).
14. Складчина, созданная со-оргом: владелец видит и управляет ею (creator ИЛИ
    manager, У-1); со-орг управляет сборами владельца.
15. Трюк с типом: `member` дёргает manager-эндпоинт → 403 (как раньше);
    JWT чужого клуба → 403/404 (IDOR закрыт существующими проверками clubId).
16. Дефолт истории: все существующие memberships не затронуты V59 (только новое
    значение enum, UPDATE нет).
17. `countActiveNonOrganizerMembersInClubs` (карточка доверия «доверяют N»):
    со-орг имеет `role != 'organizer'` → продолжает учитываться как участник. ОК.
18. Анти-фарм репутации (`ReputationService`: фильтр по `ctx.ownerId`):
    со-орг НЕ владелец → продолжает копить репутацию в клубе. Осознанно ОК
    (он реальный участник встреч; изменение — только по отдельному решению PO).

## Уточнения аналитика (принятые решения)

- **У-1. Складчины: со-орг ведёт ЛЮБЫЕ сборы клуба** (закрыть, отметить оплату
  участнику, resolve-decline, unmark) — не только свои. Обоснование: PO-принцип
  «всё, кроме владельческого»; «only creator» был артефактом эпохи, когда
  создавать мог только владелец. Гейт: `creatorId == caller` ИЛИ manager клуба
  складчины. `isOrganizerView` в DTO расширяется той же формулой.
- **У-2. Личные приглашения (PR #107): со-орг может.** `requireOwnerOrOrganizer`
  расширяется на `co_organizer` и ужесточается до `status = active` (сейчас
  внутри `findActiveByUserAndClub`, который пропускает frozen/expired — для
  владельца это было недостижимо, для ролей стало бы дырой fail-open).
- **У-3. Лимит со-оргов: 5 на клуб** (константа `CO_ORGANIZER_LIMIT = 5` с
  русским комментарием). Обоснование: офлайн-клубы — десятки людей; >5
  делегатов — признак деградации ролей («все — начальники»), а лимит держит
  ограниченным и веер DM/уведомлений. Проверка на промоуте, 400 при превышении.
  Не конфигурим через env (YAGNI).
- **У-4. Regenerate invite-link: со-оргу МОЖНО.** В owner-only списке PO его нет,
  действует «всё остальное — может»; отзыв ссылки — операционное управление
  каналом набора, не владельческое. Риск злоупотребления парируется тем, что
  со-орг — доверенное лицо владельца, и владелец может снять роль.
- **У-5. «Мои клубы» со-орга**: клуб появляется в организаторской секции с
  hot actions. Для этого кросс-клубовые скоупы «owned» расширяются до «managed»
  (owned + active co_organizer): `getMyClubsActionCounts`
  (`/api/users/me/applications-pending-count`) и awaiting-dues
  (`/api/users/me/organizer/awaiting-dues`, `countClaimedAwaitingDuesByOwner`).
  Иначе бейджи/инбокс со-орга всегда пустые — роль без глаз.
- **У-6. DM о смене роли — best-effort**: промоут «Вас назначили со-организатором
  клуба X» (+ deep-link на клуб), демоут — нейтральное «Роль со-организатора в
  клубе X снята». Паттерн как kick/freeze DM: сбой не блокирует операцию.
- **У-7. Имя поля `isOrganizerView` не меняем** при расширении семантики
  (creator|manager) — минимизация фронт-диффа; комментарий в DTO обновить.
- **У-8. Телеграм-чат клуба: со-орг НЕ получает админку чата.** Бот никого не
  промоутит; чат-механики (теги, строгий режим) работают от статусов/наград и
  не зависят от роли. Явно вне скоупа.
- **У-9. Промоут требует `status = active`** у target'а (назначать со-оргом
  должника-frozen бессмысленно и опасно — момент разморозки внезапно дал бы ему
  права). Демоут — при любом живом статусе (нужно уметь снять роль с
  замороженного); `cancelled` → 404 (as-built п. 10).
- **У-10. `GET /chat-link` остаётся owner-only** (403 со-оргу); фронт прячет таб
  «Чат» — read-only статус чата со-оргу не нужен для его задач, а таб с 403 хуже
  скрытого.

## As-built уточнения (post-flight alignment 2026-07-12)

Поведение, уточнённое в ходе разработки, ревью и security-аудита. Всё уже
внесено в соответствующие разделы выше; здесь — сводка одним списком.

1. **Фронт-гейтинг строже спеки (nit 6 ревьюера, принято).** Управляющий UI
   гейтится `isActiveManagerMembership` (fail-close: со-орг только при
   `status = 'active'`), а не чистым `isManagerRole`. `isManagerRole` остался
   только для косметики (бейджи/подписи). См. § Frontend.
2. **СБП-поля в менеджерском PUT — серверный owner-гейт (security-аудит).**
   `PUT /api/clubs/{id}` доступен менеджеру, но попытка со-орга изменить
   `paymentLink` / `paymentMethodNote` → `403 «Реквизиты для взноса задаёт
   владелец клуба»`. «Пытается изменить» = поле прислано И нормализованное
   значение (пустая строка → null, конвенция репозитория) отличается от
   текущего — no-op сабмит с тем же значением не блокируется. Мотив: деньги
   взносов идут владельцу, подмена реквизитов со-оргом = увод взносов.
   Фронт дополнительно прячет СБП-секцию в настройках со-оргу.
3. **Пейволл free→paid якорится на владельца (security-аудит).** Со-орг может
   поднять цену бесплатного клуба, но ёмкость платных клубов считается по плану
   `club.ownerId` (`countPaidByOwnerId(club.ownerId)` +
   `requirePaidClubCapacity(club.ownerId, ...)`), не по вызывающему — иначе
   со-орг с бесплатным планом обходил бы потолок владельца. 402 при исчерпании
   ёмкости получает вызывающий (со-орг).
4. **Лимит 5 со-оргов атомарный (security-аудит).** Промоуты клуба сериализуются
   `pg_advisory_xact_lock(hashtext('club-roles:<clubId>'))`
   (`lockRoleChanges`) перед `countCoOrganizers` — anti-TOCTOU. Счётчик считает
   все не-`cancelled` строки `role = co_organizer`: замороженный со-орг слот
   лимита занимает.
5. **Сообщения `ClubManagerGuard` — русские** (конвенция PO: пользовательские
   тексты ошибок авторизации на русском).
6. **`FinancesService.getFinances` переведён на manager-гейт** — в исходном
   реестре слоя 2 этот сервис отсутствовал (аннотация #12 дублируется
   service-проверкой, как у events/skladchina).
7. **`getMyPendingApplications` (сам кросс-клубовый инбокс) тоже managed** —
   исходный реестр (#22) упоминал только счётчик `getMyClubsActionCounts`.
   Оба используют новый `ClubRepository.findManagedIds(userId)`.
8. **Лента «мои складчины» и деталка сбора.** `UserSkladchinasService`
   вычисляет `isOrganizerView = creator ИЛИ manager` (одним запросом
   `findManagedIds` на страницу ленты); `SkladchinaQueryService.getDetail`
   пускает менеджера клуба даже не-участника (владелец видит и ведёт сбор
   со-орга, и наоборот — У-1). `SkladchinaMapper` принимает `callerIsManager`
   параметром (маппер репозитории не дёргает).
9. **Переименования репо-методов** `...ByOwner` → `...ByManager`:
   `findAwaitingDuesMembersByManager`, `countClaimedAwaitingDuesByManager`
   (общее SQL-условие `clubManagedBy`: владение ИЛИ exists active co-org строки).
10. **PUT role на `cancelled`-membership → 404** (вышедший — не участник; роль
    умерла со строкой). Демоут работает при любом живом статусе
    (active/frozen/expired/grace_period).
11. **Смена роли НЕ пишется в `membership_history`** — это не событие оттока
    join/leave, участник остаётся в клубе.

## Вне скоупа (привязано к `clubs.owner_id`, НЕ меняется)

- Организаторская подписка/биллинг: payer = владелец
  (`SubscriptionController`, планы-ёмкости, `countPaidByOwnerId`).
- Карточка доверия организатора (`OrganizerCardService`, organizer-card) —
  всегда владелец.
- СБП-реквизиты и claim-флоу выплат — реквизиты владельца.
- Привязка/отвязка/настройка чата клуба (`ChatLinkService`, `ChatLinkBotService`)
  и права бота в чате.
- Club quality: footprint-by-owner, per-owner cap, анти-фарм репутации владельца.
- Удаление клуба, передача владения (club-leave PR-2/PR-3 роадмап).
- Роль «модератор», приглашение со-орга извне, изменение PRD-матрицы ролей
  (PRD ролей не описывает — упоминание со-оргов добавить в PRD только с
  согласия PO при post-flight alignment).

## Критерии приёмки (staging, руками)

Подготовка: клуб K владельца O, участники A (active), F (frozen), второй клуб
для чистоты. Со-орг = A после промоута.

1. O открывает карточку A в ростере → видит «Сделать со-организатором»;
   после подтверждения карточка/ростер показывают бейдж «Со-организатор»,
   A получает DM (если писал боту).
2. В ростере и на странице клуба: O — «Организатор», A — «Со-организатор»;
   порядок: O, затем A, затем остальные. У самого A шапка клуба —
   «Вы со-организатор».
3. У A появились: вход в «Управление клубом» (табы Статистика, Финансы,
   Настройки — БЕЗ «Чат»), инбокс заявок и «Ждут оплаты» на «Моих клубах»,
   «+» создания события/складчины с клубом K в пикере.
4. A одобряет заявку → заявитель становится участником; A отклоняет заявку
   с причиной → заявителю уходит DM. Кнопка «Расширить клуб и принять всех»
   работает у A.
5. A создаёт событие, отменяет его, отмечает посещаемость прошедшего события,
   разрешает спор. В списке откликов A видит dispute-note участника.
6. A создаёт складчину; A закрывает складчину, созданную O (и наоборот: O
   управляет складчиной, созданной A); A отмечает оплату участнику чужого сбора.
7. A по участнику-`member`: freeze → unfreeze → «Взнос получен» → своя дата →
   заметка → награда (выдать/снять) — всё работает; профиль участника
   показывает A орг-поля (заметка, claim, скриншот).
8. A открывает карточку O или другого со-орга → админ-панель управления
   недоступна; прямой вызов API (freeze/награда по O) → 403.
9. A меняет настройки клуба (название/описание) → 200; A пытается удалить клуб
   (API) → 403; таб «Чат»: `GET /api/clubs/K/chat-link` от A → 403.
10. A шарит личное приглашение (prepared message) → работает;
    `POST /api/clubs/K/regenerate-invite` от A → 200, старая ссылка мертва.
11. A вызывает `PUT /api/clubs/K/members/X/role` → 403 (роли — только владелец).
12. O вызывает PUT role на себя → 400; body `{"role":"organizer"}` → 400;
    на пользователя без membership → 404; на F (frozen) промоут → 400.
13. Лимит: O назначает 5 со-оргов → 6-й промоут → 400 с сообщением про лимит.
14. O замораживает A (со-орга) → у A мгновенно пропадают manager-права
    (инбокс пуст/403 по API, «Управление» недоступно), бейдж роли остаётся;
    O размораживает → права вернулись без повторного назначения.
15. O снимает роль с A → бейдж исчез, A — обычный участник, управляющие экраны
    недоступны; повторный промоут → снова работает (идемпотентность: второй
    подряд демоут → 200 без изменений).
16. A выходит из клуба → в ростере его нет; повторное вступление по инвайту →
    A = «Участник» (роль не восстановилась).
17. Регресс: обычный участник — без изменений (нет «Управления», 403 на
    manager-API); владелец — всё как раньше; биллинг-карточка подписки
    показывается только владельцу-организатору, со-оргу — нет.
18. Регресс платного цикла: frozen-участник платит → A подтверждает взнос →
    доступ открыт до корректной даты (существующий AccessGate-флоу цел).

## Тест-план

### Backend unit/integration

- **Гейт-матрица на каждую точку реестра** (#1–#45): для owner / active co-org /
  frozen co-org / expired co-org / member / не-член — ожидаемые 200/403.
  Минимум: параметризованный тест хелпера `isClubManager` + по одному
  integration-тесту на контроллерную группу (members, applications, events,
  attendance, skladchina, club settings, finances, stats, invite-share,
  chat-link=owner-only, delete=owner-only).
- **PUT role**: промоут/демоут happy path; 400 (self, owner-target, role=organizer,
  frozen-промоут, лимит 5); 403 (co-org, member); 404 (нет клуба/membership);
  идемпотентность; 409-гонка (двойной конкурентный промоут);
  инвариант «ровно один organizer» после любых операций.
- **Per-target матрица** `loadManageableMember` + `AwardService`:
  owner→member ✅, owner→co-org ✅, owner→organizer ❌,
  co-org→member ✅, co-org→co-org ❌, co-org→organizer ❌.
- **Fail-close**: freeze со-орга → следующий manager-вызов 403 (в одном тесте,
  без пересоздания контекста).
- **Скоупы managed**: getMyClubsActionCounts / awaiting-dues возвращают данные
  co-managed клуба со-оргу и НЕ возвращают после демоута/заморозки.
- **Складчина**: closeManually и requireActiveAsCreator — creator|manager;
  isOrganizerView в DTO для co-org == true на чужом сборе клуба.
- **Re-join**: кик/выход со-орга → повторное вступление → role = member
  (включая путь реактивации старой строки membership).
- **Миграция**: V59 накатывается на копию схемы; jOOQ enum содержит co_organizer;
  существующие тесты зелёные (frozen/access-гейт целы — критерий S3 из
  member-admin-profile §7).

### Frontend (vitest)

- `isManagerRole`: organizer/co_organizer → true, member/undefined → false.
- ClubMembersTab: маппинг бейджей ролей (3 значения).
- MemberProfileModal: секция роли видна только owner'у; isManageable-матрица.
- OrganizerClubManage: набор табов для owner (4) vs co-org (3).

### Руками на staging (сверх критериев приёмки)

- Полный цикл со-орга с реального второго Telegram-аккаунта (не через API):
  промоут → ведение клуба день-в-день (заявка, событие, посещаемость,
  складчина, взнос) → демоут.
- DM-ки: назначение/снятие роли, отклонение заявки со-оргом, кик со-оргом —
  тексты и deep-link'и открываются в Mini App.
- Регресс чата клуба: строгий режим/теги живут при промоуте/демоуте участника
  с наградами.
