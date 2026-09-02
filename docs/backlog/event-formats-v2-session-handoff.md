# Хэндофф: форматы встреч v2 — реализация V86

> Составлено 2026-09-02 в конце дизайн-сессии. **Читать первым** при продолжении работы над
> форматами встреч. Заменяет `event-formats-session-handoff.md` (там состояние прогона V85,
> которое больше не актуально — прогон отменён).
>
> Команда для старта новой сессии: «продолжи с docs/backlog/event-formats-v2-session-handoff.md».

## 1. Где мы

| Что | Где | Состояние |
|---|---|---|
| Вес промаха в надёжности | master, PR #155 `fba3c63` | ✅ в проде (2026-09-02) |
| Форматы V85 (`min` / `max` / `any`) | `feature/roster-threshold`, staging | 🟡 **в прод не идёт**, заменяется V86 |
| Спека целевой модели | `docs/modules/event-formats-v2.md` | ✅ написана, три ревью, 23 правки внесены |
| Код V86 | — | ❌ **не начат** |

Ветка `feature/roster-threshold`: master влит (`a5db042`), последний коммит `009cab5`, рабочее
дерево чистое, staging на ней (деплой прошёл). Тесты: бэкенд 1093, фронт 651, `tsc` чистый.

Решение PO 2026-09-02 — вариант «Б»: **V86 делается в этой же ветке**, прод получит V83–V86 одним
мержем, прогон на staging — один, финальной модели. Прогон ТК-A…E по V85 **не нужен**.

## 2. Что решено (не обсуждать заново)

Всё — в `event-formats-v2.md`, здесь только чтобы не искать:

- **Два формата:** обычная (максимум всегда, минимум по желанию, переключатель по умолчанию
  выключен, шаблон запоминает) и открытая. Платная — позже, число там «нужно оплативших».
- **Принцип:** минимум — условие сбора состава, не проведения. ① дедлайн отменяет при недоборе,
  ② предупреждение за N минут до дедлайна с кнопкой «Напомнить», ③ распад после закрытия — DM
  организатору, встречу не отменяет. **Автоотмена после закрытия отвергнута** (§ 1.2 спеки — три
  причины, проверены на семи сценариях). Не поднимать.
- **«Проводим»** — отметка `roster_decided_at`, НЕ снятие минимума. Глушит ③, строка в закрепе,
  цены не меняются. Условие: состав закрыт, ниже минимума, до старта. Callback + REST через один
  сервисный метод с `MANAGE_EVENTS` (§ 4.1 — Security обязателен).
- **DM ③ — две кнопки:** «Проводим» (callback) и «Открыть встречу» (webApp). «Отменить» в чате
  запрещена (решение 2026-08-31), только ссылкой на диалог в приложении.
- **Цена отказа — одна формула:** `staysAtThreshold = confirmedBefore − 1 ≥ (min ?: confirmedBefore)`,
  порядок условий в § 6. Последствие отказа `declineConsequence` — с сервера.
- **Максимум обязателен** — `participant_limit IS NULL` = маркер открытой встречи для репутации.
- **Напоминание — одно на этап** (сброс `stage2_reminded_at` в `closeRoster`); «Без ответа» на
  наборе включает «Возможно», исключает создателя из списка и счётчика.
- **Кик/выход** — через `RosterService.releaseSeat` (уже сделано для V85, в V86 добавить порог).

## 3. Что делать дальше — по шагам

Трек **L**, полный флоу (`.claude/agents/00-SYSTEM.md`): Developer → Reviewer → Security → Tester →
Analyst (alignment) → staging → PO → «готово, запушь».

1. **Миграция V86** — спека § 10. `min_participants`, `roster_decided_at`, `roster_warning_sent_at`,
   CHECK, бэкфил из `limit_kind` (на staging `min` нет — проверено SELECT-ом 02.09), `DROP` колонки и
   типа на обеих таблицах, `COMMENT ON` по-русски. Потом `./gradlew generateJooq`.
2. **Модель и репозитории** — `EventFormat` → `NORMAL/OPEN`, `Event.minParticipants`,
   `isOpenEvent`/`isRosterEvent` с `participantLimit == null`; новые запросы
   `findEventsForRosterWarning`, обновление отметки ② при create/PUT (§ 3.2).
3. **Сервисы** — `RosterService` (`handleRosterDeadline` по минимуму, `releaseSeat` с порогом,
   `proceed`), `RosterPolicy` (одна формула + `declineConsequence`), `Stage2Service` (② в тике, ①
   раньше ②; сброс напоминаний в `closeRoster`), `VoteService.remind` (текст по этапу, создатель
   вне списка), `EventService` (гарды даты для минимума на create/PUT, совместимость литералов).
4. **DTO и контроллер** — § 8: `format`, `minParticipants`, `rosterDecided`, `declineConsequence`;
   `POST /proceed`; старые литералы на входе.
5. **Бот** — § 3.1, § 4.1: DM ② и ③, два callback-префикса `roster:proceed:` / `roster:remind:`,
   ответы, строка в рендере закрепа, тексты закрепа § 9.1.
6. **Фронт** — § 9: пикер из двух карточек, форма (степпер + переключатель, правило даты,
   подтягивание минимума), шторка правки § 9.3, страница встречи (бейдж `👥 4–10`, кольцо с
   засечкой, полоса статуса по таблицам § 7.4 и § 9.1, «Проводим», таб «Без ответа» на наборе),
   `eventFormat.ts`, шаблоны.
7. **Тесты** по AC-1…AC-17 (§ 13). Прогон-план на staging переписать под v2 (старый
   `party-club-staging-testplan.md` — по V85).
8. Переименовать `event-formats-v2.md` → `event-formats.md`, старую спеку — в `docs/backlog/`,
   строки в `docs/INDEX.md`.

## 4. Ловушки, найденные сегодня

- **Правка встречи после закрытия заперта** гардом `status = upcoming AND stage_2_triggered = false`
  (`JooqEventRepository.update…`). Поэтому «Проводим» — отдельный эндпоинт, а не PUT.
- **Отмена встречи не откатывает реестр репутации:** списанное остаётся, даже если встречу потом
  отменили. Это правильно, не «чинить».
- **`participant_limit IS NULL`** читают пять мест (`JooqReputationRepository`, `JooqEventRepository`
  ×2, `JooqEventResponseRepository` ×2) — не делать потолок nullable.
- **`formatBadge`/`formatEmoji`** — `switch` без `default` был бы белым экраном у закэшированного
  бандла при новом литерале. Ветка `default` уже добавлена; при смене литералов на `normal/open`
  не убирать.
- **Flyway на staging:** V83–V85 применены; prod-профиль без `baseline-on-migrate`/`repair` —
  править их нельзя, только V86 поверх. Деплой на staging ветки без этих миграций уронит бэкенд
  (обходится `SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS=*:future` в Coolify — сегодня не ставили,
  staging просто вернули на ветку форматов).
- **mockk:** `any<T>()` у `publishEvent(Any)` матчит любое событие — для `verify(exactly = 0)`
  использовать `ofType(T::class)` (без импорта, это член scope).
- **Единицы таймингов — минуты**, чтобы staging мог ужать: `ROSTER_WARNING_MINUTES_BEFORE_DEADLINE`
  дефолт 180, на staging 2–3.

## 5. Окружение staging

Как в старом хэндоффе § 6 и § 7 (env, `~/clubs-fast.sh`, доступ к БД): без изменений.
Имя контейнера Postgres добывать подстановкой при каждом обращении.

## 6. Карта документов

| Файл | Что |
|---|---|
| `docs/modules/event-formats-v2.md` | **спека цели** — всё, что реализовывать |
| `docs/modules/event-formats.md` | V85, что задеплоено на staging; после V86 — в backlog |
| `docs/backlog/event-formats-session-handoff.md` | старый хэндофф: env staging, инструменты; прогон V85 отменён |
| `docs/design/event-roster-threshold/mockups/roster-*.html` | схемы дизайн-сессии, **локально, вне git** |
| `docs/modules/reputation-v2.md` § P1b | формула надёжности с весами (в проде) |

Память проекта: `project_event_formats_restructure` (что отвергнуто и почему),
`project_reputation_severity_weight` (вес промаха — в проде).
