# Module: Clubs

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
- Только owner может обновлять / удалять клуб → 403 FORBIDDEN
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
| memberLimit | 10-80 | 400 |
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
- `ownerId`, `memberCount`, `activityRating`, `inviteLink`, `isActive` — служебные

Backend явно не принимает category/accessType в `UpdateClubRequest`. Frontend показывает их в Settings-tab как read-only.

### DELETE /api/clubs/{id} — soft delete
- Проверка owner по JWT → 403 иначе
- Устанавливает `clubs.is_active = false` + `updated_at = now()`
- **Не удаляет** связанные membership'ы, events, applications, transactions — они остаются в БД для audit trail
- Активные подписки не возвращаются (refund вне scope MVP)
- Telegram-группу клуба (`telegram_group_id`) **не трогает** — остаётся как есть
- Идемпотентно: повторный DELETE → 204 (но 404 после первого, т.к. findById фильтрует is_active)

### Corner Cases
- `GET /api/clubs/{unknownId}` → 404 NOT_FOUND
- `GET /api/clubs/{softDeletedId}` → 404 NOT_FOUND
- `PUT /api/clubs/{id}` от не-owner → 403 FORBIDDEN
- `PUT /api/clubs/{id}` с попыткой передать `category` / `accessType` — поля игнорируются (в DTO их нет)
- `POST /api/clubs` когда уже 10 клубов (только active) → 409 CONFLICT "Maximum 10 clubs per organizer"
- `PUT /api/clubs/{unknownId}` → 404 NOT_FOUND
- `DELETE /api/clubs/{unknownId}` → 404 NOT_FOUND
- `DELETE /api/clubs/{id}` от не-owner → 403 FORBIDDEN

---

## TASK-008b — Settings Tab в Панели организатора

Покрывает PRD §8 Milestone 2 *«CRUD клубов (создание, редактирование, удаление)»* — edit/delete на стороне UI. Соответствует PRD §4.5.1 (поля) и §4.5.2 (Дашборд организатора).

### UI
В `OrganizerClubManage` — новая вкладка **«⚙️ Настройки»** (пятая после Members / Applications / Events / Finances).

### Поля формы (из PRD §4.5.1, без category и accessType)
1. **Аватар** — image upload через `POST /api/upload` (MinIO/S3). Формат jpeg/png, до 5 MB. Preview локально пока идёт загрузка.
2. Название — 1-60 символов
3. Город — not blank
4. Район (опционально)
5. Лимит участников — 10-80
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
- В логах backend'а: `log.info("Club soft-deleted: id={} userId={}")`

### Out of scope (в backlog)
- Возможность восстановить клуб после удаления (админ-UI или user-action)
- Hard delete + каскад на memberships / events / applications
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
- Сортировка: `activity_rating DESC`
- `memberCount` = количество memberships со статусом `active`
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
  } | null
}
```

### Corner Cases
- Нет клубов по фильтрам → 200 с пустым `content: []`
- `page` > totalPages → 200 с пустым `content: []`
- `minPrice` > `maxPrice` → 400 VALIDATION_ERROR
- Неверный `category` в фильтре → 400 VALIDATION_ERROR
