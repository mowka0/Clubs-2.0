# Роли клуба — движок на капабилити-модели (club-roles)

> Ветка: `feature/co-organizers`. Спека написана 2026-07-12.
> **Эволюция:** бинарный гейт «менеджер / не-менеджер» (`ClubManagerGuard` +
> `@RequiresClubManager`) заменяется движком ролей на **капабилити-модели**:
> роль = именованный набор прав (capabilities), карта прав живёт **в коде**.
> `co_organizer` перестаёт быть «единственной делегируемой ролью в предикате» и
> становится **первой записью в карте ролей** — новые роли (модератор и т.п.)
> добавляются одной строкой, без правки гейтов.
>
> **Отношение к `co-organizers.md`:** тот документ остаётся **as-built-записью
> семантики `co_organizer`** (матрица прав, edge-cases, тест-план, миграция V59,
> уточнения У-1…У-10). Этот документ — **целевой дизайн рефактора** (какую
> структуру принимает авторизация). Реестр 45 точек здесь **наследуется** из
> sweep'а `co-organizers.md` (2026-07-12); повторного sweep'а не делалось —
> добавлена только колонка «capability». Пути/строки актуальны на дату sweep'а.
>
> **Регресс-инвариант (PO, залочено 2026-07-12):** эффективный набор прав для
> `organizer` (владелец) и `co_organizer` после рефактора **идентичен текущему**.
> Меняется только внутреннее устройство гейта, не наблюдаемое поведение (кроме
> одного edge-case free→paid, теперь owner-only — решено PO, см. § 11 п. 1).

## Цель и контекст

Текущий гейт выражает ровно одно различие: «менеджер клуба» (владелец ИЛИ
активный со-орг) против всех остальных. Это тупик для будущих ролей: как только
появится **модератор** (только заявки/события, без управления участниками и
финансов), бинарный предикат придётся дробить руками по каждой точке. PO решил
перейти на капабилити-модель заранее, пока делегируемая роль ровно одна и
рефактор дёшев.

**Как** владелец клуба, **я хочу** выдавать участнику **роль** (а не набор голых
галочек-прав), **чтобы** одним выбором дать понятный, названный уровень доступа.

**Как** будущий продукт, **я хочу** описывать роль одной записью «роль → набор
прав» в коде, **чтобы** добавление роли не требовало трогать 45 точек авторизации.

## Решения PO (залочены 2026-07-12, НЕ пересматривать)

1. **Выдаём РОЛИ, не голые права.** Под капотом — капабилити-модель: роль =
   именованный набор capabilities.
2. **У каждой роли — русское описание** для UI-селектора (объясняет, что роль даёт).
3. **Права ролей — В КОДЕ.** Карта `роль → Set<ClubCapability>`. «Добавить/убрать
   право у роли» = поправить карту + деплой. **Без** редактора прав per-club в UI
   (YAGNI, отдельная фича).
4. **Назначение роли — СЕЛЕКТОР** в карточке участника (сегмент/пикер:
   Участник · Со-организатор · …), не одна кнопка. Новая роль = новая строка в
   селекторе.
5. **`co_organizer` сохраняется 1:1:** все права, КРОМЕ владельческих (удалить
   клуб, менять роли, СБП-реквизиты + перевод в платный, привязка чата, биллинг).
   `organizer` (владелец) = все права. `member` = ничего.
6. **Роль действует только при активном членстве** (fail-close: frozen/expired/
   cancelled = прав нет) — как сейчас.
7. **Target-матрица сохраняется:** делегат управляет только участниками
   `role = member`, не владельцем и не другими делегатами.

Иерархия неизменна: **organizer (владелец) > co_organizer > member**. Владелец
проходит любой гейт (owner-bypass = «все capabilities»).

---

## 1. Enum `ClubCapability`

Единица авторизации — **capability** (право на класс операций), а не отдельный
эндпоинт. 14 значений: 9 делегируемых + 5 владельческих. Каждое значение — с
русским пояснением «за что отвечает» (конвенция PO: именованные константы
комментируются).

Расположение: `backend/src/main/kotlin/com/clubs/common/auth/ClubCapability.kt`.

### Делегируемые (входят в набор `co_organizer`)

| Capability | За что отвечает |
|---|---|
| `APPROVE_APPLICATIONS` | Заявки клуба: смотреть инбокс и счётчики, одобрить, отклонить (с причиной), «Расширить клуб и принять всех». |
| `MANAGE_EVENTS` | События: создать, отменить, отметить посещаемость, разрешать споры, видеть dispute-note откликнувшихся. |
| `MANAGE_SKLADCHINA` | Складчины клуба: создать, вести, отметить оплату участнику, resolve-decline, закрыть — по ЛЮБОМУ сбору клуба (не только своему, У-1). |
| `MANAGE_MEMBERS` | Участники (`role = member`): заморозка/разморозка, «взнос получен»/«не получен», reject-dues, своя дата доступа (access-until), заметка, кик; менеджерский вид ростера и карточки участника. |
| `GRANT_AWARDS` | Награды участнику (`role = member`): выдать, снять, видеть подсказки-награды. |
| `EDIT_CLUB_SETTINGS` | Настройки клуба: название, описание, город, правила, лимит участников, аватар. БЕЗ СБП-реквизитов и БЕЗ перехода в платный (это `EDIT_PAYMENT_REQUISITES`). |
| `VIEW_FINANCES` | Просмотр финансов клуба (`GET /finances`). |
| `VIEW_STATS` | Просмотр статистики/аналитики клуба и списка ушедших (`stats`, `churned-members`). |
| `SEND_INVITES` | Личные приглашения (invite-share, prepared message) и перегенерация инвайт-ссылки. |

### Владельческие (только у `organizer`, НЕ делегируются)

| Capability | За что отвечает |
|---|---|
| `MANAGE_ROLES` | Назначать/снимать роли участникам (`PUT .../role`). Единственный владелец этого права — сам владелец клуба. |
| `EDIT_PAYMENT_REQUISITES` | СБП-реквизиты клуба (`paymentLink`, `paymentMethodNote`) и перевод бесплатного клуба в платный. Деньги взносов идут владельцу — подмена реквизитов = увод взносов. |
| `MANAGE_CHAT` | Привязка/отвязка/настройка Telegram-чата клуба (chat-link), включая бот-сторону (`/link`). |
| `MANAGE_BILLING` | Организаторская подписка/биллинг (планы-ёмкости). Payer = владелец. Сейчас гейтится user-scope'ом (`SubscriptionController`), club-scoped точек нет — capability включена для полноты владельческого набора и как дом для будущих club-scoped биллинг-действий. |
| `DELETE_CLUB` | Мягкое удаление клуба (каскад по событиям/складчинам/заявкам). |

> **Почему `GRANT_AWARDS` отдельно от `MANAGE_MEMBERS` (решение аналитика).**
> PO обозначил это как точку решения. Награды — отдельная продуктовая поверхность
> (репутация/геймификация), и это ровно тот случай, ради которого вводится
> капабилити-модель: будущая роль-«модератор» может управлять участниками
> (freeze/кик) без права раздавать репутационные награды, или наоборот. Для
> `co_organizer` разницы нет (у него оба права), но модель остаётся выразительной.
> Если PO хочет склеить — это одна правка карты, не 45 точек.

> **Почему `VIEW_FINANCES` и `VIEW_STATS` — раздельные.** Финансы (взносы, деньги)
> и статистика (активность, отток) — разные чувствительности данных; будущая роль
> может видеть аналитику без доступа к финансам. Для `co_organizer` оба входят.

---

## 2. Карта `роль → Set<ClubCapability>`

Единственный источник истины о правах. Расположение:
`backend/src/main/kotlin/com/clubs/common/auth/RoleCapabilities.kt` (карта +
русские описания ролей рядом). Владелец в карте **не** нуждается в перечислении —
owner-bypass (`club.ownerId == caller`) = «все capabilities»; для чистоты в карте
`organizer` = полный набор (используется как справочник и для UI-метаданных).

```
organizer     → ВСЕ 14 capabilities (owner-bypass; в карте — полный набор)
co_organizer  → { APPROVE_APPLICATIONS, MANAGE_EVENTS, MANAGE_SKLADCHINA,
                  MANAGE_MEMBERS, GRANT_AWARDS, EDIT_CLUB_SETTINGS,
                  VIEW_FINANCES, VIEW_STATS, SEND_INVITES }         // 9 делегируемых
member        → {} (пусто)
```

**Задел на будущее — `moderator` (ПРИМЕР, НЕ реализуем сейчас).** Показывает, как
роль встаёт одной записью, не трогая гейты:

```
moderator     → { APPROVE_APPLICATIONS, MANAGE_EVENTS, MANAGE_SKLADCHINA }
```

Добавление такой роли = (а) `ALTER TYPE membership_role ADD VALUE 'moderator'`
(будущая миграция), (б) одна строка в карте, (в) label+описание, (г) добавление
значения в TS-union `MembershipRole` и badge-label на фронте. Ни одной правки в 45
точках авторизации — они гейтятся по capability, а не по имени роли.

**As-built — направление вычисления инвертировано на fail-closed (реализация).**
Источник истины в коде — **делегируемый** бакет: `RoleCapabilities.DELEGATED_CAPABILITIES`
— ЯВНЫЙ список 9 делегируемых прав. Владельческий бакет **выводится как разница**:
`OWNER_ONLY_CAPABILITIES = ClubCapability.entries − DELEGATED_CAPABILITIES`. В первой
редакции спеки формулировка была обратной («делегируемое = всё МИНУС владельческий
бакет»); при реализации инвертировано осознанно.

**Почему инверсия (защита от fail-open при расширении enum):** дефолт для НОВОГО,
ещё-не-классифицированного `ClubCapability` — «владельческое» (fail-closed). Забытая
при добавлении значения enum capability НЕ утечёт со-оргу автоматически — она попадёт
во владельческий бакет и достанется только владельцу через owner-bypass. Обратное
направление (делегируемое = разница) было бы fail-open: новое право по умолчанию
раздавалось бы со-оргу. Добавляя право, которое должно быть делегируемым, — впиши его
в `DELEGATED_CAPABILITIES` осознанно.

Владельческий бакет (5, выводится): `MANAGE_ROLES`, `EDIT_PAYMENT_REQUISITES`,
`MANAGE_CHAT`, `MANAGE_BILLING`, `DELETE_CLUB`. Инвариант решения PO №5 машинно-проверяем:
`organizer.capabilities − co_organizer.capabilities == OWNER_ONLY_CAPABILITIES`. Тест
классификации (`ClubRoleGuardTest`, `role capability classification is exhaustive`) пинует
ОБА явных набора и требует полного непересекающегося разбиения `ClubCapability.entries`:
новое право ломает тест, пока разработчик осознанно не отнесёт его к делегируемым ИЛИ
владельческим (§ 10 AC-1).

---

## 3. Описания ролей (русские, для селектора)

Живут рядом с картой (Kotlin) и/или на фронте (см. § 6 «Метаданные ролей»).

| Роль | Label | Описание для селектора |
|---|---|---|
| `member` | Участник | «Обычный участник клуба: посещает встречи, участвует в складчинах. Без доступа к управлению.» |
| `co_organizer` | Со-организатор | «Ведёт клуб вместе с вами: разбирает заявки, создаёт и проводит события и складчины, управляет участниками, видит финансы и статистику. Не может: менять роли, СБП-реквизиты, чат клуба, удалять клуб.» |
| `organizer` | Организатор | (владелец; в селектор не входит — назначить/снять владельца нельзя) |
| `moderator` | Модератор | *(пример будущей роли — не показывается, пока не реализована)* «Помогает с потоком: заявки, события, складчины. Без управления участниками, финансами и настройками.» |

Селектор показывает только **назначаемые** роли (`member`, `co_organizer`);
`organizer` — read-only статус владельца (передача владения вне скоупа,
club-leave PR-2).

---

## 4. Как гейтить

### Аннотация `@RequiresCapability(cap)` вместо `@RequiresClubManager`

```kotlin
@RequiresCapability(ClubCapability.MANAGE_MEMBERS, clubIdParam = "clubId")
```

`AuthorizationAspect` получает ветку `checkCapability`: извлекает `clubId`,
вызывает `clubRoleGuard.requireCapability(clubId, userId, cap)` (404 если клуба
нет/неактивен, 403 если нет права).

**As-built:** `@RequiresClubManager` удалён полностью; рекомендация «владельческие
точки — на `@RequiresCapability` соответствующей owner-only capability» принята —
единственная аннотированная владельческая точка (PUT `.../role`) гейтится
`@RequiresCapability(MANAGE_ROLES)`, а не `@RequiresOrganizer`. `@RequiresMembership`
**остаётся** в использовании. `@RequiresOrganizer` (аннотация + ветка `checkOrganizer`
аспекта) **больше не висит ни на одном эндпоинте** — весь клуб-скоуп гейтится единым
механизмом `@RequiresCapability`. Аннотация оставлена как определение (мёртвый код,
кандидат на удаление отдельным cleanup'ом). Прямой owner-чек живёт только внутри
сервисов, где логика уже завязана на `club.ownerId` (`deleteClub`, chat-link, СБП-поле
и free→paid в `updateClub`).

### Предикат `hasCapability(club, userId, cap)`

Единственная реализация (as-built: прежний `ClubManagerGuard` переименован в
**`ClubRoleGuard`** — `common/auth/ClubRoleGuard.kt`; **копипасты быть не должно**):

```
hasCapability(club, userId, cap):
  if club.ownerId == userId: return true            // owner-bypass = все права
  membership = repo.findByUserAndClub(userId, club.id)
  if membership == null || membership.status != active: return false   // fail-close (PO №6)
  return RoleCapabilities[membership.role].contains(cap)
```

Формы (сохранить сигнатурный ряд текущего `ClubManagerGuard`):
- `hasCapability(club, userId, cap): Boolean` — предикат по загруженному клубу.
- `hasCapability(clubId, userId, cap): Boolean` — по clubId; нет клуба → false.
- `requireCapability(club, userId, cap)` — 403 если нет.
- `requireCapability(clubId, userId, cap): Club` — 404 (нет клуба) + 403 (нет права).
- `hasCapabilityInMembership(membership, cap): Boolean` — для мест, где
  caller-membership уже прочитан (ростер/карточка, без второго запроса).
- `requireManageableTarget(club, target, callerId)` — **не меняется**: target-матрица
  (PO №7) ортогональна capability; проверяется дополнительно к `MANAGE_MEMBERS`/
  `GRANT_AWARDS` (owner → любой target кроме `organizer`; делегат → только `member`).

Текущий `isActiveManagerMembership` эквивалентен `hasCapability != пусто` —
сохраняется как «есть ли хоть одно делегируемое право = менеджер» для **view-level**
гейтинга (бакеты ростера, general-manager-view). Строгий fail-close (только
`status = active`) остаётся каноном.

### Полный реестр 45 точек → capability

Целевая capability каждой точки. Наследовано из sweep'а `co-organizers.md`
(2026-07-12); «manager» там → конкретная capability здесь; «owner» → owner-only
capability. Все пути от `backend/src/main/kotlin/com/clubs/`.

#### Слой 1 — аннотация (сейчас `@RequiresClubManager`) → `@RequiresCapability`

| # | Файл | Операция | Capability |
|---|---|---|---|
| 1 | `membership/MemberController.kt:48` | POST `.../freeze` | `MANAGE_MEMBERS` |
| 2 | `membership/MemberController.kt:57` | POST `.../unfreeze` | `MANAGE_MEMBERS` |
| 3 | `membership/MemberController.kt:66` | POST `.../dues-paid` | `MANAGE_MEMBERS` |
| 4 | `membership/MemberController.kt:77` | POST `.../reject-dues` | `MANAGE_MEMBERS` |
| 5 | `membership/MemberController.kt:89` | POST `.../remove` (кик) | `MANAGE_MEMBERS` |
| 6 | `membership/MemberController.kt:99` | POST `.../dues-unpaid` | `MANAGE_MEMBERS` |
| 7 | `membership/MemberController.kt:109` | POST `.../access-until` | `MANAGE_MEMBERS` |
| 8 | `membership/MemberController.kt:119` | PATCH `.../note` | `MANAGE_MEMBERS` |
| 9 | `membership/MemberController.kt:143` | POST `.../awards` | `GRANT_AWARDS` |
| 10 | `membership/MemberController.kt:153` | DELETE `.../awards/{awardId}` | `GRANT_AWARDS` |
| 11 | `membership/MemberController.kt:168` | GET `/{clubId}/award-suggestions` | `GRANT_AWARDS` |
| 12 | `club/ClubController.kt:92` | GET `/{id}/finances` | `VIEW_FINANCES` |
| 13 | `event/EventController.kt:31` | POST `/api/clubs/{id}/events` | `MANAGE_EVENTS` |
| 14 | `skladchina/SkladchinaController.kt:26` | GET `.../skladchinas/active` | `MANAGE_SKLADCHINA` |
| 15 | `skladchina/SkladchinaController.kt:36` | POST `.../skladchinas` | `MANAGE_SKLADCHINA` |
| 16 | `clubquality/ClubQualityController.kt:43` | GET `/{clubId}/stats` | `VIEW_STATS` |
| 17 | `clubquality/ClubQualityController.kt:53` | GET `/{clubId}/churned-members` | `VIEW_STATS` |

Точки 1–8 (per-target) дополнительно проходят `requireManageableTarget` в сервисе
(делегат → только `role = member`).

#### Слой 2 — service-level проверки

| # | Файл:строка | Операция | Capability |
|---|---|---|---|
| 18 | `application/ApplicationService.kt:150` | approveApplication | `APPROVE_APPLICATIONS` |
| 19 | `application/ApplicationService.kt:197` | expandAndApproveAll | `APPROVE_APPLICATIONS` |
| 20 | `application/ApplicationService.kt:256` | rejectApplication | `APPROVE_APPLICATIONS` |
| 21 | `application/ApplicationService.kt:304` | getClubApplications (инбокс) | `APPROVE_APPLICATIONS` |
| 22 | `application/ApplicationService.kt` | getMyClubsActionCounts + getMyPendingApplications (кросс-клубовый инбокс) | `APPROVE_APPLICATIONS` — скоуп «managed» (`ClubRepository.findManagedIds`) |
| 23 | `event/AttendanceService.kt:38` | markAttendance | `MANAGE_EVENTS` |
| 24 | `event/AttendanceService.kt:146` | resolveDispute | `MANAGE_EVENTS` |
| 25 | `event/EventService.kt:30` | createEvent (дублирует #13) | `MANAGE_EVENTS` |
| 26 | `event/EventService.kt:77` | cancelEvent | `MANAGE_EVENTS` |
| 27 | `event/VoteService.kt:92` | видимость `dispute_note` в getEventResponders | `MANAGE_EVENTS` |
| 28 | `skladchina/SkladchinaCreationService.kt:38` | create (дублирует #15) | `MANAGE_SKLADCHINA` |
| 29 | `skladchina/SkladchinaLifecycleService.kt:81` | closeManually | `MANAGE_SKLADCHINA` (creator ИЛИ право, У-1) |
| 30 | `skladchina/SkladchinaPaymentService.kt:293` | requireActiveAsCreator (resolve-decline / mark-paid / unmark) | `MANAGE_SKLADCHINA` (creator ИЛИ право, У-1) |
| 31 | `skladchina/SkladchinaMapper.kt:58` | `isOrganizerView = creatorId == caller` | `MANAGE_SKLADCHINA` (creator ИЛИ право; имя поля не менять, У-7) |
| 32 | `club/ClubService.kt:159` | updateClub (настройки) | `EDIT_CLUB_SETTINGS` (+ полевой owner-гейт: СБП/free→paid = `EDIT_PAYMENT_REQUISITES`) |
| 33 | `club/ClubService.kt:186` | deleteClub | `DELETE_CLUB` (owner-only) |
| 34 | `club/ClubService.kt:119` | regenerateInviteLink | `SEND_INVITES` (У-4) |
| 35 | `club/InviteShareService.kt:70` | личные приглашения (PR #107) | `SEND_INVITES` (только `status = active`, У-2) |
| 36 | `chatlink/ChatLinkService.kt:51,58,97,134,168,200,234,305` | get/refresh/update/unlink чат-линка | `MANAGE_CHAT` (owner-only) |
| 37 | `chatlink/ChatLinkBotService.kt:44,185` | `/link` привязка/отвязка из чата | `MANAGE_CHAT` (owner-only) |
| 38 | `membership/AccessGateService.kt:327-334` | loadManageableMember | `MANAGE_MEMBERS` + target-матрица |
| 39 | `award/AwardService.kt` grant/revoke | награды | `GRANT_AWARDS` + target-матрица (делегат → только `member`) |
| 40 | `membership/MemberService.kt:39` | getClubMembers (`forOrganizer` бакеты, canSeeScores, subscriptionExpiresAt) | `MANAGE_MEMBERS` (менеджерский вид ростера)¹ |
| 41 | `membership/MemberService.kt:84` | getMemberProfile (`callerIsOrganizer`: note/claim/proof) | `MANAGE_MEMBERS` |
| 42 | `membership/MemberService.kt` + `JooqMembershipRepository.kt` | getOrganizerAwaitingDues / awaiting-dues счётчик | `MANAGE_MEMBERS` — скоуп «managed» (У-5, `...ByManager`) |
| 43 | `membership/MembershipService.kt:98,264` | «Owner cannot leave the club» | не capability — owner-инвариант (не меняется); co-org МОЖЕТ выйти |
| 44 | `common/auth/AuthorizationAspect.kt:34-51` | механизм гейта | ветка `checkCapability` (см. § 4) |
| 45 | `membership/MemberController.kt` (role-эндпоинт) | PUT `.../role` | `MANAGE_ROLES` (owner-only) |

¹ #40/#41 — это **менеджерский вид** (видимость управляющих полей ростера/карточки),
не действие. Гейтятся `MANAGE_MEMBERS` как ближайшей capability. Замечание на будущее:
если появится роль с `MANAGE_EVENTS`/`APPROVE_APPLICATIONS`, но БЕЗ `MANAGE_MEMBERS`
(напр. `moderator`), она НЕ увидит менеджерский ростер — тогда потребуется завести
view-level capability `VIEW_ROSTER` либо гейтить эти вьюхи по «есть хоть одно
делегируемое право». Для `co_organizer` (есть `MANAGE_MEMBERS`) — 1:1, вопрос отложен
(см. § «Открытые вопросы для PO», п. 3).

**Не трогаем (проверено sweep'ом `co-organizers.md`):** `activity/ActivityController`
(`@RequiresMembership`), `storage/StorageController` (любой авторизованный),
`subscription/SubscriptionController` (user-scoped биллинг = `MANAGE_BILLING`
концептуально, но гейт по user-ownership), `membership/OrganizerMembersController`
(скоуп внутри #42), `club/ClubService.kt:143` (`hasChatAccess` — active-участник),
member-side эндпоинты, бот-механики chatlink (теги/строгий режим — от статусов/наград,
роль не участвует).

---

## 5. API контракт

### PUT /api/clubs/{clubId}/members/{userId}/role — без изменений (owner-only)

```
Request:  { "role": "member" | "co_organizer" }
Response 200: MembershipDto (поле role отражает новую роль)
```

Гейт — `MANAGE_ROLES` (owner-only). Ошибки, идемпотентность, лимит со-оргов (5,
advisory-lock), DM best-effort — **как в `co-organizers.md` § API контракт**
(не дублируем; поведение сохраняется). При добавлении будущих назначаемых ролей
enum запроса расширяется значением роли; `organizer` в body остаётся запрещён
(передача владения вне скоупа).

Валидация body — по карте **назначаемых** ролей: значение вне
`{ member, co_organizer, …назначаемые }` → 400. (Сейчас — `AssignableMemberRole`.)

### DTO участника отдаёт `role`

`MembershipDto.role`, `MemberListItemDto.role`, `MemberProfileDto.role`,
`ClubMemberInfo.role` — уже отдают строку роли; после рефактора значение то же,
структура не меняется. `GET /api/users/me/clubs` → `MembershipDto.role` = «моя
роль в клубе» для UI-гейтинга (без изменений).

---

## 6. Метаданные ролей: хардкод на фронте (рекомендация) vs эндпоинт

**Рекомендация: хардкод на фронте** (label + описание + порядок в селекторе) в
`utils/membershipRole.ts` рядом с существующим `ROLE_LABELS`:
`ROLE_DESCRIPTIONS: Record<MembershipRole, string>` + `ASSIGNABLE_ROLES: MembershipRole[]`
(упорядоченный список для селектора).

**Обоснование (почему не эндпоинт):**
1. **Эндпоинт не даёт «backend-only»-добавления роли.** Любая новая роль всё равно
   требует правок на фронте: расширить TS-union `MembershipRole` в `types/api.ts` и
   добавить **badge-label** (бейджи ростера рендерятся синхронно в списках, не могут
   ждать async-каталог). Раз фронт правится в любом случае — эндпоинт не устраняет
   фронт-деплой, только добавляет сетевой вызов и кэш.
2. **Описания — статичная UI-копирайт-строка**, не бизнес-данные. Меняются реже
   релиза, живут рядом с `ROLE_LABELS`.
3. **Безопасность не зависит от метаданных фронта:** сервер на `PUT .../role`
   всегда пере-валидирует (owner + `MANAGE_ROLES` + карта назначаемых ролей).
   Метаданные — только подсказка UI.
4. **KISS/YAGNI:** 2 назначаемые роли, короткие строки — вводить эндпоинт+кэш+
   loading-состояние в селекторе избыточно.

**Единый источник ИСТИНЫ о правах** остаётся в backend-коде (карта
`RoleCapabilities`). Фронтовые описания — это UI-пересказ, не авторитет.

**Когда пересмотреть в пользу `GET /api/club-roles`:** если ролей станет много
(5+), если описания/наборы прав нужно показывать пользователю динамически
(«что именно может эта роль» с раскладкой по capabilities), или если появится
per-club кастомизация. Тогда каталог `{ role, label, description, capabilities[] }`
отдаётся эндпоинтом (статический список из enum/карты, без БД, cache-friendly).
До этого — хардкод.

---

## 7. Frontend

### Селектор роли (замена кнопки) в карточке участника

`components/club/MemberProfileModal.tsx` — секция `RoleGate` эволюционирует из
одиночной кнопки «Сделать/Снять со-организатора» в **селектор роли** (сегмент-контрол
или список-пикер) по `ASSIGNABLE_ROLES`:

- Пункты: **Участник** · **Со-организатор** (далее — будущие роли строкой).
- У каждого пункта — **описание** (`ROLE_DESCRIPTIONS`), видимое в селекторе
  (текущая роль подсвечена).
- Смена роли = выбор пункта → инлайн-подтверждение → `PUT .../role`.
- **Состояние «выбрано, ждёт подтверждения»** (PO 2026-07-13): выбранный тапом пункт
  подсвечивается кольцом + бейджем «✓ Выбрано» и забирает отметку radiogroup себе, а
  текущая роль на это время гаснет до нейтральной — акцент носит ровно один пункт, иначе
  тап не давал обратной связи и выбор читался неоднозначно. Тап по текущей роли отменяет
  выбор (дублирует кнопку «Отмена»).
- Дизейбл промоута для `accessStatus` frozen/expired сохраняется (У-9, текст-пояснение).
- 409 закрывает карточку (кэш инвалидирован); лимит со-оргов (400) — текстом.

Видна только владельцу (`isOwner && !isSelf && target.role !== 'organizer'`), как
сейчас (`showRoleGate`).

### Бейджи ролей

`components/club/ClubMembersTab.tsx` и футер карточки — `membershipRoleLabel(role)`
для любой роли из union (уже реализовано для 3 значений; новая роль = +1 label).
Гейтинг управляющего UI — `isActiveManagerMembership` (fail-close), косметика —
`isManagerRole`/label. Без изменений семантики.

### UX-баги со staging-теста PO (критерии приёмки, обязательны)

**Баг A — секция роли не видна без растягивания Mini App. ✅ РЕШЁН 2026-07-13.**
Симптом читался как проблема вьюпорта Telegram, и три фикса в этом направлении
(`bindCssVars`, `expandViewport()` при открытии модалки, полноэкранный `.rd-sheet--full`)
результата не дали и **откачены**.

**Фактический root cause — флексбокс, не вьюпорт** (доказан замерами на живом CSS):
`.rd-sheet-body` — колоночный флекс-контейнер, его дети по умолчанию `flex-shrink: 1`.
По спеке флексбокса автоматический минимальный размер равен **нулю** у элемента с
`overflow` ≠ `visible`. Секция роли — `.rd-mgmt` с `overflow: hidden` (скруглённые углы),
и она оказалась единственным прямым ребёнком, кого можно было сжать в ноль → при
переполнении она одна поглощала весь избыток: **2px вместо 185px** в окне 640px. Секция
всё это время лежала на месте, сплющенная в полоску, а не уезжала под фолд — поэтому
баг воспроизводился и в Telegram Desktop, где medium-режима нет.

**Фикс:** `.rd-sheet-body > * { flex-shrink: 0; }` — переполнение уходит в скролл тела.
Чинит все шторки на `.rd-sheet-body`, не только карточку участника.

- Критерий (без изменений): на нерастянутом окне владелец открывает карточку участника →
  секция «Роль в клубе» видна целиком и достижима без ручного растягивания.

**Баг B — после нажатия назначения «текстовое окно и больше ничего».**
Инлайн-подтверждение (`rd-reject-confirm` с вопросом + кнопки Отмена/Назначить,
`MemberProfileModal.tsx:668-688`) появляется ПОД селектором и в нерастянутом окне
уводит кнопки Отмена/Назначить ниже видимой границы — виден только текст вопроса.
Требование:
- Селектор роли + блок подтверждения (вопрос **и** кнопки Отмена/Назначить) видимы
  **целиком** без растягивания окна: при переходе в состояние `confirming` контент
  автоскроллится к кнопкам, либо подтверждение рендерится в пределах видимой области
  (напр. якорится/поднимается), чтобы кнопки не оказались за фолдом.
- Критерий: на нерастянутом окне владелец выбирает роль → видит вопрос-подтверждение
  И обе кнопки (Отмена, Назначить) одновременно, без скролла-догадки и без растягивания.

`api/membership.ts` (`updateMemberRole`), `queries/members.ts`
(`useUpdateMemberRoleMutation`), `types/api.ts` (`MembershipRole` union) — без
структурных изменений (эндпоинт тот же); расширяются при добавлении будущих ролей.

---

## 8. Миграция БД

Capabilities и карта ролей в БД **НЕ хранятся** — только в коде (PO №3). БД знает
лишь про enum `membership_role`. `co_organizer` уже добавлен `V59` (в проде-цикле).
Новые роли (`moderator` и т.п.) — **будущие** миграции `ALTER TYPE ... ADD VALUE`
(изолированно, без UPDATE в той же миграции — урок V37/V59), только при реализации
роли. Этот рефактор миграцию **не требует** (структура enum не меняется).

Хранить метаданные ролей в БД — **не надо** (обоснование: карта прав и описания
меняются кодом+деплоем, per-club кастомизации нет — YAGNI; см. § 6). Если PO
когда-нибудь захочет per-club права — это отдельная фича с таблицей, вне текущего
скоупа.

---

## 9. Совместимость / регресс (инвариант PO)

Эффективный набор прав после рефактора обязан совпасть с текущим:

- **owner** — owner-bypass = все 14 capabilities → проходит все точки, как сейчас.
- **co_organizer (active)** — 9 делегируемых → тот же набор точек 1–42 (кроме
  owner-only 33/36/37/45), что и текущий manager-гейт. Владельческие точки → 403,
  как сейчас.
- **co_organizer (frozen/expired/cancelled)** — fail-close → 403 везде, как сейчас.
- **member / не-член** — пусто → 403/404, как сейчас.
- Target-матрица (`requireManageableTarget`) — не тронута.
- Лимит со-оргов, DM, идемпотентность, advisory-lock, «ровно один organizer» — из
  `co-organizers.md`, не меняются.

Единственное сознательное отклонение от строгого 1:1 — edge-case free→paid
(перевод бесплатного клуба в платный ужесточён до owner-only, дыра закрыта; решено
PO, as-built — см. § 11 п. 1).

Машинная проверка регресса: тест «набор точек, проходимых co_organizer, до и после
рефактора идентичен» + `organizer.caps − co_organizer.caps == OWNER_ONLY_BUCKET`.

---

## 10. Критерии приёмки

Модель и гейтинг:

- **AC-1.** Карта: `co_organizer.capabilities` = ровно 9 делегируемых; `member` = ∅;
  `organizer` = все 14. Тест `organizer − co_organizer == { MANAGE_ROLES,
  EDIT_PAYMENT_REQUISITES, MANAGE_CHAT, MANAGE_BILLING, DELETE_CLUB }`. As-built:
  тест `role capability classification is exhaustive` дополнительно фиксирует ОБА
  явных набора (делегируемый источник истины + выведенный владельческий) и требует
  полного непересекающегося разбиения `ClubCapability.entries` — новое право ломает
  тест, пока не классифицировано, а рантайм-дефолт fail-closed (§ 2).
- **AC-2.** `hasCapability`: owner → true для любой cap; active co-org → true для
  делегируемой, false для владельческой; frozen/expired/cancelled co-org → false
  для любой (fail-close); member/не-член → false.
- **AC-3.** Гейт-матрица по реестру 1–45: для owner / active co-org / frozen co-org /
  member / не-член — ожидаемые 200/403/404 совпадают с текущим поведением
  (регресс-набор из `co-organizers.md` § Критерии приёмки 1–18 проходит целиком).
- **AC-4.** `@RequiresCapability(MANAGE_MEMBERS)` на freeze/kick/note и т.д.:
  делегат без `MANAGE_MEMBERS` (сейчас таких ролей нет — проверяется на будущем
  `moderator` в unit-тесте карты) → 403; co-org (есть) → 200.
- **AC-5.** Target-матрица цела: owner→member ✅, owner→co-org ✅, owner→organizer ❌;
  co-org→member ✅, co-org→co-org ❌, co-org→organizer ❌.
- **AC-6.** PUT role: happy path промоут/демоут; 400 (self, owner-target,
  role=organizer, frozen-промоут, лимит 5); 403 (co-org, member); 404 (нет
  клуба/membership, cancelled-строка); идемпотентность; 409-гонка — как в
  `co-organizers.md` (без регресса).
- **AC-7.** `moderator` как запись карты (unit-only, роль НЕ включается в
  `ASSIGNABLE_ROLES`, миграции нет): карта возвращает
  `{ APPROVE_APPLICATIONS, MANAGE_EVENTS, MANAGE_SKLADCHINA }`; демонстрирует, что
  добавление роли = одна запись, гейты не тронуты.

Frontend / UX:

- **AC-8.** Селектор роли: владелец в карточке участника видит пикер
  «Участник · Со-организатор» с описанием каждой роли; смена → инлайн-подтверждение →
  `PUT .../role`. Не-владелец / self / target-organizer — селектор скрыт.
- **AC-9 (UX-баг A).** На нерастянутом окне владелец открывает карточку участника →
  секция «Роль в клубе» достижима (видна/доскролливаема) без ручного растягивания;
  Mini App развёрнут (`viewport.expand()` применён) к моменту показа карточки.
- **AC-10 (UX-баг B).** На нерастянутом окне владелец выбирает роль → вопрос-
  подтверждение И обе кнопки (Отмена, Назначить) видны одновременно, без растягивания
  и без «текст без кнопок».
- **AC-11.** Бейджи ролей в ростере/карточке рендерятся для всех значений union;
  гейтинг управляющего UI — `isActiveManagerMembership` (frozen co-org видит бейдж,
  но не управляющие экраны).
- **AC-12.** Регресс: обычный участник и владелец — поведение без изменений;
  биллинг-карточка подписки — только владельцу.
- **AC-13.** Обратная связь селектора: тап по роли → пункт помечен «✓ Выбрано»
  (`aria-checked=true` у него, `false` у текущей роли), текущая роль погашена; тап по
  текущей роли → выбор снят, подтверждение исчезло, отметка вернулась к текущей роли.

---

## 11. Открытые вопросы для PO

1. **free→paid перевод бесплатного клуба в платный — ✅ РЕШЕНО (PO подтвердил: owner-only,
   дыра закрыта).** Была узкая дыра: если у клуба уже сохранён `paymentLink` (легаси
   paid→free со stale-ссылкой), активный co-org мог выставить цену >0 без правки
   реквизитов → флип в платный, не будучи владельцем. **Как реализовано (as-built,
   `ClubService.updateClub`):** транзиция `subscriptionPrice 0→>0` при `club.ownerId != userId`
   → `403 «Перевести клуб в платный может только владелец»` (концептуально —
   владельческое `EDIT_PAYMENT_REQUISITES`). Это осознанное **микро-отклонение от строгого
   1:1** (co-org в редком edge-case теряет возможность флипнуть платность), закрывающее
   дыру и соответствующее решению PO №5. СБП-поля (`paymentLink`/`paymentMethodNote`)
   остаются owner-only отдельным полевым гейтом (as-built #2). Ёмкость платных клубов при
   флипе считается по `club.ownerId` (payer = владелец); 402 при исчерпании получает
   вызывающий со-орг (as-built #3).

2. **`GRANT_AWARDS` — отдельная capability или часть `MANAGE_MEMBERS`?**
   Спроектировано **отдельной** (обоснование в § 1). Для `co_organizer` разницы нет.
   Подтвердить или склеить (одна правка карты).

3. **View-level capability `VIEW_ROSTER` — нужна сейчас?** Менеджерский вид ростера/
   карточки (#40/#41) сейчас гейтится `MANAGE_MEMBERS`. Для `co_organizer` — 1:1.
   Проблема всплывёт только при будущей роли с событиями/заявками, но без управления
   участниками (напр. `moderator`): она не увидит ростер. Предложение: отложить до
   реализации первой такой роли (YAGNI). Подтвердить, что откладываем.

4. **PRD ролей — ✅ ДОБАВЛЕНО (post-flight alignment 2026-07-12, требует подтверждения PO).**
   Раздел «Роли клуба — движок на правах» внесён в `PRD-Clubs.md` §4.5.2 (на ветке
   `feature/co-organizers`): роль = именованный набор прав + описание, со-организатор —
   первая роль, будущая (модератор) — одной записью. Перечень прав/владельческого совпадает
   с as-built. **Правка PRD ждёт подтверждения PO на staging-гейте** (правка PRD — по
   решению PO).

---

## Связь с документами

- `docs/modules/co-organizers.md` — as-built семантика `co_organizer`, edge-cases,
  тест-план, миграция V59, уточнения У-1…У-10 (актуально, не дублируется здесь).
- `docs/backlog/member-admin-roles-S3-deferred.md` — исходный impact-анализ ролей.
- `PRD-Clubs.md` §4.5.2 (дашборд организатора) — содержит раздел «Роли клуба —
  движок на правах» (добавлен на этой ветке; см. § 11 п. 4).
