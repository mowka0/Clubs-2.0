# de-Stars Slice 2 — Хэндофф во фронт-сессию

> **Назначение:** self-contained точка входа для НОВОЙ сессии, которая доделывает **фронтенд** de-Stars.
> Достаточно этого файла + макета + impl-map, чтобы продолжить без иного контекста.
> **Дата:** 2026-06-25. **Ветка:** `feature/de-stars-decoupling` (НЕ закоммичено, НЕ запушено).

---

## 0. TL;DR — где мы

**Backend — 100% готов и зелёный** (полный `./gradlew test` = BUILD SUCCESSFUL). **Frontend — 0%, не начат.**
Текущий фронт **сломан против бэка** (зовёт удалённые эндпоинты/DTO) — `npm run build` (tsc) упадёт, пока не сделаем B10/B11. Это ожидаемо.

Модель (honor-system, помесячно, залочена PO):
- Платный клуб = месячная подписка, деньги участник→организатор **мимо платформы**.
- Участник вступил → нет доступа (`frozen` = «Ждёт оплаты»).
- Организатор получил взнос офлайн → **«Взнос получен»** → доступ открыт на **+30 дней от текущей даты окончания** (опция B, не теряем оплаченные дни).
- Срок прошёл → шедулер на нашей стороне роняет в `frozen` («Ждёт оплаты»), контент недоступен. Hard cut, без грейса.
- `«Закрыть доступ»` — ручное закрытие раньше срока.

**Финальный макет:** `docs/design/members-dashboard/mockups/variants.html` (открыть в браузере). Это source of truth по UI.

---

## 1. Backend API — контракт, под который кодим фронт

### Member-management (новое, организаторские)
Все 4 — owner-only (`@RequiresOrganizer`), возвращают `MembershipDto`, 409 на гонке, 400/403/404 как обычно.
| Метод | Действие |
|---|---|
| `POST /api/clubs/{clubId}/members/{userId}/dues-paid` | **«Взнос получен»** — открыть доступ + продлить на +30д от max(now, текущей даты) |
| `POST /api/clubs/{clubId}/members/{userId}/freeze` | **«Закрыть доступ»** (active→frozen) |
| `POST /api/clubs/{clubId}/members/{userId}/unfreeze` | разморозить (frozen→active), БЕЗ продления срока |
| `POST /api/clubs/{clubId}/members/{userId}/dues-unpaid` | снять отметку взноса (срок/доступ не трогает) |

> Для дашборда основное действие — **dues-paid** (это и есть «Взнос получен · продлить»). `freeze` — «Закрыть доступ». `unfreeze`/`dues-unpaid` — второстепенные, можно спрятать или не делать в v1.

### Red-dot badge
`GET /api/clubs/{clubId}/member-attention` → `{ "expiringSoon": Int }` (owner-only). Сколько участников истекает в ≤7 дней. Красная точка = `expiringSoon > 0` на «Управление» и табе «Участники».

### Member list (изменён)
`GET /api/clubs/{clubId}/members` → `List<MemberListItemDto>`.
- Для **организатора** список включает `frozen`-участников + поля `accessStatus` (`"active"`/`"frozen"`) и `subscriptionExpiresAt` (ISO date | null).
- Для **обычного участника** — только `active`, поля `accessStatus`/`subscriptionExpiresAt` = `null` (даты не утекают).
- ⚠️ Поле `subscriptionCancelled` **удалено** из DTO.

### Member profile card (изменён)
`GET /api/clubs/{clubId}/members/{userId}` → `MemberProfileDto` (та же карточка, что на странице клуба).
- Добавлено поле `subscriptionExpiresAt` (ISO date | null) — **только если вызывающий организатор** (иначе null). Это строка «Подписка активна до …».

### Join (изменён контракт!)
- `POST /api/clubs/{id}/join` → **201 + `MembershipDto`** (был 202 + PendingPaymentDto). Платный клуб → членство сразу в `frozen`.
- `POST /api/invite/{code}/join` → **201 + `MembershipDto`**.
- `MembershipDto.status` теперь может быть `"frozen"`.

### УДАЛЕНО на бэке (фронт обязан перестать звать)
- `POST /api/applications/{id}/resend-invoice` (410/нет).
- `GET /api/users/me/applications-awaiting-payment`
- `GET /api/users/me/organizer/awaiting-payment-applicants`
- `GET /api/clubs/{clubId}/awaiting-payment-applicants`
- DTO: `PendingPaymentDto`, `JoinClubResult`-union, `AwaitingPaymentApplicationDto`, `AwaitingPaymentApplicantDto`, `OrganizerAwaitingPaymentApplicantDto`.
- `GET /api/users/me/applications-pending-count` → теперь `{ "inboxCount": Int }` (поля `awaitingPaymentCount`/`organizerAwaitingPaymentCount` сняты).
- `POST /api/applications/{id}/complete-free-membership` — **ОСТАВЛЕН** (free self-heal, не Stars).

---

## 2. Логика дашборда (разложение по корзинам — на фронте)

Организатор грузит `GET /members` (приходят `accessStatus` + `subscriptionExpiresAt`) и раскладывает:
- `accessStatus === 'frozen'` → **«Ждут оплаты»** (кнопка «Взнос получен»).
- `accessStatus === 'active'` && `subscriptionExpiresAt` && до неё **≤ 7 дней** → **«Скоро закончится»** (показать дату + кнопка «Взнос получен»).
- иначе (active, далеко / `subscriptionExpiresAt === null` = бесплатный) → **«Активные»** (спокойный список).
- `role === 'organizer'` → в «Активные», без действий, бейдж «Орг».

Красная точка на табах = `expiringSoon > 0` (из `/member-attention`, или посчитать из списка). Тап по участнику → карточка профиля (`MemberProfileModal`), организатору в ней «Подписка до DATE» + «Взнос получен · продлить» + «Закрыть доступ».

Бесплатный клуб (`subscriptionExpiresAt` у всех null, `accessStatus` active) → блоков про оплату нет, просто «Участники».

---

## 3. Фронт-задачи

### B9 — дашборд участников + карточка + red dot
| Файл | Что делать |
|---|---|
| `components/club/ClubMembersTab.tsx` | Переделать в дашборд: 3 корзины (Скоро закончится / Ждут оплаты / Активные) по §2. **Снять секцию «Ожидают оплаты»** (`useClubAwaitingPaymentApplicantsQuery`, `AwaitingPaymentRow`). Тап по участнику → `MemberProfileModal`. Используется в `OrganizerClubManage` (Участники таб, org) И в `ClubPage` (обычный участник — там корзин/действий нет, т.к. поля null). |
| `components/club/MemberProfileModal.tsx` | Организатору добавить блок «Подписка активна до {subscriptionExpiresAt}» + действия: «Взнос получен · продлить» (`dues-paid`), «Закрыть доступ» (`freeze`). Гейт на наличие `subscriptionExpiresAt`/роль — но дашборд и так org-only. Обычный участник видит ту же карточку без этого блока. |
| `api/membership.ts` | + `markMemberDuesPaid(clubId,userId)`, `freezeMember`, `unfreezeMember`, `unmarkMemberDues` → `POST …/{dues-paid|freeze|unfreeze|dues-unpaid}`. + `getMemberAttention(clubId)` → `/member-attention`. **Снять** `resendApplicationInvoice`, `getMyAwaitingPaymentApplications`, `getOrganizerAwaitingPaymentApplicants`, `getClubAwaitingPaymentApplicants`. `joinClub`/`joinByInviteCode` → тип `Promise<MembershipDto>`. |
| `queries/members.ts` | Мутации freeze/unfreeze/markDuesPaid/unmarkDues + invalidate `queryKeys.clubs.members(clubId)` + member-attention. Снять `useClubAwaitingPaymentApplicantsQuery`. |
| `types/api.ts` | `MemberListItemDto`: +`accessStatus?: string`, +`subscriptionExpiresAt?: string`, **−`subscriptionCancelled`**. `MemberProfileDto`: +`subscriptionExpiresAt?: string`. +`MemberAttentionDto { expiringSoon: number }`. **Удалить** `PendingPaymentDto`, `JoinClubResult` union (→ `MembershipDto`), `isPendingPayment`, `AwaitingPayment*Dto`; `PendingApplicationsCountDto` → только `inboxCount`. |
| Red dot | На таб «Участники» в `OrganizerClubManage` (из member-attention) + на «Управление» (где оно показано — ClubPage/где организаторская точка входа; паттерн «как на активностях»). |

### B10 — pay-to-join → P2P copy (фронт сломан без этого)
По impl-map `docs/backlog/de-stars-decoupling-impl-map.md §7` (актуальные file:line там), кратко:
- `ClubPage.tsx`: снять `pendingPayment`/`isPendingPayment`/«Ожидаем оплату — N Stars»/«Счёт отправлен в Telegram». Платный join → активная «Вступить» + note «Доступ откроет организатор после взноса». `completeFreeMembership`-CTA — оставить (free self-heal).
- `MyClubsPage.tsx`: убрать `AwaitingPaymentCard`/`OrganizerAwaitingPaymentRow`/resend + переплести счётчики без awaiting.
- `InvitePage.tsx`, `CreateClubModal.tsx` (лейбл/income-hint 80%), `OrganizerClubManage.tsx:266` (settings-лейбл): Stars→₽; убрать «80% от дохода».
- `utils/formatters.ts` `formatPrice` «N Stars / мес» → ₽.
- `queries/applications.ts`/`queryKeys.ts`/`clubs.ts`: снять awaiting-payment хуки/ключи/invalidate.

### B11 — финанс-панель
- `OrganizerClubManage.tsx` FinancesTab: убрать «Комиссия платформы»/«Доля организатора» (20/80 мёртв), footer «Stars»→₽ (или скрыть панель). Цель: `api/events.ts:90-91 getFinances` + `types/api.ts FinancesDto` (НЕ `api/clubs.ts` — там finances-кода нет).

### Фронт-тесты
Переписать: `test/pages/ClubPage.test.tsx` (3 Stars-теста), `test/utils/formatters.test.ts` (₽), любые member-list тесты под новые поля. `npm test` (vitest).

---

## 4. Как запускать / проверять

```bash
# frontend (из frontend/)
npm install --legacy-peer-deps
npm run build     # tsc && vite build — СЕЙЧАС ПАДАЕТ (Stars/awaiting не сняты). Зелёным станет после B10/B11.
npm test          # vitest

# backend уже зелёный, перепроверить при желании:
cd backend && ./gradlew test
```
Mini App UX (haptic, native back, deep-links, frozen-флоу) — **тестировать вживую на устройстве**, не Playwright (см. feedback в памяти).

---

## 5. Состояние git (для подхвата)
- Ветка `feature/de-stars-decoupling`, **рабочее дерево, НЕ закоммичено** (~45 backend-файлов + 8 generated jOOQ + новые `AccessGateService`/`MembershipActivator`/`V37`/тесты). Дерево переживает смену сессии — новая сессия видит те же файлы.
- jOOQ уже сгенерирован (V37 накатан на временный PG, generated закоммитить при финальном коммите).
- ⚠️ НЕ накатывать миграции на локальную dev-БД (она на V18, есть деструктивные). Codegen — через временный PG :5433 (см. impl-map §«Codegen»).

После фронта: фича-флоу из CLAUDE.md (Reviewer → Security → Tester → Analyst alignment → staging → «готово, запушь»).

---

## 6. Ссылки
- **Макет (UI source of truth):** `docs/design/members-dashboard/mockups/variants.html`
- **Impl-map (детали B-шагов, актуальные file:line по фронту):** `docs/backlog/de-stars-decoupling-impl-map.md`
- **Спека модели:** `docs/modules/payment-v2.md` (нужно дописать date-модель + матрицу status×surface на docs-alignment шаге).
- **Память:** `[[project_monetization_v2_handoff]]` (полный прогресс), `[[project_payment_monetization_v2]]` (стратегия).

---

## 7. Матрица status × surface (для docs/modules/payment-v2.md — зафиксировать на alignment)

| Поверхность | active | frozen | grace_period(legacy) | cancelled/expired |
|---|---|---|---|---|
| Контент-доступ (`hasAccess`: feed/vote/DM/события) | ✅ | ❌ | ❌ | ❌ |
| Член клуба (мои клубы, ростер, findActive, isAlive, слот, member_count) | ✅ | ✅ | ✅ | ❌ |
| Считается в churn | нет | нет | нет | да |
| Engagement-знаменатель / участие в новой складчине | ✅ | ❌ | (legacy) | ❌ |
| Срок (`subscription_expires_at`) | да (драйвит auto-expire→frozen) | — | — | — |
