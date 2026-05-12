# Discovery redesign

Реализация финального дизайна Discovery (страница поиска клубов) в рамках feature-флоу
`feature/discovery-redesign`. Brand identity — navy + brass геральдика для аудитории
23-40 лет, see SESSION-NOTES в `docs/design/discovery-redesign/SESSION-NOTES.md`.

## Цель

Заменить дефолтный tgui-вид DiscoveryPage на bran­ded экран, отражающий
premium «private clubs» позиционирование продукта.

## Scope

### Входит (этот PR)
- Полный визуальный редизайн `DiscoveryPage` (топбар, hero, search, chips, list)
- Перевод `BottomTabBar` на brand brass-иконки
- Перевод `ClubCard` на новый layout (52px gradient avatar, capacity bar, featured-state)
- Новый компонент `DiscoveryBackdrop` (SVG-абстракция + brass mesh)
- Brand-палитра в `frontend/src/styles/brand-theme.css`
- Brand-ассеты в `frontend/public/brand/` (logo + 4 tab-иконки)
- Inter Google Fonts в `index.html`

### НЕ входит (отдельные PR/ветки)
- Секция «Сегодня в клубах» (editorial rail) — требует нового backend endpoint
  с privacy-guard аналогично PR #32. Branch `feature/discovery-today-events`
  будет отведена от текущей ветки
- Light theme — brand зашит как dark, light нужен только когда product
  пользователи попросят (низкий приоритет). Theme-toggle в топбаре намеренно
  не добавлен — добавим вместе с light-палитрой
- Геолокация-автоопределение города — текущий picker строит выбор из
  hardcoded списка (RU/BY/KZ/AM/GE/AE/TR); навешать `navigator.geolocation`
  поверх можно отдельным шагом, когда появится backend `GET /cities/nearby`
- Redesign других страниц (MyClubs, Events placeholder, Profile) — отдельные PR

## API контракты

**Не меняются.** Используется существующий `GET /api/clubs` с теми же query-параметрами
(`search`, `category`, `city`, `accessType`, `minPrice`, `maxPrice`, `page`, `size`).
Запросы делегированы `useClubsQuery` без изменений.

## Brand палитра (single source of truth)

Определена в `frontend/src/styles/brand-theme.css` как CSS custom properties.
**Намеренно НЕ зависит от `--tg-theme-*`** — brand identity сохраняется при любой
теме Telegram пользователя (паттерн как у Wallet/Fragment).

| Token | Hex | Назначение |
|---|---|---|
| `--brand-navy-bg` | `#0E1428` | Background страницы |
| `--brand-navy-card` | `#1A2138` | Карточка (= тело лого) |
| `--brand-navy-soft` | `#2E3858` | Avatar/chip mute |
| `--brand-brass` | `#C9A063` | Основной акцент |
| `--brand-brass-deep` | `#DDB87A` | Highlight на brass |
| `--brand-ink` | `#F0EEE7` | Текст основной (warm off-white) |
| `--brand-ink-3` | `#8A92AC` | Текст secondary |
| `--brand-live` | `#4FA078` | Sage — только для live indicators |

## Acceptance Criteria

### AC-1: топбар
**GIVEN** пользователь открывает `/`
**THEN** видит slot 72×72 px brand mark (shield logo) + wordmark «Clubs / СООБЩЕСТВА»
**AND** видит интерактивную city-pill с текущим городом + chevron-down иконкой справа

**GIVEN** пользователь тапает по city-pill
**THEN** срабатывает `haptic.select()`
**AND** открывается bottom-sheet «Город» с табами стран (Россия, Беларусь, Казахстан, Армения, Грузия, ОАЭ, Турция) и списком городов выбранной страны
**AND** текущий выбор подсвечен brass + check-иконка

**GIVEN** пользователь выбирает город в sheet
**THEN** срабатывает `haptic.select()`
**AND** sheet закрывается
**AND** выбор сохраняется в `localStorage` под ключом `clubs.cityChoice`
**AND** список клубов фильтруется по `city` (query-param к `GET /api/clubs`)

**GIVEN** пользователь нажимает Escape / overlay / «Закрыть»
**THEN** sheet закрывается без изменения выбора

### AC-2: hero
**GIVEN** страница загружена
**THEN** видит заголовок «Найди свой клуб» (Inter 34px bold)
**AND** слово «клуб» окрашено в `--brand-brass` (`#C9A063`)
**AND** под словом «клуб» нежное brass-свечение (CSS `::after` + `filter: blur(6px)`)

### AC-3: фоновый паттерн
**GIVEN** страница загружена
**THEN** видит за контентом абстракцию: brass radial-glow в правом верхнем углу,
navy soft-blobs, brass контурные линии, ghost-curves, brass orb-точки
**AND** паттерн затухает сверху-вниз через `mask-image` (full 0-27%, fade к 18%)
**AND** карточки клубов читаются поверх паттерна за счёт `backdrop-filter: blur(10px)`

### AC-4: search + chips
**GIVEN** пользователь набирает в поле поиска
**THEN** через 300 ms debounce запрос отправляется с новым `search`
**AND** список обновляется

**GIVEN** пользователь тапает category-chip
**THEN** активный chip помечен brass fill + navy text
**AND** немедленно (без debounce) фильтрует список по категории
**AND** срабатывает `haptic.select()` (правило haptic.md §15)

### AC-5: meta-row + список
**GIVEN** загружены `N` клубов
**THEN** видит «`N` клуб(а/ов)» (Russian pluralization) слева, price-pill «Цена» в центре, «По релевантности» справа

**GIVEN** пользователь тапает price-pill
**THEN** срабатывает `haptic.select()`
**AND** открывается bottom-sheet «Стоимость подписки» с presets:
  «Любая», «Бесплатно», «До 1 000 ₽», «1 000 – 3 000 ₽», «От 3 000 ₽»
**AND** текущий выбор подсвечен brass + check-иконка

**GIVEN** выбран любой preset кроме «Любая»
**THEN** pill окрашена в brass-soft с brass-deep текстом (active state)
**AND** label отражает выбор: «Цена · до 1 000 ₽» и т.д.
**AND** запрос отправляется с `minPrice` / `maxPrice` query-params

**GIVEN** выбрана «Любая»
**THEN** `minPrice` и `maxPrice` не передаются (фильтр снят)

**GIVEN** список пуст и filters активны
**THEN** видит «Клубы не найдены. Попробуйте изменить фильтры.»

**GIVEN** error в query
**THEN** видит сообщение ошибки в `--brand-live` ярко-красном (`#FF8B8B`)

### AC-6: club card
**GIVEN** карточка клуба `club.subscriptionPrice > 0`
**THEN** видит «`1 200 ₽` `/мес`» — амт bold, per muted

**GIVEN** клуб бесплатный (`subscriptionPrice === 0`)
**THEN** видит «Бесплатно» без `/мес`

**GIVEN** `club.nearestEvent.eventDatetime` в пределах 24 часов
**THEN** карточка получает featured-state: brass-tint фон + brass-rim вокруг аватара + brass-glow shadow
**AND** в meta видит «встреча сегодня · HH:MM» в sage цвете
**AND** capacity bar fill — sage gradient вместо brass

**GIVEN** `club.accessType !== 'open'` AND не featured
**THEN** в meta видит badge «🔒 по заявке» в brass-deep

**GIVEN** `memberCount / memberLimit >= 0.8`
**THEN** число «X / Y» окрашено в brass-deep (almost-full indicator)

### AC-7: avatar
**GIVEN** клуб имеет `avatarUrl`
**THEN** показывает изображение в 52×52 px rounded-square

**GIVEN** `avatarUrl === null`
**THEN** показывает monogram (первые буквы каждого слова имени, max 2)
**AND** background — gradient по категории клуба (sport=warm brown, creative=indigo, ...)

### AC-8: tab bar
**GIVEN** пользователь на `/`
**THEN** tab «Поиск» в active-state: full saturation, brass-чёрточка над иконкой с glow
**AND** остальные tab иконки desaturated + dim (`filter: saturate(0.35) brightness(0.85)`)

**GIVEN** тап по любому tab
**THEN** срабатывает `haptic.select()` (haptic.md §15)
**AND** навигация на соответствующий path

### AC-9: a11y
**GIVEN** screen reader
**THEN** brand logo имеет `alt="Clubs"`
**AND** chips имеют `role="tab"` + `aria-selected`
**AND** active tab имеет `aria-current="page"`
**AND** search input имеет `aria-label="Поиск клубов"`

## Non-functional

- **Производительность**: SVG backdrop инлайнится в JSX (~3 KB gzipped), без runtime cost кроме первого render
- **Bundle**: brand-ассеты в `public/brand/` ~155 KB (5 PNG, 128-256px source)
- **Безопасность**: внешний CDN `fonts.googleapis.com` — для шрифта Inter. CSP в проекте пока не enforced
- **Совместимость**: `backdrop-filter` поддерживается Safari/Chrome/Firefox 2020+. Telegram iOS/Android WebView — OK
- **Дегра­дация**: на старых браузерах без `backdrop-filter` карточки покажутся с базовым `background: rgba(26, 33, 56, 0.88)` без blur — читабельно
- **Z-index hierarchy**: `.brand-tabbar` → `z-index: 2` (выше контента, но НИЖЕ tgui Modal overlay = `--tgui--z-index--overlay: 3`). Это гарантирует, что CreateClubModal-wizard рендерится поверх tab bar — иначе нижние поля формы скрываются под баром

## Файловая карта

**Новые файлы:**
- `frontend/src/styles/brand-theme.css` — palette tokens + component styles + body/html reset (убирает 8px дефолтный body-margin, чтобы navy canvas был на всю ширину viewport)
- `frontend/src/components/DiscoveryBackdrop.tsx` — SVG паттерн с fade-mask
- `frontend/src/components/CityPicker.tsx` — bottom-sheet picker страна+город с persist в `localStorage` (хук `useCityChoice`)
- `frontend/src/components/PriceFilter.tsx` — bottom-sheet preset-picker по стоимости подписки, утилиты `presetIdFromRange` / `pillLabelFromRange`
- `frontend/public/brand/{logo,nav-search,nav-clubs,nav-events,nav-me}.png` — production ассеты

**Изменённые:**
- `frontend/index.html` — добавлен link на Inter Google Fonts
- `frontend/src/main.tsx` — импорт `./styles/brand-theme.css`
- `frontend/src/components/BottomTabBar.tsx` — `<img>` brass-иконки + active brass-indicator (вместо tgui `Tabbar`)
- `frontend/src/components/Layout.tsx` — padding-bottom = `calc(84px + env(safe-area-inset-bottom) + 8px)` на всех tabbar-routes (включая `/clubs/:id`), чтобы tab bar не перекрывал контент
- `frontend/src/pages/DiscoveryPage.tsx` — целиком новый layout, inline filters (search + chips), price-pill в meta-row
- `frontend/src/components/ClubCard.tsx` — целиком новый дизайн с capacity bar + featured state

**Удалённые:**
- `frontend/src/components/ClubFilters.tsx` — заменён inline-логикой в DiscoveryPage (фильтры неотделимы от layout редизайна; вынесение обратно — premature abstraction для одной usage point)

## Связанное

- `docs/design/discovery-redesign/SESSION-NOTES.md` — handoff doc дизайн-итерации
- `docs/design/discovery-redesign/mockups/16-discovery-dark-v3.html` — финальный мокап
- `docs/modules/haptic.md` — правила haptic feedback (обновлены: ClubFilters → DiscoveryPage)
- Memory `project_clubs_over_events_rationale.md` — почему clubs-first
- PR #32 — template privacy guard для будущего «Сегодня в клубах»
