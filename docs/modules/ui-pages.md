# Module: UI Pages

---

## TASK-026 — Discovery страница (лента клубов)

### Описание
Главная страница приложения (`/`). Список карточек клубов с фильтрацией и поиском. Server state — через `useClubsQuery` (`@tanstack/react-query`, `useInfiniteQuery` для пагинации, см. [`frontend-stores.md`](./frontend-stores.md)), UI — `@telegram-apps/telegram-ui`.

### Файловая структура

```
src/
  pages/
    DiscoveryPage.tsx          — основная страница (brand layout: topbar + hero + search + chips + list)
  components/
    ClubCard.tsx               — карточка клуба (gradient avatar + capacity bar + featured state)
    (BrandBackdrop.tsx удалён в `feature/redesign-skladchina-and-forms` — после Banco-редизайна все экраны на плоском `rd-page`, последний пользователь ушёл)
  styles/
    brand-theme.css            — легаси палитра/компоненты (частично dead-CSS после Banco-редизайна)
    redesign.css               — Banco-Plata rd-дизайн-система (см. redesign-banco-style.md)
  public/brand/                — production-ready brand assets (logo + 4 tab иконки)
```

> **2026-05-11 `feature/discovery-redesign`:** ClubFilters.tsx удалён — фильтры
> (search + category chips) inline в `DiscoveryPage` как часть единого визуального
> редизайна. Detail в [`discovery-redesign.md`](./discovery-redesign.md).

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

| Роль | Header (с avatar/name/badges) | About-секция | Tabs (brand `cp-tab-row`) | CTA |
|---|---|---|---|---|
| **Visitor** | ✓ (без role-badge) | ✓ | — | «Вступить» / «Хочу вступить» / disabled-варианты для pending state |
| **Member** | ✓ + badge «Вы участник» | ✓ | Активности / Участники | — (статус в badge) |
| **Organizer** | ✓ + badge «Вы организатор» | ✓ | Активности / Участники / **Управление** | — |

«Управление» — **navigate-link**, не state-tab: `handleTabClick('manage')` делает `haptic.impact('light')` + `navigate('/clubs/:id/manage')`, активной не становится.

> **Update (2026-05-30, `feature/profile-reputation-and-skladchina-badge`):** таб «Мой профиль» из member/organizer-видов удалён. Per-club self-view репутации перенесён в глобальную секцию «Моя репутация» на `/profile` (см. ниже § «ProfilePage»). Полные метрики (обещания % / подтверждения / посещения) остаются доступны через таб «Участники» → тап по самому себе → `MemberProfileModal`. Подробности — [`profile.md`](./profile.md).

### Tab-компоненты

Контент member/organizer-tabs вынесен в `frontend/src/components/club/`:

| Файл | Источник данных | Заметки |
|---|---|---|
| `ClubActivitiesTab.tsx` | `useClubActivitiesQuery(clubId, { type? })` (`useQuery`, без пагинации) | Read-only unified-feed events + skladchinas клуба: секция `Предстоящие` (полные карточки, `relevantDate ASC`) + сворачиваемый аккордеон `Прошедшие (N)` (компактные строки, DESC). Tap по карточке → `/events/:id` или `/skladchina/:id`. Создание активностей — через **глобальный FAB** в доке (см. ниже § «FAB и контекст клуба»), не отдельной кнопкой в табе. Заменил `ClubEventsTab.tsx` (удалён) в `feature/unified-activity-creation`. |
| `ClubMembersTab.tsx` | `useClubMembersQuery(clubId)` | rd-список `rd-rep-row` (avatar/Trust `member.trust` с тиром `rd-high/mid/low`). Фолбэк при `trust===null`: «Орг» для `role === 'organizer'` (всем); «Новичок» — **только организатору** (`forOrganizer`), обычному зрителю пусто (асимметрия #94 — чужой null неотличим от скрытого, ложный «Новичок» не пишем). При `managementView` (передаётся как `isOrganizer` со страницы клуба) — доп. attention-бакеты «Доступ истёк» (статусный, `accessStatus === 'expired'` + safety-окно active-с-прошедшей-датой) / «Скоро закончится» / «Оплата вступления» (только frozen). Тап по члену (включая себя) → `MemberProfileModal`. Используется **только** в member/organizer-view `ClubPage` (из `OrganizerClubManage` таб удалён, `feature/members-tab-unification`). |
| ~~`ClubProfileTab.tsx`~~ | — | **Удалён** в `feature/profile-reputation-and-skladchina-badge` (2026-05-30). Функция переехала в `ProfilePage` (см. ниже). |

Tabs рендерятся условно (`{activeTab === 'X' && <Tab/>}`) — non-active tabs не монтируются и их queries не выполняются. Visitor-режим вообще не подключает эти query.

### FAB и контекст клуба
Глобальный FAB «+» (в доке, `Layout`/`AppDock`) при создании активности **учитывает текущий
клуб**. Клубо-контекстные страницы (`ClubPage`, `OrganizerClubManage`, `EventPage`, `SkladchinaPage`)
проставляют `clubId` в `useClubContextStore` (хук `useSetClubContext`, set на mount / null на unmount).
`AppDock` вычисляет `presetClubId = contextClubId && организатор этого клуба ? contextClubId : null`
и передаёт в `CreateActivityFlow`. Если `presetClubId` задан — после выбора типа (Событие/Сбор)
поток **пропускает выбор клуба** и сразу ведёт на `/clubs/:id/events/new` или `/clubs/:id/skladchina/new`.
Вне клуба (или если юзер не организатор просматриваемого клуба) — обычный поток `тип → клуб → форма`.
Отдельной кнопки создания в табе активностей **нет** — всё через FAB.

Когда `presetClubId` активен, FAB получает **акцентное кольцо** (`.rd-dock-action.rd-scoped`:
оранжевая обводка + акцентный «+» + мягкое свечение; не сплошная заливка — она читалась бы как
«уже нажата», как активный таб). Сигналит, что здесь можно создать активность в текущем клубе, и
рекламирует skip-выбора-клуба. Проп `scoped` пробрасывается `AppDock` → `BottomTabBar`.

---

## EventsPage — Лента активностей (`/events`, таб «Активности»)

> **Status (2026-05-18):** ✅ реализовано в PR `feature/events-feed-page`. Полная спека реализации — [`events-feed.md`](./events-feed.md). Таб переименован «События» → «Активности», URL `/events` сохранён (переименование URL отложено до появления складчины — см. `events-feed.md` Decision 1).

### Цель (TL;DR)

Аггрегированная лента upcoming events из ВСЕХ клубов где user — active member. Цель — сократить дистанцию пользователь↔событие до **одного клика**: сейчас 3-4 клика (Мои клубы → клуб → События → событие), будет 1 (открыл таб → видишь карточки).

### Action-first сортировка

Карточки группируются в этом порядке:
1. **🔥 Требует действия** — voting открыт без vote, ИЛИ stage_2 без confirm
2. **📅 Эта неделя** — events ≤ 7 дней
3. **📆 Позже** — events > 7 дней

### Privacy invariant

События **только** из клубов где `MembershipStatus.active`. Не показывает events из:
- Клубов где user только подал application (pending)
- Soft-deleted клубов
- Публичные events (этого слоя в продукте нет — см. `project_clubs_over_events_rationale.md`)

### Реализация

- Backend endpoint `GET /api/users/me/events` — реализован в `UserController` (page-based pagination, page+size, max size = 50)
- Privacy: фильтр по `MEMBERSHIPS.STATUS = active` + `CLUBS.IS_ACTIVE = true` + `event_datetime > now` + `status IN (upcoming, stage_2)`
- Зависимость по security (`club-events-membership-check`) закрыта в PR #32

Полная спека (user-stories, AC, API контракт, анатомия event-card, frontend/backend план, post-flight decisions) — в `events-feed.md`.

### Hero «+ Создать» (итерация 4, `feature/unified-activity-creation`)

В шапку `ActivitiesPage` (`mc-hero`) добавлена кнопка «+ Создать» — параллель
кнопке «+ Создать клуб» на `MyClubsPage` (см. `my-clubs-unified.md`). Видна
**только** организаторам (`useOrganizerClubs().clubs.length > 0`). Tap → flow
`CreateActivityFlow` (тип → клуб → форма создания события/сбора). Это
единственный entry point создания активностей после удаления manage-таба
«Активности». Полная спека — [`unified-activity-creation.md`](./unified-activity-creation.md)
§ «Итерация 4».

---

## OrganizerClubManage — Страница управления клубом (`/clubs/:id/manage`)

### Описание
Страница доступна только организатору. Содержит **3 вкладки**: Статистика, Финансы, Настройки.

> **Update (`feature/members-tab-unification`, 2026-07-06):** таб **«Участники»** удалён из «Управления» — участники дублировались (таб на странице клуба + таб здесь с идентичными данными и действиями). Теперь участники живут ТОЛЬКО на `ClubPage` → таб «Участники»; организаторские attention-бакеты «Скоро закончится» / «Оплата вступления» переехали туда же (`ClubPage` рендерит `ClubMembersTab` с `managementView={isOrganizer}` — владелец видит бакеты, обычный участник плоский ростер). Дефолтная вкладка «Управления» — **Статистика**. Legacy deep-link `?tab=members` редиректит на дефолт (через `LEGACY_TAB_KEYS`). Красная точка attention на табе «Управление» **удалена целиком (2026-07-07, PO)** — с переездом участников она дублировала сигнал; организаторский attention остался только бейджем таб-бара «Мои клубы» (claimed frozen/expired, см. member-admin-profile.md).

> **Update (post `feature/applications-inbox`, 2026-05-30):** таб **«Заявки»** (`ApplicationsTab`) удалён. Approve / reject заявок выполняется через кросс-клубовый organizer-inbox на `MyClubsPage` — секция «Заявки на рассмотрении». Legacy deep-link `?tab=applications` редиректит на дефолт (через `LEGACY_TAB_KEYS`). Полная спека инбокса — [`applications-inbox.md`](./applications-inbox.md).

> **Note (post `feature/unified-activity-creation`, итерация 4 — 2026-05-24):**
> история табов: изначально было 6 (`События` + `Сборы` отдельно) → итерация 1
> объединила их в один таб `Активности` (лента + Modal-picker «+ Создать») →
> **итерация 4 убрала таб `Активности` целиком**. Создание активностей переехало
> на глобальную страницу `/events` (`ActivitiesPage`, hero «+ Создать» для
> организаторов), а лента активностей осталась только в member-view `ClubPage`
> (`ClubActivitiesTab`). Manage-панель снова без активностей.
> Legacy deep-links `?tab=activities|events|skladchina` → fallback `Участники`.
> Полная спека — [`unified-activity-creation.md`](./unified-activity-creation.md).

### Banco-редизайн (rd-, `feature/redesign-club-manage`)
Страница переведена на rd-дизайн-систему (`frontend/src/styles/redesign.css`):
- Обёртка `rd-page` (плоский `var(--bg)`); `BrandBackdrop` убран
- Шапка — full-bleed `rd-hero rd-compact` (`components/manage/ManageHeader.tsx`): обложка по
  `data-cat`/аватар клуба, бейдж «УПРАВЛЕНИЕ», заголовок, eyebrow `N/limit участников · город`.
  Hero **не кликабелен**; back-стрелка (`rd-hero-btn rd-left`, ‹-иконка) слева сверху → страница
  клуба (`/clubs/:id`). (Раньше кликабелен был весь hero с шевроном справа — путало с «вперёд».)
- Вкладки — underline-табы `rd-tabs`/`rd-tab-link` (3 лейбла влезают без скролла). Компонент
  `ManageTabs` удалён как dead code.
- **Участники** — переиспользуется общий `ClubMembersTab` (`isOrganizer`), а не локальный
  дубль `MembersTab` (удалён). Организатор теперь видит здесь и секцию «Ожидают оплаты» (как на
  member-view странице клуба).
- **Финансы** — карточки-метрики `rd-stats`/`rd-stat` (выручка градиентом, остальное `rd-plain`).
- **Настройки** — секции под `rd-section-sub-h`; поля ввода остаются telegram-ui `Input/Textarea`
  (rd-формы — отдельный пункт бэклога), read-only блок на `rd-kv`, кнопки «Сохранить»/«Удалить» —
  `rd-btn-primary`/`rd-btn-outline`; модалка удаления — telegram-ui `Modal` (recolor).
- Легаси `manage-hero`/`manage-tab*` CSS остаётся dead-CSS до общей чистки.

### Вкладки

#### Участники (`ClubMembersTab`, `isOrganizer`)
- Список участников через `GET /api/clubs/:id/members` (строки `rd-rep-row` с avatar/индексом надёжности)
- Секция «Ожидают оплаты» (`GET /api/clubs/:id/awaiting-payment`, gated `isOrganizer`)
- Клик по участнику → `MemberProfileModal`

#### MemberProfileModal
rd-sheet bottom-sheet (`createPortal`, как CityPicker/ProfileEditModal — больше **не** telegram-ui Modal).
Загружает `GET /api/clubs/:id/members/:userId/profile`. Показывает:
- Аватар (`rd-avatar`), имя, username
- «Статус в клубе» (`rd-glass rd-rep-panel` + `rd-kv`): роль, дата вступления
- «Репутация» (`rd-kv`): надёжность (Trust `profile.trust`), % выполнения обещаний, подтверждения, посещения; при `trust === null` — «Новичок» / организаторская рамка. **UPDATED 2026-07-05 (асимметричная видимость):** сервер отдаёт оценочные поля только организатору и самому участнику о себе; чужому зрителю приходят null → рендерится та же «Новичок»-подача (скрыто ≡ нет данных). bio/интересы/награды видны всем

#### ~~Заявки~~ (`ApplicationsTab`, удалён в `feature/applications-inbox`, 2026-05-30)
- Кросс-клубовый organizer-inbox теперь живёт на `MyClubsPage` — секция «Заявки на рассмотрении».
- `GET /api/clubs/:id/applications` остаётся public endpoint'ом (используется для архива, не задействован UI).
- Approve/reject — через `ApplicationReviewModal` на `MyClubsPage`. Reason при reject — обязателен (≥5 символов после trim).
- Полная спека — [`applications-inbox.md`](./applications-inbox.md).

> **Активности — больше НЕ в manage (итерация 4).** Таб «Активности»
> (`ActivitiesManageTab`) удалён. Единая лента событий + сборов клуба
> (`Предстоящие` ASC + аккордеон `Прошедшие (N)` DESC, источник
> `GET /api/clubs/:id/activities`) теперь только в **member-view** `ClubPage`
> (`ClubActivitiesTab`, read-only). Создание активностей — на глобальной странице
> `/events` (`ActivitiesPage`, hero «+ Создать» → `CreateActivityFlow`:
> тип → клуб → `CreateEventPage`/`CreateSkladchinaPage`). Полная спека —
> [`unified-activity-creation.md`](./unified-activity-creation.md).

#### Финансы (`FinancesTab`)
- `GET /api/clubs/:id/finances` → активные участники, выручка за месяц, доля организатора, комиссия платформы

#### Настройки (`SettingsTab`)
- **Аватар:** компонент `AvatarUpload` (загрузка/замена/удаление, JPEG/PNG до 5 МБ)
- **Основное:** Input'ы Название / Город / Район / Лимит участников / Цена подписки
- **Описание и правила:** `<Textarea>` Описание / Правила; `<Input>` «Вопрос для заявки» (только если `accessType === 'closed'`)
- **Нельзя изменить:** read-only блок с категорией и типом доступа
- Кнопка «Сохранить» — disabled если не dirty; при submit `PUT /api/clubs/:id`
- **Опасная зона:** кнопка «Удалить клуб» (`rd-btn-outline`, `--danger`) → модалка подтверждения → `DELETE /api/clubs/:id` → редирект на `/my-clubs` с Toast «Клуб «X» удалён»


---

## ProfilePage — Профиль (`/profile`, нижний таб «Профиль»)

> **Status (2026-05-30):** ✅ реализовано в `feature/profile-reputation-and-skladchina-badge`. Полная спека — [`profile.md`](./profile.md). Файл `frontend/src/pages/ProfilePage.tsx`. Переведён на Banco-редизайн (`rd-page`, см. [`redesign-banco-style.md`](./redesign-banco-style.md)).

### Структура

| Блок | Когда виден | Источник |
|---|---|---|
| Hero `Твой профиль` + ⚙️ | всегда | static (шестерёнка disabled пока `useMyInterestsQuery.isPending`) |
| `.pf-identity` (avatar + имя + @username + город/страна) | всегда | `useAuthStore.user` |
| `.pf-bio` (свободный текст «о себе») | если `user.bio` задан | `useAuthStore.user.bio` |
| Секция «Интересы» (чипы `.pf-tag`) | если `interests.length > 0` | `useMyInterestsQuery()` |
| Global-шапка «надёжен в N из M клубов» (плашка `.rd-empty` при пустоте/ошибке) | **всегда** | `useMyReputationQuery()` → `MyReputationDto` (только `global`) |
| ~~Секции «Репутация» (активные) / «История»~~ — **ПЕРЕЕХАЛИ 2026-07-05**: per-club репутация теперь в «Моих клубах» (раскрывающиеся карточки клубов + «путь назад»), история — там же. См. reputation-path-back.md | — | — |
| Секция «Активные заявки» | если есть pending / rejected / auto_rejected applications | `useMyApplicationsQuery()` + `useQueries(getClub)` для названий. Для отклонённых заявок с `rejectedReason` рендерится третья строка `.reason` с причиной отказа — см. [`profile.md`](./profile.md) § «AC-12». |

~~Карточка `ReputationRow`~~ — удалена из Профиля 2026-07-05: её содержимое (метрики + Trust) живёт в раскрывающейся карточке клуба на `MyClubsPage` (+ кольца Посещаемость/Сборы + «путь назад» при просадке). См. reputation-path-back.md.

### Редактирование (⚙️ → `ProfileEditModal`)

Свой `createPortal`-sheet (`pf-edit-overlay`/`pf-edit-sheet` z-index 150/151) — **не** TGUI `<Modal>`, потому что CityPicker (z 200) должен открываться поверх. Поля: Город (через `CityPicker`, страна+город из общего списка) / О себе (`textarea`, max 280) / Интересы (`InterestsInput` с дебаунс-автокомплитом, разделитель — только запятая). Submit → `PATCH /api/users/me` → `setUser(updated)` + invalidate `myInterests`.

Имя / аватар / @username **не редактируются** (синхронизируются из Telegram при каждом auth через `UserRepository.upsert`).

Полная спека эндпоинтов, нормализации интересов, миграции V16 и AC — в [`profile.md`](./profile.md).
