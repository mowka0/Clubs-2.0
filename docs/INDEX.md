# Индекс документации

Карта: **что правишь в коде → какие доки сверять**. Заменяет греп по всему `docs/`
(157 файлов, 36 тысяч строк) во время docs alignment.

## Как пользоваться

1. Взять список изменённых файлов: `git diff --name-only master...HEAD`.
2. Найти строки в § 1 по затронутым пакетам/страницам — это **вся** зона сверки.
3. Сверить код с найденными спеками + разделом PRD.
4. Файлы из § 4 (архив) **в сверке не участвуют** — открываются только по прямой ссылке.

Индекс не отменяет **грепа по коду** на ключевые значения правки (имена env-переменных,
числовые литералы, имена классов). Этот шаг дешёвый и ловил реальные прод-баги — см.
`.claude/agents/07-analyst.md` § Post-flight Docs Alignment.

Если тронутого пакета нет в таблице — значит модуль появился после последней правки
индекса. Дописать строку в § 1 — часть той же задачи.

---

## 1. Зона сверки: код → спека

### Backend (`backend/src/main/kotlin/com/clubs/<пакет>`)

| Пакет | Спеки для сверки |
|---|---|
| `activity` | `unified-activity-creation.md`, `events-feed.md` |
| `application` | `application.md`, `applications-inbox.md` |
| `auth` | `auth.md` |
| `award` | `member-admin-profile.md` |
| `bot` | `telegram-bot.md`, `club-chat-link.md` |
| `chatlink` | `club-chat-link.md` |
| `city` | `city-dictionary.md` |
| `club` | `clubs.md`, `club-page-unified.md`, `club-invites.md`, `club-leave.md`, `club-interests.md` |
| `clubquality` | `club-quality.md` |
| `common/auth` (`ClubRoleGuard`, `RoleCapabilities`, `ClubCapability`) | `club-roles.md`, `co-organizers.md` |
| `common/security` (`SecurityConfig`, `RateLimitFilter`) | `auth.md`, `infrastructure.md` |
| `common/util`, `common/dto` | спека модуля-потребителя (см. вызывающий пакет) |
| `event` | `events.md`, `event-vote-block.md`, `event-stage2-composition.md`, `event-geo.md` |
| `eventtemplate` | `event-templates.md` |
| `feedback` | `feedback.md` |
| `geo` (`SuggestService`, `CityCenterRepository` → подсказки; `GeocoderService` → гео события) | `venue-search.md`, `event-geo.md` |
| `interest` | `club-interests.md` |
| `membership` | `membership.md`, `membership-lifecycle.md` |
| `payment` | `payment.md`, `payment-v2.md` |
| `reputation` | `reputation.md`, `reputation-v2.md`, `reputation-path-back.md` |
| `skladchina` | `skladchina.md` |
| `storage` | `infrastructure.md` |
| `subscription` | `payment-v2.md`, `membership-lifecycle.md` |
| `user` | `profile.md`, `profile-quest.md` |

**Миграции** `backend/src/main/resources/db/migration/` → спека модуля, чью таблицу трогает,
**плюс** `PRD-Clubs.md` § 5.1 (именования колонок).

### Frontend (`frontend/src/pages/`)

| Страница | Спеки для сверки |
|---|---|
| `DiscoveryPage.tsx` | `discovery-card.md`, `discovery-redesign.md` |
| `ClubPage.tsx` | `club-page-unified.md`, `clubs.md` |
| `MyClubsPage.tsx` | `my-clubs-unified.md`, `applications-inbox.md`, `reputation-path-back.md` |
| `ActivitiesPage.tsx` | `events-feed.md`, `unified-activity-creation.md` |
| `EventPage.tsx` | `events.md`, `event-vote-block.md`, `event-stage2-composition.md` |
| `CreateEventPage.tsx` | `events.md`, `event-templates.md`, `event-geo.md`, `venue-search.md` |
| `EditEventTemplatePage.tsx` | `event-templates.md` |
| `SkladchinaPage.tsx`, `CreateSkladchinaPage.tsx`, `CreateSplitBillPage.tsx` | `skladchina.md` |
| `ProfilePage.tsx` | `profile.md`, `profile-quest.md` |
| `InvitePage.tsx` | `club-invites.md` |
| `OrganizerClubManage.tsx` + `src/components/manage/` | `club-roles.md`, `co-organizers.md`, `member-admin-profile.md`, `club-chat-link.md` |
| `ClubSetupWizard.tsx` + `src/components/club/setup/` | `club-chat-link.md` § «После подключения: мастер наполнения клуба» |
| `FeedbackPage.tsx` | `feedback.md` |

### Сквозное (frontend)

| Что трогаешь | Спеки для сверки |
|---|---|
| `src/api/`, `src/telegram/`, `router.tsx` | `frontend-core.md` |
| `src/config/` (`PRODUCT_PROFILE`), `HomeRoute.tsx`, `BottomTabBar.tsx` | `frontend-core.md` § «Спринт 1.0, День 1» |
| `src/store/`, `src/queries/` | `frontend-stores.md` |
| `src/utils/` | спека модуля-потребителя (см. импортирующие страницы и компоненты) |
| `src/styles/`, общие компоненты | `redesign-banco-style.md`, `ui-pages.md` |
| анимации / вибро / жесты | `haptic.md`, `swipe-navigation.md` |
| пустые экраны, первый вход | `empty-states.md`, `onboarding.md` |

### Инфраструктура

| Что трогаешь | Спеки для сверки |
|---|---|
| `docker-compose*.yml`, `Dockerfile`, `nginx.conf`, `.github/workflows/` | `infrastructure.md` + `CLAUDE.md` § Infrastructure |
| `application*.yml`, env-переменные | `infrastructure.md` + спека модуля-потребителя |

---

## 2. Живые спеки (`docs/modules/`)

48 файлов. Дата — последняя правка спеки, метка свежести, не гарантия актуальности.

### Клубы и участники
| Файл | О чём | Правлен |
|---|---|---|
| `clubs.md` | базовый CRUD клуба, поля, доступ | 2026-08-11 |
| `club-page-unified.md` | единая страница клуба для всех ролей | 2026-08-11 |
| `club-roles.md` | движок капабилити (14 прав, fail-closed) | 2026-08-16 |
| `club-roles-testplan.md` | ручной тест-план ролей на staging | 2026-07-13 |
| `co-organizers.md` | назначение со-организаторов | 2026-07-30 |
| `club-invites.md` | приглашения, два инвайт-кода, полный клуб | 2026-07-31 |
| `club-leave.md` | выход из клуба, выход-с-обязательствами | 2026-07-21 |
| `club-interests.md` | темы клуба поверх категории | 2026-08-05 |
| `club-quality.md` | срезы качества, скрытый ранг L3 | 2026-08-05 |
| `membership.md` | членство, статусы, вступление | 2026-07-07 |
| `membership-lifecycle.md` | статусная модель, honor-system | 2026-08-10 |
| `member-admin-profile.md` | карточка участника, награды, кик | 2026-07-21 |
| `application.md` | заявка на вступление | 2026-07-13 |
| `applications-inbox.md` | кросс-клубовый инбокс заявок | 2026-07-13 |
| `city-dictionary.md` | справочник городов, `city_id` FK | 2026-08-01 |

### Встречи и активности
| Файл | О чём | Правлен |
|---|---|---|
| `events.md` | встречи, двухэтапное подтверждение | 2026-08-16 |
| `events-feed.md` | вкладка «Активности», история | 2026-08-16 |
| `event-vote-block.md` | блок «Набор» на странице события | 2026-08-16 |
| `event-stage2-composition.md` | состав Этапа 2, таб «Без ответа» | 2026-08-16 |
| `event-templates.md` | шаблоны встреч | 2026-08-12 |
| `event-geo.md` | гео к событию, Яндекс.Карты | 2026-08-10 |
| `venue-search.md` | поиск места по заведениям (не начат) | 2026-08-10 |
| `unified-activity-creation.md` | единое создание активностей через «+» | 2026-08-12 |
| `skladchina.md` | складчины и сборы внутри клуба | 2026-07-21 |

### Чат, бот, репутация
| Файл | О чём | Правлен |
|---|---|---|
| `club-chat-link.md` | связка клуб ↔ чат, дверь, живой закреп | 2026-08-15 |
| `telegram-bot.md` | бот, вебхуки, уведомления | 2026-08-11 |
| `reputation.md` | базовая репутация | 2026-07-21 |
| `reputation-v2.md` | ledger, XP, уровни | 2026-07-23 |
| `reputation-path-back.md` | путь назад, асимметричная видимость Trust | 2026-07-05 |

### Деньги
| Файл | О чём | Правлен |
|---|---|---|
| `payment.md` | взносы, оплата участником | 2026-08-10 |
| `payment-v2.md` | монетизация v2, подписка организатора | 2026-07-07 |

### Пользователь и вход
| Файл | О чём | Правлен |
|---|---|---|
| `auth.md` | initData, JWT, rate limit | 2026-04-25 |
| `profile.md` | глобальный профиль, редактирование | 2026-08-05 |
| `profile-quest.md` | квест заполнения профиля, 50 XP | 2026-08-01 |
| `onboarding.md` | онбординг v3, превью экранов | 2026-08-01 |
| `feedback.md` | форма обратной связи, DM саппорту | 2026-07-23 |

### Frontend-платформа
| Файл | О чём | Правлен |
|---|---|---|
| `frontend-core.md` | SDK, apiClient, роутинг | 2026-07-29 |
| `frontend-stores.md` | Zustand, react-query | 2026-07-31 |
| `ui-pages.md` | страницы, общая структура | 2026-08-11 |
| `redesign-banco-style.md` | дизайн-система Banco-Plata | 2026-08-11 |
| `discovery-card.md` | карточка клуба v2, полка недели | 2026-08-05 |
| `discovery-redesign.md` | экран Discovery | 2026-07-31 |
| `my-clubs-unified.md` | «Мои клубы» + «Организатор» | 2026-07-29 |
| `create-club-form.md` | форма создания клуба | 2026-07-31 |
| `empty-states.md` | пустые состояния, лис-маскот | 2026-08-01 |
| `haptic.md` | вибро-отклик | 2026-07-29 |
| `swipe-navigation.md` | свайпы назад/вперёд | 2026-07-29 |

### Инфраструктура
| Файл | О чём | Правлен |
|---|---|---|
| `infrastructure.md` | Docker, Coolify, CI/CD, nginx | 2026-08-15 |

---

## 3. Стратегия и решения (`docs/design/*.md`)

Читаются при вопросах «зачем» и «куда идём». В рутинной сверке не участвуют — кроме
случая, когда правка меняет само продуктовое решение.

| Файл | О чём |
|---|---|
| `sprint-1.0-chat-pivot.md` | **действующий план**: разворот на плагин к чату, гейты, финмодель |
| `market-analysis-and-product-strategy-2026-07.md` | анализ рынка, конкуренты (в силе) |
| `strategy-simple-summary-2026-07.md` | краткая версия стратегии (в силе) |
| `payment-monetization-v2.md` | модель монетизации |
| `telegram-constraints.md` | ограничения платформы Telegram — **читать перед любой чат-фичей** |
| `stack.md` | технологический стек, справочник |

Подпапки `docs/design/<фича>/mockups/` — HTML-мокапы и картинки дизайн-сессий.
Артефакты обсуждения, **в сверке не участвуют**.

Активная дизайн-сессия: `docs/design/event-roster-threshold/` — порог набора для формата
«🎟 Встреча с местами» (решения PO 2026-08-21 + мокап экранов). Хэндофф для разработки —
`docs/backlog/event-roster-threshold-handoff.md`.

---

## 4. Вне зоны сверки

| Что | Объём | Статус |
|---|---|---|
| `docs/backlog/` | 92 файла, 10 149 строк | архив: хэндоффы, разборы багов, чек-листы прошлых сессий. Открывается **только по прямой ссылке**; греп по нему при alignment не делается |
| `docs/completion/` | 4 файла | отчёты TASK-001/002/007/023, историческое |
| `docs/qa/` | 1 файл | `docs/qa/reputation-test-plan.md` — ручной тест-план |
| `docs/design/**/mockups/` | ~250 файлов | HTML/PNG дизайн-сессий |

Дописывать в `docs/backlog/` при фиксации gap'ов **по-прежнему нужно** — вывод из зоны
сверки означает «не читаем целиком при каждой правке», а не «не ведём».

---

## 5. Корневые документы

| Файл | Роль |
|---|---|
| `CLAUDE.md` | правила проекта, флоу, команды сборки. **Читается всегда** |
| `PRD-Clubs.md` | требования к продукту. Сверяется раздел, соответствующий модулю |
| `ARCHITECTURE.md` | API-контракты, схема БД, структура пакетов |
| `progress.md` | журнал выполненных задач |
| `tasks.json` | задачи исходной декомпозиции (45 шт.) |
| `.claude/agents/*.md` | инструкции агентов |
| `.claude/rules/*.md` | правила кода: принципы, именование, ошибки, безопасность, ревью |
