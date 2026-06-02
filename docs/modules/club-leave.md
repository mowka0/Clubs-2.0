# Module: Club Leave (Выход из клуба)

Связка с [membership.md](membership.md): `leaveClub` — новая операция в `MembershipService`. Существующий `POST /api/clubs/{id}/cancel` сохраняется как legacy-endpoint (соответствует семантике «отменить подписку» из PRD §4.7.3), но новый UI использует только `/leave`.

## Цель

Дать участнику клуба возможность выйти из клуба через ClubPage. Поведение различается по типу клуба:

- **Бесплатный (`subscription_price = 0/null`)** — мгновенный выход: статус → `cancelled`, `member_count -= 1`, cascade-разрыв связей в активных событиях и сборах.
- **Платный (`subscription_price > 0`)** — отмена автопродления: статус → `cancelled`, доступ к клубу сохраняется до `subscription_expires_at`. Cascade НЕ применяется. RSVP и существующие складчины сохраняются. В новые складчины cancelled-участника нельзя добавить.

## Scope

### Входит (этот PR)
- `POST /api/clubs/{id}/leave` — единый endpoint, ветвится по типу клуба
- Cascade для free: DELETE из `skladchina_participants` (активные сборы) + `event_responses` (активные события)
- Owner-check: владелец клуба не может выйти (для owner — отдельные действия в будущих PR)
- UI: кнопка «Выйти из клуба» на ClubPage + confirm-диалог с пояснениями для free/paid
- Skladchina create-flow: cancelled-member отображается с disabled-чекбоксом и пометкой «отменил подписку»

### НЕ входит (отложено)
- Owner exit-сценарии — отдельные фичи: transfer ownership (PR-2), delete club (PR-3)
- Возврат средств для платных подписок (out of scope; backlog: refund-flow через support)
- Withdraw pending applications (у active member не должно быть pending; защита избыточна)
- Депрекейт `POST /api/clubs/{id}/cancel` — endpoint остаётся, но UI на нём не строится
- Cascade при автоматическом expire платного membership — backlog (см. Risks)

### Неприкосновенное (исторические данные)
Cascade при выходе из клуба **никогда** не трогает следующее, даже для free-клубов:

- **`user_club_reputation`** — per-club метрики. Сквозная репутация профиля (`Reputation.kt`, `Cross-club aggregate`) считается агрегатом этих записей по всем клубам. Удаление = потеря вклада клуба в сквозной reliability_index профиля.
- **`event_responses` для завершённых событий** (status `completed` / `cancelled`) — история attendance. Используется для счётчика «посещений» и для расчёта спонтанности/железобетонности в profile reputation. Cascade удаляет только записи для **активных/будущих** событий (`upcoming`/`stage_1`/`stage_2`).
- **`skladchina_participants` в закрытых сборах** (status `closed_success` / `closed_failed` / `cancelled`) — история обязательств. Cascade удаляет только участие в **активных** сборах (`status='active'`).
- **`transactions`** — финансовая история (для аудита, бухгалтерии, возможных рефандов).

Принцип: cascade обрывает только **открытые обязательства** пользователя в клубе. Историческая «карма» пользователя в клубе и его вклад в сквозную репутацию остаются нетронутыми.

## API контракт

### `POST /api/clubs/{id}/leave`

```
Path: id = clubId (UUID)
Auth: JWT обязателен
Response 200: MembershipDto (status=cancelled)
Errors: 400 (owner), 404 (нет active/grace_period membership)
```

Caller = владелец membership (`principal.userId`). Параметра userId в пути нет — нельзя выйти за другого.

### Бизнес-правила (free club)

1. Найти membership для (caller, clubId) со статусом `active` или `grace_period` → иначе **404** "Membership not found".
2. Если caller = `club.owner_id` → **400** "Owner cannot leave the club".
3. Cascade в одной транзакции:
   - DELETE `skladchina_participants` где `user_id=caller AND skladchina_id IN (SELECT id FROM skladchinas WHERE club_id=:clubId AND status='active')`.
   - DELETE `event_responses` где `user_id=caller AND event_id IN (SELECT id FROM events WHERE club_id=:clubId AND status IN ('upcoming','stage_1','stage_2'))`.
   - DELETE `applications` где `user_id=caller AND club_id=:clubId AND status IN ('pending','approved')` — устраняет залипшие approved-but-unpaid состояния и **гарантирует, что повторное вступление в приватный клуб снова требует new заявки**.
4. UPDATE `memberships`: `status='cancelled'`, `updated_at=now()`. Поля `joined_at` и `subscription_expires_at` НЕ трогаем (история).
5. UPDATE `clubs.member_count -= 1`.
6. НЕ трогаем: `user_club_reputation`, `transactions`, rejected/auto_rejected applications, прошлые завершённые события/сборы.

### Бизнес-правила (paid club)

1. Найти membership active/grace_period → **404** если нет.
2. Owner-check → **400** если caller=owner.
3. UPDATE memberships: `status='cancelled'`, `updated_at=now()`. `subscription_expires_at` сохраняется.
4. DELETE `applications` где `user_id=caller AND club_id=:clubId AND status IN ('pending','approved')` — убирает зависшую approved-but-unpaid application, иначе при перезагрузке клуб появлялся бы в «Ожидают оплаты» у пользователя и в «approved-but-unpaid applicants» у организатора.
5. `clubs.member_count` НЕ декрементим — пользователь формально остаётся до expire.
6. Cascade на `event_responses`/`skladchina_participants` НЕ применяется — RSVP и членство в существующих сборах сохраняются до конца оплаченного периода.
7. В новые сборы cancelled-пользователя нельзя добавить (см. Skladchina-интеграция).

### Видимость cancelled-в-периоде membership

`MembershipRepository.findByUserId` (источник `GET /api/users/me/clubs`) возвращает membership со статусом `cancelled`, если `subscription_expires_at > now()`. Это значит, что paid-cancelled клуб остаётся в «Мои клубы → Активные» до истечения оплаченного периода. То же распространяется на `isMember` / `isActiveMemberInActiveClub` — cancelled-в-периоде пользователь сохраняет полный доступ (голосование, список участников, RSVP), как и обещает PRD §4.7.3 «Доступ сохраняется до конца оплаченного периода».

После natural expire (`subscription_expires_at <= now`) пользователь автоматически перестаёт быть member для всех проверок без необходимости дополнительного эндпоинта.

### Изменения в `GET /api/clubs/{id}/members`

Endpoint используется UI создания складчины. Расширение:

- Текущий фильтр `status='active'` расширяется до `status IN ('active','cancelled')` — но только если `cancelled` запись имеет `subscription_expires_at > now()` (то есть платный member, который отменил, но ещё в периоде).
- В `MemberListItemDto` добавляется поле `subscriptionCancelled: Boolean`. Для active = `false`, для cancelled-в-периоде = `true`.

Альтернатива (отброшена): держать отдельный endpoint для skladchina-create. Решение: расширить существующий — другие потребители получают `subscriptionCancelled=false` для active members, что обратно совместимо.

## Acceptance Criteria

### AC-1: Free leave успешный + cascade
**GIVEN** caller — active member свободного клуба X (`owner_id != caller`); в X есть 2 active skladchinas с caller=pending; есть 1 upcoming event с caller=going RSVP; есть 1 completed event с caller=attended RSVP
**WHEN** POST /api/clubs/X/leave
**THEN** 200 OK
**AND** response.status = "cancelled"
**AND** memberships.status = cancelled для (caller, X)
**AND** clubs.member_count уменьшилось на 1
**AND** skladchina_participants — 2 строки удалены
**AND** event_responses — удалена только строка upcoming-event (completed остаётся)
**AND** user_club_reputation — НЕ изменено
**AND** transactions — НЕ изменены

### AC-2: Paid leave успешный
**GIVEN** caller — active member платного клуба X (subscription_expires_at = now+15d); 1 upcoming event с RSVP=confirmed; 1 active skladchina с caller=pending
**WHEN** POST /api/clubs/X/leave
**THEN** 200 OK
**AND** memberships.status = cancelled
**AND** subscription_expires_at не изменился
**AND** clubs.member_count НЕ изменено
**AND** skladchina_participants НЕ изменено
**AND** event_responses НЕ изменено

### AC-3: Owner не может выйти
**GIVEN** caller — owner клуба X
**WHEN** POST /api/clubs/X/leave
**THEN** 400 "Owner cannot leave the club"

### AC-4: Не member или уже cancelled
**GIVEN** нет membership active/grace_period для (caller, X) (либо нет записи, либо status уже cancelled/expired)
**WHEN** POST /api/clubs/X/leave
**THEN** 404 "Membership not found"

### AC-5: Free leave → повторное вступление
**GIVEN** caller вышел из free клуба X (status=cancelled), у него сохранилась репутация в `user_club_reputation`
**WHEN** POST /api/clubs/X/join
**THEN** 201 OK, membership реактивирован через `FreeMembershipActivator` (status=active, joined_at=now, subscription_expires_at=null)
**AND** user_club_reputation сохраняет прежние значения

### AC-6: Skladchina create — cancelled-member дизаблед
**GIVEN** club X (платный), есть cancelled-member Y с `subscription_expires_at > now`
**WHEN** organizer запрашивает GET /api/clubs/X/members
**THEN** Y присутствует в ответе с `subscriptionCancelled=true`
**AND** в UI CreateSkladchinaPage чекбокс рядом с Y — `disabled`, справа подпись «Отменил подписку»

### AC-7: Skladchina create — expired cancelled-member не возвращается
**GIVEN** cancelled-member Z с `subscription_expires_at < now` (период подписки истёк)
**WHEN** GET /api/clubs/X/members
**THEN** Z в ответе отсутствует

## Non-functional

### Транзакционность
Вся операция в одном `@Transactional` блоке `MembershipService.leaveClub`. Падение на любом из шагов — rollback всего.

### Производительность
Один user в (N skladchinas + M event_responses) на клуб — типично N+M < 50. Запросы:
1. SELECT-by-club indexed (есть индексы `idx_skladchinas_club_id`, `idx_events_club_id`)
2. DELETE через subquery — атомарный
Пагинации в MVP не требуется.

### Безопасность
- JWT обязателен (Spring Security)
- Owner-check предотвращает self-leave владельца
- Caller не может вызвать leave для другого юзера (нет path-параметра userId)
- Авторизация по принципу least-privilege: проверка идентичности `caller.userId = membership.user_id` неявная (поиск всегда по принципалу)
- Rate-limiting через bucket4j (общий policy для membership-операций)

### Логирование
- INFO `"User left free club: clubId={} userId={} cascadedSkladchinas={} cascadedEventResponses={}"`
- INFO `"User cancelled paid subscription via /leave: clubId={} userId={}"`
- WARN `"Owner attempted to leave own club: clubId={} userId={}"`

## Интеграции

### С payment
- Paid leave не вызывает PaymentService — биллинг автоматически прекратится (новые invoice не создаются)
- Возврат средств не реализуется (out of scope)

### С events
- Cascade удаляет `event_responses` для активных events (`upcoming`/`stage_1`/`stage_2`)
- Прошлые/завершённые/отменённые events — сохраняются (история attendance, репутация)
- В коде `EventResponseRepository` добавляется метод `deleteByUserAndClubAndActiveEvents(userId, clubId)`

### С skladchina
- Cascade удаляет `skladchina_participants` для активных сборов (`status='active'`)
- Завершённые сборы — сохраняются
- Reputation не пересчитывается (выход не считается «отказом» от обязательств в смысле штрафа)
- В `SkladchinaParticipantRepository` добавляется метод `deleteByUserAndClubAndActiveSkladchinas(userId, clubId)`
- Новые сборы: в `MemberService` (или новом методе) фильтр расширяется до active+cancelled-в-периоде

### С reputation
- `user_club_reputation` НЕ удаляется при leave — это вклад клуба в сквозную репутацию профиля. Удаление исказило бы агрегированный `reliability_index`, `promiseFulfillmentPct`, счётчики `totalConfirmations` / `totalAttendances` в профиле пользователя
- Cross-club aggregate (см. `Reputation.kt` `Cross-club aggregate of one user's reputation rows`) продолжает считаться по всем клубам, включая те, из которых пользователь вышел
- При повторном вступлении в free club через `FreeMembershipActivator` (см. `membership.md` § «Free-club reactivate-or-create») пользователь видит свою прежнюю репутацию

## Frontend

### ClubPage (`frontend/src/pages/ClubPage.tsx`)

Логика отображения кнопки:

| Состояние membership пользователя | Кнопка |
|---|---|
| Не member | — (показывается «Вступить» / «Подать заявку») |
| Owner клуба | — (кнопка скрыта) |
| Active member free клуба | «Выйти из клуба» |
| Active member paid клуба | «Выйти из клуба» |
| Cancelled member paid клуба (в периоде) | «Подписка отменена. Доступ до DD MMMM YYYY» (read-only badge) |

Кнопка — в нижней части страницы, low-emphasis (`mode="outline"`, цвет destructive).

Confirm-modal:
- **Free**: «Вы выйдете из клуба «X». Доступ к клубу будет отозван сразу. Вы будете удалены из всех активных событий и сборов.»
- **Paid (active)**: «Подписка будет отменена. Доступ к клубу сохранится до DD MMMM YYYY. После этой даты вы будете удалены из клуба.»

При успехе:
- Toast: «Вы вышли из клуба X»
- Redirect: `/clubs` (главный экран)

### CreateSkladchinaPage (`frontend/src/pages/CreateSkladchinaPage.tsx`)

В списке участников для cancelled-members:
- Чекбокс `disabled`
- Справа от имени — текст «Отменил подписку» (мелкий, secondary color)
- Сортировка: cancelled — в конце списка

## Риски и открытые вопросы

- **Resolved (закрыт в bug-fix-итерации этого PR)**: `MembershipRepository.isMember` / `isActiveMemberInActiveClub` / `findByUserId` теперь возвращают true для `status='cancelled' AND subscription_expires_at > now`. Это даёт paid-cancelled-в-периоде пользователю реальный доступ (голосование, список участников, моё членство), а не только UI-обещание. Существующий backlog-файл `docs/backlog/membership-ismember-cancelled-in-period.md` оставлен как trace; можно архивировать после следующего ревью.
- **Open**: после `cancel` для paid → наступает natural expire (`subscription_expires_at < now`). Сейчас нет scheduled job, который выполнит cascade при expire. В результате могут остаться «висящие» `event_responses` и `skladchina_participants` для уже-не-member. **Backlog**: scheduled job `MembershipExpireProcessor` выполняет такой же cascade при переходе active/cancelled → expired.
- **Decision**: `user_club_reputation` сохраняется при leave. Если пользователь повторно вступит в клуб — его прежние метрики возвращаются. Это разумный дефолт; если бизнес захочет «чистый старт» — переключаемся позже.
- **Decision**: Pending applications при leave не удаляются. У active member pending application не должно быть по бизнес-инвариантам. Защита здесь избыточна.
- **Side-effect (изменение существующего поведения)**: `FreeMembershipActivator.activate` теперь инкрементит `clubs.member_count` на reactivate (раньше не инкрементил — см. [membership.md](membership.md) § «Free-club reactivate-or-create»). Это вынужденная коррекция для согласованности с новым `/leave`-decrement (без неё цикл leave/rejoin уплывал бы в минус). Поведение легаси `POST /api/clubs/{id}/cancel` без `/leave` пути не изменилось; `decrementMemberCountSafely` гарантирует floor=0.
