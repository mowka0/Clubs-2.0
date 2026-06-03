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

## Известные ограничения / дальнейшие шаги
- Виджеты дашборда Главной (сборы/события рядом/свежие клубы) отложены до появления данных.
- Контент-табы Клуба (ClubActivitiesTab/ClubMembersTab), OrganizerClubManage, страница Складчины
  и формы создания — перекрашены, но не переведены на `rd`-компоненты (CSS-примитивы готовы).
- Event: реальная карта/хост-клуб не подключены (нет данных в DTO) — `rd-mini-map` декоративная,
  адрес из `locationText`.
- Легаси `.discovery-*`/`.cp-*` остаются как dead-CSS до перевода соответствующих экранов.
