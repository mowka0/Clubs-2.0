# Онбординг + интересы пользователя + recommendations по интересам

**Статус:** partially-done · **Создано:** 2026-05-18 · **Last update:** 2026-07-13 · **Origin:** обсуждение empty state для EventsPage (lента активностей) — пользователю с 0 клубов нужно показывать «рекомендуем клубы с ближайшими событиями», для этого нужны интересы пользователя.

> **Status update (2026-07-13, `feature/onboarding`): Часть 2 закрыта, но НЕ так, как здесь описано.**
> Онбординг первого входа реализован — и **без единого вопроса**: карусель из 3 слайдов (что это →
> путь участника → путь организатора), две двери-кнопки. Ни города, ни категорий, ни интересов не
> спрашиваем: клубов в системе нет, подбирать не из чего, и вопрос про интересы превращается в ритуал,
> за который человек платит временем и ничего не получает (решение PO).
> - Триггер показа — **явная колонка** `users.onboarded_at` (V60), а не производный признак «0 интересов»:
>   производный ломается при ошибке запроса («данные не пришли» ≠ «данных нет») и выкатывает онбординг
>   поверх обжитого аккаунта (баг F5-20).
> - Эндпоинт `POST /api/users/me/onboarding { door: MEMBER | ORGANIZER }`; дверь не хранится, уходит в INFO-лог.
> - `OnboardingIntroPage` / `OnboardingCategoriesPage` **не появятся** — онбординг сделан гейтом в `Layout`,
>   а не роутом (роут обходится навигацией). Моки `05/06/11-onboarding-*.html` — мёртвые, актуальный
>   мокап: `docs/design/onboarding/mockups/onboarding-flow.html`.
> - Спека: [`docs/modules/onboarding.md`](../modules/onboarding.md).
>
> **Что осталось open здесь:** сбор интересов при первом входе (вернуть, когда в системе появятся клубы —
> тогда подбор станет осмысленным), Часть 3 (recommendations), вопрос «каталог категорий vs свободный
> словарь». Проработанная таксономия «категория → интерес» (миграция, словарь на ~100 интересов, правила
> подбора) лежит в истории ветки `feature/onboarding`, коммиты `bda16be`…`947b3be` — не изобретать заново.

> **Status update (2026-05-30, `feature/profile-reputation-and-skladchina-badge`):**
> **Часть 1 (модель интересов) и редактирование интересов в `ProfilePage` — реализованы.** Подробности — [`docs/modules/profile.md`](../modules/profile.md). Конкретно:
> - V16: таблицы `interests (id, name UNIQUE, usage_count)` + `user_interests (user_id, interest_id)` + префиксный btree-индекс `varchar_pattern_ops`. Решено хранить интересы **свободным словарём** (не enum), а каталог категорий — отложено.
> - Эндпоинты: `PATCH /api/users/me { country?, city?, bio?, interests }`, `GET /api/users/me/interests`, `GET /api/interests/suggest?q=&limit=` (префиксный автокомплит).
> - Нормализация интересов: NFC → trim → снять кавычки → схлопывание пробелов → lowercase → `ё→е` → truncate 40. Разделитель — запятая (фразы из нескольких слов разрешены). Лимит 15 интересов на юзера.
> - В `ProfileEditModal` — `InterestsInput` с дебаунс-автокомплитом, в `ProfilePage` — чипы `.pf-tag` в read-view.
>
> **Что остаётся open в этом backlog'е:**
> - ~~**Часть 2 (онбординг при первом запуске)** — `OnboardingIntroPage` / `OnboardingCategoriesPage` не реализованы; триггер «если у юзера 0 интересов — показать онбординг» отсутствует.~~ **Закрыто 2026-07-13 (см. блок выше): онбординг есть, но без вопросов и без интересов; триггер — `users.onboarded_at`, а не «0 интересов».** Заполнить интересы по-прежнему можно в Профиле через ⚙️.
> - **Часть 3 (recommendations по интересам)** — `GET /api/users/me/recommended-clubs` не реализован; `EventsPage` empty state остаётся stub'ом. MyClubs recommendations всё ещё geo-only.
> - **Каталог категорий vs свободный словарь.** В этой итерации выбран свободный словарь со словарём `interests`. Если на этапе онбординга появится фиксированный каталог категорий (Часть 2 / `R-1`) — нужно решить, как он соотносится со свободными интересами: отдельная сущность, мэппинг, или категории = «избранные» интересы в том же словаре. См. также `R-3` про `club.tags`.
>
> Старый текст ниже описывает изначальный план. Сохраняется для исторического контекста; актуальная архитектура интересов — в `profile.md`.

## Проблема

1. **HTML-моки онбординга есть, кода нет.** В `docs/design/discovery-redesign/mockups/` лежат `05-onboarding-intro.html`, `06-onboarding-categories.html`, `11-onboarding-categories-live.html` — пользователь выбирает категории интересов при первом заходе. Это нарисовано, но в `frontend/src/pages/` онбординг-страницы отсутствуют, поле интересов в БД нет.
2. **Дизайн-моки устарели.** Сделаны до brand-redesign серии (PR #33/#40/#41 = navy+brass + BrandBackdrop) — нужно переделать под текущий brand-стиль.
3. **Recommendations сейчас работают по geo-match** (см. `docs/backlog/myclubs-recommended-clubs.md` MVP). V2 этого backlog'а уже упоминает «по категориям клубов где user состоит» — но без явных интересов user'а холодный старт (0 клубов) работает плохо.
4. **EventsPage empty state** должен предлагать клубы с ближайшими событиями. Без интересов пользователя — рекомендуем что-то наугад / по geo / самые активные. Слабый сигнал.

## Идея

Единая фича из 3 связанных частей:

### Часть 1: модель интересов
- В БД: новая таблица `user_interests (user_id, category_id)` ИЛИ поле `interests: text[]` в `users`. Решается на этапе спеки
- Каталог категорий — фиксированный список (10-20 шт): «Спорт / Книги / Бизнес / Творчество / Технологии / ...» — определяется на этапе спеки
- У клубов уже есть `tags: text[]` — мэппинг tags ↔ категории решается на этапе спеки

### Часть 2: онбординг при первом запуске
- Реализовать HTML-моки `05` и `06` в React + brand-стиль
- Триггер: `users.interests IS NULL` (или эквивалент) при логине → показ онбординга
- 2-3 экрана: intro → выбор категорий (≥3 для перехода дальше) → done
- Кнопка «Пропустить» (потом задать в Профиле)
- Поле редактирования интересов в `ProfilePage` (отдельная секция)

### Часть 3: recommendations по интересам
- Backend: `GET /api/users/me/recommended-clubs` (уже спроектирован в `myclubs-recommended-clubs.md` V2)
- Сортировка: по overlap между `user_interests` и `club.tags`, потом по живому счёту участников (`memberCount`, считается из `memberships`)
- Используется:
  - В EventsPage empty state — «Эти клубы по твоим интересам уже планируют события»
  - В MyClubsPage (см. `myclubs-recommended-clubs.md` — мерж V1 geo-match → V2 interests)

## Зависимости и связи

- Этот backlog **заменяет/уточняет V2 секцию `myclubs-recommended-clubs.md`** (где было «collaborative-filtering lite по категориям клубов где user уже состоит»). После реализации онбординга — у user'а будут **явные** интересы, не косвенные
- `EventsPage` (текущая разработка, feature/events-feed-page) — в v1 empty state остаётся **stub'ом** «Вы пока не состоите в клубах. Перейти в Поиск» (без recommendations). Этот backlog потом подменит stub на recommendations-block
- Существующие HTML-моки — точка отсчёта для дизайна, но **переделать под brand** (navy+brass + BrandBackdrop)

## Что нужно сделать (примерный объём, ~1-2 недели solo)

### Backend
- Flyway миграция: `user_interests` / `interests text[]`
- Каталог категорий: `categories` таблица или enum (решить на спеке)
- `UserController` / `ProfileController`: `PATCH /api/users/me { interests: [...] }`
- `GET /api/users/me/recommended-clubs?context=events_feed` (опц. параметр уточняет сортировку — больше weight на upcoming events для EventsPage, на живой счёт участников для MyClubsPage)
- Тесты: ownership, валидация набора категорий, ratio recommendations при разных user-профилях

### Frontend
- `OnboardingIntroPage`, `OnboardingCategoriesPage` — реализация моков под brand-стиль
- Routing: если `user.interests == null` → редирект на `/onboarding` после auth
- В `ProfilePage` — секция «Мои интересы» с возможностью редактирования
- В `EventsPage` empty state — `<RecommendedClubsBlock context="events" />`
- В `MyClubsPage` — апгрейд секции «Рекомендуем» (если уже реализована по V1) на сортировку по интересам

## Acceptance Criteria (укрупнённо)

- **AC-1:** User с `interests == null` при логине попадает на `/onboarding`, выбирает ≥3 категорий, попадает на главную
- **AC-2:** User может «Пропустить» онбординг — попадает на главную, при этом recommendations работают в degraded-режиме (по geo / живому счёту участников)
- **AC-3:** В Профиле есть редактирование интересов
- **AC-4:** EventsPage empty state с recommendations: 3-5 клубов с upcoming events, отсортированных по interests-overlap
- **AC-5:** MyClubs recommendations используют те же интересы (consistent recommendations across pages)
- **AC-6:** Privacy: private клубы не попадают в recommendations

## Открытые вопросы

- **R-1:** Каталог категорий — фиксированный (мы определяем) или редактируемый (community / admin)? Для MVP — фиксированный, edit через миграцию
- **R-2:** Минимум категорий для прохождения онбординга — 3 (заставляем подумать) или 1 (минимальное трение)?
- **R-3:** Мэппинг существующих `club.tags` (свободные строки) на категории — лекаль или ручной?
- **R-4:** Что показывать в onboarding-categories — иконки + название? Только название? Цветные tag-pills?
- **R-5:** Reauth-онбординг — если user уже зарегистрирован и есть интересы, но мы добавили новые категории — переспрашивать?

## Не приоритет

Не блокер для текущего MVP (events-feed, складчина потом). Чем раньше внедрим — тем релевантнее будут recommendations на росте базы. Без интересов база до ~500 user'ов работает на geo-match, дальше нужна явная сегментация.

## Связанное

- `docs/design/discovery-redesign/mockups/05-onboarding-intro.html` — мок intro
- `docs/design/discovery-redesign/mockups/06-onboarding-categories.html` — мок выбора категорий
- `docs/design/discovery-redesign/mockups/11-onboarding-categories-live.html` — live-вариант
- `docs/backlog/myclubs-recommended-clubs.md` — recommendations в MyClubs, V2 секция = этот backlog
- `docs/modules/events-feed.md` — EventsPage spec, empty state v1 — stub без recommendations (этот backlog потом подменит)
- `PRD-Clubs.md` — продуктовые требования
