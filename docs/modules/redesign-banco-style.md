# Модуль: Редизайн «Banco-Plata» (этап 1 — ядро)

## Цель
Перевести фронтенд на новый визуальный язык из мокапа `redesign-banco-style.html`:
near-black/off-white палитра + оранжево-розово-фиолетовый акцент, glassmorphism,
плавающий «док» вместо таб-бара, **две темы** (dark/light). Этап 1 покрывает
дизайн-систему, оболочку (темы + док) и экраны **Главная** и **Профиль**.
Остальные экраны (Активности, Мои клубы, Клуб, Событие) переводятся следующими
итерациями; за счёт repoint'а легаси-токенов они уже перекрашены, не «сломаны».

## Дизайн-система

### Темы и токены — `frontend/src/styles/brand-theme.css`
- Семантические токены задаются под две темы через `[data-theme]` на `<html>`:
  `:root` = DARK по умолчанию, `[data-theme="light"]` переопределяет значения.
- Ключевые токены: `--bg`, `--text`/`--text-dim`/`--text-faint`,
  `--glass-bg`/`--glass-border`/`--glass-shadow` (+ `*-strong`), `--surface`/`--surface-2`,
  `--hairline`/`--hairline-2`, `--accent`/`--accent-deep`/`--accent-soft`/`--accent-glow`,
  `--accent-grad`/`--accent-grad-strong`, `--grad-text`/`--grad-num`, `--on-accent`,
  `--live`/`--live-soft`, `--danger`, `--dock-bg`, `--grain-opacity`.
- Легаси `--brand-*` переменные **алиасятся** на семантические токены (разрешаются
  лениво → следуют активной теме). Поэтому непереписанные экраны recolor'ятся сами.
- `html/body` фон = `var(--bg)`. Плёночный грейн — `body::after` (mix-blend overlay,
  `--grain-opacity`: 0.08 dark / 0 light), `z-index:1` (над контентом, под доком/модалками).

### Компоненты ДС — `frontend/src/styles/redesign.css`
Все классы с префиксом `rd-` (изолированы от легаси-каскада), цвета только из токенов,
обе темы покрыты: `.rd-glass`/`.rd-glass-strong`, `.rd-page`, `.rd-header`/`.rd-avt`/
`.rd-city-pill`, `.rd-stats`/`.rd-stat`, `.rd-feature`/`.rd-ft-*`, `.rd-stack`/`.rd-mini-card`,
`.rd-section-h`/`.rd-section-sub-h`/`.rd-page-h`, `.rd-search`, `.rd-cat-chips`/`.rd-cat-chip`,
`.rd-club-card`/`.rd-cover[data-cat]`, `.rd-rep-row`/`.rd-rep-panel`, `.rd-pf-identity`/`.rd-bio`,
`.rd-tags`/`.rd-tag`, `.rd-icon-btn`, `.rd-dock`/`.rd-dock-pill`/`.rd-dock-item`/`.rd-dock-action`,
`.rd-seg-control`, `.rd-activity-card`, `.rd-tabs`/`.rd-tab-link`, `.rd-member-strip`/`.rd-m-avt`,
`.rd-sticky-cta`, `.rd-hero`, `.rd-body-text`, `.rd-empty`/`.rd-ghost-btn`/`.rd-spinner-row`.
(Часть классов добавлена авансом для следующих этапов — JSX подключит позже.)

## Темы (поведение)
- Стор: `frontend/src/store/useThemeStore.ts` — `mode: 'system'|'light'|'dark'`,
  производное `theme`, `cycle()`/`setMode()`/`syncSystem()`. `mode` persist в
  `localStorage` (`clubs-theme-mode`).
- `system` резолвится из `window.Telegram.WebApp.colorScheme`, фолбэк
  `matchMedia('(prefers-color-scheme: dark)')`, дефолт `dark`.
- Применение: `App.tsx` ставит `document.documentElement.dataset.theme` и пробрасывает
  `appearance={theme}` в `<AppRoot>`; при `mode==='system'` слушает изменения схемы.
- Ручной тумблер — в `ProfilePage` (пилюля, циклит system→light→dark), `haptic.select()`.

## Навигация — док + FAB
- `frontend/src/components/BottomTabBar.tsx` рендерит `.rd-dock`: пилюля с 4 табами +
  отдельный круглый FAB.
- Табы (маршруты прежние): **Главная** `/` · **Активности** `/activities` · **Клубы**
  `/my-clubs` · **Профиль** `/profile`. Иконки — инлайн-SVG (brass-PNG больше не используются).
- Точки-уведомления на табах: Клубы (`myClubsActionTotal`), Активности (`unpaidCount`).
  FAB **без бейджа**.
- FAB = **всегда «создать»**: логика поднята в `Layout.tsx`. Через `useOrganizerClubs`:
  организатор → открыть `CreateActivityFlow`; не организатор → `Toast`-подсказка.
- Дубль-кнопка «Создать» из `ActivitiesPage` удалена.
- Старый `.brand-tabbar` CSS удалён.

## Экраны
### Главная — `frontend/src/pages/DiscoveryPage.tsx`
Шапка (аватар + имя + «Состоишь в N клубах» + city-pill) → stat-grid (Репутация средняя /
В клубах — из `useMyReputationQuery`, показывается при ≥1 клубе) → секция «Найди свой клуб» →
поиск → чипы категорий + чип фильтра цены → список `ClubCard` (cover по `data-cat`) с
бесконечной подгрузкой/empty. Виджеты «Сборы недели», «События поблизости», «Свежие клубы» —
**отложены** (нет источников данных), будут отдельной итерацией.

### Профиль — `frontend/src/pages/ProfilePage.tsx`
Тумблер темы + шестерёнка → `.rd-pf-identity` (аватар, имя + ★, `@handle · город`) → bio →
stat-grid (Надёжность средняя+tier / В клубах) → интересы (теги) → репутация по клубам
(`.rd-rep-row`, score-цвета high/mid/low). Пустое состояние — призыв найти клуб.

### `ClubCard` — `frontend/src/components/ClubCard.tsx`
Новая cover-карточка: градиент по категории (или аватар клуба фоном), бейдж «встреча HH:MM»
при событии <24ч, мета `Город · N участников · цена/мес`.

## Критерии приёмки
1. Главная и Профиль визуально соответствуют мокапу в **обеих** темах.
2. Тумблер темы в Профиле мгновенно меняет тему всего приложения и **переживает
   перезагрузку** (persist), дефолт следует теме Telegram.
3. Док: 4 таба в порядке Главная/Активности/Клубы/Профиль; точки-уведомления корректны;
   FAB открывает поток создания (организатор) или подсказку (не организатор); переходы с haptic.
4. Непереписанные экраны перекрашены новой палитрой и работают в обеих темах (не «сломаны»).
5. `npm run build` без ошибок TS.

## Этап 2 — Клуб + Событие (детальные страницы)
- **ClubPage** (`frontend/src/pages/ClubPage.tsx`): `rd-page` + `rd-hero` (обложка по
  `data-cat`/аватар клуба, тип-бейдж роли, заголовок, eyebrow `доступ · город · N/limit · цена`),
  кнопка выхода — `rd-hero-btn`. Описание/правила — `rd-glass` + `rd-body-text`. Табы участника/
  организатора — `rd-tabs`/`rd-tab-link`. Visitor: `rd-locked` + CTA на `rd-btn-primary`/
  `rd-btn-outline` (логика join/apply/оплаты/cancelled сохранена). Контент табов
  (ClubActivitiesTab/ClubMembersTab) пока легаси-стиль (recolor).
- **EventPage** (`frontend/src/pages/EventPage.tsx`): ушли от telegram-ui List/Section на
  `rd-page` + `rd-hero` (бейдж «СОБЫТИЕ», дата) + `rd-mini-map` + адрес, описание `rd-glass`,
  набор (`rd-progress` + счётчики), голосование `rd-vote-btn`, stage-2 подтверждение/отказ
  на `rd-btn-*`, статус-бейджи `rd-badge`. Логика голосования/подтверждения сохранена.
- Новые CSS в `redesign.css`: `rd-hero-btn`/`rd-hero-bg[data-cat]`/`rd-hero-type-badge`,
  `rd-btn-primary`/`rd-btn-outline`/`rd-cta-*`/`rd-note`/`rd-error`, `rd-locked`, `rd-host-row`,
  `rd-mini-map`/`rd-addr-body`, `rd-vote-*`/`rd-progress`/`rd-badge`/`rd-kv`.

## Этап 3 — Активности + Мои клубы
- **ActivitiesPage** (`frontend/src/pages/ActivitiesPage.tsx`): `rd-page` + header
  (`rd-ft-eyebrow` + `rd-page-h`) + `rd-seg-control` (События/Сборы, бейдж `rd-seg-badge`).
- **Лента**: `EventCard`/`SkladchinaCard` → `rd-activity-card` (cover + тип-бейдж + дата-бейдж,
  клуб-row, заголовок, мета, прогресс, статус-бейджи). Общие `FeedSection`→`rd-section-sub-h`,
  `FeedEmpty`→`rd-glass rd-empty`, `FeedSkeleton`→`rd-skeleton`. Логика/группировки/пагинация
  сохранены.
- **MyClubsPage** (`frontend/src/pages/MyClubsPage.tsx`): `rd-page` + header (greeting eyebrow +
  `rd-page-h` + summary) + кнопка «+ Клуб». Все секции унифицированы под `rd-glass rd-rep-panel`
  со строками `rd-rep-row`; инбокс заявок — `rd-glass-strong` с `rd-inbox-head`. Подкарточки
  (MyClubCard/AppCard/PendingAppCard/AwaitingPaymentCard/OrganizerAwaitingPaymentRow) →
  `rd-rep-row` со статус-бейджами `rd-badge`. Вся логика (resend invoice, review-modal,
  self-heal free-membership, deep-link focus=inbox) сохранена.
- Новые CSS: `rd-act-club-row .rd-club-avt`, `rd-act-cover.rd-c-coin`, `rd-badges-row`,
  `rd-badge.rd-neutral/.rd-rep`, `rd-seg-badge`, `rd-skeleton*`, `rd-inbox-head`.

## Этап 3.1 — правки по мокапу
- **Плоский фон**: `.rd-page` и `.brand-page` теперь используют ровный `var(--bg)` без
  радиальных градиентов — фон одинаковый и тёмный (`#0B0B0E`) на всех экранах, как в мокапе.
- **Карточка события** (`EventCard`): убран прогресс-бар, мета приведена к виду мокапа
  («локация · N идут»), статус-бейдж показывается только для action-required.
- **Страница события** (`EventPage`): добавлена host-карточка организующего клуба
  (подтягивается `useClubQuery(clubId)`, тап → страница клуба) — соответствует `event-host`
  из мокапа.

## Этап 3.2 — карточки активностей клуба + голосование
- **Карточка активности на странице клуба** (`components/manage/ActivityCard`): перешла на
  `rd-feature rd-glass` — без тамбнейла и эмодзи-календаря; число «идут» (для сборов — % собрано/
  кол-во оплат) показано **градиентным** `rd-ft-stat-num`. `ActivityCompactRow` (прошедшие) →
  `rd-rep-row` без иконки. `ActivityFilterChips` → `rd-cat-chip`. Секции/тоггл «Прошедшие» →
  `rd-section-sub-h`. `ActivityThumb` больше не используется.
- **EventPage**: hero-обложка = аватар клуба-организатора (как на странице клуба,
  `data-cat` fallback). Блок набора: слева 3 кнопки голосования (`rd-vote-stack`, со счётчиками),
  справа **круговая диаграмма** (`rd-donut`, conic-gradient: идут/возможно/нет) — заменила
  прогресс-бар. Для не-upcoming статусов — read-only счётчики + диаграмма.
- Организатор/владелец **может голосовать**: владелец получает active organizer-membership при
  создании клуба → `isMember=true` в `VoteService.castVote`; UI EventPage не гейтит по роли.

## Этап 3.3 — диаграмма голосования + список проголосовавших
- **Диаграмма набора** (EventPage): круг увеличен (~46% ширины, `aspect-ratio`), кнопки
  голосования компактнее (≈50/50 со списком кнопок), в центре — `идут/лимит` (напр. `14/20`)
  под наклоном (`rotate(-16deg)`), градиентом.
- **Список проголосовавших** — новый backend-эндпоинт `GET /api/events/{id}/responses`
  (`EventController.getEventResponders` → `VoteService.getEventResponders`, authz: только член
  клуба, как у голосования; `EventResponseRepository.findRespondersWithUsers` — join
  `event_responses ⨝ users`). DTO `EventResponderDto(userId, firstName, lastName, avatarUrl,
  status)`, где `status` = final_status (confirmed/waitlisted/declined) либо stage_1 vote
  (going/maybe/not_going). Фронт: `getEventResponders`/`useEventRespondersQuery`, на EventPage —
  секция «Кто идёт» с легендой и сеткой в 2 колонки (аватар + имя + цветная точка статуса).
  Инвалидация — через префикс `events.detail` после голосования/подтверждения.
- **Приватность**: список голосов виден только членам клуба (включая статус «не идут») —
  осознанное продуктовое решение (видимость как у самого голосования).

## Этап 4 — Управление клубом
- **OrganizerClubManage** (`frontend/src/pages/OrganizerClubManage.tsx`): `rd-page` (вместо
  `brand-page` + `BrandBackdrop`). Шапка — full-bleed `rd-hero rd-compact` (`ManageHeader`,
  обложка по `data-cat`/аватар, бейдж «УПРАВЛЕНИЕ», кликабельна → `/clubs/:id`). Табы —
  underline `rd-tabs`/`rd-tab-link` (3 шт.). Компонент `ManageTabs` удалён (dead code).
- **Участники**: локальный дубль `MembersTab` удалён — переиспользуется общий
  `ClubMembersTab` с `isOrganizer` (rd-список `rd-rep-row` + секция «Ожидают оплаты»).
- **ClubMembersTab** (`components/club/ClubMembersTab.tsx`) переведён на `rd-glass rd-rep-panel`
  + строки `rd-rep-row` (avatar в `rd-ico`, индекс надёжности `rd-score` с цветами
  `rd-high/mid/low`, badge «Орг» как `rd-badge rd-rep`); awaiting-payment строки — `rd-rep-row`
  + `rd-badge rd-warn`. Затрагивает и member-view `ClubPage`.
- **Финансы**: карточки `rd-stats`/`rd-stat` (выручка градиентом `rd-stat-value`, остальные `rd-plain`).
- **Настройки**: секции под `rd-section-sub-h`, read-only — `rd-kv`, кнопки — `rd-btn-primary`/
  `rd-btn-outline` (Удалить — `--danger`). Поля ввода остаются telegram-ui `Input/Textarea`
  (rd-формы — отдельный этап), модалка удаления — telegram-ui `Modal` (recolor). Логика
  валидации/dirty/save/delete сохранена.

## Этап 5 — Складчина + формы создания
- **rd-форм слой** (`redesign.css` § Forms): `rd-form`/`rd-field`/`rd-label`(`rd-req`/`rd-count`)/
  `rd-input`/`rd-textarea`/`rd-select`(+`rd-select-wrap`)/`rd-hint`/`rd-datetime`/`rd-form-actions`/
  `rd-mode-option`/`rd-check`/`rd-pick-*`. Нативные элементы (работают с RHF `register`). Для
  модалок — `.rd-modal-form` (box-sizing reset вне `.rd-page`).
- **SkladchinaPage** (детальная): `rd-page`; клуб-карточка → `rd-glass rd-host-row` (кликабельна →
  клуб); заголовок `rd-page-h` + бейджи статуса/режима/репутации (`rd-badge` + `rd-going`/`rd-decline`/
  `rd-neutral2`/`rd-warn`); описание/правила/фото → `rd-glass`; прогресс → `rd-amounts`
  (собрано градиентом) + `rd-progress` + `rd-sklad-stats`; платёжный блок → `rd-glass` + `rd-btn-primary`;
  подтверждение оплаты → `rd-input` + `rd-form-actions`; закрытие сбора → `rd-btn-outline` (`--danger`).
- **OrganizerParticipantList** → `rd-glass rd-rep-panel` + `rd-rep-row` (статус-бейджи `rd-badge`).
- **CreateEventPage / CreateSkladchinaPage**: `rd-page` + eyebrow/`rd-page-h` + `rd-form`. Режимы сбора —
  `rd-mode-option`, чекбокс репутации — `rd-check`, пикер участников — `rd-pick-toggle`. `BrandStepper`
  (лимит участников) оставлен (recolor). Кнопки — `rd-btn-*`.
- **CreateClubModal** (5-шаговый wizard): telegram-ui `Input/Select/Textarea/Section/Cell/Button` →
  rd-form примитивы; `register` напрямую на нативных полях (без `Controller`). Логика/валидация/шаги
  сохранены. См. [`create-club-form.md`](./create-club-form.md).
- `BrandBackdrop` удалён (последний пользователь ушёл на `rd-page`). Легаси `sklad-*`/`field`/
  `mode-option`/`brand-page` CSS остаётся dead-CSS до общей чистки.

## Известные ограничения / дальнейшие шаги
- Виджеты дашборда Главной (сборы/события рядом/свежие клубы) отложены до появления данных.
- Поля ввода в `SettingsTab` (OrganizerClubManage) остаются telegram-ui `Input/Textarea` — переход
  на rd-form примитивы возможен отдельной правкой (rd-form слой уже готов).
- Все основные экраны переведены на rd. Дальше — точечная косметика и чистка dead-CSS.
- Event: реальная карта/хост-клуб не подключены (нет данных в DTO) — `rd-mini-map` декоративная,
  адрес из `locationText`.
- Легаси `.discovery-*`/`.cp-*` остаются как dead-CSS до перевода соответствующих экранов.
