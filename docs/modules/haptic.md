# Module: Haptic Feedback

Pre-design подготовка фронта: единая обёртка над Telegram Haptic API + расстановка вызовов на ключевых пользовательских взаимодействиях.

Источник правды по паттернам — [`docs/design/telegram-constraints.md` §15](../design/telegram-constraints.md). Этот документ конкретизирует: какой API экспортируем и где именно в нашем UI его дёргать.

---

## Цель

Тактильная обратная связь делает Mini App «нативным». Без неё кнопки/табы/выбор воспринимаются как веб-страница, не как Telegram-приложение. Цель этого PR:

1. Инкапсулировать Telegram `hapticFeedback*` API в один React-хук `useHaptic` — Developer не должен помнить про `.isAvailable()` и про то, что на Desktop API недоступен.
2. Расставить вызовы по уже существующим страницам/компонентам — таб, карточка клуба, фильтр, submit формы, успех/ошибка mutate-операций.

Не-цели — см. § «Out of scope».

---

## Scope

### Входит
- Хук `frontend/src/hooks/useHaptic.ts` с silent no-op fallback
- Вызовы хука в: `BottomTabBar`, `ClubCard`, `ClubFilters`, `AvatarUpload`, `ClubPage` (включая role-aware tabs), `EventPage`, `CreateClubModal`, `OrganizerClubManage`, `InvitePage`, `components/club/ClubEventsTab`, `ProfilePage`, `MyClubsPage`, `DiscoveryPage`, `useBackButton`. **`ClubInteriorPage` удалён** в `feature/unified-club-page` — точки кода переехали в unified `ClubPage` + три tab-компонента (см. `club-page-unified.md`).
- Unit-тест хука (mock SDK, проверка no-op при `isAvailable() === false`)

### НЕ входит
- См. § «Out of scope»

---

## API хука

**Файл:** `frontend/src/hooks/useHaptic.ts`

### Сигнатура

```ts
type ImpactStyle = 'light' | 'medium' | 'heavy' | 'rigid' | 'soft';
type NotifyType  = 'success' | 'warning' | 'error';

interface Haptic {
  impact: (style: ImpactStyle) => void;
  notify: (type: NotifyType) => void;
  select: () => void;
}

export function useHaptic(): Haptic;
```

### Контракт

| Свойство | Поведение |
|---|---|
| Стабильность ссылок | Возвращаемый объект **мемоизирован** (`useMemo` без зависимостей) — безопасно передавать в `useCallback`/`useEffect` deps без ререндеров |
| Доступность | Каждый метод внутри проверяет `hapticFeedbackImpactOccurred.isAvailable()` (и аналоги). Если `false` — **silent no-op**, без `console.warn`, без throw |
| Импорты | `hapticFeedbackImpactOccurred`, `hapticFeedbackNotificationOccurred`, `hapticFeedbackSelectionChanged` из `@telegram-apps/sdk-react` |
| SSR / тесты | На сервере и в `vitest`-окружении без mock SDK — все методы no-op (та же ветка `isAvailable() === false`) |
| Throwing | Хук **никогда не бросает** даже при internal-exception SDK — лишний `try/catch` вокруг вызовов в обработчиках не нужен |

### Использование

```ts
const haptic = useHaptic();
const onTabClick = (path: string) => {
  haptic.select();        // tick на смене таба
  navigate(path);
};
```

> Имена методов **`impact` / `notify` / `select`** — короче API SDK, но однозначно мапятся на §15 telegram-constraints.md (impactOccurred / notificationOccurred / selectionChanged). Стили / типы передаются строкой как у SDK — это уменьшает обучение и не плодит свои enum'ы.

---

## Маппинг паттерны → точки кода

Колонка «Метод» — точный вызов на этой точке. Колонка «Reason» — почему именно этот стиль/тип, со ссылкой на §15.

### Навигация по табам

| Файл | Событие | Метод | Reason |
|---|---|---|---|
| `components/BottomTabBar.tsx` | `handleTabClick` (до `navigate`), только если `location.pathname !== path` | `select()` | Смена выбора таба — `selectionChanged()`. Не `impact` — это переключение, не подтверждение |

### Навигация назад

| Файл | Событие | Метод | Reason |
|---|---|---|---|
| `hooks/useBackButton.ts` | callback нативной Telegram BackButton (внутри `onBackButtonClick`, до `navigate(-1)`) | `impact('light')` | Telegram не генерит haptic на back-tap надёжно — добавляем явно для консистентности с in-app навигацией |

### Списки и навигация в детали

| Файл | Событие | Метод | Reason |
|---|---|---|---|
| `components/ClubCard.tsx` | `Cell.onClick` (до `navigate`) | `impact('light')` | Тап по карточке-Cell — light impact (§15: «по Cell, открытие модалки») |
| `components/club/ClubEventsTab.tsx` | tap event-Cell → `navigate('/events/:id')` (строки 73, 97) | `impact('light')` | То же правило для navigation-cell. Перенесено из удалённого `ClubInteriorPage.tsx` |
| `pages/MyClubsPage.tsx` | tap клуба (`role === 'member'`) → `navigate('/clubs/:id')` (`handleClubClick`, строки 89-95) | `impact('light')` | То же |
| `pages/MyClubsPage.tsx` | tap клуба (`role === 'organizer'`) → `navigate('/clubs/:id/manage')` (`handleClubClick`, строки 89-95) | `impact('light')` | То же — общий `handleClubClick` маршрутизирует по роли, haptic один и тот же |
| `pages/MyClubsPage.tsx` | tap «+ Создать клуб» (Section-кнопка, строки 102-108) → открытие `<Modal>` с `CreateClubModal` | `impact('light')` | §15: открытие модалки — light |
| `pages/ProfilePage.tsx` | tap клуба или application → `navigate(...)` (строки 70, 77, 91, 103) | `impact('light')` | То же |
| `pages/ClubPage.tsx` | tap по tab «Управление» (organizer-only, `handleTabClick('manage')`, строки 149-154) → `navigate('/clubs/:id/manage')` | `impact('light')` | Открытие nested-страницы — light. Tab-как-link: `setActiveTab` НЕ вызывается, отличие от `select()` тактильно сигнализирует переход (см. `club-page-unified.md` § R-2) |
| `pages/OrganizerClubManage.tsx` MembersTab | tap по member-Cell → `setSelectedMember(m)` (строка 162) — открытие профильной модалки | `impact('light')` | §15: открытие модалки — light |

### Внутри-страничные переключатели

| Файл | Событие | Метод | Reason |
|---|---|---|---|
| `pages/ClubPage.tsx` | `handleTabClick('events' / 'members' / 'profile')` → `setActiveTab(tab)` (строки 149-157) | `select()` | Смена секции — selectionChanged. Перенесено из удалённого `ClubInteriorPage.tsx`. Только эти три tab'а; tab `'manage'` идёт по другой ветке (impact, см. таблицу выше) |
| `pages/OrganizerClubManage.tsx` | `setActiveTab(key)` (строка 1017) | `select()` | То же |
| `components/ClubFilters.tsx` | клик по chip-кнопке категории (`onChange` в строке 53) | `select()` | Смена выбора в picker — selectionChanged (§15: «смена chip — не подтверждение») |

### Submit / mutating actions (success / error)

Шаблон одинаковый: на старте — `impact('medium')`, в `.then` — `notify('success')`, в `.catch` — `notify('error')`. Если операция «крупная» (создан клуб, оплата, удалён клуб) — старт `impact('heavy')`.

| Файл | Событие | Старт | Success | Error |
|---|---|---|---|---|
| `pages/ClubPage.tsx` | `handleJoin` (строки 87-110) — вступление в открытый клуб (visitor-only, `isMember`/`isOrganizer` идут по tab-ветке) | `impact('medium')` | `notify('success')` | `notify('error')` |
| `pages/ClubPage.tsx` | `handleApply` (строки 112-137) — заявка в закрытый клуб | `impact('medium')` | `notify('success')` | `notify('error')` |
| `pages/ClubPage.tsx` | open apply modal через «Хочу вступить» (строка 201) | `impact('light')` (open) / no haptic (close через X или «Отмена», строка 336) | — | — |
| `pages/InvitePage.tsx` | `handleJoin` (строка 35) — вступление по invite | `impact('medium')` | `notify('success')` | `notify('error')` |
| `pages/EventPage.tsx` | `handleVote('going' / 'maybe' / 'not_going')` (строка 88) | `select()` (выбор RSVP-опции) | `notify('success')` | `notify('error')` |
| `pages/EventPage.tsx` | `handleConfirm` (строка 109) — подтверждение участия | `impact('medium')` | `notify('success')` | `notify('error')` |
| `pages/EventPage.tsx` | `handleDecline` (строка 130) — отказ | `impact('medium')` | `notify('warning')` | `notify('error')` |
| `components/CreateClubModal.tsx` | `handleNext` (wizard step forward, строки 96-105) | `impact('light')` (success-ветка) / `notify('error')` (RHF `trigger()` вернул false) | — | — |
| `components/CreateClubModal.tsx` | step back-кнопка (строка 297) | `impact('light')` | — | — |
| `components/CreateClubModal.tsx` | финальный submit: `onValid` (строки 113-141, старт `impact('heavy')`) + `onInvalid` (строки 109-111, `notify('error')` при провале RHF-валидации) | `impact('heavy')` (старт `onValid`) / `notify('error')` (старт `onInvalid`) | `notify('success')` (mutation `onSuccess`) | `notify('error')` (mutation `onError`) |
| `pages/OrganizerClubManage.tsx` | `handleApprove(appId)` (строка 217) | `impact('medium')` | `notify('success')` | `notify('error')` |
| `pages/OrganizerClubManage.tsx` | `handleReject(appId)` (строка 234) | `impact('medium')` | `notify('warning')` | `notify('error')` |
| `pages/OrganizerClubManage.tsx` | `handleCreateEvent` (строка 436) | `impact('medium')` | `notify('success')` | `notify('error')` |
| `pages/OrganizerClubManage.tsx` | `handleMarkAttendance` (строка 472) | `impact('medium')` | `notify('success')` | `notify('error')` |
| `pages/OrganizerClubManage.tsx` | `handleSave` (строка 769) — сохранение настроек клуба. **Также** на каждом early-return клиентской валидации — `notify('error')` (через локальный `fail(msg)` хелпер), иначе клик «Сохранить» с невалидным значением даёт только текст ошибки без тактильного отклика | `impact('medium')` | `notify('success')` + Toast «Изменения сохранены» (см. § Side-effect) | `notify('error')` |
| `pages/OrganizerClubManage.tsx` | `handleDelete` (строка 826) — удаление клуба | `impact('heavy')` | `notify('success')` | `notify('error')` |
| `pages/OrganizerClubManage.tsx` | open delete-modal (строка 912) | `impact('light')` (open) / no haptic (close через X или «Отмена», строка 932) | — | — |

> **Симметрия close-modal:** обе модалки (apply и delete) дают `impact('light')` только на **открытие**. На закрытие через X или «Отмена» — silent. Закрытие — отказ от действия, не подтверждение; добавлять haptic = шум. Канонический паттерн «open=light / close=silent» зафиксирован для apply- и delete-модалки одинаково.

### Загрузка файлов

| Файл | Событие | Метод | Reason |
|---|---|---|---|
| `components/AvatarUpload.tsx` | `pick` — открытие file-picker (через `Button` + клик по preview) | `impact('light')` | Открытие модалки — light |
| `components/AvatarUpload.tsx` | успех `uploadImage` | `notify('success')` | Файл загружен |
| `components/AvatarUpload.tsx` | ошибка валидации (MIME/size) или upload | `notify('error')` | Каждая ветка ошибки |
| `components/AvatarUpload.tsx` | «Убрать» (строка 101) | `impact('light')` | Удаление preview |

### Side-effect требования к error-веткам и тихим success-веткам

`notify('error' | 'success')` — это **тактильный сигнал**, не пользовательская обратная связь. Глухая вибрация без визуального ответа = баг с точки зрения юзера: он не понимает, что произошло.

**Правило 1 (error):** каждый `.catch(...)`-блок, в котором есть `haptic.notify('error')` или `haptic.notify('warning')`, **обязан** показать сообщение пользователю — inline-текст в той же секции/модалке. Паттерн: локальный `useState<string | null>` + рендер `{error && <div style={{ color: 'var(--tgui--destructive_text_color)' }}>{error}</div>}`. То же — для **client-side валидации** (early-return до API-вызова): фон `setError(msg) + haptic.notify('error')`, инкапсулированный в локальный `fail(msg)` хелпер, чтобы все ветки валидации синхронно давали и текст и тактильный сигнал.

**Правило 1.1 (красная подсветка поля):** валидация конкретного поля **обязана** также подсвечивать это поле красным контуром через `status="error"` prop у `Input`/`Textarea` из telegram-ui. Это даёт пользователю однозначно понять **какое именно** поле не прошло, без необходимости читать текст ошибки сверху. Два паттерна реализации:
- **RHF (CreateClubModal):** `status={errors.fieldName ? 'error' : undefined}` напрямую из `formState.errors` — автоматически синхронизируется с RHF.
- **useState (SettingsTab):** локальный `errorField` state + типизированный `fail(field, msg)` хелпер; `status={errorField === 'fieldName' ? 'error' : undefined}` на каждом Input. Используется когда форма не на RHF.

**Правило 1.2 (RHF validation haptic):** для форм на react-hook-form `notify('error')` вызывается:
- На **шаге wizard'а** (next-кнопка): после `await trigger(fields)` если возвращает false (см. `components/CreateClubModal.tsx handleNext`).
- На **финальном submit**: через `handleSubmit(onValid, onInvalid)` — RHF сам вызовет `onInvalid` callback при провале валидации перед mutation. См. `components/CreateClubModal.tsx onInvalid`.

**Правило 2 (success без перехода):** если success-путь **не** меняет страницу/модалку (юзер остаётся на том же экране — типичный пример: `handleSave` настроек клуба), `notify('success')` **обязан** сопровождаться transient UI-фидбеком — `<Toast>` с текстом подтверждения. Если success-путь делает navigate / закрывает модалку — Toast не нужен, переход сам по себе сигнал.

Дополнительно (рекомендуется для новых обработчиков): `console.error('handleX failed', e)` для прод-диагностики — см. [`.claude/rules/error-handling.md`](../../.claude/rules/error-handling.md) § «Логирование ошибок».

Текущие реализации паттерна:
- `pages/OrganizerClubManage.tsx` — `actionError` (строка 201, рендер 265-267) для approve/reject; `attendanceError` (строка 419, рендер 648-650) для отметки посещений. Оба — с `console.error`. `SettingsTab` (`handleSave`): `fail(msg)` хелпер для валидации + `savedToast` стейт + `<Toast message="Изменения сохранены" />` на success.
- `pages/ClubPage.tsx` — `joinError` (строка 66, рендер inline visitor-секции 274-278 и в apply-modal 330-334) для handleJoin / handleApply.
- `pages/EventPage.tsx`, `pages/InvitePage.tsx`, `components/CreateClubModal.tsx` — собственные локальные `error`/`setError`-стейты с inline-рендером.

### DiscoveryPage

DiscoveryPage сама по себе не имеет специфичных кнопок — взаимодействия идут через `ClubCard` и `ClubFilters` (уже покрыты выше). Прямых вызовов `useHaptic()` не требуется.

### Запрещённые точки (явно не добавляем)

Из §15 «Не добавлять в hover, скролл, автоматические события»:

- ❌ Скролл / pull-to-refresh (нет реализации)
- ❌ Автозагрузка следующей страницы DiscoveryPage (infinite scroll fetch — system event)
- ❌ `useEffect`-fetched data success (не пользовательское действие)
- ❌ `Toast` появление (system notification)
- ❌ MainButton клик (нативный Telegram-элемент сам генерит haptic)
- ❌ Hover-эффекты (на Desktop)

> **Был раньше в этом списке:** `BackButton`. На staging обнаружили что Telegram **не** генерит haptic на нативную back-кнопку надёжно (зависит от платформы / версии клиента) — сейчас в `useBackButton` явно добавлен `haptic.impact('light')` как fallback. См. секцию «Навигация назад» ниже.

---

## Acceptance Criteria

### AC-1: Хук компилируется и собирается
GIVEN ветка `feature/pre-design-haptic`
WHEN запустить `npm run build` в `frontend/`
THEN сборка проходит без TypeScript-ошибок
AND `npm test` в `frontend/` проходит зелёным
AND `useHaptic.ts` экспортирует именованный `useHaptic` с возвращаемым типом `Haptic`

### AC-2: Silent no-op на платформах без поддержки
GIVEN среда, в которой все три `hapticFeedback*.isAvailable()` возвращают `false` (Desktop Telegram до 6.1, браузер вне Telegram, vitest без моков)
WHEN компонент вызывает `haptic.impact('light')`, `haptic.notify('success')`, `haptic.select()`
THEN ни один вызов не бросает исключение
AND в консоли отсутствуют `warn` / `error` от хука
AND UI отрабатывает событие штатно (навигация / submit проходят)

### AC-3: Стабильная ссылка возвращаемого объекта
GIVEN компонент с `const haptic = useHaptic()`
WHEN компонент ререндерится без размонтирования
THEN референс `haptic` не меняется между рендерами (проверяется `Object.is` или `useEffect([haptic])`, который вызывается ровно один раз)

### AC-4: Точки кода покрыты
GIVEN таблица «Маппинг паттерны → точки кода»
WHEN Tester проходит чек-лист руками
THEN каждая строка таблицы соответствует реальному вызову в коде (grep по `haptic.impact|haptic.notify|haptic.select` находит вызов в указанном файле/функции)

### AC-5: Поведение в Telegram (мобильный клиент)
GIVEN iOS или Android Telegram-клиент с Bot API ≥ 6.1
WHEN пользователь:
- (a) переключает таб в `BottomTabBar`
- (b) тапает по `ClubCard` в DiscoveryPage
- (c) меняет chip в `ClubFilters`
- (d) нажимает «Вступить» на `ClubPage` для открытого клуба и операция успешна
- (e) submit'ит финальный шаг wizard'а в `CreateClubModal` (открытый из `MyClubsPage`) с ошибкой сервера
THEN ощущается haptic:
- (a) `selectionChanged` — короткий tick
- (b) `impactOccurred('light')` — лёгкий тап
- (c) `selectionChanged` — короткий tick
- (d) `impactOccurred('medium')` на клик + `notificationOccurred('success')` через ~200-500мс
- (e) `impactOccurred('heavy')` на клик + `notificationOccurred('error')` через ~200-500мс

### AC-6: Поведение вне Telegram-клиента
GIVEN приложение открыто в браузере (vite dev / production URL без Telegram)
WHEN пользователь повторяет сценарии (a)-(e) из AC-5
THEN ни один сценарий не бросает ошибку
AND навигация / mutate-действия выполняются как до этого PR
AND DevTools Console не показывает новых warn/error относительно baseline

---

## Non-functional

- **Производительность:** хук не делает аллокаций в рендере (мемоизация), вызовы методов синхронные, без подписок. Влияние на bundle — < 1 KB gzipped (re-export SDK, который уже импортирован).
- **Безопасность:** API не принимает user input — статические enum-строки в коде. Уязвимостей нет.
- **Логирование:** хук молчит. Логирование haptic-событий не нужно (это UI-сахар, не бизнес-эвент).
- **Совместимость:** Bot API 6.1+. Ниже — silent fallback (см. AC-2).

---

## Out of scope (этого PR)

- ~~Миграция формы `OrganizerPage` wizard / `CreateClubModal` на React Hook Form — отдельный PR `feature/pre-design-rhf-forms`~~ — выполнено в PR #26 `feature/pre-design-rhf-wizard`. `CreateClubModal` теперь в `frontend/src/components/CreateClubModal.tsx` (вынесен из удалённого `OrganizerPage` в PR `feature/restructure-bottom-tabs`)
- Замена Zustand на TanStack Query для server-state — отдельный PR `feature/pre-design-stores`
- Haptic для hover / scroll / pull-to-refresh / infinite-scroll fetch — запрещено §15
- Haptic для системных Toast-уведомлений (появление Toast не от прямого user action — не haptic'ается)
- Расширение API хука: `vibrate(pattern)` / кастомные duration / `useHapticOnMount` — YAGNI, добавим когда будет 3+ кейса
- Haptic для long-press quick actions (long-press пока не реализован — §18 «📦»)

---

## Зависимости и следующие шаги

**Зависит от:**
- `feature/pre-design-infra` (PR #22) — `useHaptic.ts` ляжет рядом с `useBackButton.ts`, без блокирующих зависимостей по коду

**Следующий шаг:**
- `feature/pre-design-stores` — миграция server-state на TanStack Query. После него haptic в `notify('success' / 'error')` переедет с inline `.then/.catch` в `onSuccess` / `onError` callbacks мутаций (`useMutation`). Сам хук менять не придётся — только точки вызова.

---

## Связанные документы

- [`docs/design/telegram-constraints.md` §15](../design/telegram-constraints.md) — каноничный список haptic-паттернов Telegram
- [`docs/modules/frontend-core.md`](./frontend-core.md) — структура хуков, рядом с `useBackButton`
- [`.claude/rules/frontend.md`](../../.claude/rules/frontend.md) § «Telegram Mini Apps SDK / HapticFeedback» — общие правила использования
