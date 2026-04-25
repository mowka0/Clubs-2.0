# MyClubs Unified — объединение «Мои клубы» + «Организатор», новый таб «События»

## Цель

Текущая bottom nav имеет 4 таба: Discovery / Мои клубы / Организатор / Профиль. Таб «Организатор» виден всем, но релевантен ~5% пользователей (только тем, у кого есть свой клуб) — для остальных это пустой экран с одной кнопкой «Создать клуб». Это dead-tab.

Этот PR:
1. **Объединяет** «Мои клубы» и «Организатор» в один таб `/my-clubs` с role-aware UI: один список, в нём и member-clubs и organizer-clubs, разный destination по тапу + бейдж роли. Кнопка «+ Создать клуб» переезжает сюда же.
2. **Добавляет** новый таб «События» (`/events`) — заглушка-placeholder. Реальная логика (лента upcoming events из всех клубов user'а) — отдельным PR позже.
3. Сохраняет product-инвариант [`project_clubs_over_events_rationale`](../../.claude/projects/.../memory/project_clubs_over_events_rationale.md): «События» = upcoming **только из моих клубов**, не публичная лента.

## Scope

### Входит
- Перенос секции «Мои клубы» из `OrganizerPage` в `MyClubsPage` (объединение списка) с visual differentiation organizer / member.
- Перенос «+ Создать клуб» + `CreateClubModal` из `OrganizerPage` в `MyClubsPage`.
- Вынос `CreateClubModal` в отдельный файл `frontend/src/components/CreateClubModal.tsx` (255 строк сейчас inline в OrganizerPage — превышает порог 150-200 из `frontend.md` § «Компоненты»).
- Удаление `frontend/src/pages/OrganizerPage.tsx`.
- Новый `frontend/src/pages/EventsPage.tsx` — placeholder.
- Обновление `BottomTabBar` (4 таба: Поиск / Мои клубы / События / Профиль) и `router.tsx` (убрать `/organizer`, добавить `/events`).
- Адаптация `frontend/src/test/pages/CreateClubModal.test.tsx` — путь импорта `../../pages/OrganizerPage` → `../../components/CreateClubModal`.
- Обновление `docs/modules/ui-pages.md` § «Панель организатора» — пометить deprecated, ссылка на этот документ; обновление `docs/modules/haptic.md` § «Списки и навигация» (строки 99 + 124-126: упоминания `OrganizerPage` → `MyClubsPage` / `CreateClubModal`).

### Изменение `BottomTabBar.isTabBarRoute` regex (NEW behaviour vs pre-PR)

Регулярка расширена с `/^\/clubs\/[^/]+(\/manage)?$/` до `/^\/(clubs|events)\/[^/]+(\/manage)?$/` (`BottomTabBar.tsx:29`). Причина: новый таб «События» ведёт на `/events`, и при тапе в карточку события (`/events/:id`) tab-bar должен оставаться видимым — иначе пользователь теряет контекст и не может вернуться в «События» одним тапом. Поведение симметрично уже существующему `/clubs/:id` (детальная страница клуба тоже сохраняет tab-bar). Найдено Reviewer'ом как [Blocker], исправлено в этом PR.

### НЕ входит (отдельный PR)
- Реальная логика `EventsPage`: backend endpoint `GET /api/users/me/events?status=upcoming` + frontend список с RSVP — only placeholder сейчас.
- Любые изменения backend (`getMyClubs()` уже возвращает `MembershipDto.role` — `frontend/src/types/api.ts:113`).
- Изменения других страниц: `ClubPage`, `EventPage`, `OrganizerClubManage`, ~~`ClubInteriorPage`~~ (удалён в `feature/unified-club-page`, 2026-04-25), `DiscoveryPage`, `ProfilePage`.
- Реструктуризация роутера за пределами этих 4 routes.
- Discovery по событиям (запрещено, см. инвариант выше).

## Маппинг изменений по файлам

| Файл | Действие | Что меняется |
|---|---|---|
| `frontend/src/pages/MyClubsPage.tsx` | EDIT | Объединить секцию membership: показывать **все** клубы (organizer + member), бейдж роли, разные destinations по клику; добавить sticky FAB «+ Создать клуб», открывающий `CreateClubModal`. Сохранить секцию «Мои заявки» как сейчас. |
| `frontend/src/components/CreateClubModal.tsx` | NEW | Вынести компонент `CreateClubModal` из `OrganizerPage.tsx` (строки 66-320) **без изменений** в логике/UX. Только перенос. |
| `frontend/src/pages/OrganizerPage.tsx` | DELETE | Файл удаляется целиком. |
| `frontend/src/pages/EventsPage.tsx` | NEW | Placeholder-страница. |
| `frontend/src/components/BottomTabBar.tsx` | EDIT | Обновить `TABS`: `/` Поиск, `/my-clubs` Мои клубы, `/events` События, `/profile` Профиль. |
| `frontend/src/router.tsx` | EDIT | Убрать import + route `/organizer`; добавить eager-import + route `/events` → `EventsPage`. |
| `frontend/src/test/pages/CreateClubModal.test.tsx` | EDIT | Изменить строку 34: `import { CreateClubModal } from '../../components/CreateClubModal';` (default или named — Developer фиксит под фактический export). Логика тестов — без изменений. |
| `docs/modules/ui-pages.md` | EDIT | TASK-030 пометить «**Deprecated** — функционал перенесён в [my-clubs-unified.md](./my-clubs-unified.md)». Не удалять — это история. |
| `docs/modules/haptic.md` | EDIT (post-flight) | Перемап `OrganizerPage` → `MyClubsPage` (tap клуба, разделение по `role`) + `components/CreateClubModal.tsx` (`handleNext`/back/submit wizard). Добавлена строка «MyClubsPage `+ Создать клуб` button → impact light». Список консьюмеров `useHaptic` обновлён. Done в этом PR. |

## Решение по `CreateClubModal`: выносим в отдельный файл

**Решение:** выносим в `frontend/src/components/CreateClubModal.tsx`.

Обоснование:
- Текущий размер inline в `OrganizerPage.tsx` — ~255 строк (66-320 из 383 общих). Превышает порог `frontend.md` § «Компоненты» (150-200).
- После удаления `OrganizerPage` файл-носитель исчезает — некуда оставлять «inline».
- Уже импортируется отдельно в тестах (`test/pages/CreateClubModal.test.tsx:34`) — фактически уже использовался как самостоятельная единица. Вынос только формализует это.
- Логика модала **не меняется** — это чистая реорганизация. RHF-миграция уже выполнена в предыдущем PR.

## UX-детали unified MyClubsPage

> Это решения, требующие подтверждения пользователя ДО разработки. Помечены **[DECIDE]** где есть варианты.

### Структура списка

Один общий `<Section header="Мои клубы">` со всеми клубами вперемешку. Differentiation:
- Бейдж роли в `subtitle` ячейки `Cell`: «Организатор» (для role='organizer') / «Участник» (для role='member'). Уже реализовано в текущем `MyClubsPage:93`.
- **[DECIDE]** Дополнительная визуальная отметка organizer-клубов? Варианты:
  - (A) **Только subtitle-текст** — KISS, никакого иконографического шума. Рекомендация.
  - (B) Цветной бейдж (зелёный pill «⚙️ Организатор» в `after`-слоте Cell).
  - (C) Отдельные секции «Где я организатор» + «Где я участник» сверху-вниз.

  **Рекомендация: (A).** До дизайн-итерации не вносим визуального шума, текущий subtitle уже различает. Сортировка естественная — organizer-клубы будут вверху по joinedAt (см. ниже).

### Где «+ Создать клуб»

**[LOCKED] Section-кнопка в верхней части страницы** (отдельная `<Section>` с `<Button size="l" stretched>` поверх списка клубов).

Рассмотренные альтернативы:
- (A) Sticky FAB (Floating Action Button) — **отклонён**: визуально конфликтует с BottomTabBar без custom-позиционирования (требует hardcoded `bottom: 80` поверх tab-bar, плюс tgui не имеет готового FAB-компонента). Pre-design фаза — не время для custom-патчей; Claude Design пересмотрит паттерн на дизайн-итерации.
- (B) Section-кнопка **в конце** списка (как было в OrganizerPage) — отклонено: на длинных списках требует scroll, отодвигает CTA от точки входа.
- (C) Header-кнопка в шапке Section — отклонено: тесно, плохо различима как primary action.

**Реализация (см. `MyClubsPage.tsx:100-110`):**
```tsx
<Section>
  <div style={{ padding: 16 }}>
    <Button size="l" stretched onClick={() => { haptic.impact('light'); setShowCreateModal(true); }}>
      + Создать клуб
    </Button>
  </div>
</Section>
```
Размещена **первой** в `<List>` — выше списка клубов и заявок. Видна сразу при открытии `/my-clubs`, не требует скролла. Дизайн-итерация может перенести в FAB / native MainButton — точка изменения локальна.

### Empty states

| Состояние | Что показывать |
|---|---|
| Нет ни одного клуба и нет заявок | **Один** комбинированный `<Placeholder description="Вы пока не состоите ни в одном клубе. Найдите интересный в Поиске или создайте свой выше.">` (combined empty state — без отдельных placeholder'ов на «нет клубов» и «нет заявок» рядом). Кнопка «+ Создать клуб» остаётся видимой сверху. |
| Только member-clubs | Список без empty state (всё ок), кнопка «+ Создать клуб» видима |
| Только organizer-clubs | Список без empty state (всё ок), кнопка «+ Создать клуб» видима |
| Есть клубы + есть pending applications | Показать обе секции как сейчас |

Никаких отдельных empty state per-role — слишком много шума. Combined-placeholder реализован в `MyClubsPage.tsx:114-116` под условием `!loading && myClubs.length === 0 && applications.length === 0`. Текст ссылается на «выше» — это «+ Создать клуб» Section в шапке списка.

### Сортировка

`getMyClubs()` отдаёт уже отсортированные данные (по joinedAt DESC у бэкенда — verify в Developer-этапе). На фронте **без доп. сортировки**, рендерим в исходном порядке. Organizer-clubs обычно созданы позже member-clubs (юзер сначала вступает куда-то, потом сам создаёт), так что они часто и так окажутся вверху.

### Тап-поведение

- `m.role === 'organizer'` → `navigate('/clubs/${m.clubId}/manage')` (как в текущем OrganizerPage).
- `m.role === 'member'` → `navigate('/clubs/${m.clubId}')` (как в текущем MyClubsPage).
- Haptic — `impact('light')` в обоих случаях, до `navigate` (см. `haptic.md` правило для navigation-cells).

## EventsPage placeholder

**Файл:** `frontend/src/pages/EventsPage.tsx`

Содержимое — `<Placeholder>` из `@telegram-apps/telegram-ui`:
- `header="События"`
- `description="Здесь скоро появится лента ваших ближайших событий из всех клубов, где вы состоите."`
- Иконка/emoji — внутри `<Placeholder>` через `children` слот: большая `📅` (просто emoji span 64px, без отдельной иконки-компоненты).

**Никакого haptic** — placeholder не действие. Никаких queries, никаких state. Просто статичный рендер. ~15-20 строк.

## Обработка `/organizer` legacy-route

**[DECIDE]** Куда отправлять users со старым URL (закладки, deep-links, history)?

- (A) Redirect на `/my-clubs` (через `<Navigate to="/my-clubs" replace />` в роутере). Рекомендация.
- (B) 404. Семантически чище, но ломает UX для тех, у кого `/organizer` сохранён.

**Рекомендация: (A) redirect.** Причина: функциональность мигрировала, не пропала. Для пользователя — то же самое, просто новый URL. Реализация — добавить в `router.tsx`:
```ts
{ path: '/organizer', element: <Navigate to="/my-clubs" replace /> }
```

## Acceptance Criteria

### AC-1: build & test green
GIVEN ветка с реализованными изменениями
WHEN `npm run build && npm test` в `frontend/`
THEN exit 0; нет TypeScript-ошибок; адаптированный `CreateClubModal.test.tsx` зелёный.

### AC-2: BottomTabBar содержит 4 таба в правильном порядке
GIVEN любая основная страница
WHEN рендерится `BottomTabBar`
THEN видны ровно 4 таба слева направо: Поиск (`/`), Мои клубы (`/my-clubs`), События (`/events`), Профиль (`/profile`). Таба «Организатор» нет.

### AC-3: переход на `/organizer` редиректит на `/my-clubs`
GIVEN пользователь открывает URL `/organizer` (по старой закладке)
WHEN router резолвит маршрут
THEN происходит redirect на `/my-clubs` (без сохранения `/organizer` в history через `replace`).

### AC-4: переход на `/events` показывает placeholder
GIVEN пользователь тапает таб «События»
WHEN навигация завершена
THEN отрендерен `EventsPage` с текстом «Здесь скоро появится лента ваших ближайших событий из всех клубов, где вы состоите.»
AND haptic не срабатывает на самой странице (только `select()` от `BottomTabBar` на смену таба).

### AC-5: unified `/my-clubs` показывает organizer + member клубы
GIVEN user является организатором клуба A и участником клубов B, C
WHEN открывает `/my-clubs`
THEN видит секцию «Мои клубы» с тремя cells: A (subtitle «Организатор»), B (subtitle «Участник»), C (subtitle «Участник»)
AND секция «Мои заявки» работает как раньше.

### AC-6: тап на organizer-club → manage; на member-club → public ClubPage
GIVEN user находится на `/my-clubs` со списком из AC-5
WHEN тапает по клубу A (organizer)
THEN `navigate('/clubs/A/manage')` + `haptic.impact('light')`
WHEN тапает по клубу B (member)
THEN `navigate('/clubs/B')` + `haptic.impact('light')`.

### AC-7: «+ Создать клуб» доступен на `/my-clubs`
GIVEN user на `/my-clubs`
WHEN тапает Section-кнопку «+ Создать клуб» (в верхней `<Section>` страницы)
THEN открывается `<Modal>` с `CreateClubModal` (5-шаговый wizard, идентично прежнему OrganizerPage до этого PR)
AND поведение модалки (валидация, submit, haptic, redirect на `/clubs/:id/manage` после создания) — без изменений.

### AC-8: empty state когда нет ни клубов, ни заявок
GIVEN новый user без membership и заявок
WHEN открывает `/my-clubs`
THEN видит **один** `<Placeholder>` с текстом «Вы пока не состоите ни в одном клубе. Найдите интересный в Поиске или создайте свой выше.» (combined — отдельных placeholder'ов на пустые «Мои клубы» и «Мои заявки» рядом не появляется)
AND Section-кнопка «+ Создать клуб» видна сверху.

## Non-functional

- **Bundle:** `EventsPage` eager-imported (как остальные tab-страницы — см. `router.tsx:5-9`). Размер < 1 KB. `CreateClubModal` остаётся eager (импортируется в `MyClubsPage`, а не lazy — модалка может открываться сразу после загрузки app, lazy создаст mini-задержку при первом открытии).
- **Безопасность:** изменений нет. Endpoint'ы и авторизация — те же.
- **Логирование:** новых событий нет.

## Зависимости

- `useMyClubsQuery()` в `frontend/src/queries/clubs.ts:38` — уже возвращает `MembershipDto[]` с полем `role`. Никаких backend-изменений.
- `useCreateClubMutation()` там же — без изменений.
- `useMyApplicationsQuery()` — без изменений.
- Telegram-UI: `Placeholder`, `List`, `Section`, `Cell`, `Modal`, `Button` — все уже используются.

## Риски и открытые вопросы

### R-1: pending applications в списке
Сейчас `MyClubsPage` показывает заявки в **отдельной секции** «Мои заявки» (строки 101-121). Сохраняем эту логику — заявки **не** мерджим в общий список с членствами (разные семантики: заявка = ожидание, членство = доступ). Это уже работает, не трогаем.

### R-2: lazy vs eager import EventsPage
В `router.tsx` основные tab-страницы — eager (комментарий `// Main tab pages — eagerly imported for instant tab switching`). Делаем `EventsPage` тоже eager для консистентности и instant switching. Когда EventsPage обрастёт queries — пересмотреть.

### R-3: дизайн FAB до design-итерации
Текущая рекомендация — простой inline-style FAB (Telegram-UI не имеет готового FAB-компонента). Если дизайнер выкатит другой паттерн «создать что-то» — переделаем в одной точке (`MyClubsPage`).

### R-4: что если у user удалился клуб где он organizer
Existing behaviour: `OrganizerClubManage.handleDelete` редиректит на `/my-clubs` с Toast. Эта логика уже в `MyClubsPage` (`navState?.toast`, строки 47-54). Сохраняется без изменений.

## Связанные документы

- [`docs/modules/ui-pages.md`](./ui-pages.md) — TASK-030 (deprecated после этого PR)
- [`docs/modules/haptic.md`](./haptic.md) — обновляется (см. таблицу выше)
- [`docs/modules/create-club-form.md`](./create-club-form.md) — RHF-форма, без изменений в этом PR
- [`PRD-Clubs.md`](../../PRD-Clubs.md) — bottom navigation структура
- Memory: `project_clubs_over_events_rationale` — почему «События» только из моих клубов
