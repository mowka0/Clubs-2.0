# Хэндофф: форматы встреч v2 — V86 реализована, ждёт прогона на staging

> Обновлено 2026-09-02 в конце сессии реализации. **Читать первым** при продолжении работы над
> форматами встреч. Предыдущая версия этого файла описывала план реализации; он выполнен.
>
> Команда для старта новой сессии: «продолжи с docs/backlog/event-formats-v2-session-handoff.md».

## 1. Где мы

| Что | Где | Состояние |
|---|---|---|
| Вес промаха в надёжности | master, PR #155 `fba3c63` | ✅ в проде (2026-09-02) |
| Форматы v2 (V86): миграция, бэкенд, бот, фронт, доки | `feature/roster-threshold` | ✅ реализовано, тесты зелёные, **запушено на staging** |
| Прогон на staging | `docs/backlog/event-formats-v2-staging-testplan.md` | ⏳ не начат — ждёт PO |
| Мерж в master | — | ⏳ после «готово, запушь» |

Ветка несёт V83–V86 разом: ни одна из них в проде не применялась, бэкфил V86 по спеке § 10.

## 2. Что сделано в этой сессии (трек L)

**Бэкенд.** `V86__event_formats_v2.sql` (min_participants, roster_decided_at,
roster_warning_sent_at, бэкфил из limit_kind, DROP limit_kind + тип); `EventFormat` →
`NORMAL/OPEN` по `participantLimit == null`; `EventFormatInput` принимает и литералы V85
(`min` → минимум = лимит); `RosterPolicy` — одна формула цены + `DeclineConsequence` +
`RosterSchedule`; `RosterService` — правило ① по минимуму, ② (`sendDueRosterWarnings` в тике
Stage2Service после дедлайнов), `proceed` с `MANAGE_EVENTS`, `settleClosedRoster` (общий путь
отказа/кика/выхода), сброс напоминаний в `closeRoster`; `EventService` — гард даты при минимуме,
отметка ② при create/PUT; `POST /api/events/{id}/proceed`; «Без ответа» без создателя и на
обоих этапах; бот — `RosterCallbackService` (`roster:proceed:` / `roster:remind:`), DM ② и ③ с
кнопками, текст напоминания по этапу, закреп с «⚠️ Состав 3 из 4» и строкой «Организатор
подтвердил…»; шаблоны и activity-DTO с `minParticipants`; env
`ROSTER_WARNING_MINUTES_BEFORE_DEADLINE` (дефолт 180).

**Фронт.** `EventFormat = 'normal' | 'open'`, `formatBadge(format, limit, min)` (`👥 4–10` /
`👥 До 10` / `👥 Ровно 4` / `🌊 Открытая`, ветка `default` сохранена), пикер из двух карточек,
`RosterLimitsFields` + `useRosterLimits` (общие для формы и шторки правки), страница встречи:
засечка минимума на кольце, полосы статуса по § 7.4 / § 9.1, «Проводим» с инлайн-подтверждением,
таб «Без ответа» на наборе, диалог отказа по `declineConsequence`.

**Доки.** `event-formats-v2.md` → `docs/modules/event-formats.md`; V85 → `docs/backlog/event-formats-v85.md`;
выровнены `events.md`, `telegram-bot.md`, `event-templates.md`, `event-vote-block.md`,
`event-stage2-composition.md`, `events-feed.md`, `unified-activity-creation.md`, `PRD-Clubs.md`,
`docs/INDEX.md`; план прогона `event-formats-v2-staging-testplan.md`.

## 3. Что делать дальше

1. **Coolify staging:** добавить `ROSTER_WARNING_MINUTES_BEFORE_DEADLINE=3` и редеплоить —
   иначе при 10-минутном интервале набора правило ② «израсходуется» уже при создании.
2. Прогон по `event-formats-v2-staging-testplan.md` (разделы 1–7). Отклонения — сюда, в § 5.
3. «Готово, запушь» → PR → squash-мерж → прод получит V83–V86.
4. Открытые вопросы PO (спека § 15): дефолт окна ② (180 мин), формулировки DM/закрепа,
   «Ровно N» при min = max (сделано по здравому смыслу, в спеке не было), порядок кнопок в DM ③
   («Проводим» сверху).

## 4. Ловушки, найденные в этой сессии

- **Kotlin-LSP Serena не поднимается** (таймаут инициализации) — вся навигация была grep/cat.
- **Локальная БД:** `flyway_schema_history` заканчивается на ранге 76, а схема при этом V85 —
  V77–V85 применялись мимо Flyway. V86 для `generateJooq` тоже применена через `psql`. Локальный
  `bootRun` упадёт на валидации Flyway; лечится `docker compose down -v` и чистым стартом.
- **jOOQ KotlinGenerator:** конструктор `UsersRecord()` приватный — в тестах строить через
  `mockk<UsersRecord> { every { id } returns … }`.
- **`EventFormatInput` обязан иметь `@JsonValue`** на литерале: иначе `objectMapper.writeValueAsString`
  в интеграционных тестах шлёт `NORMAL`, а `fromWire` его не знает → 400.
- **Цикл зависимостей:** `RosterService → EventService`, поэтому отметку ② при create/PUT
  считает сам `EventService` через чистый `RosterSchedule`, а не через `RosterService`.
- **Тест закрепа** проверяет заголовок «Встреча: до 15 человек» — при смене словаря
  `formatName` менять и `LivePinServiceTest`, и `LivePinRendererTest`.
- **mockk:** `any<T>()` у `publishEvent(Any)` матчит любое событие — для `verify(exactly = 0)`
  использовать `ofType(T::class)`.
- **`participant_limit IS NULL`** по-прежнему читают пять мест как маркер открытой встречи —
  потолок nullable не делать.
- Фронт: `useRosterLimits` гасит минимум эффектом при `minUnavailable`; после возврата даты на
  безопасную минимум остаётся выключенным (организатор включает заново) — осознанно.

## 5. Прогон на staging

_Заполняется по ходу прогона._

## 6. Карта документов

| Файл | Что |
|---|---|
| `docs/modules/event-formats.md` | **действующая спека v2** (модель, три правила, «Проводим», цена отказа, API, экран, V86, AC) |
| `docs/backlog/event-formats-v2-staging-testplan.md` | план прогона на staging под v2 |
| `docs/backlog/event-formats-v85.md` | архив спеки V85 |
| `docs/backlog/event-formats-session-handoff.md` § 6–7 | env staging и инструменты (`~/clubs-fast.sh`, доступ к БД) |
| `docs/modules/reputation-v2.md` § P1b | формула надёжности с весами (в проде) |
| `docs/modules/telegram-bot.md` § «набор состава» | DM ②/③, callback-кнопки, ответы |

Память проекта: `project_event_formats_restructure` (что отвергнуто и почему, состояние ветки),
`project_reputation_severity_weight` (вес промаха — в проде).
