# S3 — роли + права участников: ОТЛОЖЕНО (с готовым impact-анализом)

> **Статус:** ⏸️ отложено решением PO 2026-06-27. Не начато. Этот документ — точка входа, если вернёмся.
> **Спека:** `docs/modules/member-admin-profile.md` (§4 матрица — черновик, §8 R1). **Ветка фичи:** member-admin жил на `feature/de-stars-decoupling` (S1+S2 смёржены отдельно; S3 пойдёт новой веткой).

## Почему отложили (решение PO)

Полноценные роли с **реальными правами** (R1) — это перестройка контроля доступа по всему приложению (**OWASP A01 — Broken Access Control**, самая частая уязвимость). В отличие от S1/S2 (аддитивные: поле/таблица/UI), S3 переписывает авторизацию в ~20 точках. Ценность условна: роли нужны только клубу, доросшему до делегирования (модераторы/со-организаторы). Для клуба с одним организатором — большинство сейчас — роли не дают ничего. Вывод: **YAGNI** — не строить RBAC до реального спроса на «как добавить модератора».

«Ярлык без прав» (вариант из §4) **отвергнут**: бейдж «Со-организатор», который ничего не даёт, хуже отсутствия — обещает полномочия, которых нет. Значит либо честные права, либо никаких ролей.

**Триггер вернуться:** реальный запрос организатора(ов) на делегирование (модератор одобряет заявки / со-орг ведёт клуб).

## Impact-анализ (готов — 2026-06-27)

Авторизация в проекте **двухслойная**, поэтому это не «аннотация на 10 эндпоинтов»:

### Слой 1 — аннотация `@RequiresOrganizer(clubIdParam)` (owner-only, `AuthorizationAspect`)
clubId есть в пути → можно завести `@RequiresClubRole(min = MODERATOR|CO_ORGANIZER, clubIdParam)`:
- `MemberController` — ~11: freeze/unfreeze/dues-paid/dues-unpaid/access-until/note/awards(grant/revoke)/award-suggestions/member-attention. (управление участником + награды → CO_ORGANIZER)
- `ClubController` — finances (CO_ORG/аналитика). `ChatLinkController` (привязка чата) — OWNER.
- `EventController` — createEvent (MODERATOR).
- `SkladchinaController` — getClubActiveSkladchinas, create (MODERATOR).
- `ClubQualityController` — stats, churned-members (аналитика → CO_ORG).

### Слой 2 — service-level `if (club.ownerId != userId) throw ForbiddenException` (clubId НЕ в пути, аннотацию не повесить)
Менять внутри сервисов через общий хелпер `clubRole(userId, clubId): MembershipRole?` (или `requireClubRole(userId, clubId, min)`):
- `ApplicationService` — approveApplication / rejectApplication / getClubApplications (3 проверки `club.ownerId != organizerId`) → MODERATOR. Путь `/api/applications/{id}` — клуб берётся из заявки.
- `AttendanceService` — markAttendance, resolveDispute (2 проверки) → MODERATOR.
- `SkladchinaPaymentService` — markPaid / «manage» (≥1 проверка `Only the organizer can manage`) → MODERATOR.
- `SkladchinaCreationService` — create (`Only the club organizer can create skladchina`) → MODERATOR. (дублирует аннотацию контроллера — свериться, чтобы не было двойного гейта)
- `SkladchinaLifecycleService` — close: **«Only creator can close»** (проверка по СОЗДАТЕЛЮ сбора, не владельцу клуба) — отдельная семантика, решить: модератор/со-орг закрывает любой сбор или только свой созданный.
- `ClubService` — update/delete/regenerate-invite → OWNER; `ChatLinkService` (чат) → OWNER (владельческое, не трогаем).
- `FinancesService` — view finances → CO_ORG.

**Итого ~20 точек авторизации в двух слоях.** Каждую — покрыть тестами на каждую роль (owner проходит любой гейт; member — нет; moderator/co-org — по матрице).

## Что строить (когда вернёмся)

1. **V43** `ALTER TYPE membership_role ADD VALUE IF NOT EXISTS 'moderator'; ADD VALUE 'co_organizer';` — изолированно, без UPDATE в той же миграции (урок V37). Затем codegen (temp PG :5433, рецепт в `branch-handoff-de-stars-member-admin.md` §6). *(V41 = claim-флоу оплаты, V42 = ослабление UNIQUE заявок.)*
2. **`@RequiresClubRole(min, clubIdParam)`** + ветка в `AuthorizationAspect.checkClubRole` (owner проходит всегда; иначе сверять роль membership ≥ min по иерархии organizer > co_organizer > moderator > member).
3. **`clubRole(userId, clubId)` / `requireClubRole(...)`** хелпер для слоя 2 — заменить разбросанные `ownerId != userId`.
4. **`PUT /api/clubs/{clubId}/members/{userId}/role`** `{role}` — owner меняет любой; co-org только член↔модератор (§4 сноска ¹). Нельзя разжаловать владельца.
5. **Frontend:** сегмент роли в форме ✎ (карточка участника) + бейдж роли в ростере; гейтить видимость управляющих экранов под роль вызывающего.

## Открытые вопросы (ответы PO нужны ПЕРЕД сборкой — заданы 2026-06-27, остались без ответа из-за отсрочки)

1. **Полномочия Модератора:** предложение — заявки (одобр/откл) + события (создать+посещаемость+споры) + складчина (создать+отметить оплату). БЕЗ настроек/финансов/управления участниками/ролей.
2. **Полномочия Со-орга:** предложение — всё кроме владельческого (delete/transfer/billing); роли меняет только член↔модератор; не трогает др. со-оргов и не повышает до со-орга.
3. **Аналитика/финансы (stats/churned/finances):** предложение — со-орг + владелец (модератор — нет).
4. **Складчина «закрыть»:** сейчас по создателю сбора. Решить: модератор/со-орг закрывает любой сбор клуба или только свой созданный.

## Матрица (PROPOSED — моя рекомендация на момент отсрочки)

| Возможность | Участник | Модератор | Со-орг | Владелец |
|---|---|---|---|---|
| Контент клуба (feed/события/складчина) | ✅ | ✅ | ✅ | ✅ |
| Заявки: одобрить/отклонить | — | ✅ | ✅ | ✅ |
| События: создать / посещаемость / споры | — | ✅ | ✅ | ✅ |
| Складчина: создать / отметить оплату | — | ✅ | ✅ | ✅ |
| Участники: взнос/заморозка/награды/заметка | — | — | ✅ | ✅ |
| Менять роли (со-орг — только член↔модератор) | — | — | ✅¹ | ✅ |
| Настройки клуба / финансы / аналитика | — | — | ✅ | ✅ |
| Удалить клуб / сменить владельца / биллинг | — | — | — | ✅ |

¹ Со-орг назначает только Участник↔Модератор; повышать до Со-орга и трогать др. Со-оргов — только Владелец.
