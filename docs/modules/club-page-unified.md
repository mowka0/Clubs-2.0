# Club Page Unified — единая страница клуба для всех ролей

> **Update (post `feature/profile-reputation-and-skladchina-badge`, 2026-05-30):**
> таб **«Мой профиль»** удалён из карточки клуба. `ClubProfileTab.tsx` удалён.
> Per-club self-view репутации (мои метрики в этом клубе) переехал в глобальную
> секцию «Моя репутация» в [`ProfilePage`](./profile.md) (одна карточка на клуб
> с индексом надёжности и компактной строкой метрик). Полные метрики (обещания
> % / подтверждения / посещения) по-прежнему доступны через таб «Участники»
> → тап на себя → `MemberProfileModal`. TabId сократился до `'activities' |
> 'members'`. Все упоминания «Мой профиль» в этой спеке ниже — **исторический
> контекст**. Актуальные табы member-view: Активности / Участники (+
> Управление у организатора). Полная спека профиля + новых эндпоинтов
> (`/me/reputation`, `/me/interests`, `PATCH /me`, `/interests/suggest`,
> миграция V16) — [`profile.md`](./profile.md).
>
> **Update (post `feature/unified-activity-creation`, 2026-05-24):** таб
> `События` переименован в `Активности` и теперь содержит unified-ленту
> events + skladchinas (read-only для member, без `+ Создать`). Лента разрезана
> на секцию `Предстоящие` (полные карточки) + сворачиваемый аккордеон
> `Прошедшие (N)` (компактные строки); без пагинации. Компонент
> `ClubEventsTab.tsx` удалён, его место занял `ClubActivitiesTab.tsx`. Все
> прежние упоминания «events tab», `<TabId='events'>`, `ClubEventsTab` ниже
> остаются как **исторический контекст** этой спеки. Актуальный TabId —
> `'activities'`. Полная спека — [`unified-activity-creation.md`](./unified-activity-creation.md).
>
> **Update round 4 (2026-05-24):** карточки активностей (`ActivityCard`) теперь
> с фото-thumbnail слева (`ActivityThumb`, placeholder при отсутствии — позже
> убран в Banco-редизайне) и type-иконкой
> в правом верхнем углу; событиям добавлено фото (миграция V15). Аккордеон
> `Прошедшие` теперь анимируется (grid-rows transition). `ClubActivitiesTab`
> остаётся read-only без `+ Создать` — создание активностей переехало на
> глобальную `ActivitiesPage` (`/events`), доступно организаторам.

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
| **Organizer** | Всё что у member + шестерёнка в шапке (вход на `/clubs/:id/manage`; до 2026-07-30 — таб «Управление»). Контент управления **не интегрируется в страницу** — только точка входа меняется. |

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

**Переписан 2026-07-30 (дизайн-итерация PO).** Обложка чистая: ни названия, ни характеристик
поверх картинки. Всё текстовое живёт на странице, на стык наезжает аватар клуба.

```
.rd-page
  .rd-hero.rd-compact.rd-club-cover   ← обложка: только фон + одна кнопка роли (шестерёнка/выход)
  .rd-club-avatar                     ← аватар 70px кругом, наезжает на стык (margin-top -34px)
  .rd-club-name                       ← название клуба, 30px/800
  .rd-club-facts                      ← чипы одной строкой: доступ · город · N/limit · взнос

  {owner, чат не привязан}            ← панель «Подключи чат клуба» (club-chat-link.md);
                                        сверху тот же шаг 20px, что у заголовков секций

  .rd-section-sub-h «О клубе»         ← ярлык секции + волосяная линейка
  .rd-club-about                      ← описание + правила + пилюля «В чат» в углу блока

  {all viewers}                       ← .rd-section-sub-h «Жизнь клуба» + кольца (club-quality.md)

  {visitor-only}                      ← замок «Активности доступны участникам» + тизер-афиша
                                        + чип про чат + CTA («Вступить» / «Хочу вступить»)

  {member|manager}                    ← .rd-seg сегментный переключатель (Активности / Участники)
                                        + содержимое активного таба
```

### Header — visible всем

Обложка (`.rd-hero.rd-compact.rd-club-cover`) несёт **только** фон и кнопки в правом верхнем
углу. Фон — `club.coverUrl` (**отдельное поле от аватара**, V70; до этого хиро растягивал
`avatarUrl`, и смена аватара молча меняла обложку), фолбэк — градиент по `data-cat`.
Кнопки (`.rd-hero-acts`, флекс-пара с зазором 9px): менеджеру — камера смены обложки
(`ClubCoverButton`) плюс шестерёнка «Управление»; участнику — только выход из клуба
(см. § «Organizer tabs»). Скругление снизу 30px, обложка уходит под плавающие контролы
Telegram (см. § «Врезка Telegram» ниже). Ошибка загрузки обложки — плашка `.rd-hero-err`
под кнопками, поверх картинки.

Ниже обложки, уже на странице:
- `ClubAvatarButton` (`.rd-club-avatar`) — `club.avatarUrl` кругом 70px с оконтовкой 4px цветом
  фона; без картинки — первая буква названия на брендовом градиенте. **Менеджеру кружок
  кликабелен** (значок камеры в углу): тап → выбор файла → `POST /api/upload` →
  `PUT /api/clubs/{id}` с одним полем `avatarUrl`. Валидация (JPEG/PNG, ≤5 МБ) — общая
  `utils/imageUpload.ts`, зеркалит `StorageController`. Снять аватар можно только в
  «Управление → Настройки»: тап по кружку не должен требовать промежуточного меню;
- `.rd-club-name` — название, 30px/800/−0.04em;
- `.rd-club-facts` — четыре чипа **строго в одну строку**: `ACCESS_LABELS[accessType]`, город
  (📍, единственный сжимаемый чип — обрезается многоточием), `memberCount / memberLimit`,
  `formatPrice(subscriptionPrice)`. Взнос цветной: `--accent` у платного, `--live` у бесплатного.

**Бейджа роли нет.** «Вы участник» / «Вы организатор» снят 2026-07-30 — PO счёл его мусором.
Роль читается по кнопке в шапке и по наличию табов.

### Врезка Telegram — обложка под контролами

В полноэкранном режиме Telegram рисует статус-бар и «Назад / ⌄ / ⋯» **поверх** страницы, поэтому
`.rd-page` держит сверху отступ `--app-inset-top`. Обложка — единственный блок, которому этот
отступ вреден: она гасит его отрицательным margin и возвращает ту же величину себе в `height`,
поэтому видимая часть картинки не съедается. Кнопки внутри обложки опущены на ту же врезку,
а полоса врезки затемняется градиентом кратной высоты (иначе белые контролы теряются на светлой
обложке). Вне полноэкранного режима врезка = 0 и вёрстка не меняется. Правило общее для всех
трёх экранов с обложкой: страница клуба, страница события, «Управление».

### About — visible всем

Один блок `.rd-club-about` (заливка + рамка 1px, радиус 22px, **высота строго по содержимому**):
- `club.description` прозой 13.5px;
- `club.rules` — если заданы: волосяная линейка, капс-ярлык «ПРАВИЛА», текст той же прозой.
  Отдельной секции «Правила» больше нет (объединено 2026-07-30);
- пилюля «💬 В чат» — участнику с доступом, прижата к нижнему правому углу границы блока
  (`.rd-club-chatrow` гасит отступы карточки отрицательными margin; зазор от текста до кнопки —
  20px). Заливка пилюли = цвет фона страницы, поэтому она читается вырезанной в карточке.

Состав и взнос переехали в чипы под названием (см. § Header).

### Качество клуба — visible всем (post `feature/club-quality-foundation`, 2026-06-17)

После блока «О клубе» — **единый блок качества** (`ClubQualityFacts`,
`components/club/ClubQualityFacts.tsx`) под ярлыком секции **«Жизнь клуба»** (ярлык живёт внутри
компонента: блок сам скрывается при пустом ответе, и в родителе ярлык висел бы над пустотой).
Внутри всё по центру (дизайн-вариант «кольца + лёгкая строка», 2026-06-17; кольца подтверждены
PO 2026-07-30 против варианта «крупные цифры с линейками»). Сверху — три донат-кольца `QualityRing` (4 сектора, центр =
distinct-абсолют): **основа клуба** (ядро) · **частота встреч** · **обычно приходит** («из M», M = `memberCount`).
Под ними — разделитель и строка-капшн (через точку, по центру): возраст-бейдж (золотой) + живые счётчики
«N встреч»/«N сборов» (fact-backed, без очков/порогов). Клуб без событий → только строка «🎂 Клубу N мес ·
пока нет встреч». Данные — `GET /api/clubs/{clubId}/quality` (модуль `clubquality`); fail-soft. Полная спека —
[`club-quality.md`](./club-quality.md). В проде: Фундамент (#69) + Кольца (#70); единый блок + счётчики — текущий
срез. Owner-«Статистика» (приватная панель управления, `GET /api/clubs/{clubId}/stats`) — реализована
(см. [`club-quality.md`](./club-quality.md) §9). L3-ранг — построен v1 (за фиче-флагом, default off); soft-ранг
«★ Топ-5 в категории» показывается **только на карточке Discovery, не на странице клуба** (см. §10).

### Чат клуба — вне табов (post `club-chat-link`)

Страница несёт три чат-элемента, все — вне табов и вне блока качества: панель
«Подключи чат клуба» владельцу непривязанного клуба (**до** секции «О клубе»), пилюлю
«💬 В чат» участнику с доступом (**внутри** блока «О клубе», прижата к его нижнему правому
углу — переехала туда 2026-07-30 из широкой кнопки под карточкой) и чип «У клуба есть чат…»
гостю (в visitor-блоке CTA). Условия показа, тексты, хаптика и правила скрытия панели —
[`club-chat-link.md`](./club-chat-link.md); сводная таблица — [`ui-pages.md`](./ui-pages.md)
§ «Чат-блоки на странице». Здесь не дублируем.

### Visitor-specific

```
<Section>
  <Placeholder description="События доступны участникам клуба" />
</Section>

<Section>
  {renderJoinButton()}      ← существующая логика из ClubPage.tsx:136-185
</Section>
```

`renderJoinButton()` сохраняется в полном объёме (open → «Вступить»; closed → «Хочу вступить»; pending application → disabled; approved + платный клуб → «Ожидаем оплату» disabled; approved + бесплатный клуб (legacy stuck) → активная кнопка «Завершить вступление» — см. ниже; pending payment — disabled). Логика не меняется; убирается только ветка `if (isMember)` и `if (isOrganizer)` — для них CTA не рендерится вовсе (статус в header).

**Approved + free club «Завершить вступление» (post-flight 2026-05-31):** если у visitor'а есть approved-заявка и `club.subscriptionPrice <= 0` — рендерится активная кнопка «Завершить вступление» вместо disabled «Ожидаем оплату». Это recovery-state для легаси-данных, где `ApplicationService.approveApplication` для бесплатного клуба не создал membership (старая версия, ручное вмешательство в БД). Клик зовёт `POST /api/applications/{id}/complete-free-membership` — backend идемпотентно создаёт membership (счётчик участников не пишется — он считается на лету из `memberships`, колонка `member_count` дропнута в V33; новая active-строка автоматически входит в live-счёт). На успех страница перерисуется как `member` (через инвалидацию `clubs.my()` и `clubs.detail(clubId)`). См. `docs/modules/applications-inbox.md` §`POST /api/applications/{id}/complete-free-membership`.

### Member tabs

```
<TabsList>
  <TabsList.Item selected={activeTab === 'events'} onClick={...}>События</TabsList.Item>
  <TabsList.Item selected={activeTab === 'members'} onClick={...}>Участники</TabsList.Item>
  <TabsList.Item selected={activeTab === 'profile'} onClick={...}>Мой профиль</TabsList.Item>
</TabsList>

{activeTab === 'events'  && <ClubEventsTab clubId={id} />}
{activeTab === 'members' && <ClubMembersTab clubId={id} isOrganizer={isOrganizer} />}
{activeTab === 'profile' && <ClubProfileTab clubId={id} userId={user.id} />}
```

`useState<TabId>('events')` — default «События».

### Organizer tabs

**Изменено 2026-07-30 (решение PO).** Отдельного таба «Управление» больше нет — набор табов
у менеджера и участника одинаковый (`Активности` / `Участники`). Вход в `/clubs/:id/manage`
переехал в **шестерёнку в шапке**, на то же место, где у участника кнопка выхода из клуба:

```tsx
{isManager ? (
  <button className="rd-hero-btn rd-right" onClick={handleOpenManage} aria-label="Управление клубом">
    <ManageIcon />
  </button>
) : showLeaveIcon ? (
  <button className="rd-hero-btn rd-right" onClick={handleOpenLeaveModal} aria-label="Выйти из клуба">
    <LeaveIcon />
  </button>
) : null}
```

Кнопка одна на обе роли — они не пересекаются: владелец из клуба не выходит, у участника нет
управления. Haptic сохранён прежний: `impact('light')` (навигация), не `select()`.

Заодно снят бейдж роли с обложки (`Вы участник` / `Вы организатор`) — PO счёл его мусором;
`roleBadgeLabel` удалён, тип `TabKey` схлопнут обратно в `TabId`.

> **История решения.** Раньше «Управление» было табом-ссылкой: таб визуально никогда не
> `selected`, а по тапу уводил на отдельную страницу. Trade-off признавался прямо в этой спеке —
> «tab-как-link нарушает ожидание UX, tap по табу обычно меняет содержимое; если в design-итерации
> окажется confusing — переделаем, точка изменения локальная». Так и вышло: переделано в
> иконку-действие, где ожидание «тап уводит на другой экран» естественно.

## Tabs реализация

**[ОБНОВЛЕНО 2026-07-30]** На странице клуба tabs — **сегментный переключатель**
`<button className="rd-seg-btn">` в `<div className="rd-seg">`: вдавленная дорожка, активный
сегмент выпуклый. Это одно из двух мест, где применён неоморфизм (второе — круглые кнопки на
обложке); оба источника света живут в токенах `--neu-lo` / `--neu-hi` и переопределены для светлой
темы, где блик должен быть чистым белым. Стили — `frontend/src/styles/redesign.css`
§ «Сегментный переключатель». **Переключатель в приложении один** (2026-07-30): те же классы
в «Активностях» (События/Сборы), шторке взноса (СБП/Наличными) и «Управлении клубом»
(4 таба — влезают в 390px без переноса). Underline-табы `rd-tabs` / `rd-tab-link` удалены
как класс. Таба «Управление» на странице клуба больше нет (см. § Organizer tabs).
См. [`ui-pages.md`](./ui-pages.md) § OrganizerClubManage и [`redesign-banco-style.md`](./redesign-banco-style.md) Этап 4.

Аналогично header, about, rules, locked, CTA, events, members, profile — вынесены из telegram-ui-компонентов (`List/Section/Cell/Badge/Placeholder/Button`) в brand-классы `.cp-*` для единства с DiscoveryPage (PR #33). `Modal/Input/Spinner` для apply-modal — остались.

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
AND если caller — organizer и есть applicants с approved-but-unpaid статусом, **перед** списком участников отображается секция «Ожидают оплаты · N» (см. `applications-inbox.md` § `GET /api/clubs/{clubId}/awaiting-payment-applicants`). Член клуба этой секции не видит — backend возвращает 403, фронт не делает запрос.

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
**[ЗАКРЫТ] (design-итерация 2026-07-30):** оказался лишним, снят. Раньше был
`<Badge type="number" mode="primary">` рядом с чипами в header; PO счёл его мусором на обложке.
Опасение «новый member потеряется — а вступил ли я?» не подтвердилось: роль читается по кнопке
в шапке (шестерёнка у менеджера, выход у участника) и по наличию табов, которых у гостя нет.

### R-2: tab «Управление» как link, а не toggle — UX-ambiguity
**[ЗАКРЫТ] (design-итерация 2026-07-30):** таб-ссылка переделана в шестерёнку в шапке — ожидание
«тап уводит на другой экран» для иконки-действия естественно, а тактильное отличие
(`impact` вместо `select`) перестало быть единственным сигналом перехода. Подробности и код —
§ «Organizer tabs». Точка изменения оказалась ровно такой локальной, как и предполагалось.

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
