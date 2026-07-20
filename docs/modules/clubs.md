# Module: Clubs

> **Members tab (organizer-view, 2026-05-30):** `ClubMembersTab` теперь
> показывает дополнительную секцию «Ожидают оплаты · N» **перед** списком
> участников, если caller — owner клуба и есть applicants с approved-заявкой
> без active membership (Stars-инвойс не оплачен). Источник данных —
> `GET /api/clubs/{clubId}/awaiting-payment-applicants` (organizer-only,
> backend возвращает 403 для не-owner). Полная спека таба и API контракт
> живут в `docs/modules/club-page-unified.md` и `docs/modules/applications-inbox.md`
> соответственно.

## Архитектура

```
ClubController, InviteController
    │
    ▼
ClubService ───────────────▶ ClubRepository (interface) ──▶ JooqClubRepository ──▶ jOOQ DSL
    │   ▲                                  │
    │   │ ClubMapper                       │ ClubMapper
    │   │ (record → domain / domain → DTO) │
    │   └───────────────────────────────────┘
    │
    └──▶ MembershipRepository (организатор-membership при createClub)

FinancesService ──▶ ClubRepository (ownership), MembershipRepository (active count),
                    TransactionRepository.sumCompletedSubscriptionRevenueSince
                                            │
                                            └──▶ возвращает Int, никакого DSL в Service
```

Слоистость по `.claude/rules/backend.md`: Service не имеет `DSLContext`, `ClubsRecord` не покидает `JooqClubRepository`/`ClubMapper`. Все мутации клуба проходят через интерфейс `ClubRepository`.

### Файлы (целевая структура)
| Файл | Роль |
|---|---|
| `club/ClubController.kt` | REST endpoints `/api/clubs/*` |
| `club/InviteController.kt` | REST endpoints `/api/invite/*` + `/api/clubs/{id}/regenerate-invite` |
| `club/ClubService.kt` | Бизнес-логика клуба (CRUD, регенерация инвайт-кода; поля `chat*` детали клуба собирает из `chatlink/`) |
| `club/FinancesService.kt` | Расчёт `FinancesDto` для организатора (active members, monthly revenue, доля 80/20) |
| `club/Club.kt` | Domain data class (внутренний тип Service-слоя) |
| `club/ClubMapper.kt` | `ClubsRecord → Club` + `Club → ClubDetailDto` |
| `club/ClubRepository.kt` | **Интерфейс** репозитория |
| `club/JooqClubRepository.kt` | Реализация на jOOQ |
| `club/ClubDto.kt` | Request/Response/Filter DTOs |
| `club/FinancesDto.kt` | Финансовый response |

`ClubsRecord` импортируется **только** в `JooqClubRepository.kt` и `ClubMapper.kt`. Любое другое место — нарушение слоистости.

---

## TASK-008 — CRUD клубов

### Endpoints
```
POST   /api/clubs          → 201 ClubDetailDto
GET    /api/clubs/{id}     → 200 ClubDetailDto
PUT    /api/clubs/{id}     → 200 ClubDetailDto     (Settings tab save)
DELETE /api/clubs/{id}     → 204 No Content        (soft delete)
```

### Бизнес-правила
- `owner_id` берётся из JWT, никогда из тела запроса
- Максимум 10 клубов на одного организатора → 409 CONFLICT (считаются только `is_active = true`)
- Обновлять клуб (`PUT /api/clubs/{id}`) может **менеджер** — владелец ИЛИ активный со-организатор (`role = co_organizer`, `status = active`); см. `co-organizers.md`. **Исключение:** СБП-реквизиты (`paymentLink`, `paymentMethodNote`) и перевод клуба в платный меняет только владелец → иначе 403. **Удалять** клуб может только owner → 403 FORBIDDEN. Регенерация инвайт-ссылки — тоже менеджер
- При создании клуба автоматически создаётся membership с `role = organizer`, `status = active` для владельца — чтобы `/api/users/me/clubs` возвращал клуб в списке и фронтенд мог показывать кнопку управления
- **`is_active = false` (soft-deleted) клубы скрыты для всех запросов** (`findById`, `findAll`, `findByInviteCode`) — возвращают 404. Запись остаётся в БД для audit trail; UI восстановления не предусмотрен в MVP

### Валидация CreateClubRequest (Bean Validation на backend)
| Поле | Правило | Ошибка |
|------|---------|--------|
| name | not blank, 1-60 символов | 400 |
| description | not blank, 1-500 символов | 400 |
| category | one of: sport, creative, food, board_games, cinema, education, travel, other | 400 |
| accessType | one of: open, closed, private | 400 |
| city | not blank | 400 |
| memberLimit | 1-80 (мин. временно 1, V56; было 10) | 400 |
| subscriptionPrice | >= 0 (0 = free club) | 400 |

### UpdateClubRequest (PUT /api/clubs/{id})
Все поля nullable (частичное обновление). Набор редактируемых полей **уже**, чем CreateClubRequest:
`name`, `description`, `city`, `district`, `memberLimit`, `subscriptionPrice`, `avatarUrl`, `rules`, `applicationQuestion`.

**Семантика null vs пустой строки** (для nullable-в-БД полей `district`, `avatarUrl`, `rules`, `applicationQuestion`):
- **ключ отсутствует в JSON** → поле не трогается
- **значение `""` (blank)** → поле очищается в `NULL` в БД (пользователь удалил аватар / стёр правила)
- **значение non-blank** → поле обновляется

Для required-в-БД полей (`name`, `description`, `city`, `memberLimit`, `subscriptionPrice`) пустая строка невалидна; Bean Validation отклонит запрос.

**НЕ редактируемы после создания**:
- `category` — смена категории сломает discovery/фильтры
- `accessType` — смена типа доступа у клуба с действующими membership'ами/заявками требует отдельной миграции бизнес-логики (out-of-scope MVP)
- `ownerId`, `memberCount`, `inviteLink`, `isActive` — служебные

Backend явно не принимает category/accessType в `UpdateClubRequest`. Frontend показывает их в Settings-tab как read-only.

### DELETE /api/clubs/{id} — soft delete
- Проверка owner по JWT → 403 иначе
- **Каскад перед soft-delete (в одной `@Transactional`, добавлен `bugfix/club-delete-cascade` 2026-06-13):**
  - `eventRepository.cancelActiveEventsByClub(id)` — все **нефинализированные** события клуба
    (`status IN upcoming/stage_1/stage_2 AND attendance_finalized = false`) → `cancelled`.
    Завершённые (`completed`) / уже финализированные / уже `cancelled` события **не трогаются** —
    их репутация заперта. Подробности lifecycle — `docs/modules/events.md` § «Каскадная отмена…».
  - `skladchinaRepository.cancelActiveByClub(id)` — все `active`-складчины клуба → `cancelled`,
    их `pending`-участники → `released` (репутационно-нейтрально, **без ledger-строк**, минуя
    `SkladchinaService.closeInternal`, чтобы не начислить штраф и не разослать DM). Уже
    закрытые/отменённые складчины не трогаются. Подробности — `docs/modules/skladchina.md`
    § «Удаление клуба».
  - `applicationRepository.deleteActiveByClub(id)` — `pending`/`approved` заявки в клуб
    hard-удаляются (зеркало `deleteActiveByUserAndClub` из `leaveClub`), чтобы не висели сиротами
    в «Моих заявках». Терминальные `rejected`/`auto_rejected` сохраняются как аудит-история.
- Устанавливает `clubs.is_active = false` + `updated_at = now()`
- **Репутацию не трогает** — это явное продуктовое требование (2026-06-13): удаление клуба
  не должно ни начислять, ни списывать очки. Поэтому каскад работает через репозитории напрямую,
  минуя сервисы с реп-хуками
- **Membership'ы не трогает** — `«Мои клубы»` уже фильтруют `clubs.is_active`
  (см. `membership.md` § AC-2), orphan-membership в выдачу не попадает
- `transactions` остаются в БД для audit trail
- Активные подписки не возвращаются (refund вне scope MVP)
- **Привязку телеграм-чата освобождает полностью** (`chatLinkService.releaseOnClubDeleted(id)`,
  `bugfix/chat-link-deadlock` 2026-07-20) — та же процедура, что у владельческой отвязки:
  снимаются закрепы событий и статус-посты складчин, снимаются мьюты и баны строгого режима
  (учёт `chat_strict_bans`), снимаются теги наград, отзывается door-invite-ссылка, бот выходит
  из чата, строка `club_chat_links` удаляется. Вызов идёт **до** `softDelete` — чтобы
  освобождение работало на ещё видимом клубе; порядок относительно каскадов выше не важен
  (закрепы/статус-посты снимаются по `chat_id`, а не по статусу активностей). Нет привязки — no-op.
  Детали — `docs/modules/club-chat-link.md` § «Освобождение чата при удалении клуба».
  > _Было до 2026-07-20:_ строка переживала soft-delete, бот из группы не выходил. Чат навсегда
  > числился за скрытым клубом: отвязать нечем (эндпоинты chat-link владельческие и ходят через
  > `findById`), повторная привязка упиралась в «Этот чат уже привязан к другому клубу» — дедлок.
- Идемпотентно: повторный DELETE → 204 (но 404 после первого, т.к. findById фильтрует is_active)

### Corner Cases
- `GET /api/clubs/{unknownId}` → 404 NOT_FOUND
- `GET /api/clubs/{softDeletedId}` → 404 NOT_FOUND
- `PUT /api/clubs/{id}` от обычного участника/не-члена → 403 FORBIDDEN; от активного со-организатора → 200 (кроме СБП-полей и перехода в платный — там 403, см. `co-organizers.md`)
- `PUT /api/clubs/{id}` с попыткой передать `category` / `accessType` — поля игнорируются (в DTO их нет)
- `POST /api/clubs` когда уже 10 клубов (только active) → 409 CONFLICT "Maximum 10 clubs per organizer"
- `PUT /api/clubs/{unknownId}` → 404 NOT_FOUND
- `DELETE /api/clubs/{unknownId}` → 404 NOT_FOUND
- `DELETE /api/clubs/{id}` от не-owner → 403 FORBIDDEN

---

## TASK-008b — Settings Tab в Панели организатора

Покрывает PRD §8 Milestone 2 *«CRUD клубов (создание, редактирование, удаление)»* — edit/delete на стороне UI. Соответствует PRD §4.5.1 (поля) и §4.5.2 (Дашборд организатора).

### UI
В `OrganizerClubManage` — вкладка **«⚙️ Настройки»**.

> **Update (`feature/applications-inbox`, 2026-05-30):** таб **«Заявки»** (`ApplicationsTab`) удалён из `OrganizerClubManage`. `TabKey` теперь = `'members' | 'finances' | 'settings'`. Approve/reject заявок выполняется только через кросс-клубовый organizer-inbox на `MyClubsPage` («Заявки на рассмотрении» секция) — см. [`applications-inbox.md`](./applications-inbox.md). Legacy deep-link `?tab=applications` добавлен в `LEGACY_TAB_KEYS` → fallback на `members`.

### Поля формы (из PRD §4.5.1, без category и accessType)
1. **Аватар** — image upload через `POST /api/upload` (MinIO/S3). Формат jpeg/png, до 5 MB. Preview локально пока идёт загрузка.
2. Название — 1-60 символов
3. Город — not blank
4. Район (опционально)
5. Лимит участников — 1-80 (минимум временно 1 для теста заполняемости, V56; было 10)
6. Цена подписки — `0` (free) или `>= 1` (Stars)
7. Описание — 1-500 символов
8. Правила (опционально)
9. Вопрос для заявки (показывается только если `accessType = closed`)

Read-only блок внизу с текущими `category` и `accessType` + подсказка *«Смена категории / типа доступа не поддерживается»*.

### Acceptance Criteria

**AC-1: Сохранение базовых полей**
```
GIVEN владелец клуба открывает Settings tab
WHEN меняет название и нажимает "Сохранить"
THEN PUT /api/clubs/{id} с новым name
AND toast "Настройки сохранены"
AND клуб в списке "Мои клубы" отображается с новым названием
```

**AC-2: Upload аватара**
```
GIVEN выбран файл image.jpg < 5 MB
WHEN пользователь его выбирает в input
THEN POST /api/upload → возвращает url
AND preview показывает новую картинку сразу
AND после "Сохранить" url сохраняется в clubs.avatar_url
```

**AC-3: Upload — ограничения**
```
WHEN файл > 5 MB
THEN frontend валидация "Файл больше 5 МБ"
AND upload не запускается

WHEN файл не jpeg/png
THEN frontend валидация "Только JPEG и PNG"
```

**AC-4: Non-owner НЕ видит Settings tab**
```
GIVEN пользователь с role=member (не organizer) каким-то образом открыл OrganizerClubManage
WHEN пытается PUT /api/clubs/{id}
THEN 403 FORBIDDEN (фронт вообще не должен показывать OrganizerClubManage такому юзеру — это bypass guard)
```

**AC-5: Удаление клуба**
```
GIVEN владелец нажимает "Удалить клуб"
WHEN в confirm-диалоге видит количество активных участников и подтверждает
THEN DELETE /api/clubs/{id} → 204
AND клуб пропадает из "Мои клубы"
AND клуб не находится в discovery
AND навигация уходит на /clubs (главная)
```

**AC-5a: Удаление клуба освобождает привязанный телеграм-чат**
```
GIVEN у клуба привязан телеграм-чат (включены дверь / живой закреп / строгий режим / теги)
WHEN владелец удаляет клуб
THEN бот выходит из группы сам
AND закрепы событий и статус-посты сборов откреплены
AND мьюты и баны строгого режима сняты (ранее забаненный входит по обычной ссылке)
AND теги наград сняты, door-invite-ссылка отозвана
AND строка club_chat_links удалена
AND тот же чат можно сразу привязать к другому клубу (без «Этот чат уже привязан к другому клубу»)
AND в логе `chatUnlinked=true`
```
_Детали и матрица поведения — `docs/modules/club-chat-link.md` § «Освобождение чата»._

**AC-6: Удаление защищено от случайного клика**
```
GIVEN нажал "Удалить клуб"
THEN показан модал с текстом "Клуб «X» и доступ {N} участников будут удалены. Это действие необратимо."
AND две кнопки: "Отмена" (default) и "Удалить" (destructive, красная)
AND только "Удалить" инициирует DELETE-запрос
```

**AC-7: Readonly поля**
```
GIVEN в Settings tab есть блок с category и accessType
WHEN пользователь их видит
THEN они отображаются как метка (не input) + disabled visual cue
AND под ними подсказка о невозможности смены
```

### Non-functional
- Форма не шлёт PUT если ни одно поле не менялось (frontend dirty-check)
- `Button` "Сохранить" disabled пока форма pristine или идёт сохранение
- `Button` "Удалить" всегда active, но подтверждение блокирует случайность
- Upload — только через authenticated request (Bearer JWT), не анонимный
- В логах backend'а: `log.info("Club soft-deleted: id={} userId={} cancelledEvents={} cancelledSkladchinas={} deletedApplications={} chatUnlinked={}")` (счётчики каскада добавлены в `bugfix/club-delete-cascade`, boolean `chatUnlinked` — в `bugfix/chat-link-deadlock`)

### Out of scope (в backlog)
- Возможность восстановить клуб после удаления (админ-UI или user-action). **Примечание:**
  каскад событий/складчин **необратим** — восстановление клуба не вернёт их из `cancelled`
- Hard delete клуба. _Каскад на events/skladchinas (статусная отмена) + applications (hard-delete
  pending/approved) реализован — `bugfix/club-delete-cascade` 2026-06-13; каскад на memberships не
  нужен (UI фильтрует `is_active`). См. `docs/backlog/orphan-memberships-cleanup.md`_
- Смена category / accessType (требует проработки бизнес-правил для active membership'ов)
- Cancel всех active subscriptions при удалении клуба (зависит от GAP-2 cancel-flow)

---

## TASK-009 — Каталог клубов (Discovery)

### Endpoint
```
GET /api/clubs
  Query: category?, city?, accessType?, minPrice?, maxPrice?, search?, page=0, size=20
  Response 200: PageResponse<ClubListItemDto>
```

### Бизнес-правила
- Приватные клубы (`access_type = private`) **не отображаются** в каталоге
- Сортировка: derived-сигнал «свежая активность» (число non-cancelled событий за окно 90 дней + предстоящие) DESC → tiebreak **живой счёт участников** DESC → `created_at` DESC. Колонка `activity_rating` ретайрнута (V30, всегда была 0); сортировка/тег пересобраны как derived в `JooqClubRepository.findAll`
- `memberCount` = **живой счёт** memberships со статусом `active`, `frozen` или `expired` (принадлежность = слот занят; см. `membership-lifecycle.md`), **включая организатора** (его membership создаётся при создании клуба). Считается на лету из `memberships`, НЕ из денормализованной колонки — та дрейфовала (счётчик правился в разрозненных, неполных местах: `cancelManagement` не уменьшал, leave/rejoin рассинхронизировали), показывая 0 для живого клуба. Колонка `clubs.member_count` **дропнута в V33** вместе со всей inc/dec-машинерией. Тот же живой счёт — в `ClubDto.memberCount` (страница клуба, «X/80») и в знаменателе кольца «обычно приходит N из M».
- `nearestEvent` = ближайшее событие клуба с `status = 'upcoming'` и `event_datetime > now()`, limit 1
- Фильтр `search` ищет по полям `name` и `description` (case-insensitive LIKE)
- Фильтры `minPrice`/`maxPrice` по `subscription_price`

### ClubListItemDto
```json
{
  "id": "uuid",
  "name": "string",
  "category": "sport",
  "accessType": "open",
  "city": "Москва",
  "subscriptionPrice": 500,
  "memberCount": 25,
  "memberLimit": 40,
  "avatarUrl": "string|null",
  "nearestEvent": {
    "id": "uuid",
    "title": "string",
    "eventDatetime": "ISO datetime",
    "goingCount": 12
  } | null,
  "tags": ["Новый", "Популярный", "Свободные места"]
}
```

### Теги (`tags`)
Производные ярлыки, считаются на лету в `findAll` (порядок в массиве — как ниже):
- **«Новый»** — `created_at` в пределах последних 14 дней
- **«Популярный»** — **живой счёт участников** в верхней децили текущей выборки (порог = top-10%) **и порог > 0**. Гард `> 0` не даёт повесить тег на всех, когда у всех клубов 0 участников (регрессия мёртвого `activity_rating`). При `< 10` клубов в выборке тег не вешается
- **«Свободные места»** — `живой счёт участников / member_limit < 0.8`

### Corner Cases
- Нет клубов по фильтрам → 200 с пустым `content: []`
- `page` > totalPages → 200 с пустым `content: []`
- `minPrice` > `maxPrice` → 400 VALIDATION_ERROR
- Неверный `category` в фильтре → 400 VALIDATION_ERROR
