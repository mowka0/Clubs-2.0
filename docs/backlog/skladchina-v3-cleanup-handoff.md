# Хэндофф: чистка после «Сборы и долги v3»

> Написан 2026-09-13, в день мержа `feature/skladchina-rethink` в `master`. Задача следующей
> сессии — убрать всё, что осталось от прежних редакций модуля, не трогая v3. Каждый пункт
> самостоятелен; порядок — сверху вниз по риску.

## 1. Сначала решения PO (блокируют остальное)

1. **Нумерация Flyway.** В master теперь V90–V93. Локальная ветка биллинга
   `feature/sprint-1.0-day4-payment-model` несёт V87/V88, ветка `feature/skladchina-payment-confirmation`
   — V89. `spring.flyway.out-of-order` не включён: прод откажется применять номер ниже текущего.
   Варианты: (а) перенумеровать V87/V88 → V94/V95 перед пушем биллинга (рекомендую; ветка не
   пушена, checksum-истории нет); (б) включить `out-of-order: true` в prod-профиле. V89 не мержить
   никогда — сверка оплат отменена v3.
2. **Ветка `feature/skladchina-payment-confirmation`** (локально и на origin): удалить. Её V89 на
   staging был применён и вручную вычищен из `flyway_schema_history` 2026-09-12 (дамп до чистки —
   `/root/staging-pre-v90-20260912-1448.sql` на VPS, можно удалить).
3. **`MANAGE_SKLADCHINA`** осталась ради одного эндпоинта `GET …/skladchinas/active`
   (список в «Управлении»). Оставить как есть или отдать список любому участнику и убрать
   капабилити из карты ролей (`RoleCapabilities`, `club-roles.md`, тест-план ролей).

## 2. Ветки (после решения п. 1.1)

Смержены давно, можно удалять на origin и локально: `feature/skladchina-mvp`,
`feature/skladchina-phase-a`, `feature/skladchina-reputation`, `feature/skladchina-templates`,
`feature/skladchina-split-and-description`, `feature/skladchina-chat-status`,
`feature/redesign-skladchina-and-forms`, `feature/profile-reputation-and-skladchina-badge`.
`feature/skladchina-rethink` — после мержа тоже (правило «не `--delete-branch` при мерже»
не запрещает чистить потом).

## 3. Документы

**Перенести в `docs/backlog/` (архив) или удалить:**

| Файл | Что делать |
|---|---|
| `docs/modules/skladchina.md` | в `docs/backlog/skladchina-pre-v3.md`; строку в `docs/INDEX.md` убрать |
| `docs/backlog/skladchina-2.0-roadmap.md` | удалить: «шаблоны-стратегии» отменены v3 |
| `docs/backlog/skladchina-templates-architecture.md`, `skladchina-templates-handoff.md` | удалить, та же причина |
| `docs/backlog/skladchina-payment-confirmation-handoff.md` | удалить: V89 отменена |
| `docs/backlog/skladchina-type-scenarios-handoff.md` | удалить: «С каждого по…» вошёл в v3 как «Кто берёт?» |
| `docs/backlog/skladchina-split-test-cases.md` | удалить: тест-кейсы сплита старой модели |
| `docs/backlog/skladchina-reputation-redesign.md` | оставить как историю решения ±10/−40, добавить шапку «см. skladchina-v3 § 4» |
| `docs/design/skladchina-split-header/` | удалить (мокапы старого экрана сбора) |
| `docs/design/skladchina-rethink/` | оставить: источник v3 |

**Сверить упоминания старой модели** (`payment_disputed`, `closed_success`, `skladchina_participants`,
`SkladchinaManageTab`, «Важный сбор», «Разделить счёт»): `docs/modules/club-quality.md`,
`unified-activity-creation.md`, `reputation-v2.md`, `club-leave.md`, `club-chat-link.md`
(разделы про статус-пост сбора и напоминание за 24 ч частично обновлены, остальное проверить),
`PRD-Clubs.md` § 4.4.4 (финансовая ось репутации). Правило: `[spec updated]` или явный `[backlog]`.

## 4. Код

- `LeavePreviewDto.skladchinaObligations` — всегда 0 (долги переживают выход). Убрать поле из
  DTO и фронта (`LeaveClub*`), либо оставить с комментарием «контракт».
- `DebtCallbackService` обслуживает и сборы (`enroll`, `take`, `close`) — переименовать в
  `SkladchinaCallbackService` или разнести.
- `SkladchinaDetailDto.enrolled` в `voluntary` значит «кого позвали» — переименовать в `people`
  или задокументировать в DTO (сейчас комментарий есть).
- Девять штампов напоминаний (`due_reminder_sent_at`, `overdue_reminded_at`, `promise_reminded_at`,
  `claim_reminded_at`, `minus_week_reminded_at`, `minus_day_reminded_at`,
  `debt_settlements.reminded_at`, `skladchinas.reminder_sent_at`, `order_reminded_at`) можно
  свернуть в одну таблицу «что кому отправлено». Рефакторинг с миграцией — только после того,
  как v3 поживёт в проде.
- Фронт: grep `?kind=` — старые ссылки на форму читаются, но новых быть не должно;
  `KIND_HINT` и прочие остатки трёх видов в `CreateSkladchinaPage` уже убраны, проверить
  `SkladchinasTab`/`ActivityCard` на тексты старой модели («Требует оплаты» и т. п.).

## 5. Окружения (Coolify)

- Удалить у staging и prod переменные `SKLADCHINA_REMINDER_POLL_MS`, `SKLADCHINA_CONFIRMATION_POLL_MS`
  — код их больше не читает. Актуальные ручки: `DEBT_POLL_MS`, `DEBT_OVERDUE_WEEKS`,
  `DEBT_CLAIM_STALE_HOURS`, `SKLADCHINA_DEADLINE_REMINDER_MINUTES_BEFORE` (§ 5 спеки).
- На staging после теста вернуть дефолты, если ужимались.

## 6. Память Claude

Пометить как отменённые/закрытые: `project_skladchina_payment_confirmation` (V89),
`project_skladchina_split_selfpaid` (сплит), `project_skladchina_templates_roadmap`,
`project_skladchina_reputation_design` (актуальное — § 4 спеки v3). Актуальный файл —
`project_skladchina_rethink`.

## 7. Открытые вопросы PO (не чистка, но висят)

- Rate limit на создание сборов (сейчас общий 120/мин).
- Порог +10: сейчас любой `shared`-долг, закрытый до срока.
- «Кто в деле?» с будущей встречи (вход со страницы предстоящей встречи) — по запросу клуба.
- «Привязать к встрече» уже созданный сбор — по запросу.
