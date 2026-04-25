# Club Page Unified — единая страница клуба для всех ролей

## Цель

Сейчас один и тот же клуб имеет **три разные frontend-страницы** в зависимости от роли user'а:

1. `/clubs/:id` (`ClubPage.tsx`, ~280 строк) — visitor view + join/apply. Member видит то же самое что visitor + disabled-кнопка «Вы участник ✓». События / участники / профиль — НЕ видны отсюда.
2. `/clubs/:id/interior` (`ClubInteriorPage.tsx`, ~290 строк) — member tabs (События / Участники / Мой профиль). **Из UI до неё практически не добраться** — half-implemented dead-end.
3. `/clubs/:id/manage` (`OrganizerClubManage.tsx`, ~1100 строк) — admin tabs (Members / Applications / Events / Finances / Settings). Доступна организатору.

Это fragmented UX: member, открыв клуб через MyClubsPage, попадает на visitor-view и не видит контент клуба, в котором состоит. Текущий fix частично делает MyClubsPage (organizer → `/manage`, member → `/clubs/:id`), но landing для member — всё равно visitor-страница.

Этот PR унифицирует точку входа `/clubs/:id` под одну страницу с **role-aware visibility**: каждая роль видит один и тот же header/about + дополнительный role-специфичный контент. `OrganizerClubManage` остаётся отдельной страницей (1100 строк управления — за рамками этого PR), но точка входа в неё перестаёт быть отдельной кнопкой и становится tab «Управление» внутри unified `ClubPage`.

## Product rationale

Решение по visibility основано на принципе «visitor видит ровно столько, сколько нужно для решения вступать или нет; member получает доступ к жизни клуба».

| Роль | Что видит |
|---|---|
| **Visitor** (вариант B, минимальная видимость) | Header + about + CTA. **Не видит** список событий, не видит список участников. Вместо событий — placeholder «События доступны участникам клуба». Это снижает «утечку контента» закрытых сообществ без полной изоляции. |
| **Member** | Тот же header + about + tabs [События / Участники / Мой профиль]. Контент мигрирует из `ClubInteriorPage`. CTA «Вступить» отсутствует (вместо неё — статус в header). |
| **Organizer** | Всё что у member + дополнительный tab «Управление» (link на `/clubs/:id/manage`). Контент управления **не интегрируется в страницу** — только точка входа меняется. |

Вариант B (vs A — «visitor вообще ничего не видит» / vs C — «visitor видит участников и события публично») выбран как баланс: hide-by-default защищает закрытые клубы, но header + описание + правила + цена остаются — без них visitor не примет решение вступать.

## Scope

### Входит

- Refactor `frontend/src/pages/ClubPage.tsx` в unified shell с role-aware tabs.
- Удаление `frontend/src/pages/ClubInteriorPage.tsx` (контент мигрирует в tab-компоненты).
- Обработка legacy-route `/clubs/:id/interior` в `frontend/src/router.tsx` — redirect на `/clubs/:id` (см. § «Legacy route»).
- Вынос tab-контента в отдельные компоненты `frontend/src/components/club/ClubEventsTab.tsx`, `ClubMembersTab.tsx`, `ClubProfileTab.tsx` (см. § «Файловая структура»).
- Обновление `frontend/src/test/pages/ClubPage.test.tsx` под новую структуру (visitor / member / organizer ветки).
- Обновление `docs/modules/ui-pages.md` (раздел про ClubPage) и `docs/modules/haptic.md` (миграция точек кода для ClubInteriorPage → новые tab-компоненты).

### НЕ входит

- Реализация `OrganizerClubManage` (остаётся как есть, 1100 строк, своя страница на `/clubs/:id/manage`).
- Реализация tab-страницы `/events` (placeholder из PR #27, не трогаем).
- Любые backend-изменения — все нужные queries уже есть (`useClubQuery`, `useMyClubsQuery`, `useClubMembersQuery`, `useClubEventsQuery`, `useMemberProfileQuery`).
- Визуальный дизайн tabs (форма, отступы, иконки) — для design-итерации после.
- Новые user actions (например, write-permissions для member внутри events tab) — только перенос текущего read-only контента.

## Маппинг изменений по файлам

| Файл | Действие | Что меняется |
|---|---|---|
| `frontend/src/pages/ClubPage.tsx` | EDIT | Refactor: header + about (visible всем) + role-aware tabs/CTA. Размер не должен превышать 200 строк за счёт выноса tab-контента (см. ниже). |
| `frontend/src/pages/ClubInteriorPage.tsx` | DELETE | Контент мигрирует в три tab-компонента. Файл удаляется полностью. |
| `frontend/src/components/club/ClubEventsTab.tsx` | NEW | Контент events-tab из `ClubInteriorPage` строки 129-175 (upcoming + past sections, navigate to `/events/:id`). Принимает `clubId: string`. |
| `frontend/src/components/club/ClubMembersTab.tsx` | NEW | Контент members-tab из `ClubInteriorPage` строки 178-210 (список participants с avatar/reliability). Принимает `clubId: string`. |
| `frontend/src/components/club/ClubProfileTab.tsx` | NEW | Контент profile-tab из `ClubInteriorPage` строки 213-283 (reputation, stats). Принимает `clubId: string, userId: string`. |
| `frontend/src/router.tsx` | EDIT | Удалить lazy-import + route `/clubs/:id/interior`; добавить redirect-route `<Navigate to="/clubs/:id" replace />` сохраняющий `:id` (см. § «Legacy route»). |
| `frontend/src/test/pages/ClubPage.test.tsx` | EDIT | Добавить тесты на: member view (tabs visible, default «События»), organizer view (extra tab «Управление» + navigate), visitor variant B (placeholder вместо событий). Существующие visitor-тесты сохранить. |
| `docs/modules/ui-pages.md` | EDIT (post-flight) | Обновить раздел «4.1.3 Страница клуба» / убрать отдельное описание `ClubInteriorPage` если есть. Сослаться на этот документ. |
| `docs/modules/haptic.md` | EDIT (post-flight) | Перемап `pages/ClubInteriorPage.tsx` строки 109-121 → новые tab-компоненты. Точки кода: `setActiveTab` (`select()` остаётся), tap event-Cell (`impact('light')` остаётся), новый tap «Управление»-tab (`impact('light')` — это link, не tab-content). |
| `docs/design/stack.md` | EDIT (post-flight) | Удалить строку про `/clubs/:id/interior` из таблицы routes (185); удалить § 7.6 ClubInteriorPage; обновить § 7.5 ClubPage до unified-структуры. |

## Файловая структура

```
frontend/src/
├── pages/
│   ├── ClubPage.tsx                  ← refactor: shell + role-aware visibility
│   └── ClubInteriorPage.tsx          ← DELETE
└── components/
    └── club/                         ← NEW directory
        ├── ClubEventsTab.tsx
        ├── ClubMembersTab.tsx
        └── ClubProfileTab.tsx
```

> **[Решение по выносу tabs]:** выносим в `components/club/`, не в `features/clubs/components/` (несмотря на правило `frontend.md` § «Структура — feature-based»). Причина: текущий проект не использует feature-folders нигде (`pages/` + `components/` + `queries/` + `hooks/` — flat). Заводить feature-folder только под три файла одной страницы — нарушение KISS / YAGNI. Когда проект мигрирует на feature-структуру (отдельным PR / на design-итерации) — `components/club/` переедет в `features/clubs/components/` за один move. Развилка локализована.

## Структура unified ClubPage

### Layout (рендерится всем)

```
<List>
  <Section>          ← Header (avatar, name, badges, location)
    + role-status badge (member/organizer) — см. § «Role status display»
  </Section>
  <Section>          ← About (member count, price, description, rules)
  </Section>

  {visitor-only}     ← Placeholder «События доступны участникам клуба»
                       + CTA Section («Вступить» / «Хочу вступить»)

  {member|organizer} ← <TabsList> с role-aware items
                       + tab content (через switch по activeTab)
</List>
```

### Header — visible всем

Поля как в текущем `ClubPage.tsx:188-205`:
- avatar (если есть) или emoji `🏠` 80x80
- name (Text weight=1, 20px)
- badges: category, accessType (см. `CATEGORY_LABELS`, `ACCESS_LABELS`)
- city / district (`Cell` с subtitle)

**Role status display** (single source of truth): для member и organizer вместо CTA-кнопки внизу — текстовый бейдж в header-секции рядом с category/accessType-чипами:
- Member: `<Badge type="number" mode="primary">Вы участник</Badge>`
- Organizer: `<Badge type="number" mode="primary">Вы организатор</Badge>`
- Visitor: ничего (CTA внизу).

### About — visible всем

`<Section>` с теми же `<Cell>`-ями что в текущем `ClubPage.tsx:208-228`:
- member count `N / max`
- price (`formatPrice(subscriptionPrice)`)
- description (block, lineHeight 1.5)
- rules (block, hint color) — если `club.rules`

### Visitor-specific

```
<Section>
  <Placeholder description="События доступны участникам клуба" />
</Section>

<Section>
  {renderJoinButton()}      ← существующая логика из ClubPage.tsx:136-185
</Section>
```

`renderJoinButton()` сохраняется в полном объёме (open → «Вступить»; closed → «Хочу вступить»; pending application → disabled; approved → «Ожидаем оплату»; pending payment — disabled). Логика не меняется; убирается только ветка `if (isMember)` и `if (isOrganizer)` — для них CTA не рендерится вовсе (статус в header).

### Member tabs

```
<TabsList>
  <TabsList.Item selected={activeTab === 'events'} onClick={...}>События</TabsList.Item>
  <TabsList.Item selected={activeTab === 'members'} onClick={...}>Участники</TabsList.Item>
  <TabsList.Item selected={activeTab === 'profile'} onClick={...}>Мой профиль</TabsList.Item>
</TabsList>

{activeTab === 'events'  && <ClubEventsTab clubId={id} />}
{activeTab === 'members' && <ClubMembersTab clubId={id} />}
{activeTab === 'profile' && <ClubProfileTab clubId={id} userId={user.id} />}
```

`useState<TabId>('events')` — default «События».

### Organizer tabs

То же что у member + дополнительный tab «Управление». **Особый case**: tab «Управление» — это **navigate-link**, не tab-content. При tap не меняется `activeTab`, а вызывается `navigate('/clubs/:id/manage')`. Переключатель внутри `onClick`:

```ts
const handleTabClick = (tab: TabId | 'manage') => {
  if (tab === 'manage') {
    haptic.impact('light');
    navigate(`/clubs/${id}/manage`);
    return;
  }
  haptic.select();
  setActiveTab(tab);
};
```

Визуально tab «Управление» рендерится как обычный `<TabsList.Item selected={false}>` — он никогда не selected, так как это link. Отличий в UI нет; различие только в обработчике.

> **[Решение про tab «Управление» как link, не toggle]:** альтернатива — отдельная Section-кнопка «Управление клубом» внизу страницы (как сейчас в строке 137). Tab выбран потому что: (1) визуально объединяет все organizer-actions в одну строку controls; (2) минимизирует вертикальный shift при role-switch (visitor vs member vs organizer не меняют layout кроме content tabs); (3) согласовано с тем что organizer всё равно использует /manage чаще чем events/members tabs (это его primary action). Trade-off: tab-как-link нарушает ожидание UX (tap на tab обычно меняет content). Mitigation: haptic `impact('light')` (как navigation), не `select()` (как tab-switch) — отличие тактильно ощутимо. Если в design-итерации это окажется confusing — переделаем в Section-кнопку, точка изменения локальная.

## Tabs реализация

Используется `TabsList` / `TabsList.Item` из `@telegram-apps/telegram-ui`, как уже сделано в `OrganizerClubManage.tsx:1009-1020` и в текущем `ClubInteriorPage.tsx:106-126`. Никаких новых UI-зависимостей.

### Haptic mapping (preserved from ClubInteriorPage)

| Действие | Haptic | Reason |
|---|---|---|
| Tap по tab «События / Участники / Мой профиль» (смена `activeTab`) | `select()` | Смена секции — selectionChanged (как в ClubInteriorPage:109/115/121) |
| Tap по tab «Управление» (navigate на `/manage`) | `impact('light')` | Открытие nested-страницы — light, как navigation-cell. Отличается от `select()` — пользователь чувствует что это переход, а не переключение |
| Tap по event-Cell внутри events-tab → `navigate('/events/:id')` | `impact('light')` | Перенос из ClubInteriorPage:142,166 |
| Tap «Вступить» (visitor open) | `impact('medium')` старт + `notify('success'/'error')` | Перенос из ClubPage.tsx handleJoin (sustained) |
| Tap «Хочу вступить» (visitor closed) → open modal | `impact('light')` (open modal) | Перенос из ClubPage.tsx |
| Submit заявки в modal | `impact('medium')` старт + `notify('success'/'error')` | Перенос из ClubPage.tsx handleApply |

Все паттерны — продолжение уже задокументированных в `docs/modules/haptic.md`.

## Legacy route `/clubs/:id/interior`

**[LOCKED] (post-flight 2026-04-25): Redirect (вариант A).** Реализовано в `frontend/src/router.tsx:25-30,67-69`. `InteriorRedirect` — 4-строчный FC, читает `useParams<{id: string}>()` и возвращает `<Navigate to={`/clubs/${id}`} replace />`. `replace` — чтобы старый URL не оставался в history. Тест в `frontend/src/test/router/interiorRedirect.test.tsx` (mirror компонента, потому что router.tsx подтягивает eager-pages → не подходит для unit-теста).

```tsx
// router.tsx
{
  path: '/clubs/:id/interior',
  element: <InteriorRedirect />,
},
```

Альтернатива (B — 404) отклонена: ломает UX для bookmarks / deep-links. Функциональность не пропала — просто переехала. Симметрично решению `/organizer` → `/my-clubs` redirect в `my-clubs-unified.md`.

## Acceptance Criteria

### AC-1: build & test green
GIVEN ветка `feature/unified-club-page` с реализованными изменениями
WHEN `npm run build && npm test` в `frontend/`
THEN exit 0; нет TypeScript-ошибок; обновлённый `ClubPage.test.tsx` зелёный.

### AC-2: visitor видит about + minimum-info + CTA, без событий/участников
GIVEN user НЕ состоит в клубе и НЕ owner клуба
WHEN открывает `/clubs/:id` (open-club, accessType='open')
THEN видит header (avatar, name, category/access badges, city) + about-секцию (member count, price, description, rules)
AND **не** видит TabsList
AND видит `<Placeholder description="События доступны участникам клуба" />`
AND видит `<Button>Вступить</Button>` внизу
AND **не** видит ни одного `<Cell>` участника или `<Cell>` события.

### AC-3: visitor closed-club видит «Хочу вступить»
GIVEN user НЕ состоит в клубе с accessType='closed'
WHEN открывает `/clubs/:id`
THEN кнопка-CTA — «Хочу вступить»
AND нажатие открывает apply-modal с вопросом организатора (если задан) — поведение идентично текущему `ClubPage.tsx`.

### AC-4: member видит tabs со «Событиями» по умолчанию
GIVEN user — member клуба (membership.status='active', role='member')
WHEN открывает `/clubs/:id`
THEN видит header + about
AND **не** видит CTA-кнопку внизу
AND видит badge «Вы участник» в header
AND видит `<TabsList>` с тремя items: «События», «Участники», «Мой профиль»
AND active tab — «События»
AND рендерится `ClubEventsTab` с upcoming events (статусы upcoming / stage_1 / stage_2) и past events (max 5).

### AC-5: member tap по tab «Участники» → меняется content
GIVEN user — member, открыта tab «События»
WHEN тапает «Участники»
THEN haptic `select()`
AND active tab — «Участники»
AND рендерится `ClubMembersTab` со списком members (avatar, name, reliability, badge «Организатор» для role='organizer').

### AC-6: organizer видит дополнительный tab «Управление»
GIVEN user — owner клуба (`club.ownerId === user.id`) ИЛИ membership.role === 'organizer'
WHEN открывает `/clubs/:id`
THEN видит header + about
AND видит badge «Вы организатор» в header
AND видит `<TabsList>` с **четырьмя** items: «События», «Участники», «Мой профиль», «Управление»
AND active tab — «События» (default)
AND tab «Управление» **никогда не selected**.

### AC-7: organizer tap по tab «Управление» → navigate на /manage
GIVEN organizer на `/clubs/:id` с visible tabs
WHEN тапает «Управление»
THEN haptic `impact('light')` (НЕ `select()`)
AND `navigate('/clubs/:id/manage')` сработал
AND `activeTab` НЕ изменился (если вернётся обратно — будет та же tab что была).

### AC-8: route `/clubs/:id/interior` редиректит на `/clubs/:id`
GIVEN пользователь открывает URL `/clubs/abc-123/interior` (старая закладка / deep-link)
WHEN router резолвит маршрут
THEN происходит redirect на `/clubs/abc-123`
AND `replace: true` — `/interior` не остаётся в history (back-button с `/clubs/abc-123` ведёт куда был до открытия закладки, не в `/interior`)
AND страница рендерится в режиме определяемом ролью user'а (visitor / member / organizer).

### AC-9: haptic preserved
GIVEN unified ClubPage в production
WHEN Tester проходит сценарии:
- (a) visitor тапает «Вступить» в open-клубе и операция успешна
- (b) visitor тапает «Хочу вступить» в closed-клубе → открывается modal
- (c) member тапает по tab «Участники»
- (d) member тапает по event-Cell в events-tab
- (e) organizer тапает по tab «Управление»
THEN ощущается haptic:
- (a) `impact('medium')` на клик + `notify('success')`
- (b) `impact('light')` (open modal)
- (c) `select()` (смена секции)
- (d) `impact('light')` (navigation)
- (e) `impact('light')` (navigation, не select)

### AC-10: сохранение существующих visitor-тестов
GIVEN существующий `ClubPage.test.tsx` (407 строк, 11 тестов)
WHEN запускается обновлённый файл
THEN все существующие visitor-сценарии (open join, closed apply, pending application, approved/payment, error) проходят без логических изменений
AND **только** ветка organizer-тестов (`'shows "Управление клубом" button when ownerId matches'`) переписана: вместо `<Button>` ищется `<TabsList.Item>Управление</TabsList.Item>` и tap по нему ведёт на `/clubs/:id/manage`.

## Non-functional

- **Производительность:** на role-switch не делается дополнительных API-запросов visitor-режиме (`useClubMembersQuery` / `useClubEventsQuery` / `useMemberProfileQuery` не enabled когда tabs не рендерятся). Member-режим: те же три запроса что сейчас в `ClubInteriorPage`. Bundle: `ClubInteriorPage.tsx` (~290 строк) удаляется, добавляются 3 tab-компонента (~100 строк суммарно за счёт выноса хелперов). Чистый delta — отрицательный.
- **Безопасность:** изменений нет. Endpoint'ы и авторизация — те же. Backend сам гарантирует что `getClubMembers` / `getMemberProfile` доступны только member'ам клуба (не frontend tabs visibility — она UX-only).
- **Логирование:** новых событий нет.
- **Доступность:** на role-switch (например, member вступил → стал organizer через approval flow) — `useMyClubsQuery` invalidate уже происходит в существующих mutations. Страница пересчитает `isMember` / `isOrganizer` автоматически.

## Зависимости

Все queries уже готовы:

- `useClubQuery(id)` — `frontend/src/queries/clubs.ts:45`
- `useMyClubsQuery()` — `frontend/src/queries/clubs.ts:38` (для определения role)
- `useMyApplicationsQuery()` — для visitor с pending application
- `useClubMembersQuery(clubId)` — `frontend/src/queries/members.ts:5`
- `useClubEventsQuery(clubId, params)` — `frontend/src/queries/events.ts:15`
- `useMemberProfileQuery(clubId, userId)` — `frontend/src/queries/members.ts:13`
- `useJoinClubMutation()`, `useApplyToClubMutation()` — `frontend/src/queries/clubs.ts:99,115`

Telegram-UI: `TabsList`, `TabsList.Item`, `Section`, `Cell`, `Placeholder`, `Avatar`, `Badge`, `Modal`, `Button`, `Spinner`, `Input`, `Text`, `List` — все уже используются.

Никаких backend-изменений.

## Risks & open questions

### R-1: badge «Вы участник» / «Вы организатор» в header — визуально лишний?
**[LOCKED] (post-flight 2026-04-25):** реализовано как `<Badge type="number" mode="primary">`, рядом с category/accessType-чипами в header (`ClubPage.tsx:238-240`). Лейбл — `Вы организатор` для organizer (приоритет), `Вы участник` для member, ничего для visitor. Альтернатива «inline-text под name» отложена до design-итерации; до неё — badge как минимально-инвазивный вариант. Без CTA новый member иначе теряется («а вступил ли я?»).

### R-2: tab «Управление» как link, а не toggle — UX-ambiguity
**[LOCKED] (post-flight 2026-04-25):** реализовано в `ClubPage.tsx handleTabClick` (строки 149-157). При `tab === 'manage'` — `haptic.impact('light')` + `navigate(/clubs/:id/manage)`, `setActiveTab` НЕ вызывается, сама tab никогда не selected (`tabItems` push с `selected: false`, строки 219-221). Тактильное отличие (impact vs select) — единственный сигнал перехода. Если на user-тестировании окажется confusing — переделать в Section-кнопку. Точка изменения локальная.

### R-3: дублирование events-tab с будущей global EventsPage
Когда `/events` (placeholder из PR #27) обрастёт логикой «лента upcoming events из всех моих клубов», возникнет дублирование: глобальный список + per-club список внутри `ClubEventsTab`. **Это не дублирование с точки зрения semantic**: глобальная страница агрегирует across clubs, club-tab показывает scope этого клуба + past events (которые в глобальной не нужны). Поэтому оба остаются, но компонент отображения event-Cell стоит вынести в `frontend/src/components/club/EventCell.tsx` для переиспользования. **Не делаем в этом PR** — global EventsPage пока placeholder, нет реальной точки переиспользования (см. KISS / 3+ кейсов из `principles.md`).

### R-4: что отображать visitor'у с pending payment / pending application
Текущий `ClubPage` уже корректно обрабатывает 4 состояния visitor'а: новый, pending application, approved (ожидает оплату), pending payment. Все они НЕ являются «member» (нет `membership.status='active'`), поэтому остаются в visitor-ветке: header + about + placeholder + соответствующий disabled-CTA с пояснением. Это сохраняется без изменений.

### R-5: stack.md outdated info про BottomTabBar на /interior
`docs/design/stack.md:185` говорит что BottomTabBar показан на `/clubs/:id/interior`. Реально — НЕТ (regex в `BottomTabBar.tsx:29` — `/^\/(clubs|events)\/[^/]+(\/manage)?$/` не матчит `/interior`). После удаления `/interior` route эта строка станет N/A — обновить таблицу в post-flight (см. маппинг файлов).

### R-6: что если у user есть membership но status='pending' (заявка одобрена, не оплачено)
По текущему коду `ClubPage.tsx:64`: `isMember = !!membership && membership.status === 'active'`. Pending-membership (если бэкенд такое возвращает) НЕ считается member — visitor view с placeholder для оплаты. Сохраняется без изменений.

## Связанные документы

- [`docs/modules/ui-pages.md`](./ui-pages.md) — общее описание frontend pages (обновляется в post-flight)
- [`docs/modules/haptic.md`](./haptic.md) — таблица haptic-точек (обновляется в post-flight)
- [`docs/modules/my-clubs-unified.md`](./my-clubs-unified.md) — симметричное решение для `/organizer` → `/my-clubs` redirect, включая differentiation по role
- [`docs/design/stack.md`](../design/stack.md) — § 7.5 ClubPage и § 7.6 ClubInteriorPage (обновляются в post-flight)
- [`PRD-Clubs.md`](../../PRD-Clubs.md) §4.1.3 «Страница клуба», §4.3 «Внутренний экран клуба», §7.2 «Ключевые экраны»
- [`.claude/rules/frontend.md`](../../.claude/rules/frontend.md) § «Компоненты» — порог 150-200 строк
