# Module: UI Pages

---

## TASK-026 — Discovery страница (лента клубов)

### Описание
Главная страница приложения (`/`). Список карточек клубов с фильтрацией и поиском. Использует `useClubsStore` для данных, `@telegram-apps/telegram-ui` для UI.

### Файловая структура

```
src/
  pages/
    DiscoveryPage.tsx          — основная страница
  components/
    ClubCard.tsx               — карточка клуба
    ClubFilters.tsx            — фильтры (категория, город, тип доступа, цена)
```

### ClubCard

Поля из `ClubListItemDto`:
- `name` — название
- `category` — категория (badge/chip)
- `city` — город
- `subscriptionPrice` — цена (0 = бесплатно)
- `memberCount / memberLimit` — `12/30 участников`
- `nearestEvent?.eventDatetime` — дата ближайшего события (если есть)
- `accessType` — тип доступа (open = "Открытый", closed = "По заявке", private = скрыт в каталоге)

### Промо-теги (TASK-035 — в будущем)
Пока не реализуем. Будут добавлены в TASK-035.

### Фильтры
| Фильтр | Тип | API параметр |
|--------|-----|-------------|
| Категория | chips | `category` |
| Город | text input | `city` |
| Тип доступа | chips | `accessType` |
| Текстовый поиск | text input | `search` |

Фильтры отправляются при изменении (debounce 300ms для текста).

### Пагинация
Загружать по 20 клубов. Кнопка "Загрузить ещё" или infinite scroll через intersection observer.

### Corner Cases
- Нет клубов → empty state: "Клубы не найдены. Попробуйте изменить фильтры."
- Загрузка → Skeleton cards (3-5 placeholder)
- Ошибка API → Banner с текстом ошибки

---

## TASK-030 — Панель организатора (создание клуба)

### Описание
Страница `/organizer`. Две секции:
1. Список своих клубов
2. Кнопка создания нового клуба → пошаговая форма (modal или отдельная страница)

### Пошаговая форма создания клуба (по PRD 4.5.1)

| Шаг | Поля |
|-----|------|
| 1 | `name` (до 60 символов), `city`, `district?` |
| 2 | `category` (select), `accessType` (radio: open/closed) |
| 3 | `memberLimit` (1-200), `subscriptionPrice` (0 или > 0) |
| 4 | `description` (до 500 символов), `rules?` |
| 5 | `applicationQuestion?` (если accessType = closed) |

### Калькулятор дохода
При вводе `subscriptionPrice` и `memberLimit`:
```
"При N участниках вы будете зарабатывать X ₽ в месяц (80% от дохода)"
X = memberLimit * subscriptionPrice * 0.8
```

### Валидация
- `name`: 3-60 символов
- `city`: обязательный
- `memberLimit`: 1-200
- `subscriptionPrice`: 0 или >= 100 (в Telegram Stars)
- `description`: до 500 символов
- `applicationQuestion`: до 200 символов

### После создания
Редирект на `/clubs/{newClubId}/manage` (страница управления клубом). Перед переходом вызывается `fetchMyClubs()` чтобы обновить список в стор.

### Список клубов организатора
Для каждого membership с `role = organizer` загружается имя клуба через `GET /api/clubs/{id}`. Навигация по клику: `/clubs/{clubId}/manage`.
