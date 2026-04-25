# Module: UI Pages

---

## TASK-026 — Discovery страница (лента клубов)

### Описание
Главная страница приложения (`/`). Список карточек клубов с фильтрацией и поиском. Server state — через `useClubsQuery` (`@tanstack/react-query`, `useInfiniteQuery` для пагинации, см. [`frontend-stores.md`](./frontend-stores.md)), UI — `@telegram-apps/telegram-ui`.

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

### Промо-теги (TASK-035 — реализовано)
Backend возвращает `tags: string[]` в каждом `ClubListItemDto`. Три типа тегов, вычисляются в `ClubRepository.findAll()`:

| Тег | Условие |
|-----|---------|
| «Новый» | клуб создан < 14 дней назад |
| «Популярный» | входит в топ-10% по заявкам / местам |
| «Свободные места» | заполнено < 80% от `memberLimit` |

Frontend рендерит теги как badges на `ClubCard`.

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

## TASK-030 — Панель организатора (создание клуба) — **DEPRECATED**

> **Status:** функционал консолидирован в **TASK-018 (унифицированная `MyClubsPage`)** в PR `feature/restructure-bottom-tabs` (2026-04-25). Отдельная страница `/organizer` удалена; `/organizer` URL редиректится на `/my-clubs` через `<Navigate>` в `router.tsx`. Wizard-форма создания клуба вынесена в `frontend/src/components/CreateClubModal.tsx` и открывается из Section-кнопки «+ Создать клуб» в шапке `MyClubsPage`. История описания TASK-030 ниже сохранена для аудита; для актуального поведения см. [`my-clubs-unified.md`](./my-clubs-unified.md).

### Описание (исторически)
Страница `/organizer`. Две секции:
1. Список своих клубов
2. Кнопка создания нового клуба → пошаговая форма (modal или отдельная страница)

### Пошаговая форма создания клуба (по PRD 4.5.1)

| Шаг | Поля |
|-----|------|
| 1 | `name` (до 60 символов), `city`, `district?` |
| 2 | `category` (select), `accessType` (radio: open/closed) |
| 3 | `memberLimit` (10-80), `subscriptionPrice` (0 или > 0) |
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
- `memberLimit`: 10-80 (синхронизировано с backend `@Min(10) @Max(80)` в `CreateClubRequest`)
- `subscriptionPrice`: 0 или >= 100 (в Telegram Stars)
- `description`: до 500 символов
- `applicationQuestion`: до 200 символов

### После создания
Редирект на `/clubs/{newClubId}/manage` (страница управления клубом). Перед переходом вызывается `fetchMyClubs()` чтобы обновить список в стор.

### Список клубов организатора
Для каждого membership с `role = organizer` загружается имя клуба через `GET /api/clubs/{id}`. Навигация по клику: `/clubs/{clubId}/manage`.

---

## ClubPage — Унифицированная страница клуба (`/clubs/:id`)

> **Status (2026-04-25):** реализовано в `feature/unified-club-page`. Single entry point под три роли: visitor / member / organizer. Раздельная `ClubInteriorPage` удалена; легаси-роут `/clubs/:id/interior` редиректит на `/clubs/:id` через `<InteriorRedirect>` (`router.tsx:25-30`). Подробная спецификация — [`club-page-unified.md`](./club-page-unified.md).

### Что показывается по роли

| Роль | Header (с avatar/name/badges) | About-секция | TabsList | CTA |
|---|---|---|---|---|
| **Visitor** | ✓ (без role-badge) | ✓ | — | «Вступить» / «Хочу вступить» / disabled-варианты для pending state |
| **Member** | ✓ + badge «Вы участник» | ✓ | События / Участники / Мой профиль | — (статус в badge) |
| **Organizer** | ✓ + badge «Вы организатор» | ✓ | События / Участники / Мой профиль / **Управление** | — |

«Управление» — **navigate-link**, не state-tab: `handleTabClick('manage')` делает `haptic.impact('light')` + `navigate('/clubs/:id/manage')`, активной не становится.

### Tab-компоненты

Контент member/organizer-tabs вынесен в `frontend/src/components/club/`:

| Файл | Источник данных | Заметки |
|---|---|---|
| `ClubEventsTab.tsx` | `useClubEventsQuery(clubId, { size: '100' })` | Upcoming (статусы `upcoming`/`stage_1`/`stage_2`) + past (max 5). Tap по Cell → `/events/:id` |
| `ClubMembersTab.tsx` | `useClubMembersQuery(clubId)` | Список с avatar/reliability, badge «Организатор» для `role === 'organizer'` |
| `ClubProfileTab.tsx` | `useMemberProfileQuery(clubId, userId)` | Avatar + reputation-метрики (reliability / promiseFulfillmentPct / totalConfirmations) |

Tabs рендерятся условно (`{activeTab === 'X' && <Tab/>}`) — non-active tabs не монтируются и их queries не выполняются. Visitor-режим вообще не подключает эти три query.

---

## OrganizerClubManage — Страница управления клубом (`/clubs/:id/manage`)

### Описание
Страница доступна только организатору. Содержит **5 вкладок**: Участники, Заявки, События, Финансы, Настройки.

### Вкладки

#### Участники (`MembersTab`)
- Список участников через `GET /api/clubs/:id/members`
- Клик по участнику → `MemberProfileModal`

#### MemberProfileModal
Загружает `GET /api/clubs/:id/members/:userId/profile`. Показывает:
- Аватар, имя, username
- Роль и дата вступления
- Репутация: индекс надёжности, % выполнения обещаний, подтверждения, посещения

#### Заявки (`ApplicationsTab`)
- Список pending заявок через `GET /api/clubs/:id/applications`
- Кнопки "Принять" / "Отклонить" → `POST /api/applications/:id/approve` или `/reject`
- Таймер до автоотклонения (48ч с момента создания)

#### События (`EventsTab`)
- Список через `GET /api/clubs/:id/events?size=50`
- Предстоящие (статусы: `upcoming`, `stage_1`, `stage_2`) и Завершённые (`completed`)
- Клик по событию → `EventDetailModal`
- Кнопка "Присутствие" на завершённых → AttendanceModal (отметка явки)
- Форма создания события: название, место, дата/время, лимит участников

#### EventDetailModal
Загружает `GET /api/events/:id`. Показывает:
- Название, дата/время, место, статус (с русской меткой)
- Счётчики: пойдут / лимит, может быть, не пойдут, подтверждено
- Описание (если есть)
- Для завершённых: флаги `attendanceMarked` и `attendanceFinalized`

#### Финансы (`FinancesTab`)
- `GET /api/clubs/:id/finances` → активные участники, выручка за месяц, доля организатора, комиссия платформы

#### Настройки (`SettingsTab`)
- **Аватар:** компонент `AvatarUpload` (загрузка/замена/удаление, JPEG/PNG до 5 МБ)
- **Основное:** Input'ы Название / Город / Район / Лимит участников / Цена подписки
- **Описание и правила:** `<Textarea>` Описание / Правила; `<Input>` «Вопрос для заявки» (только если `accessType === 'closed'`)
- **Нельзя изменить:** read-only блок с категорией и типом доступа
- Кнопка «Сохранить» — disabled если не dirty; при submit `PUT /api/clubs/:id`
- **Опасная зона:** кнопка «🗑 Удалить клуб» → модалка подтверждения → `DELETE /api/clubs/:id` → редирект на `/my-clubs` с Toast «Клуб «X» удалён»
