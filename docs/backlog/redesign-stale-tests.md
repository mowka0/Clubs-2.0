# Устаревшие тесты после Banco-редизайна (PR #48/#49)

**Статус:** ✅ RESOLVED (`bugfix/redesign-stale-tests`, 2026-06-03) · **Найдено:** 2026-06-03

## Резолюция

Ассерты приведены к фактической rd-разметке. `npm test` зелёный (81/81, было 84
с 12 fail — 3 устаревших thumb/фото/эмодзи-теста ActivityCard консолидированы в 2,
т.к. фичи удалены редизайном). Ключевые изменения:
- `ActivityCard.test.tsx`: «5/20» → `rd-ft-stat-num`/`rd-ft-stat-cap`; thumb/фото/type-emoji
  тесты схлопнуты в один «карточка текстовая»; `.completed`-класс → инлайн `opacity`;
  складчина → «collected / goal» + `%` собрано.
- `ClubPage.test.tsx`: цена «200 Stars / мес» — substring-match (единый hero-eyebrow).
- `ActivitiesPageCreate.test.tsx` → переименован в `AppDockCreate.test.tsx`: in-page «+»
  убран редизайном, гард организатора переехал в `AppDock` (FAB всегда виден; организатор →
  поток, иначе → toast). `AppDock` экспортирован из `Layout.tsx` для теста.
- `ActivityFeedList.test.tsx`: аккордеон `.activity-past-body.open`/`.activity-list.compact[hidden]`
  → условный монтаж `.rd-rep-panel` + `aria-expanded`.

---

## (исходный контекст)


## Суть

12 frontend-тестов падают, потому что проверяют **старую (до-редизайна) разметку**.
Редизайн Banco-Plata (PR #48/#49) поменял JSX компонентов на `rd-`классы, но ассерты
тестов не обновили. **Это не регрессии** — компоненты работают, тесты отстали.

Подтверждено: 12 fail / 72 pass **идентично на master** и на ветках — независимо от текущей работы.

## Что чинить

| Файл | Падений | Тест ждёт (старое) | Реальность (rd-редизайн) |
|---|---|---|---|
| `src/test/components/ActivityCard.test.tsx` | 9 | бейдж `«5/20»`, thumb с эмодзи, `💰`-placeholder | градиентное число `rd-ft-stat-num` («5» + подпись «идут»), thumb убран (Этап 3.2 `redesign-banco-style.md`) |
| `src/test/pages/ClubPage.test.tsx` | 1 | текст `«200 Stars / мес»` | hero-eyebrow форматирует цену через `formatPrice` + `·` (напр. `«200 Stars»`) |
| `src/test/pages/ActivitiesPageCreate.test.tsx` | 1 | кнопка с именем `/создать/i` в странице | дубль-кнопка «Создать» удалена, создание ушло в FAB дока |
| `src/test/components/ActivityFeedList.test.tsx` | 1 | DOM-класс аккордеона «Прошедшие (N)» | структура аккордеона переразмечена под rd |

## Как чинить

Привести ассерты к новой rd-разметке (по факту рендера компонентов), не трогая
логику компонентов. Отдельная ветка `bugfix/redesign-stale-tests`. После — `npm test`
должен быть зелёным (84/84).

## Контекст

- Дизайн-система rd-: `frontend/src/styles/redesign.css`, спека `docs/modules/redesign-banco-style.md`.
- Не связано с `feature/redesign-club-manage` (Управление клубом) — те тесты предшествуют ей.
