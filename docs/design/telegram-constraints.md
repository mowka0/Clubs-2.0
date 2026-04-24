# Telegram Mini Apps — дизайн-ограничения и возможности

Полный свод правил и возможностей платформы, которые влияют на визуал и UX
Clubs 2.0. Источник — [core.telegram.org/bots/webapps](https://core.telegram.org/bots/webapps)
(актуально на 2026-04-24, Bot API 9.6). Стек: `@telegram-apps/sdk-react` v3 +
`@telegram-apps/telegram-ui` v2.

Файл — референс для **Claude Design** (чтобы предлагал макеты, реализуемые на
платформе) и для разработчиков (что можно, чего нельзя, где лежит тонкий лёд).

> **Легенда:** ⚡ релевантно сейчас · 📦 доступно, пока не используем
> · 🚫 не используем осознанно. Рядом с фичами — минимальная версия Bot API.

---

## Содержание

**Основа**
1. Launch modes & compact vs fullscreen
2. Тема и палитра (полный список CSS vars)
3. tgui v2 ↔ tg-theme — мост переменных
4. Типографика и системные шрифты
5. Viewport и iOS-баг `100vh`
6. Safe areas (system + content)
7. Fullscreen mode (8.0+)
8. Клавиатура и её влияние на viewport

**Native UI Telegram**
9. BackButton
10. MainButton / SecondaryButton (BottomButton)
11. SettingsButton
12. BottomBar и header colors
13. Native popups (alert / confirm / popup / QR)
14. Loading screen — **design artifact**

**Motion, ownership, платформы**
15. Haptic feedback
16. Motion / easing / duration conventions
17. Z-index / layering stack
18. Gesture ownership matrix
19. Touch-only: hover, tap-target, long-press
20. Platform-specific quirks (iOS / Android / Desktop)
21. Производительность + Android performance class
22. Accessibility

**Возможности платформы**
23. Closing confirmation + swipe-to-close + isActive
24. Storage (Cloud / Device / Secure)
25. Share / deep-link / payments
26. Permission-флоу (write / contact / location / biometric / download)
27. Доступные данные пользователя
28. Orientation & motion sensors (8.0+)
29. Прочие API

**Интеграция и ассеты**
30. Asset specs (иконки, loading, custom emoji, images)
31. Edge cases и error states
32. Чего НЕ делаем
33. Чеклист design-деливери

---

## 1. Launch modes & compact vs fullscreen ⚡

Telegram запускает Mini App **семью способами** — от этого зависит, что доступно
и как приложение открывается.

| Способ | Дефолт-высота | Доступы | Наш случай |
|---|---|---|---|
| Main Mini App (Launch app button на профиле бота) | fullscreen | полный | ⚡ основной |
| Direct link `t.me/bot/app?startapp=X` | fullscreen | полный | ⚡ share-ссылки |
| Menu button (рядом с полем ввода) | half, expandable | полный | ⚡ быстрый запуск |
| Inline button под сообщением | half, expandable | полный | 📦 invite-карточки |
| Keyboard button (reply keyboard) | half | только `sendData` | 🚫 |
| Inline mode results | — | нет доступа к чату | 🚫 |
| Attachment menu (скрепка) | — | major advertisers only | 🚫 |

### Compact vs fullscreen

- **Fullscreen (expanded):** `isExpanded === true`, `viewportHeight ≈ screen.height`.
- **Compact:** открывается на половину экрана. Пользователь может вытянуть
  жестом вверх. `isExpanded === false`.
- **`WebApp.expand()`** — программно раскрыть. Вызываем при инициализации.
- **Параметр URL `mode=compact`** — принудительно открывает в compact даже для
  Main Mini App (который иначе fullscreen).

**Дизайн-вывод:** первый экран должен быть осмысленно читаем **и при
половинной высоте** — шапка + хотя бы часть контента видны без раскрытия.

### startapp и entry points

`t.me/botusername?startapp=value` → значение в `initDataUnsafe.start_param`
и GET-параметре `tgWebAppStartParam`. Используем для deep-linking
(`startapp=club_abc123` → сразу открываем `/clubs/abc123`).

`chat_type` / `chat_instance` — передаются при запуске из direct link в
групповом чате, позволяют делать multiplayer-сервисы.

**Дизайн-вывод:** каждая страница — потенциальная точка входа. UI должен
корректно работать без стекa истории. «Назад к списку» показываем только если
есть куда возвращаться.

---

## 2. Тема и палитра ⚡

Telegram в реальном времени передаёт цвета темы (Day / Night / кастомные) через
событие `themeChanged`. **Хардкод `#000` / `#fff` запрещён**, всё через CSS-vars.

### Полный список ThemeParams

| CSS variable | Назначение | Bot API |
|---|---|---|
| `--tg-theme-bg-color` | основной фон страницы (tier 1) | 6.0+ |
| `--tg-theme-secondary-bg-color` | фон между секциями (tier 2) | 6.1+ |
| `--tg-theme-section-bg-color` | фон карточки-секции (tier 3) | 7.0+ |
| `--tg-theme-header-bg-color` | фон header'а Telegram | 7.0+ |
| `--tg-theme-bottom-bar-bg-color` | фон нижнего бара | 7.10+ |
| `--tg-theme-text-color` | основной текст | 6.0+ |
| `--tg-theme-hint-color` | вторичный, подписи | 6.0+ |
| `--tg-theme-subtitle-text-color` | подзаголовки | 7.0+ |
| `--tg-theme-section-header-text-color` | заголовок секции | 7.0+ |
| `--tg-theme-section-separator-color` | разделитель секций | 7.6+ |
| `--tg-theme-link-color` | ссылки | 6.0+ |
| `--tg-theme-accent-text-color` | акцент-текст (badges) | 7.0+ |
| `--tg-theme-destructive-text-color` | деструктив (Удалить) | 7.0+ |
| `--tg-theme-button-color` | фон CTA-кнопки | 6.0+ |
| `--tg-theme-button-text-color` | текст CTA-кнопки | 6.0+ |
| `--tg-color-scheme` | `"light"` / `"dark"` | — |

### Трёхуровневая иерархия фонов

Telegram задумал цвет фона не плоским, а **слоистым**:

```
tier 1: bg-color            ← тело приложения
  tier 2: secondary-bg-color   ← область под секциями
    tier 3: section-bg-color     ← сама «карточка» секции
```

В iOS-стиле: `bg` → чуть темнее `secondary` → `section` как выделенный блок.
В Android-стиле (Material): `bg` → тот же `secondary` → elevated `section`.
**Дизайн должен это понимать** — секции «карточками» выглядят нативно, всё
плоско в один фон — не-нативно.

### Программное перекрашивание Telegram-окна

- `setHeaderColor(color)` — красит Telegram header. `#RRGGBB` или keyword
  `bg_color` / `secondary_bg_color`.
- `setBackgroundColor(color)` — фон между header'ом и контентом.
- `setBottomBarColor(color)` **7.10+** — нижняя полоска (на Android — nav bar).

**Правило Clubs:** на каждой странице устанавливаем `setHeaderColor('bg_color')`
для бесшовного перехода header → наш контент. `setBottomBarColor` — под фон
BottomTabBar.

**Fallback для старых клиентов:** переменные 7.0+ могут быть `undefined` в
старых версиях. Всегда пишем цепочку:
```css
color: var(--tg-theme-subtitle-text-color, var(--tg-theme-hint-color));
background: var(--tg-theme-section-bg-color, var(--tg-theme-bg-color));
```

---

## 3. tgui v2 ↔ tg-theme — мост переменных ⚡

`@telegram-apps/telegram-ui` прокидывает theme-переменные **с другим префиксом**
через `<AppRoot>`. Важно понимать маппинг — дизайнер будет композить tgui-
компоненты, но указывать цвета через tg-theme переменные.

### Маппинг tgui → tg-theme

| tgui variable | источник tg-theme |
|---|---|
| `--tgui--bg_color` | `bg-color` |
| `--tgui--secondary_bg_color` | `secondary-bg-color` |
| `--tgui--tertiary_bg_color` | computed от `section-bg-color` |
| `--tgui--header_bg_color` | `header-bg-color` |
| `--tgui--text_color` | `text-color` |
| `--tgui--hint_color` | `hint-color` |
| `--tgui--subtitle_text_color` | `subtitle-text-color` |
| `--tgui--section_header_text_color` | `section-header-text-color` |
| `--tgui--link_color` | `link-color` |
| `--tgui--accent_text_color` | `accent-text-color` |
| `--tgui--destructive_text_color` | `destructive-text-color` |
| `--tgui--button_color` | `button-color` |
| `--tgui--button_text_color` | `button-text-color` |
| `--tgui--divider` | computed (separator color) |
| `--tgui--outline` | computed (border color для inputs) |

**Правило:** в нашем custom-коде используем `--tg-theme-*` (источник истины).
В overrides tgui-компонентов — `--tgui--*`. Внутри самих tgui-компонентов —
они уже используют `--tgui--*` автоматически, трогать не надо.

### Two-platform visual: iOS vs base

tgui v2 поддерживает два набора стилей: **`ios`** и **`base`**. Выбирается через
`platform` prop у `<AppRoot>`. Отличаются: радиусы, плотность, типографика,
behavior Cell'ов и Tabbar'а. **Дизайн должен решить** — живём на одном стиле
(обычно `base`) или платформо-специфично (как делает сам Telegram).

---

## 4. Типографика и системные шрифты ⚡

Telegram **не навязывает** шрифт Mini App'у, но по-умолчанию tgui использует
системные:

- **iOS:** `-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display"`
- **Android:** `Roboto, "Helvetica Neue", sans-serif`
- **Desktop:** `system-ui, "Segoe UI", Roboto, sans-serif`

### Шкала (рекомендация, базируется на iOS HIG / Material)

| Стиль | Размер | Line-height | Применение |
|---|---|---|---|
| `largeTitle` | 34px | 41px | Hero-заголовки (редко) |
| `title1` | 28px | 34px | ClubPage header, OrganizerPage шаг |
| `title2` | 22px | 28px | Page titles |
| `title3` | 20px | 25px | Section headers жирные |
| `headline` | 17px | 22px (600) | Cell titles, ClubCard name |
| `body` | 17px | 22px (400) | Основной текст |
| `callout` | 16px | 21px | Subtle body |
| `subheadline` | 15px | 20px | Subtitle |
| `footnote` | 13px | 18px | Badges, метаданные |
| `caption1` | 12px | 16px | Подписи |
| `caption2` | 11px | 13px | Микро-метки |

**Правила:**
- Не задавать `font-family` если не нужна альтернатива — Telegram использует
  системный шрифт, это ощущается нативнее.
- Веса: `400` / `500` / `600` / `700`. Избегать `900` и ниже `400` — не везде
  рендерится.
- Emoji в тексте — не стилизуются, не применяется `font-weight`, занимают
  больше высоты чем кажется → учитывать.

---

## 5. Viewport и iOS-баг `100vh` ⚡

Высота Mini App ≠ `100vh` / `window.innerHeight`. Пользователь может приспустить
шапку жестом, клавиатура сдвигает viewport, compact-режим даёт половину высоты.

| Переменная | Поведение | Когда применять |
|---|---|---|
| `viewport.height` / `--tg-viewport-height` | **живое**, обновляется во время анимации | визуал, который может «дышать» |
| `viewport.stableHeight` / `--tg-viewport-stable-height` | **стабильное** после конца жеста | прибивать к низу (sticky footer, BottomTabBar) |

**Правила:**
- Элемент с `position: fixed/sticky; bottom: 0` — `var(--tg-viewport-stable-height)`,
  **не** `100vh` и **не** живую `--tg-viewport-height`.
- Событие `viewportChanged` прилетает с `{ isStateStable: boolean }`.
- `expand()` вызываем один раз при инициализации (уже в `sdk.ts`).

---

## 6. Safe areas (system + content) ⚡

Bot API **8.0+** вводит **два уровня** отступов — обязательно учитывать оба.

| Переменная | Что это |
|---|---|
| `--tg-safe-area-inset-top/bottom/left/right` | системные (notch, dynamic island, home-indicator) |
| `--tg-content-safe-area-inset-top/bottom/left/right` | отступы от Telegram-UI (header, swipe-handle внизу) |

**События:** `safeAreaChanged`, `contentSafeAreaChanged`.

**Правила Clubs 2.0:**
- Корневой `Layout`:
  `padding-top: max(var(--tg-content-safe-area-inset-top), 0px)`.
- Контент-страница (под header Telegram): учитывает
  `--tg-content-safe-area-inset-*`.
- BottomTabBar (наш нижний бар): сидит поверх **system** safe-area
  (`--tg-safe-area-inset-bottom`) — мы сами являемся нижней UI-панелью, content
  safe-area не нужна.
- Horizontal insets — актуальны для iPhone в landscape. У нас portrait-only,
  игнорируем.

---

## 7. Fullscreen mode (Bot API 8.0+) 🚫

`requestFullscreen()` / `exitFullscreen()`, события `fullscreenChanged`,
`fullscreenFailed` (`UNSUPPORTED` / `ALREADY_FULLSCREEN`).

- В fullscreen: portrait **и** landscape, header Telegram становится прозрачным
  (обязательно `setHeaderColor` для контраста status bar).
- `lockOrientation()` / `unlockOrientation()` + `isOrientationLocked`.

**Для Clubs:** 🚫 не используем — фиды и формы, landscape не нужен.

---

## 8. Клавиатура и её влияние на viewport ⚡

Когда юзер открывает `<input>`:
- **iOS:** webview поднимается, `viewportHeight` уменьшается на высоту
  клавиатуры. Событие `viewportChanged` срабатывает.
- **Android:** поведение зависит от `windowSoftInputMode` клиента. Обычно —
  `adjustResize`, т.е. viewport шринкается.

**Правила:**
- Формы не пишем с `position: fixed` по низу — при открытии клавиатуры
  кнопка «Отправить» окажется за ней. Вместо этого — MainButton Telegram
  (он автоматически поднимается над клавиатурой) или скроллим к активному
  полю через `element.scrollIntoView({ block: 'center' })`.
- `hideKeyboard()` **9.1+** — программно закрыть клавиатуру. Полезно после
  submit или при навигации.
- Не используем `autofocus` при mount — клавиатура подпрыгнет и сразу сменит
  viewport, UX дёрганый. Фокусируем по явному действию.

---

## 9. BackButton ⚡

Native-элемент в шапке Telegram (слева). Методы: `show()`, `hide()`, `onClick()`.
Событие: `backButtonClicked`.

**Правило Clubs:** показываем на всех nested-страницах (`/clubs/:id`,
`/events/:id`, …), скрываем на tab-страницах. Клик → `navigate(-1)`. Сделано
в `useBackButton`. **Никакой стрелки «назад» в нашем UI.**

---

## 10. MainButton / SecondaryButton (BottomButton) ⚡ 📦

Оба — экземпляры класса `BottomButton`, рисуются Telegram'ом внизу экрана **над
нашим BottomTabBar**. `MainButton` — с Bot API 6.0, `SecondaryButton` — **7.10+**.

### Свойства

| Свойство | Дефолт | Примечание |
|---|---|---|
| `text` | main: `"Continue"`, secondary: `"Cancel"` | нет явного лимита, но ≤20 симв. читаемо |
| `color` | main: `themeParams.button_color`, secondary: `bottom_bar_bg_color` | `#RRGGBB` |
| `textColor` | main: `button_text_color`, secondary: `button_color` | `#RRGGBB` |
| `isVisible` | `false` | `show()` / `hide()` |
| `isActive` | `true` | `enable()` / `disable()` |
| `hasShineEffect` **7.10+** | `false` | блики для привлечения внимания |
| `iconCustomEmojiId` **9.5+** | — | эмодзи слева от текста (см. §30) |
| `position` (только secondary, 7.10+) | `left` | `left` / `right` / `top` / `bottom` |
| `isProgressVisible` | `false` | readonly |

### Методы

- `showProgress(leaveActive?)` — loader на кнопке. По умолчанию она
  disable'ится; `leaveActive=true` оставляет активной.
- `hideProgress()` — убрать loader.
- `setParams({ text, color, text_color, has_shine_effect, position, is_active, is_visible, icon_custom_emoji_id })` — batch-апдейт.

### Конфликт с нашим BottomTabBar

Если показан MainButton — наш BottomTabBar надо **скрыть**, иначе два «низа»
налезут. Обычно это nested-страницы, где BackButton и так активен.

**Дизайн-вывод для Clubs:**
- **Tab-страницы:** наш BottomTabBar, без MainButton.
- **Nested-страницы** (ClubPage, EventPage, OrganizerPage wizard): скрываем
  BottomTabBar, показываем MainButton как primary CTA («Вступить», «Записаться»,
  «Создать клуб»). Это даёт native-look.
- **SecondaryButton** — отмена/альтернатива на тех же экранах. Для пары
  «Назад / Далее» в wizard'е: MainButton=«Далее», SecondaryButton=«Назад»,
  `position=left`.
- **`showProgress()`** — вместо Spinner'а внутри кнопки при submit формы.
- **`hasShineEffect`** — для critical CTA («Оплатить», «Создать клуб» финальный
  шаг). Не злоупотреблять — привыкание убьёт эффект.

### Почему это важно

Native MainButton автоматически:
- Поднимается над клавиатурой.
- Использует theme-colors и корректно работает в dark-mode.
- Обрабатывает safe-area-inset-bottom.
- Имеет правильный haptic-feedback при нажатии.
- Уменьшает наш visual-debt (не рисуем кастомную кнопку).

Собственный CTA на nested-страницах — **анти-паттерн**.

---

## 11. SettingsButton 📦

Пункт в контекстном меню Mini App («⋯» в шапке Telegram). Активируется через
BotFather, показывается методом `show()`. Событие `settingsButtonClicked`.

**Для Clubs:** можно привязать к ProfilePage настроек. Пока не приоритет.

---

## 12. BottomBar и header colors ⚡

Цвет шапки Telegram и нижней полоски можно менять программно (см. §2).

**Правило:** на смене страницы вызываем:
```ts
setHeaderColor(currentPageTheme.headerBg ?? 'bg_color');
setBottomBarColor(currentPageTheme.bottomBarBg ?? 'bg_color');
```

Дизайн выдаёт по странице, какой цвет шапки и нижнего бара. Обычно все страницы
одинаковы (= `bg_color`), но можно сделать акцентный цвет на промо-экране
(InvitePage).

---

## 13. Native popups ⚡

Используем **вместо** `window.alert` / `confirm` / `prompt`.

### `showAlert(message, cb?)` и `showConfirm(message, cb?)`

Короткие диалоги. `showConfirm` — callback с `boolean` результатом.

### `showPopup(params, cb?)`

| Параметр | Лимит | Обязательно |
|---|---|---|
| `title` | 0-64 симв. | нет |
| `message` | 1-256 симв. | **да** |
| `buttons` | 1-3 шт. | нет (дефолт: `[{type:'close'}]`) |

Типы кнопок (`PopupButton.type`):
- `default` — обычная, требует `text`
- `ok` / `close` / `cancel` — локализованный текст, `text` игнорируется
- `destructive` — красная, требует `text`

Событие закрытия: `popupClosed` с `{ button_id }` (или `null` если
pop закрыли без клика).

**Правила:**
- Текст ошибки > 256 симв. — inline в UI, **не** через popup.
- «Удалить клуб» → `showPopup` с `destructive` кнопкой + `cancel`.
- Не делать popup внутри popup'а (не поддерживается).

### `showScanQrPopup` 📦

Нативный QR-сканер. `text` (подпись): 0-64 симв. События: `qrTextReceived`
(каждое чтение), `scanQrPopupClosed` (7.7+).

### `readTextFromClipboard` 📦

**Ограничение:** работает **только** из Mini App, запущенного из attachment
menu, **и** только в ответ на явное действие юзера (click). Событие:
`clipboardTextReceived`.

---

## 14. Loading screen customization — **design artifact** ⚡

Telegram показывает **свой** splash от открытия Mini App до вызова
`WebApp.ready()`. Настраивается через **@BotFather** → `/mybots` → Bot Settings
→ Configure Mini App → Enable Mini App.

**Design должен выдать:**
- Иконку приложения (требования — см. §30).
- HEX-цвет основы для **светлой** темы.
- HEX-цвет основы для **тёмной** темы.

Без этого юзер видит дефолтный Telegram-placeholder — «сырая» заглушка, не
фирменно. **Это обязательный artifact** дизайн-итерации.

---

## 15. Haptic feedback ⚡

`HapticFeedback` API, Bot API 6.1+. В каждое осмысленное взаимодействие
добавляем сигнал.

| API | Стиль | Когда |
|---|---|---|
| `impactOccurred('light')` | лёгкий тап | по Cell, переключение секции, открытие модалки |
| `impactOccurred('medium')` | средний | swipe-действие, drag-and-drop, подтверждение |
| `impactOccurred('heavy')` | тяжёлый | большое действие (клуб создан, платёж ушёл) |
| `impactOccurred('rigid')` | жёсткий | коллизия «твёрдых» элементов (toggle) |
| `impactOccurred('soft')` | мягкий | pull-to-refresh release, soft-drop |
| `notificationOccurred('success')` | зелёный | форма отправлена, оплата прошла |
| `notificationOccurred('error')` | красный | ошибка валидации, отказ сервера |
| `notificationOccurred('warning')` | жёлтый | деструктив-подтверждение |
| `selectionChanged()` | тик | **смена** выбора (tab, chip, picker), не подтверждение |

**Не добавлять** в hover, скролл, автоматические события (системные
уведомления).

**Паттерн использования:**
```ts
// На onClick-хендлере
hapticFeedback.impactOccurred('light');
doAction();
// На успехе async
.then(() => hapticFeedback.notificationOccurred('success'));
// На ошибке
.catch(() => hapticFeedback.notificationOccurred('error'));
```

---

## 16. Motion / easing / duration conventions ⚡

Telegram не навязывает animation-конвенции, но следование iOS/Material HIG
даёт нативное ощущение.

### Длительность (ms)

| Тип | Длительность |
|---|---|
| micro (color hover/active, tooltip fade) | 100 |
| small (toggle, icon swap) | 150-200 |
| standard (fade-in, slide-in) | 250 |
| emphasis (modal enter/exit) | 300-350 |
| large (page transition) | 400 |

Анимации длиннее 500ms — запрещены (кроме явных «wow»-моментов).

### Easing

- **`ease-out`** (`cubic-bezier(0.0, 0.0, 0.2, 1.0)`) — для входа (элемент
  появляется).
- **`ease-in`** (`cubic-bezier(0.4, 0.0, 1.0, 1.0)`) — для выхода (элемент
  исчезает).
- **`ease-in-out`** (`cubic-bezier(0.4, 0.0, 0.2, 1.0)`) — для изменения в
  пределах видимого (переход цвета, move).
- **`linear`** — только для progress-bars и спиннеров.

### Что анимируем

- **Только `transform` и `opacity`** — 60fps-safe.
- Ширина/высота/позиция через `transform: translate/scale`, не через `width`/
  `top`/`left`.
- `backdrop-filter: blur()` — только на `HIGH` Android performance class
  (см. §21).

### Что **не** анимируем

- Длинные спиннеры (> 10 сек без прогресса — заменяем на progress-bar или
  skeleton).
- Параллакс скролла (сложно с Telegram viewport).
- Infinite-loop ambient-анимации на static-элементах.

---

## 17. Z-index / layering stack ⚡

Layering всего, что может быть на экране, сверху вниз:

```
1. Telegram native UI (header, BackButton, MainButton) — вне нашего контроля
2. 2000 — Toast notifications (наш компонент)
3. 1500 — Popup / Modal (tgui Modal)
4. 1000 — Dropdown menus (если будут)
5.  500 — BottomTabBar (sticky bottom)
6.  100 — Floating elements (scroll-to-top button)
7.    0 — page content
```

**Правила:**
- Не превышать `z-index: 2000` — Telegram native UI всегда выше, попытки
  перекрыть сломают UX (не смогут закрыть наш модал через нативный BackButton).
- Toast сидит **над** BottomTabBar, но **под** Popup (чтобы не перекрывать
  критичные диалоги).
- Modal (tgui) уже имеет свой z-index управление — не переопределяем.

---

## 18. Gesture ownership matrix ⚡

Конфликт жестов — главная причина кривого UX в Mini App. Чётко распределяем,
кто чем владеет.

| Жест | Владелец | Наше использование |
|---|---|---|
| Swipe-down сверху для закрытия | Telegram | не вмешиваемся |
| Swipe-down в header области | Telegram | не вмешиваемся |
| Vertical swipes в контенте | Telegram (close/minimize) | можно отключить `disableVerticalSwipes()` если нужна карусель/drag |
| Horizontal swipe между tab'ами | свободно | 📦 пока не используем |
| Swipe-to-delete в list | свободно | 📦 можно реализовать на MyClubsPage |
| Pull-to-refresh | свободно | 📦 можно на DiscoveryPage |
| Long-press на Cell | свободно | 📦 можно для quick actions |
| Pinch-zoom | — | Mini App его блокирует по умолчанию |
| System back (Android hardware) | Telegram | отдельно от `BackButton` API |

**Правила:**
- `disableVerticalSwipes()` вызываем **только** на конкретных страницах,
  возвращаем после ухода.
- Pull-to-refresh — реализовываем через native iOS bounce (CSS overscroll) +
  кастомный индикатор. Не блокируем bounce.

---

## 19. Touch-only: hover, tap-target, long-press ⚡

Mini App всегда на мобильном клиенте (даже Desktop Telegram использует
мобильный layout внутри Mini App-окна).

- **Никаких `:hover` для ключевого UX** — действие дублируется в `:active` /
  `onClick`. `:hover` — только мелкие визуальные подсказки для Desktop.
- **Минимальный tap-target — 44×44 pt** (Apple HIG / Material). tgui гарантирует,
  для custom — проверяем вручную.
- **Long-press** — работает (touchstart + timeout). Используем для quick
  actions (копировать id, share). **Обязателен haptic impact на срабатывание**.
- **Никаких tooltip'ов по hover, long-press-preview Link'ов** — альтернатива:
  inline-описание или `showPopup`.
- **`cursor: pointer`** — не задаём (на Desktop Telegram курсор и так меняется
  на интерактиве).
- **`:focus-visible`** — оставляем (Desktop клавиатурная навигация).

---

## 20. Platform-specific quirks ⚡

`WebApp.platform` возвращает: `"ios"` / `"android"` / `"tdesktop"` / `"macos"`
/ `"weba"` / `"webk"` / `"unknown"`.

### iOS
- **Bounce-scroll overscroll** — работает, не блокируем (нативно). Если
  bouncing ломает sticky-header — добавляем overscroll-behavior на
  конкретные скролл-контейнеры.
- Input-focus сдвигает viewport (см. §8).
- VoiceOver работает полноценно.

### Android
- System back button (аппаратный) — **отдельно** от `BackButton` API.
  Telegram сам навешивает на него закрытие Mini App. Наш `BackButton.hide()`
  не влияет на system back.
- WebView разных версий — тестируем на Android 10+.
- Keyboard = `adjustResize`, viewport шринкается (см. §8).
- Performance class передаётся в UA (см. §21).

### Desktop (tdesktop / macos)
- Нет haptic feedback — вызовы игнорируются без ошибок.
- Клавиатура всегда — обрабатываем Enter/Escape в формах.
- Mini App открывается в фиксированном окне (не fullscreen).
- Нет bounce-scroll.

### Web (weba / webk)
- Самые большие расхождения. Не все API могут работать (biometric, motion,
  QR-scan).
- `isVersionAtLeast` может возвращать младшие версии даже если клиент
  новый — проверяем feature-by-feature через try-catch или свойство.

**Правило:** пишем «Web first, enhance for mobile» — UX должен быть полноценным
везде, где запускается.

---

## 21. Производительность + Android performance class ⚡

- **60fps target** — только `transform` / `opacity`, никаких layout-trigger
  (width/height/top/left).
- Android Telegram добавляет в UA:
  `Telegram-Android/11.3.3 (Manufacturer Model; Android 14; SDK 34; LOW)`
- Последнее поле — **`LOW` / `AVERAGE` / `HIGH`**.
- На `LOW` — отключаем: blur, крупные градиенты, Lottie с высоким fps,
  параллакс, backdrop-filter.
- Картинки — **WebP / AVIF**, progressive, lazy-load всё ниже первого экрана.
- Парсим UA в `sdk.ts`, `performanceClass` раздаём через React Context.

### Бюджет первой отрисовки (цель)

- **TTI (Time To Interactive) ≤ 2 сек** на mid-range Android.
- **Initial JS bundle ≤ 200 KB** (gzipped).
- **Largest Contentful Paint ≤ 1.5 сек**.
- `WebApp.ready()` вызываем **как можно раньше** — чтобы Telegram скрыл свой
  loading screen. До этого юзер видит splash.

---

## 22. Accessibility ⚡

- Все `<input>` / `<img>` — labels (`aria-label` / `alt`). Критично для
  VoiceOver (iOS) и TalkBack (Android).
- Контраст текста — **WCAG AA** (4.5:1 body, 3:1 large). Проверяем **на обеих
  темах** (light + dark).
- `focus-visible` не удаляем (Desktop keyboard-nav).
- Анимации уважают `prefers-reduced-motion` — отключаем non-essential motion.
- Touch-target ≥ 44×44 pt.
- Семантический HTML: `<button>` а не `<div onClick>`, `<h1-6>` в порядке.

---

## 23. Closing confirmation + swipe-to-close + isActive ⚡

- `enableClosingConfirmation()` — показывает нативный диалог «Вы уверены?» при
  попытке закрыть. **Включаем** на экранах с несохранёнными данными (wizard
  OrganizerPage 2-5, формы с unsaved changes).
- `disableClosingConfirmation()` — выключаем при чистом состоянии (не забываем
  вызвать на unmount).
- `enableVerticalSwipes()` / `disableVerticalSwipes()` **7.7+** — по дефолту
  свайп вниз закрывает. Отключаем только если свой жест конфликтует.
  **Свайп за шапку** всегда работает, даже при disabled.
- `isActive` **8.0+** + события `activated` / `deactivated` — Mini App «в фоне»
  (минимизирован / в другой вкладке). Останавливаем таймеры, автообновление,
  видео, polling.

**Паттерн для форм:**
```ts
useEffect(() => {
  if (isDirty) WebApp.enableClosingConfirmation();
  else WebApp.disableClosingConfirmation();
  return () => WebApp.disableClosingConfirmation();
}, [isDirty]);
```

---

## 24. Storage — три уровня 📦

| API | Лимит | Persistence | Что хранить |
|---|---|---|---|
| `CloudStorage` (6.9+) | 1024 ключа, value 0-4096 симв., ключ 1-128 симв. `[A-Za-z0-9_-]` | cross-device | preferences, бизнес-state |
| `DeviceStorage` (9.0+) | 5 MB на бот | только на текущем устройстве | UI state, кэш списков |
| `SecureStorage` (9.0+) | 10 items | iOS Keychain / Android Keystore, encrypted | токены, secrets, auth state |

`SecureStorage.restoreItem(key, cb)` — запрос на восстановление, если ключ
был создан на этом же устройстве ранее.

**Дизайн-вывод:** можно персистить фильтры Discovery (city, category) в
CloudStorage → при входе с любого девайса фильтры восстановлены.

---

## 25. Share / deep-link / payments 📦

### Shareable

| API | Описание | Bot API |
|---|---|---|
| `shareMessage(msg_id, cb?)` | пошарить PreparedInlineMessage в чат; cb с `bool` | **8.0+** |
| `shareToStory(media_url, params?)` | media в Stories; `text` 0-200 (premium 0-2048); `widget_link.name` 0-48 (premium-only) | **7.8+** |
| `switchInlineQuery(query, choose_chat_types?)` | подставить `@bot query` в инпут; `choose_chat_types` = `users` / `bots` / `groups` / `channels` | **6.7+** |

### Links

| API | Поведение |
|---|---|
| `openLink(url, { try_instant_view?: true })` | внешний браузер; Mini App **не закрывается**; `try_instant_view` — через IV |
| `openTelegramLink(url)` | `t.me/…` внутри Telegram; **не закрывается** (с 7.0+) |
| `openInvoice(url, cb?)` | Telegram Stars; событие `invoiceClosed` со статусами `paid` / `cancelled` / `failed` / `pending` |

**Ограничения:**
- `openLink` можно вызывать **только** в ответ на user-interaction.
- Никаких `<a target="_blank">` — всё через API.

### Deep linking

`t.me/botusername/appname?startapp=value` или
`t.me/botusername?startapp=value` → `initDataUnsafe.start_param` +
`tgWebAppStartParam` GET.

Дополнительный URL-параметр `mode=compact` — форсит compact-запуск.

---

## 26. Permission-флоу — дизайним моменты запроса ⚡

Диалоги — нативные, **мы решаем, когда их показывать**. Запрос прав — UX-
момент, требует pre-context (объясняем **зачем** перед запросом).

| Метод | Что | Событие | Bot API |
|---|---|---|---|
| `requestWriteAccess(cb?)` | бот может писать юзеру | `writeAccessRequested` → `allowed`/`cancelled` | 6.9+ |
| `requestContact(cb?)` | поделиться номером | `contactRequested` → `sent`/`cancelled` | 6.9+ |
| `requestChat(req_id, cb?)` | выбрать чат для PreparedKeyboardButton | cb с `bool` | **9.6+** |
| `requestEmojiStatusAccess(cb?)` | менять emoji-статус | `emojiStatusAccessRequested` | 8.0+ |
| `setEmojiStatus(id, params?, cb?)` | поставить emoji-статус (native confirm) | `emojiStatusSet` / `emojiStatusFailed` с error-code | 8.0+ |
| `LocationManager.init()` + `getLocation(cb)` | геолокация; LocationData c lat/lon/alt/course/speed + accuracy | `locationManagerUpdated`, `locationRequested` | 8.0+ |
| `BiometricManager.requestAccess({ reason })` | биометрия; `reason` 0-128 симв. | `biometricAuthRequested` с `biometricToken` | 7.2+ |
| `downloadFile({ url, file_name }, cb?)` | нативный popup скачивания; server должен отдавать `Content-Disposition: attachment; filename=...` | `fileDownloadRequested` → `downloading`/`cancelled` | 8.0+ |
| `addToHomeScreen()` / `checkHomeScreenStatus(cb)` | shortcut на home; статусы `unsupported`/`unknown`/`added`/`missed` | `homeScreenAdded` | 8.0+ |

**Дизайн-вывод для Clubs:**
- **Перед `requestContact`** в OrganizerPage — экран объяснения «Поделитесь
  номером, чтобы с вами могли связаться участники».
- **LocationManager** — для будущей фичи «клубы рядом». UI должен graceful
  fallback: denied → ручной city-picker.
- **`addToHomeScreen`** — предлагаем после первого позитивного действия
  (вступление в клуб), **не сразу** при первом запуске.
- **BiometricManager** — для подтверждения оплаты (будущее).
- **`setEmojiStatus`** — геймификация «установить статус клуба» для member'ов.

### Error codes для emojiStatusFailed

`UNSUPPORTED` / `SUGGESTED_EMOJI_INVALID` / `DURATION_INVALID` / `USER_DECLINED`
/ `SERVER_ERROR` / `UNKNOWN_ERROR` — для каждого должен быть UX-fallback.

### Error codes для shareMessageFailed

`UNSUPPORTED` / `MESSAGE_EXPIRED` / `MESSAGE_SEND_FAILED` / `USER_DECLINED`
/ `UNKNOWN_ERROR`.

---

## 27. Доступные данные пользователя ⚡

Через `initDataUnsafe.user` (`WebAppUser`):

| Поле | Всегда | Примечание |
|---|---|---|
| `id` | ✅ | 64-bit int (52 significant bits) — хранить как number/BigInt |
| `first_name` | ✅ | |
| `last_name` | optional | |
| `username` | optional | |
| `language_code` | optional | IETF tag (`ru`, `en-US`) |
| `is_premium` | optional (true-only) | для premium-фич и иконки |
| `photo_url` | optional (8.0+) | JPEG/SVG; доступно если privacy позволяет |
| `allows_write_to_pm` | optional (true-only) | можем ли писать без запроса |
| `added_to_attachment_menu` | optional (true-only) | |

**Дизайн-вывод:**
- Реальные аватары (`photo_url`) доступны — используем их, не placeholder с
  инициалами (если `photo_url` есть).
- `language_code` — для будущего i18n (сейчас только `ru`).
- `is_premium` — показываем premium-бейдж в профиле.

---

## 28. Orientation & motion sensors (8.0+) 🚫

Для fullscreen-игр, не для нас:
- `lockOrientation()` / `unlockOrientation()`.
- `Accelerometer` (x/y/z в m/s²).
- `DeviceOrientation` (alpha/beta/gamma в rad, флаг `absolute` для магнитного
  севера).
- `Gyroscope` (rad/s).
- Все стартуют через `start({ refresh_rate: 20-1000ms })`.
- События: `*Started` / `*Changed` / `*Stopped` / `*Failed` (единственный
  error — `UNSUPPORTED`).

**Для Clubs:** 🚫 не используем.

---

## 29. Прочие API ⚡ 📦

- `hideKeyboard()` **9.1+** — программно скрыть клавиатуру.
- `isVersionAtLeast('7.10')` — фича-гейтинг.
- `WebApp.platform` — см. §20.
- `WebApp.version` — читаемая версия Bot API клиента.
- `WebApp.ready()` — скрыть Telegram splash.
- `close()` — закрыть Mini App программно.
- `WebApp.sendData(data)` — **только** для keyboard-button Mini App, до 4096
  байт, закрывает Mini App.

---

## 30. Asset specs (иконки, loading, custom emoji, images) ⚡

### Иконка приложения (для loading screen + home shortcut)

- Формат: **SVG** (предпочтительно) + **PNG** 512×512 как fallback.
- Без прозрачности (home shortcut иконка не круглится).
- Без текста.
- Визуальный padding внутри — ~12% от размера (safe area для circular crop
  на iOS).
- Выдаём две версии: под светлый фон (тёмная иконка) и под тёмный (светлая).

### Loading screen (BotFather)

- Иконка (см. выше).
- HEX для **светлой** темы (обычно наш `bg_color` light).
- HEX для **тёмной** темы (наш `bg_color` dark).

### Custom emoji для MainButton (9.5+)

- Нужен `custom_emoji_id` из Telegram — получается через стикер-бот
  [@stickers](https://t.me/stickers) при создании premium emoji pack.
- Альтернатива: не использовать кастом-emoji, оставить кнопку текстовой.

### Иконки интерфейса (замена emoji)

- Формат: **SVG** (inline или import-as-react-component).
- Grid: **24×24** primary, **20×20** для Cell-`before`, **16×16** для badges.
- Stroke: 1.5px или 2px, uniform по набору.
- Fill: `currentColor` — чтобы наследовали цвет текста через `color: var(--tgui--hint_color)`.
- Набор: рекомендую Lucide Icons (MIT, 1500+ иконок, React wrapper).
- Альтернатива: нарисовать свой набор ~40 штук под бренд.

### Avatars

- Форма: квадрат с `border-radius: 50%` (круг).
- Размеры: 24 / 32 / 48 / 64 / 96 / 120 px.
- Ratio: 1:1, object-fit: cover.
- Placeholder: инициалы на цветном фоне (цвет hash от `user.id`).

### Images (club cover, event cover)

- Ratio: **16:9** для hero, **1:1** для list thumbnails.
- Min-resolution: 600×337 (16:9), 300×300 (1:1).
- Формат: WebP primary, JPEG fallback.
- Размер: ≤ 200 KB для hero, ≤ 50 KB для thumbnail (progressive encoding).

---

## 31. Edge cases и error states ⚡

### initData invalid / missing

Бывает: юзер открывает Mini App «не из Telegram» (например, прямая URL в
браузере). Показываем centered-placeholder:
- Иконка приложения
- «Clubs 2.0 работает только в Telegram»
- CTA «Открыть в Telegram» → `tg://resolve?domain=clubs_admin_bot`

### Offline / network error

Mini App — online-first, но 3G-обрыв случается.
- Catch всех API-запросов → `notificationOccurred('error')` + inline-toast.
- Если полная потеря связи при старте — full-screen «Нет интернета» с retry-
  кнопкой.
- Не используем SW для офлайн-режима (overengineering для нашей фичи-матрицы).

### Rate limit / 429

Показываем Toast с `Retry-After` временем («Попробуйте через X секунд»). Не
спамим повторными запросами.

### Auth expired (JWT)

401 от backend → silent refresh (если есть) либо перезапрос initData →
`/auth/telegram`. При повторной 401 — полный logout, редирект на DiscoveryPage
(публичный).

### MainButton action failed

Обязательно `hideProgress()` в `finally` — иначе кнопка навсегда в loading
state.

### `shareMessage` failed / `emojiStatus` failed

Каждый error-code (см. §26) имеет UX-fallback — либо alert через `showAlert`,
либо inline-hint.

### Fullscreen denied (`fullscreenFailed.UNSUPPORTED`)

У нас fullscreen не используется, но если будем — graceful fallback на обычный
режим с уведомлением.

### `addToHomeScreen` не поддерживается

`checkHomeScreenStatus('unsupported')` — скрываем CTA «Добавить на главный».

---

## 32. Чего НЕ делаем

- ❌ Свой header / back-button (Telegram рисует свой, дубль уродлив).
- ❌ `window.alert` / `confirm` / `prompt` — `WebApp.showAlert` / `showConfirm`
  / `showPopup`.
- ❌ `<a target="_blank">` — только `WebApp.openLink()`.
- ❌ localStorage для секретов — `SecureStorage`.
- ❌ `100vh` — `var(--tg-viewport-stable-height)`.
- ❌ `100vw` для прибивания справа — safe-area-inset-right может быть ≠ 0.
- ❌ `cursor: pointer` на всём подряд — touch-only.
- ❌ Fallback `prefers-color-scheme` — Telegram управляет темой сам.
- ❌ Эмодзи для key-UI-иконок (рендер разный между платформами; см. `stack.md §6`).
- ❌ Landscape-оптимизация — portrait-only.
- ❌ Hamburger-menu / breadcrumbs — не нативно для Mini App.
- ❌ Web-only жесты (horizontal scroll без явных snap-points, ctrl-click, etc.).
- ❌ Собственный splash-screen поверх Telegram'овского — вызываем `ready()`.
- ❌ Блокировка pinch-zoom через собственный JS — платформа сама не даёт.
- ❌ Autofocus при mount — клавиатура подпрыгнет, viewport дёрнется.
- ❌ Тяжёлые анимации (> 500ms) как обычный transition.
- ❌ Собственная кнопка «Назад» / «Главная» в контенте — есть BackButton API.

---

## 33. Чеклист design-деливери

На выходе дизайн-итерации нам нужны следующие артефакты:

### Tokens
- [ ] Типографическая шкала (11-12 стилей, см. §4)
- [ ] Spacing-scale (кандидаты из `stack.md §5`: 4, 8, 12, 16, 20, 24, 32, 40)
- [ ] Radius-scale (4, 8, 12, 16, 20, pill, circle)
- [ ] Motion tokens (durations + easings, см. §16)
- [ ] Z-index layers (см. §17)

### Colors
- [ ] Mapping семантических цветов на `--tg-theme-*` переменные
- [ ] Проверка контраста **в обеих темах** (light + dark)
- [ ] Accent цвет (акцентный бренд Clubs) — если вообще нужен поверх
      button_color

### Assets
- [ ] Иконка приложения (SVG + PNG 512, две темы) — для loading screen + home
- [ ] HEX цвета loading screen (light + dark) — для BotFather
- [ ] Иконочный набор (24px grid, currentColor fill) — замена emoji
- [ ] Аватар-placeholder (инициалы, цветной фон от hash)

### Компоненты (макеты)
- [ ] **9 страниц** (см. `stack.md §1`) в обеих темах
- [ ] Nested-страницы учитывают место под Telegram MainButton
- [ ] Tab-страницы учитывают BottomTabBar + safe-area-bottom
- [ ] **Empty states** для каждой страницы (пустая лента, нет клубов, etc.)
- [ ] **Loading states** (skeleton, не просто Spinner)
- [ ] **Error states** (network, 404, permission denied)

### Паттерны (guidelines)
- [ ] Toast (shape, position, timing)
- [ ] Popup (когда вместо inline-ошибки)
- [ ] Confirm (destructive кнопки)
- [ ] Pull-to-refresh (если решим делать)
- [ ] Swipe-to-delete (если решим делать)
- [ ] MainButton progress (flow при submit формы)

### UX-флоу
- [ ] Permission-запросы (§26): для каждого — pre-context экран «зачем»
- [ ] Deep-link entry (с `/clubs/:id` сразу, без стека): как выглядит «Назад»
- [ ] Onboarding: первый запуск (проходит или нет) и `addToHomeScreen` prompt
- [ ] Keyboard-behaviors (§8): формы с клавиатурой в fullscreen и compact

---

## Приложение: быстрые ссылки на API

- `WebApp.MainButton.setParams({ text, color, text_color, has_shine_effect, position, is_active, is_visible, icon_custom_emoji_id })`
- `WebApp.setHeaderColor(color | 'bg_color' | 'secondary_bg_color')`
- `WebApp.setBottomBarColor(color | 'bg_color' | 'secondary_bg_color' | 'bottom_bar_bg_color')`
- `WebApp.showPopup({ title, message, buttons }, callback)`
- `WebApp.HapticFeedback.impactOccurred('light' | 'medium' | 'heavy' | 'rigid' | 'soft')`
- `WebApp.CloudStorage.setItem(key, value, callback)` — 1024 keys / 4096 chars
- `WebApp.SecureStorage.setItem(key, value, callback)` — 10 items, Keychain/Keystore
- `WebApp.LocationManager.getLocation(callback)` → `LocationData`
- `WebApp.onEvent(eventType, handler)` / `offEvent(eventType, handler)`

Полный список — [core.telegram.org/bots/webapps#initializing-mini-apps](https://core.telegram.org/bots/webapps#initializing-mini-apps).
