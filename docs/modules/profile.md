# Profile — глобальный экран «Профиль» + редактирование

> **Origin:** `feature/profile-reputation-and-skladchina-badge` (2026-05-24..2026-05-30).
> Объединяет три связанных изменения: бренд-редизайн ProfilePage, перенос
> per-club репутации из карточки клуба в глобальный профиль, редактирование
> профиля (страна/город/«о себе»/интересы) с авто-словарём интересов.

## Цель

Сделать «Профиль» единым местом «обо мне»:
- видна моя репутация по **всем клубам** сразу (раньше — только внутри каждой карточки клуба отдельным табом);
- доступно редактирование пользовательских полей, которые не приходят из Telegram (страна/город, «о себе», интересы);
- интересы нормализуются и собираются в общий словарь с авто-подсказками, чтобы дубликаты схлопывались и можно было дальше строить рекомендации (см. backlog `onboarding-interests-and-recommendations.md`).

## Scope

### Входит
- Бренд-редизайн `ProfilePage` (раньше — TGUI `List/Section/Cell`, теперь `.brand-page` + `BrandBackdrop`).
- Шапка-идентичность (аватар/имя/@username/город+страна).
- Секция «О себе» (читалка bio).
- Секция «Интересы» (чипы).
- Секция «Моя репутация» — список клубов с индексом надёжности; плашка-CTA если клубов нет.
- ~~Секция «Активные заявки» (pending applications).~~ **Удалена** (2026-05-31, см. § «Updates»).
- Шестерёнка ⚙️ в шапке → модалка редактирования (свой portal, под z-index CityPicker'а).
- Эндпоинты `GET/PATCH /api/users/me`, `GET /api/users/me/reputation`, `GET /api/users/me/interests`, `GET /api/interests/suggest`.
- Миграция V16 (поля `users.country`, `users.bio` + таблицы `interests`, `user_interests` + префиксный индекс).
- Удаление per-club таба «Мой профиль» внутри карточки клуба (был `ClubProfileTab.tsx`, удалён).

### НЕ входит
- Онбординг при первом запуске (`docs/backlog/onboarding-interests-and-recommendations.md` Part 2).
- Recommendations клубов по интересам (там же Part 3 + `myclubs-recommended-clubs.md` V2).
- Редактирование имени / аватара / @username — это TG-managed поля, перезаписываются при каждом auth через `UserRepository.upsert` (см. `frontend-core.md` § auth).
- Распознавание опечаток в интересах (`pg_trgm`) — fallback, добавим если упрёмся.
- Кросс-клубовый «топ-N надёжных» — рейтинги вне scope.

---

## PRD drift (фикс этого PR)

PRD §4.3 описывает «Внутренний экран клуба: События / Участники / Мой профиль» (см. §4.3.2 «Профиль участника» — три метрики репутации крупно). Реализация изменена:

- Таб **«Мой профиль» внутри карточки клуба удалён.** Карточка клуба теперь содержит «Активности», «Участники» (+ «Управление» у организатора).
- **Per-club self-view** (мои метрики в этом клубе) → переехал в глобальную секцию «Моя репутация» в `ProfilePage`. Там — карточка на клуб с индексом надёжности и компактной строкой метрик. Полные метрики (обещания % / подтверждения / посещения) видны при тапе по самому себе в табе «Участники» через `MemberProfileModal` (peer-view = self-view, одна модалка для всех).
- PRD §7.2 «Внутренний экран клуба: Табы — Активности / Участники / Мой профиль» → актуально: Активности / Участники.

Эта спека — источник истины для текущего поведения. PRD §4.3.2 концептуально не противоречит (репутация per-club — да, считается per-club; меняется только UI-локация показа).

---

## UI

### Маршрут и обёртка

- Маршрут: `/profile` (нижний таб «Профиль»), компонент `frontend/src/pages/ProfilePage.tsx`.
- Канвас: `<div className="brand-page"><BrandBackdrop />…</div>` — единый стиль с MyClubsPage / ClubPage / ActivitiesPage.

### Структура страницы (сверху вниз)

```
mc-hero
 ├ h1: «Твой профиль»
 └ pf-gear (⚙️)            ← открывает ProfileEditModal

pf-identity
 ├ аватар (img или brass-circle с инициалами)
 ├ имя + @username
 └ pf-identity .location    ← «Москва, Россия» если city+country заданы

pf-bio (если user.bio задан)

mc-section-label «Интересы»  (если interests.length > 0)
pf-tags
 └ pf-tag × N

mc-section-label «Моя репутация»
 ├ list pf-rep-list             — если есть клубы
 └ mc-empty (plate с CTA)       — если клубов нет
```

> **Update (`feature/applications-inbox`, 2026-05-30):** ранее фильтр был
> расширен с `status === 'pending'` до `pending | rejected | auto_rejected`,
> чтобы у отклонённых заявок было место показать причину (`rejectedReason`).
>
> **Update (`feature/applications-inbox`, 2026-05-31):** секция «Активные
> заявки» **удалена из ProfilePage**. Причина: показывали уже-просроченные
> rejected/auto_rejected заявки, которые юзеру не интересны, и пересекалась
> по смыслу с секцией «Заявки» на MyClubsPage. Все статусы заявок (pending /
> rejected / auto_rejected / approved-awaiting-payment) теперь живут только
> на `/my-clubs`. Соответствующие импорты (`useMyApplicationsQuery`,
> `useQueries`, `getClub`, `ClubDetailDto`) удалены из `ProfilePage.tsx`.

### Карточка `.pf-rep-card`

Лаконичная: avatar + название клуба + (опц. строка метрик) + индекс надёжности справа.

- **Avatar**: `r.clubAvatarUrl ? <img/> : initials` (нейтральный фон, без category-gradient — лишний шум на этом блоке).
- **Body**:
  - `<div className="name">{clubName}</div>`
  - `<div className="metrics">обещания N% · M подтв. · K посещ.</div>` — рендерится только при `hasScore` (есть число) и ненулевой активности (новичкам/owner не показываем обманчивые нули).
- **Score**: при `reliabilityIndex !== null` — крупное число + caption «надёжность», цвет по тиру; при `null` — **«Новичок»** (нет числа), а для своего клуба (`role === 'organizer'`) — организаторская рамка «репутация за организаторские качества». Средняя надёжность в шапке **исключает** null-клубы (не NaN). Тиры:
  - high (≥85) → `var(--brand-live)` (зелёный)
  - mid (70–84) → `var(--brand-brass-deep)` (латунь)
  - low (<70) → `var(--brand-ink-3)` (серый)

Тап по карточке → `navigate('/clubs/{r.clubId}')`.

> Категория/роль/срок в клубе **в карточке не показываются**. Эти поля DTO остались (см. § «API»), на случай если визуал вернётся.

### Плашка «нет клубов»

Когда `reputation.length === 0` — секция «Моя репутация» рендерит `.mc-empty` (тот же класс что у пустого MyClubsPage) с текстом «Тут появится репутация. Вступи в клуб — будем считать твою надёжность по каждому из них» и CTA `ghost-btn` «Найти клуб» → `/`.

Это **заменяет** прежний нижний placeholder «Профиль пока пуст» — теперь подсказка живёт прямо рядом с интересами/секцией репутации, где её и должно быть видно.

### Модалка редактирования (`ProfileEditModal`)

Свой `createPortal` sheet (overlay `.pf-edit-overlay` z-index `150`, sheet `.pf-edit-sheet` z-index `151`) — **не** TGUI `<Modal>` потому что CityPicker открывается на z-index `200/201` и должен оказываться поверх; TGUI Modal принудительно ставится на `1000` и накрыл бы CityPicker.

Поля:
- **Город** — `<button className="pf-edit-field">` показывает `«Москва, Россия»` или плейсхолдер «Не указан»; тап открывает `<CityPicker>` (переиспользование общего компонента, `CityChoice = { country, city }`).
- **О себе** — `<textarea>` с `maxLength={280}` + счётчик.
- **Интересы** — `<InterestsInput value onChange>` (см. ниже).
- Действия: «Отмена» (ghost) / «Сохранить» (brass).

Сохранение → `PATCH /api/users/me` с **полным** состоянием формы (`{ country, city, bio, interests }`):
- пустая строка / `null` → backend очистит поле;
- интересы — массив, заменяет полностью.

После успеха — `setUser(updated)` в `useAuthStore` + invalidate `clubs.myInterests()`.

**Защита от затирания**: шестерёнка disabled пока `useMyInterestsQuery().isPending` — иначе модалка могла бы открыться с `initialInterests=[]` и save очистил бы реальные интересы.

### Компонент `InterestsInput` (`components/profile/InterestsInput.tsx`)

Чип-инпут с авто-подсказками:
- Placeholder при пустом списке: **«Введите через запятую»**.
- Разделитель — **только запятая** (Enter тоже коммитит). Пробелы — НЕ разделители, чтобы можно было ввести фразу «настольные игры» одним токеном.
- Backspace на пустом инпуте — удаляет последний чип.
- Дебаунс 250 мс → `useInterestSuggestQuery(debounced)`; запрос отправляется при `debounced.length >= 2`, до этого dropdown не показывается.
- Suggestion-tap добавляет токен и чистит инпут.
- Лимиты: ≤15 интересов, ≤40 символов на токен (truncated).
- Клиентская нормализация (`normalizeInterest`) — зеркало серверной (см. § «Нормализация»), чтобы чип отображал ту же канонику, что попадёт в БД.

### CityPicker

Существующий компонент `frontend/src/components/CityPicker.tsx`: portal-sheet с каскадом «страна → город» (Россия / Беларусь / Казахстан / Армения / Грузия / ОАЭ / Турция). Дополнительно экспортирует helper `countryNameByCode(code)` — нужен ProfilePage для подписи `«Москва, Россия»`.

### Шестерёнка `pf-gear`

В шапке `.mc-hero-row` справа (там же, где у MyClubsPage / ActivitiesPage кнопка «Создать») — round icon button 40×40 с SVG `<GearIcon>`. `disabled` пока `interestsQuery.isPending`.

---

## API контракты

Все эндпоинты под `/api/**` — требуют JWT (`SecurityConfig` уже покрывает) + rate-limit 60/мин (`RateLimitFilter`). Дополнительной конфигурации не нужно.

### `GET /api/users/me`
Расширен полями `country`, `bio`. Возвращает `UserDto`.

```kotlin
data class UserDto(
    val id: UUID,
    val telegramId: Long,
    val telegramUsername: String?,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val city: String?,
    val country: String?,   // код страны (например "RU")
    val bio: String?        // ≤280 символов
)
```

`/api/auth/telegram` возвращает тот же `UserDto` в поле `user` — стор `useAuthStore.user` сразу получает новые поля без отдельного fetch.

### `PATCH /api/users/me` (NEW)

Полный replace user-editable полей. Форма всегда шлёт все четыре поля (включая интересы массивом), потому что бэкенд трактует `null`/`""` как «очистить».

```kotlin
data class UpdateMeRequest(
    @field:Size(max = 8)   val country: String? = null,
    @field:Size(max = 255) val city: String? = null,
    @field:Size(max = 280) val bio: String? = null,
    val interests: List<String> = emptyList()
)
```

Логика:
- `country/city/bio` — `blankToNull` → `users.country/city/bio`. `UPDATED_AT = NOW()`.
- `interests` — `InterestNormalizer.normalizeList` → upsert в словарь `interests` → diff против существующих `user_interests` → unlink/link с корректировкой `usage_count`.

Возвращает обновлённый `UserDto`. Имя / аватар / @username при этом **не трогаются** — они синхронизируются из Telegram через `UserRepository.upsert` на каждый auth и были бы перезаписаны.

### `GET /api/users/me/reputation` (NEW)

Агрегат: моя репутация по всем клубам, где состою (`status IN active|grace_period`, `club.is_active = true`). Один запрос вместо N вызовов `GET /api/clubs/{id}/members/{userId}` — питает «Моя репутация» в Profile.

Response: `List<UserClubReputationDto>`, упорядочен по `MEMBERSHIPS.JOINED_AT DESC NULLS LAST`.

```kotlin
data class UserClubReputationDto(
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val category: String,            // .literal от ClubCategory
    val role: String,                // .literal от MembershipRole
    val joinedAt: OffsetDateTime?,   // используется в peer-view, в карточке скрыт
    val reliabilityIndex: Int?,      // null = «Новичок» (outcome_count < 3) или владелец своего клуба
    val promiseFulfillmentPct: BigDecimal?,
    val totalConfirmations: Int?,
    val totalAttendances: Int?
)
```

> `category` / `role` / `joinedAt` сейчас не используются на фронте (в карточке оставлены только название + метрики + индекс), но возвращаются для будущих UI-итераций и совместимости. Удалять преждевременно — лишний цикл миграции при возврате.

### `GET /api/users/me/interests` (NEW)

Возвращает `List<String>` — отсортированный список названий интересов авторизованного юзера.

### `GET /api/interests/suggest?q=<prefix>&limit=10` (NEW)

Префиксный автокомплит. Нормализует `q` (NFC → trim → нижний регистр → ё→е), требует ≥2 символов после нормализации, `limit` clamp `[1, 10]`.

Под капотом: `SELECT name FROM interests WHERE name LIKE 'prefix%' ORDER BY usage_count DESC, name ASC LIMIT ?` — обслуживается индексом `idx_interests_name_prefix` (см. § «Миграция»).

Защищён JWT и накрыт глобальным rate-limit 60/мин на юзера (`RateLimitFilter`). При больших нагрузках первая остановка — Redis-кэш горячих префиксов (Redis уже в стэке). Сейчас YAGNI.

---

## Нормализация интересов

Источник истины — `com.clubs.interest.InterestNormalizer` (`backend/src/main/kotlin/com/clubs/interest/InterestNormalizer.kt`). Фронт повторяет ту же логику в `InterestsInput.normalizeInterest`, чтобы чип отображал то, что попадёт в БД.

Pipeline на каждый токен:

1. `Normalizer.NFC` (unicode canonical composition)
2. `trim()` пробелов с краёв
3. снять обрамляющие кавычки `" ' « » “ ” ‘ ’ \``
4. ещё раз `trim()`
5. внутренние пробелы → один пробел (`\s+` → ` `) — сохраняет фразы «настольные игры»
6. `lowercase()`
7. `replace('ё', 'е')` — дедуп `конёк/конек`
8. truncate до 40 символов

После — на список: `LinkedHashSet` (дедуп с сохранением порядка), cap 15.

Пустые токены (после нормализации) выкидываются.

### Дедуп в словаре

`InterestRepository.upsertAll(names)`:
- Для каждого имени `INSERT INTO interests (id, name) VALUES (..., ?) ON CONFLICT (name) DO NOTHING`.
- Затем `SELECT id, name FROM interests WHERE name IN (...)` → возвращает map `name → id` для линковки.

`usage_count` двигается **только** на реальные изменения связи: `replaceUserInterests` диффит текущий набор связей юзера и инкрементирует/декрементирует только для add/remove (если повторно сохранил тот же список — счётчики не дёргаются). Декремент с floor: `WHERE usage_count > 0` (защита от ухода в минус).

---

## Миграция V16

Файл: `backend/src/main/resources/db/migration/V16__add_profile_fields_and_interests.sql`.

```sql
ALTER TABLE users ADD COLUMN IF NOT EXISTS country VARCHAR(8);
ALTER TABLE users ADD COLUMN IF NOT EXISTS bio VARCHAR(280);

CREATE TABLE IF NOT EXISTS interests (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(40) NOT NULL UNIQUE,
    usage_count INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- varchar_pattern_ops — чтобы LIKE 'prefix%' использовал индекс
-- независимо от локали БД. Обычный btree в не-C locale prefix не ускоряет.
CREATE INDEX IF NOT EXISTS idx_interests_name_prefix ON interests (name varchar_pattern_ops);

CREATE TABLE IF NOT EXISTS user_interests (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    interest_id UUID NOT NULL REFERENCES interests(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, interest_id)
);
CREATE INDEX IF NOT EXISTS idx_user_interests_user ON user_interests (user_id);
```

Идемпотентна (`IF NOT EXISTS`), безопасно прогоняется повторно (что и делает Flyway, если объекты уже подняли вручную при codegen).

После миграции — `./gradlew generateJooq` (jOOQ кодген живой к локальной БД, см. `backend/build.gradle.kts:91+`). Регенерация даёт `Interests.kt`, `UserInterests.kt`, расширенный `UsersRecord`.

---

## Связанные query-хуки фронта

См. полную таблицу в [`frontend-stores.md`](./frontend-stores.md). Краткий список добавленных в этой итерации:

| Хук | Назначение |
|---|---|
| `useMyReputationQuery()` | `clubs.myReputation()` — список клубов с моей репутацией |
| `useMyInterestsQuery()` | `clubs.myInterests()` — мои интересы |
| `useInterestSuggestQuery(q)` | автокомплит, `enabled = trimmed.length >= 2`, staleTime 60s |
| `useUpdateProfileMutation()` | PATCH /me → `setUser(user)` + invalidate `clubs.myInterests()` |
| `useSkladchinaActionRequiredCountQuery()` | бейдж на табе «Сборы» (см. [`skladchina.md`](./skladchina.md) § «Action-required count») |

---

## Acceptance Criteria

### AC-1: профиль рендерится в бренд-стиле, не TGUI
GIVEN авторизованный юзер открывает `/profile`
THEN страница в `.brand-page` + `BrandBackdrop` (визуальное единство с MyClubsPage/ClubPage)
AND видны: шапка-идентичность, секция «Моя репутация», шестерёнка ⚙️

### AC-2: репутация по всем клубам в одном списке
GIVEN юзер состоит в 3 клубах (active или grace_period, клубы is_active=true)
WHEN открывает «Профиль»
THEN видит ровно 3 карточки «Моя репутация» (одна на клуб)
AND каждая карточка содержит название клуба + индекс надёжности справа
AND для клубов с активностью (totalAttendances>0 OR totalConfirmations>0 OR promiseFulfillmentPct>0) под названием — строка «обещания N% · M подтв. · K посещ.»
AND тап → `/clubs/{clubId}`

### AC-3: новичок без активности
GIVEN юзер только что вступил в клуб, ни одного финализированного события
WHEN открывает «Профиль»
THEN карточка показывает «Новичок» (нет числа, `reliabilityIndex = null`), без строки метрик

### AC-4: плашка при нулевой репутации
GIVEN юзер не состоит ни в одном клубе
WHEN открывает «Профиль»
THEN секция «Моя репутация» всё равно видна
AND внутри неё — `.mc-empty` с текстом «Тут появится репутация. Вступи в клуб — будем считать твою надёжность по каждому из них.»
AND CTA `ghost-btn` «Найти клуб» → `/`

### AC-5: шестерёнка disabled до загрузки интересов
GIVEN юзер открыл «Профиль», `useMyInterestsQuery` ещё in flight
THEN шестерёнка `disabled` (opacity 0.5)
AND тап ничего не делает
AND после `interestsQuery.isPending === false` шестерёнка активна

### AC-6: редактирование сохраняет город/страну/bio
GIVEN юзер открыл модалку редактирования
WHEN тапает «Город», в CityPicker выбирает «Алматы / Казахстан»
AND вводит «о себе» 150 символов
AND жмёт «Сохранить»
THEN `PATCH /api/users/me` отправлен с `{ country: 'KZ', city: 'Алматы', bio: '...', interests: [...] }`
AND модалка закрывается
AND identity-card показывает «Алматы, Казахстан»
AND блок «pf-bio» виден с новым текстом

### AC-7: интересы — комма-разделитель и автокомплит
GIVEN юзер открыл модалку редактирования, в БД уже есть интерес «политика» (от другого юзера)
WHEN вводит в поле «по» → дебаунс → запрос `GET /api/interests/suggest?q=по&limit=10`
THEN в dropdown появляется «политика»
WHEN тапает по «политика»
THEN добавляется чип «политика», инпут чистится
WHEN вводит «настольные игры, кино, » (с запятой)
THEN добавляются ОДИН чип «настольные игры» и ОДИН чип «кино» (фразы сохраняются — пробел НЕ разделитель)
WHEN жмёт «Сохранить»
THEN в `user_interests` юзера есть три записи; в `interests.usage_count` для каждого нового интереса инкрементировано на 1

### AC-8: дедуп интересов через нормализацию
GIVEN юзер вводит ` Политика `, потом ещё один токен «политика»
WHEN сохраняет
THEN в чипах виден один токен «политика» (NFC + trim + lower + ё→е)
AND в `user_interests` ровно одна запись на этот интерес
AND `interests` содержит ровно одну строку `name = 'политика'`

### AC-9: затирание интересов не происходит
GIVEN юзер имеет 5 интересов в БД, ProfilePage уже отрендерил их
WHEN жмёт ⚙️ → save без изменений (interests = тот же массив)
THEN `usage_count` ни одного интереса не меняется (diff пуст)
AND `user_interests` не меняется (вставки/удаления не выполняются)

### AC-10: имя/аватар из Telegram перезаписывают локальные изменения нельзя
GIVEN юзер изменил `firstName` в Telegram
WHEN открывает Mini App
THEN `UserRepository.upsert` обновляет `first_name` в БД из initData
AND `city/country/bio/interests` НЕ затрагиваются (не в upsert)
AND identity-card показывает новое имя из TG, при этом сохранённые город/bio/интересы остаются

### AC-11: «Мой профиль» таб в карточке клуба отсутствует
GIVEN member или organizer открывает `/clubs/:id`
THEN видит табы «Активности», «Участники» (+ «Управление» у организатора)
AND **не** видит таба «Мой профиль»
AND полные метрики репутации (обещания % / подтверждения / посещения) доступны через `/clubs/:id` → «Участники» → тап на себя → `MemberProfileModal`

### ~~AC-12: rejected reason виден заявителю в секции «Активные заявки»~~ (REMOVED 2026-05-31)
Секция «Активные заявки» удалена из ProfilePage. Статусы заявок
(`pending` / `rejected` / `auto_rejected` / approved-awaiting-payment) видны
только на MyClubsPage в секциях «Заявки» и «Ожидают оплаты». Rejected-причины
рендерятся карточкой `AppCard` на `/my-clubs` (поле `application.rejectedReason`
прокинуто без изменений).

### AC-13: миграция применима повторно
GIVEN свежее окружение (БД без V16)
WHEN backend стартует, Flyway применяет V16
THEN V16 проходит без ошибок
AND на повторном старте (V16 уже применена) — exit 0, идемпотентно через `IF NOT EXISTS`

---

## Non-functional

- **Производительность.** `GET /me/reputation` — один SELECT с двумя join'ами (без coalesce-дефолтов; порог «Новичок» применяется маппером); покрывается существующими PK/FK. `GET /me/interests` — два таблицы, через PK. Suggest — индекс `varchar_pattern_ops` даёт O(log n) + matched rows для `LIKE 'prefix%'`. На текущем масштабе (десятки тысяч интересов в перспективе) — < 10ms на запрос.
- **Безопасность.** Все новые эндпоинты под `/api/users/me/**` или `/api/interests/**` → JWT (`SecurityConfig`). `UpdateMeRequest` валидирует `@Size`. `PATCH /me` действует ТОЛЬКО над `user.userId` из принципала — IDOR невозможен. `q` для suggest нормализуется (нет SQL-инъекции — jOOQ параметризует, `startsWith` escape'ит `%/_`). Интересы — публичный словарь, без PII.
- **Rate limiting.** Глобальный `RateLimitFilter` (60/мин на юзера для `/api/**`) покрывает оба новых эндпоинта. Дебаунс 250 мс + `enabled: q.length >= 2` на фронте дополнительно сжимают трафик.
- **Логирование.** `InterestService.replaceUserInterests` пишет `INFO` с counts (`added=`, `removed=`). Sensitive данных нет.

---

## Связанные модули и backlog

- [`reputation.md`](./reputation.md) § «Per-user reputation overview» — endpoint детали + правила порога «Новичок» (модель v2 ledger).
- [`skladchina.md`](./skladchina.md) § «Action-required count» — связанный фронт-сигнал (бейдж на табе «Сборы») реализован в той же ветке.
- [`club-page-unified.md`](./club-page-unified.md) — устаревшее «Мой профиль» upd-блок наверху.
- [`ui-pages.md`](./ui-pages.md) § «ProfilePage» — это место в общей навигации фронт-страниц.
- [`frontend-stores.md`](./frontend-stores.md) § «Query-хуки» — расширение таблицы хуками этой итерации + `AuthStore.setUser`.
- [`docs/backlog/onboarding-interests-and-recommendations.md`](../backlog/onboarding-interests-and-recommendations.md) — Part 1 (модель интересов + редактирование) частично закрыт этой спекой; Part 2 (онбординг) + Part 3 (recommendations) остаются open.
