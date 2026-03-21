# Module: Clubs

## TASK-008 — CRUD клубов

### Endpoints
```
POST /api/clubs            → 201 ClubDetailDto
GET  /api/clubs/{id}       → 200 ClubDetailDto
PUT  /api/clubs/{id}       → 200 ClubDetailDto
```

### Бизнес-правила
- `owner_id` берётся из JWT, никогда из тела запроса
- Максимум 10 клубов на одного организатора → 409 CONFLICT
- Только owner может обновлять клуб → 403 FORBIDDEN
- При создании клуба автоматически создаётся membership с `role = organizer`, `status = active` для владельца — чтобы `/api/users/me/clubs` возвращал клуб в списке и фронтенд мог показывать кнопку управления

### Валидация CreateClubRequest
| Поле | Правило | Ошибка |
|------|---------|--------|
| name | 1-60 символов, not blank | 400 |
| description | 1-500 символов, not blank | 400 |
| category | одно из: sport, creative, food, board_games, cinema, education, travel, other | 400 |
| accessType | одно из: open, closed, private | 400 |
| city | not blank | 400 |
| memberLimit | 10-80 | 400 |
| subscriptionPrice | >= 0 | 400 |

### Corner Cases
- `GET /api/clubs/{unknownId}` → 404 NOT_FOUND
- `PUT /api/clubs/{id}` от не-owner → 403 FORBIDDEN
- `POST /api/clubs` когда уже 10 клубов → 409 CONFLICT "Maximum 10 clubs per organizer"
- `PUT /api/clubs/{unknownId}` → 404 NOT_FOUND

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
