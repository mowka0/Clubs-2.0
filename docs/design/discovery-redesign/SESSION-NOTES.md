# Discovery redesign — session handoff

**Branch:** `feature/discovery-redesign`
**Status:** mockups финализированы, frontend-интеграция начата
**Last updated:** 2026-05-11

## Где мы сейчас

Дизайн-итерация прошла через **9+ мокапов** в этой папке. Итеративно ушли:
- От handoff'а Claude Design (mesh purple/pink, эмодзи, gradient text — слишком молодёжно)
- Через editorial cream/brass (слишком luxury)
- К финальной версии: **dark navy + brass геральдика** для 23-40 платёжеспособной аудитории

**Финальный мокап**: [`mockups/16-discovery-dark-v3.html`](mockups/16-discovery-dark-v3.html) + [PNG screenshot](mockups/16-discovery-dark-v3.png)

## Принятые решения

### Палитра (single source of truth — копировать в CSS токены)

```css
/* Dark theme */
--navy-bg:     #0E1428;   /* page background */
--navy-card:   #1A2138;   /* card surface (= logo body color) */
--navy-card-2: #232B45;   /* elevated */
--navy-soft:   #2E3858;   /* avatar/chip muted */

--brass:       #C9A063;   /* main accent — like in logo */
--brass-2:     #B58A4D;
--brass-soft:  rgba(201, 160, 99, 0.14);
--brass-deep:  #DDB87A;

--ink:         #F0EEE7;   /* warm off-white (НЕ pure white — pair with brass) */
--ink-2:       #C2C8D9;
--ink-3:       #8A92AC;
--ink-4:       #5C6584;

--live:        #4FA078;   /* sage — единственный non-brand accent для live indicators */
--live-soft:   rgba(79, 160, 120, 0.18);

--hairline:    rgba(240, 238, 231, 0.07);
--hairline-2:  rgba(240, 238, 231, 0.12);
```

Палитра **жёстко зашита**, **НЕ** respects `--tg-theme-*` vars. Намеренно — premium brands в TG (Wallet, Fragment) тоже не следуют theme-vars, держат свой brand. Зафиксировать в спеке.

### Hero
- Заголовок: **«Найди свой клуб»** (informal "ты", brass на «клуб»)
- Brass-подсветка под словом «клуб»: `::after` pseudo-element с `background: brass`, `opacity: 0.16`, `filter: blur(6px)` создаёт нежное золотое свечение под буквами
- **Без description, без cursor-icon, без tooltip** — минимум

### Топбар
- **72px shield logo** (раньше был 38px — увеличили) + wordmark «Clubs / СООБЩЕСТВА» справа
- `drop-shadow(0 4px 12px rgba(0,0,0,0.35))` на лого для глубины на тёмном фоне
- City pill + theme toggle справа

### Bottom tabs
- 4 brass-иконки (поиск / клубы / события / профиль) из `frontend/resources/images/`
- Tab «Клубы» использует тот же brand-shield что и lого — намеренное визуальное равенство
- Active state: full saturation + brass-чёрточка `width: 18px; height: 2px` над иконкой с лёгким glow
- Inactive: `filter: saturate(0.35) brightness(0.85)` + `opacity: 0.62`

### Background pattern
- SVG-абстракция: navy blobs + brass radial-glow + topographic contour lines + brass orbs + dotted clusters + white ghost-curves
- `mask-image: linear-gradient(top→bottom)` — full brightness 0-27%, fading к 18% на bottom, чтобы паттерн жил на всю длину но не мешал карточкам
- Карточки используют `backdrop-filter: blur(10px)` — frosted-glass над декором
- **Orb около лого УБРАН** (изначально был `circle cx="58" cy="80"` — перекрывался с увеличенным лого)

### Карточки клубов
- Avatar 52px с **category-gradient** (light→dark, по 7 категориям, навейные + brass для литературы)
- **Capacity bar** 3px высотой с brass-gradient fill вместо текста «24/40 · вт чт сб»
- **Featured state** для live-карточек (с событием сегодня): brass-tint фон + brass-rim вокруг аватара + brass-glow shadow
- Live-карточки используют sage-fill в capacity-bar вместо brass
- Меньше текста в meta — только category + один signal max

### Цена
- Inline формат: `1 200 ₽ /мес` (НЕ ⭐ Stars — реальные ₽)
- Bold weight на сумме, muted на `/мес`
- Free → «Бесплатно» обычным весом

## Brand assets

**Источник** (committed): `frontend/resources/images/`
- `logo.png` — shield (= brand mark = clubs tab icon)
- `search.png`, `events.png`, `LK.png` — три остальных tab иконки

**Промежуточные** (committed, для рендеринга мокапов): `docs/design/discovery-redesign/mockups/assets/`
- Cropped + transparent versions через PIL (см. Python скрипт в истории сессии)

**Production-ready** (будут созданы при интеграции): `frontend/public/brand/`
- Оптимизированные PNG с убранным белым фоном (`pngquant` или Pillow)
- Целевые размеры: ~10-20KB каждая

## Implementation checklist

### V1 — frontend-only (этот PR)
- [ ] Brand assets optimized + placed in `frontend/public/brand/`
- [ ] Brand theme CSS file `frontend/src/styles/brand-theme.css`
- [ ] `frontend/index.html`: добавить `<link>` на Google Fonts Inter
- [ ] `BottomTabBar.tsx`: заменить эмодзи на `<img src="/brand/nav-*.png">`, active state с brass-чёрточкой
- [ ] `ClubCard.tsx`: redesign — 52px gradient avatar, capacity bar, featured state, no badges
- [ ] `DiscoveryPage.tsx`: новый layout — топбар с brand mark, hero «Найди свой клуб», search field, chips, meta-row, list
- [ ] `DiscoveryBackdrop.tsx`: новый компонент с SVG абстракцией + mask-fade
- [ ] `tsc --noEmit` зелёный
- [ ] `npm test` зелёный
- [ ] Commit + push
- [ ] Staging deploy + manual test

### V2 — backend «Сегодня в клубах» (следующий PR на отдельной ветке)
- [ ] Backend: `GET /api/users/me/today-events` или `/api/clubs/today-events`
  - Aggregation: events from user's active-member clubs в течение 24 часов
  - **Privacy guard**: только active memberships (как в PR #32)
  - Integration test (bypass + happy path)
- [ ] Frontend: `useTodayEventsQuery` hook + `<TodayRail />` компонент
- [ ] Spec: `docs/modules/discovery-today-rail.md`

## Если контекст потерян — как восстановить

1. **Прочитать этот файл** + просмотреть мокапы 9, 11, 12, 13, 14, 15, 16 для эволюции
2. Открыть финальный мокап в браузере: `open docs/design/discovery-redesign/mockups/16-discovery-dark-v3.html`
3. Сверить с реализацией в frontend файлах branch'а
4. Продолжить с первого незакрытого пункта из checklist выше

## Workflow

Текущий PR (V1) идёт по feature-флоу из CLAUDE.md:
- Analyst spec → Developer (тут) → Reviewer → Security → Tester → Analyst alignment → staging push → user test → готово запушь

PR `feature/discovery-today-events` будет branch off от этой ветки (НЕ от master), чтобы staging показал full picture. Merge order: backend PR первым → frontend rebase → frontend PR.

## Связанное

- PR #31 — spec `docs/modules/events-feed.md` (для отдельной фичи «События» tab, НЕ для discovery rail)
- PR #32 — закрыл `club-events-membership-check` (template для privacy guard в V2)
- Memory `project_clubs_over_events_rationale.md` — почему **clubs-first**, не event-discovery
- Memory `feedback_docs_consistency_priority.md` — Analyst alignment trip-wire на каждом PR
