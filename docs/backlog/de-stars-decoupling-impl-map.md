# de-Stars Decoupling (Slice 2) — Implementation Map

> **Назначение:** self-contained карта для кодинг-сессии (xhigh). Собрана ultracode understand-фазой
> (6 читателей + 2 адверсариальных верификатора) по ЖИВОМУ коду на коммите `470da92` (после Slice 1, PR #87).
> **Заменяет** устаревшие file:line из `monetization-v2-model-handoff.md §6/§7` (план писался ДО Slice 1).
> **Дата сборки:** 2026-06-25.

## 0. TL;DR — что строим

Доступ к платному клубу перестаёт зависеть от Telegram Stars-платежа и становится
**организатор-контролируемым гейтом** (`membership.status = active` / новый `frozen`). Взносы участник→организатор
идут мимо платформы (honor-system, как складчина). Stars-инвойс нейтрализуется, `transactions` остаётся как
frozen ledger. Реальный сервисный сбор платформы (Slice 1, `PaymentProvider`-seam) — **ортогонален, не трогаем.**

Объём: ~2 BE-дня + 1 FE-день. Ядро риска — **B5** (предикат доступа) и миграция enum `frozen`.

## 1. Дрейф плана §7 — что изменилось после Slice 1 (ОБЯЗАТЕЛЬНО учесть)

| План §7 говорит | Реальность сейчас |
|---|---|
| B1 = `V35__membership_access_gate.sql` | **V35 занята** Slice 1 (`V35__create_service_subscription.sql`). → **V37** |
| B2 = `V36__neutralize_platform_split.sql` | **V36 занята** (`V36__create_subscription_pricing.sql`). → **V38** |
| B3 = построить `PaymentProvider`-seam | **УЖЕ СДЕЛАН** (`payment/PaymentProvider.kt` + `StubPaymentProvider.kt`). НЕ делать. Это про СВОЙ сбор платформы, не Stars. |
| B2 «добавить DEFAULT 0» | DEFAULT 0 **уже есть** в V8. V38 функционально почти no-op (ценность = COMMENT/CHECK-маркер). |
| `frozen` как данность | `frozen` в enum `membership_status` **ОТСУТСТВУЕТ** (V3 = active/grace_period/cancelled/expired). Надо ADD VALUE. |
| Единый `hasAccess`-гейт | Доступ **размазан**: 4 сайта через `hasAccess` + 3 inline-копии предиката + `findActiveByUserAndClub` + 8 прямых статус-фильтров. См. §3. |
| file:line в §6.x | Доверять НЕЛЬЗЯ (сдвиг после Slice 1). §7 B-шаги концептуально верны, строки — из этого документа. |

## 2. Миграции

### V37 — `V37__membership_access_gate.sql` (B1)
```sql
-- ADD VALUE изолированно: на PG<12 не txn-safe и значение не usable в той же txn, что добавляет.
-- Flyway оборачивает миграцию в txn → НИКАКОГО UPDATE со 'frozen' здесь. Backfill — отдельной поздней миграцией.
ALTER TYPE membership_status ADD VALUE IF NOT EXISTS 'frozen';

ALTER TABLE memberships
    ADD COLUMN IF NOT EXISTS access_frozen_at    TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS dues_marked_paid_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS dues_marked_by      UUID NULL REFERENCES users(id);  -- users.id PK подтверждён (V1)
```
ADD COLUMN не использует enum-value → можно в той же миграции, что и ADD VALUE. Prod на V36, PG16. Колонки NULLable → безопасны на живой таблице.

### V38 — `V38__neutralize_platform_split.sql` (B2) — опциональна
DEFAULT 0 уже в V8 → реальная ценность только в маркерах:
```sql
COMMENT ON COLUMN transactions.platform_fee      IS 'DEPRECATED (Slice 2): Stars split frozen, 0 for new rows.';
COMMENT ON COLUMN transactions.organizer_revenue IS 'DEPRECATED (Slice 2): historical ledger only.';
COMMENT ON COLUMN transactions.telegram_payment_charge_id IS 'DEPRECATED (Slice 2): no new Stars charges.';
-- опц. hard guard (NOT VALID — не перепроверяет исторические строки):
ALTER TABLE transactions ADD CONSTRAINT chk_split_frozen CHECK (platform_fee = 0) NOT VALID;
```
⚠️ **НЕ `DROP COLUMN`** — `platform_fee`/`organizer_revenue` биндятся jOOQ в 4 файлах (`Transaction.kt`, `TransactionMapper.kt`, `JooqTransactionRepository.kt`, `FinancesService`). Дроп = compile-fail codegen + runtime. Дроп — поздний cleanup (паттерн V33). **[DECISION D5]** можно вообще пропустить V38, переложив «писать 0» на код.

### Codegen после V37/V38 (НЕ накатывать на dev-БД — она на V18 с деструктивными V30/V33)
1. Временный `postgres:16-alpine` на :5433. 2. Накатить все миграции `sort -V` (psql concat). 3. `DB_URL=jdbc:postgresql://localhost:5433/clubs ./gradlew generateJooq`. 4. Снести контейнер. Generated jOOQ **коммитится**. LSP будет врать «Unresolved reference» на новый enum — верить `./gradlew compileKotlin`.

## 3. B5 — предикат доступа (HIGHEST RISK). Полная карта enforcement

`MembershipAccess.hasAccess` (`membership/MembershipAccess.kt:24-30`):
```kotlin
hasAccess(now) = STATUS.eq(active).or(STATUS.eq(cancelled).and(SUBSCRIPTION_EXPIRES_AT.greaterThan(now)))
```
Семантика: доступ = `active` ИЛИ `cancelled`-но-оплаченный-период-жив (Stars paid-driver). `grace_period`/`expired` исключены намеренно.

**Цель пивота:** access = `status == active` (организатор-контролируемый), `frozen` отсекается по умолчанию (fail-closed — не добавлять в предикат). Ветку `cancelled & expires_at>now` — sunset (см. [DECISION D1]).

### 3a. Канонические call-sites `hasAccess` (4 шт., ловятся Serena) — менять НЕ нужно, наследуют предикат
> ⚠️ Верификатор поправил строки читателя на **−1**: фактические **198/212/403/139**.

| Сайт | Что гейтит | Тест |
|---|---|---|
| `JooqMembershipRepository.kt:198` isMember | vote (`VoteService`), Stage-2, member-card read | frozen теряет vote |
| `JooqMembershipRepository.kt:212` isActiveMemberInActiveClub | HTTP `/api/clubs/{id}/events` + `/activities` (через `AuthorizationAspect @RequiresMembership`) | frozen → 403 |
| `JooqMembershipRepository.kt:403` findMemberTelegramIds | DM-рассылка о событии (`NotificationService`) | frozen не в списке DM |
| `event/JooqEventRepository.kt:139` findMyFeed | events feed (`/api/events/feed`). Инвариант feed==vote==DM (тест `UserEventsControllerTest.kt:151`) | расширить кейсом frozen |

### 3b. 🔴 INLINE-копии предиката (3 шт.) — Serena по символу НЕ находит. ГЛАВНЫЙ блайнд-спот
Рефактор `MembershipAccess` их НЕ затронет. Меняешь предикат — эти молча остаются paid-driven → silent drift.
Искать `grep` по `subscription_expires_at` + `cancelled`, НЕ по `hasAccess`.

| Сайт | Что определяет | Риск если забыть |
|---|---|---|
| `JooqMembershipRepository.kt:64-66` findByUserId | список «мои клубы» в профиле (`MembershipService:127`) | cancelled-член висит активным в профиле |
| `JooqMembershipRepository.kt:78-80` findClubMembersWithUserInfo | список участников клуба (`MemberService:37`); двойная семантика по флагу `includeCancelledInPeriod` | фантомные cancelled-paid в списке |
| `JooqMembershipRepository.kt:131-134` findUserClubsWithReputation | разделение «активные клубы / История» в профиле (`MembershipService:139 .partition`) | клуб «активен» по мёртвому paid-драйверу |

### 3c. `findActiveByUserAndClub` (`:28-36`, фильтр `IN(active,grace_period)`) — широкий членский гейт
Callers: `MembershipService` leave/cancel/renew (`:47/:58/:84/:117/:211`), `ApplicationService` idempotency (`:391/:446`). `grace_period` тут СЧИТАЕТСЯ членом (расхождение с hasAccess). После ввода `frozen`: проверить, что frozen-член, пытающийся выйти/переподписаться, не получает «не член».

### 3d. 8 прямых статус-фильтров (НЕ через hasAccess) — ревизовать ПОКЕЙСОВО [DECISION D2]
⚠️ **Полярность различается** (верификатор):
- `JooqClubStatsRepository.kt:216`, `JooqClubQualityRepository.kt:197` = `IN(active,grace_period)` → frozen ВЫПАДЕТ.
- `JooqClubStatsRepository.kt:160` = `notIn(active,grace_period)` внутри `.or()` → **frozen ПОПАДЁТ** (инверсия!). Прочитать тело, решить намеренность для знаменателя качества.
- `JooqApplicationRepository.kt:127/153/181` (applications-инбокс), `JooqClubRepository.kt:41` (member-list/счёт), `JooqSkladchinaRepository.kt:545` `eq(active)` (участие в НОВОЙ складчине), `FreeMembershipActivator.kt:65-66` `isAlive=active||grace_period`.

### 3e. НЕ-предикатные, но связанные
- `MembershipService.hasActivePaidAccess` (`:109,:217`) — **НЕ access-гейт, а router выхода** (soft-cancel vs exit-with-obligations). In-memory, НЕ через `MembershipAccess`. `subscriptionPrice>0 || expires_at>now`. В модели «деньги вне платформы» `subscriptionPrice>0` станет всегда-true → весь leave уйдёт в soft без обязательств. [DECISION D6] пересмотреть отдельно.
- `MemberService.getMemberCard` (`:50`) — читает карточку БЕЗ фильтра статуса (гейт на caller через `isMember`). frozen-цель всё равно отдаст карточку — зафиксировать в матрице status×surface.
- `JooqSkladchinaRepository.findMyFeed` (`:149`) и `EventController:113` (dispute) — НАМЕРЕННО доступны вышедшим (involvement-scoped, deep-link DM). **НЕ трогать.**

### 3f. Обязательно для B5
Свести в `docs/modules/payment-v2.md` **матрицу status × surface** (какой статус что даёт на каждой поверхности), сверить с as-built перед кодингом. Менять 4 места в §3b/§3c **одновременно** с `MembershipAccess`. Focused-тесты: frozen теряет feed/vote/DM/HTTP-403; `paid→active` сохраняет; legacy grandfathering не сломан.

## 4. B4/B9 — AccessGateService: clone blueprint из складчины

Источник: `SkladchinaPaymentService.organizerMarkPaid/organizerUnmarkPaid` (`:218-272`).

### Backend service — новый `membership/AccessGateService.kt`
Методы: `freezeAccess` (active→frozen, set `access_frozen_at`), `unfreezeAccess` (frozen→active), `markDuesPaid` (set `dues_marked_paid_at`/`dues_marked_by`; если frozen → unfreeze; идемпотентно), `unmarkDues`.
Копировать из складчины:
- **idempotent early-return** (уже-в-целевом-статусе → вернуть текущее состояние, НЕ исключение).
- **`WHERE status=<expected>` + rows-affected guard**: `if (updated == 0) throw ConflictException(...)` → 409 (гонка).
- `@Transactional` на каждом методе.
- ⚠️ **НЕ копировать** `maybeAutoCloseAfterStateChange` (`SkladchinaPaymentService:241`) и любую reputation-связь — freeze/dues это чистый статус-переход без финансовых/репутационных последствий. **Событий НЕ публиковать** (DM «вам заморозили» — позже, не в B4).
- owner-check: **на контроллере** через `@RequiresOrganizer(clubIdParam="clubId")` (путь содержит `{clubId}` — в отличие от складчины, где `requireActiveAsCreator` в сервисе из-за `{skladchinaId}`).

### Repo — новые методы в `MembershipRepository` + `JooqMembershipRepository`
`freezeAccess/unfreezeAccess/markDuesPaid/unmarkDues` возвращающие **`Int`** (rows-affected). Шаблон — `JooqSkladchinaRepository.setParticipantPaid/revertParticipantToPending` (`:364-412`): `dsl.update(MEMBERSHIPS).set(STATUS,frozen).set(ACCESS_FROZEN_AT,now).where(ID.eq(...).and(STATUS.eq(active))).execute()`. ⚠️ соседние `cancel()/reactivateFree()` НЕ возвращают rows — брать шаблон из складчины, не из них.

### Controller — `MembershipController` (уже `@RequestMapping("/api/clubs")`)
```kotlin
@RequiresOrganizer(clubIdParam = "clubId")
@PostMapping("/{clubId}/members/{userId}/freeze")     // + unfreeze, dues-paid, dues-unpaid
fun ... = ResponseEntity.ok(accessGateService.freezeAccess(...))  // → MembershipDto, 400/403/404/409
```

### Frontend B9 — клон skladchina org-toggle UX
- `api/membership.ts`: `freezeMember/unfreezeMember/markMemberDuesPaid/unmarkMemberDues` → `POST /api/clubs/${clubId}/members/${userId}/{...}`.
- `queries/members.ts`: 4 мутации + `invalidateAfterMemberGateAction(qc, clubId)` → `queryKeys.clubs.members(clubId)`.
- `components/club/ClubMembersTab.tsx`: per-row org-actions (gated на `isOrganizer`), бейджи `Активен`/`Заморожен`/`Взнос не получен`, `window.confirm` + haptic + toast (паттерн `OrganizerParticipantList` + `SkladchinaPage` handlers). ⚠️ member-row сейчас `<button>` открывающий профиль — org-actions вынести в отдельный блок (вложенные buttons недопустимы).
- 🔴 **B9-предусловие:** `MemberListItemDto` сейчас НЕ несёт статус доступа. Расширить: BE `MemberListDto.kt` + `MembershipMapper.toMemberListItemDto` + `ClubMemberInfo` + repo-query, FE `types/api.ts`. Иначе кнопки не знают, какой статус рендерить. Это шире чистого клона.

## 5. B6/B8 — нейтрализация Stars I/O + awaiting-payment

### Egress (отрезать формирование инвойсов)
`createInvoice` — 3 прод-вызова + сам метод (`PaymentService.kt:42`, строит `SendInvoice`/`XTR`/`LabeledPrice`):
- `MembershipService.kt:233` joinOrRequestPayment → заменить ветку `createInvoice→PendingPayment` на создание membership сразу в **frozen** → `JoinResult.Joined`.
- `ApplicationService.kt:154` approveApplication → платная ветка `createInvoice` → создать frozen-membership напрямую.
- `ApplicationService.kt:411` resendInvoice + эндпоинт `ApplicationController.kt:98-107` → deprecate/410. `resendCooldown`/`RESEND_INVOICE_COOLDOWN` станут мёртвыми.
- После снятия 3 callers — `createInvoice` удалить.
- 🔴 `FreeMembershipActivator` создаёт только `active` и бросает `IllegalState` на alive-членстве. Для frozen-пути — **новый метод** `activateFrozen` (не переиспользовать неявно). `isAlive()` (`:64-66`) дополнить значением `frozen` (иначе frozen-член считается мёртвым → дубль-реактивация).

### Ingress (stray Stars-событие не должно активировать доступ)
- `ClubsBot.kt:84` handlePreCheckoutQuery → форсить `ok=false` (отклонять checkout ДО списания).
- `ClubsBot.kt:54` consume / successful_payment → НЕ звать `handleSuccessfulPayment`; log-and-ignore (WARN + chargeId для ручного refund). `handleSuccessfulPayment` остаётся в коде, но без прод-вызова (16 unit-тестов обновить/удалить).
- ⚠️ оба обязательны: pre_checkout отклоняет на входе, successful_payment — defence-in-depth (иначе TG спишет Stars без активации = потерянные деньги).

### B8 — awaiting-payment кластер (ДЕЛАТЬ В ОДНОМ PR с B6, иначе мёртвый код/stale-zeros)
Источник состояния — только неоплаченный инвойс после approve. С B6 его нет.
- 3 эндпоинта `ApplicationController.kt:79/85/91` + Service `getMyAwaitingPaymentApplications/getOrganizerAwaitingPaymentApplicants/getAwaitingPaymentApplicantsByClub` → пусто/удалить.
- `getMyClubsActionCounts` (`:259-283`): убрать `awaitingPaymentCount` + `organizerAwaitingPaymentCount`, **оставить `inboxCount`** (pending-inbox). Поправить `PendingApplicationsCountDto`.
- 3 SQL `JooqApplicationRepository.findApprovedWithoutMembershipBy{UserId|ClubId|ClubIds}` (`:115/:143/:169`) + объявления `ApplicationRepository.kt:51/60/68` — удалить после снятия потребителей.
- Удалить из live-пути: `JoinResult.PendingPayment` (`JoinResult.kt`), `PendingPaymentDto.kt` (файл целиком); `MembershipController.toHttpResponse:56` упрощается до `Joined→201`.

## 6. B7/B11 — split-финансы (платформа вне потока денег)

`PLATFORM_FEE_PERCENT=20` дублирован **ровно в 2 местах**: `PaymentService.kt:20` (write `:128`) и `FinancesService.kt:12` (read `:31`).
- B7: `FinancesService` убрать 20/80-расчёт; `/api/clubs/{id}/finances` → raw amounts / historical-only. `FinancesDto.kt` (platformFee/organizerShare/Pct) — убрать/relabel.
- ⚠️ [DECISION D3]: финанс-панель — это про деньги, а слайс про access-gate. Минимум в слайсе — footer «Stars»→нейтральное / скрыть «Комиссия платформы»; полная переделка `FinancesDto` — отдельный billing-слайс.
- B11 plan-drift: §7 указывал `api/clubs.ts` — там finances-кода НЕТ. Реальная цель: `OrganizerClubManage.tsx` FinancesTab (`:47-89`, footer «Stars» ×3) + `api/events.ts:90-91` getFinances + `types/api.ts:171-178`.

### L3 charge-id сигнал (club-quality) [DECISION D4]
`JooqClubRankRepository.kt:158` фильтр `TELEGRAM_PAYMENT_CHARGE_ID.isNotNull` (payersByClub, вызовы `:62-63`) — единственный «real-payer» сигнал L3. После retire Stars источник иссякнет → `payingRaw=0` (`ClubRankPolicy.kt:265-288`). Импакта нет сейчас (флаг L3 `CLUB_RANK_BADGE_ENABLED=off`, <8 RANKED). Варианты: (A) repoint на `dues_marked_paid_at IS NOT NULL`; (B) изъять paying-axis. **Рекомендация: B сейчас + TODO на A.** Изолированный сабтаск, не блокер.

## 7. Frontend Stars-copy (B10/B11)

> Витрина `ClubCard.tsx` УЖЕ на ₽ (эталон). Остальное на Stars — продукт в полусостоянии.
> Перед коммитом: `grep -rn 'Stars\|⭐' frontend/src` (искл. .test) → 0.

- `ClubPage.tsx`: state `pendingPayment` (`:93`), `isPendingPayment` (`:153`), 2 ветки «Ожидаем оплату — N Stars» (`:263-271` open, `:301-310` closed-approved) + hint «Счёт отправлен в Telegram». Снять; платный join → активная «Вступить» + note «Доступ откроет организатор после взноса». `completeFreeMembership` CTA+handler (`:200-216,:280-300`) — [DECISION D7].
- `MyClubsPage.tsx` (основная масса B8-FE): `AwaitingPaymentCard` (`:254-314`, resend-invoice), `OrganizerAwaitingPaymentRow` (`:330-354`), `AppCard` проп `awaitingPayment`, хуки `useMyAwaitingPaymentQuery/useOrganizerAwaitingPaymentQuery/useResendInvoiceMutation`, `awaitingPaymentIds` (`:378-381`), счётчики (`:503-504`) — удалить/перепереплести.
- `InvitePage.tsx:118` «{price} Stars / мес» → ₽.
- `CreateClubModal.tsx` (drift: план говорил 234/252): лейбл `:294` «Цена подписки (Stars/мес)» → ₽; income-hint `:310-314` «зарабатывать N Stars (80% от дохода)» + `monthlyIncome=*0.8` (`:95`) — 20/80 отвергнут, переписать на 100% / снять.
- `OrganizerClubManage.tsx:266` settings-лейбл «Цена подписки (Stars/мес)» — **в плане НЕ упомянут**, добавить в scope (дубль лейбла).
- `types/api.ts`: `PendingPaymentDto` (`:382-387`) + `isPendingPayment` guard (`:391-393`) удалить, `JoinClubResult`→`MembershipDto` (`:389`); `AwaitingPayment*Dto` (`:253-293`) удалить; `PendingApplicationsCountDto` (`:242-246`) снять 2 awaiting-поля.
- `api/membership.ts`: `joinClub`/`joinByInviteCode` тип → `Promise<MembershipDto>`; удалить resend/awaiting/completeFree.
- `queries/applications.ts` (`:41-61,:137-177`) + `queries/queryKeys.ts` (`:28,:57,:59`) + invalidate в `clubs.ts:135` (leave-flow) — почистить.
- `utils/formatters.ts:1-3` `formatPrice` «N Stars / мес» → ₽ (единая точка для heroMeta; inline-строки отдельны).
- Тесты: `test/pages/ClubPage.test.tsx` (3 теста: `:238-269`, `:271-311` мокает 202 pending_payment, `:555` «200 Stars / мес»), `test/utils/formatters.test.ts` (`:26-46`, 5 assert) — переписать. ⚠️ BE 202→201 и FE — **в одном релизе** (рассинхрон ломает join).

## 8. Lifecycle / шедулеры

🔴 **Naming-trap:** ДВА класса с почти одним именем —
- `com.clubs.payment.SubscriptionScheduler` / `SubscriptionLifecycleService` (Stars v1) — **ЭТО трогаем.**
- `com.clubs.subscription.ServiceSubscriptionScheduler` / `ServiceSubscriptionLifecycleService` (Slice 1, PR #87) — **НЕ трогаем** (сломается организаторская подписка). Всегда проверять пакет.

Stars-lifecycle (`active→grace_period→expired`) драйвится только `subscription_expires_at`, которую пишет лишь `PaymentService.handleSuccessfulPayment`. После B6 колонка не заполняется → scheduler крутит 0 строк (безвредный no-op).
- **Рекомендация: оставить ДРЕМАТЬ, не удалять.** Аргумент: (1) §7 billing-машина переиспользует time-based expiry; (2) legacy Stars-подписчики с непустым `subscription_expires_at` доживут период через дремлющий scheduler.
- **Минимум в слайсе:** снять Stars-текст в `SubscriptionScheduler.kt:33` («3 дня на пополнение баланса Stars»).
- Опция (на грани over-eng): `@ConditionalOnProperty` kill-switch. Рекомендация — не надо, дремать достаточно.
- Осиротеют (только тесты остаются): `activateSubscription`/`renewSubscription`/`findExpiryRefByUserAndClub`. Дремлют: `moveActiveToGracePeriod`/`moveGracePeriodToExpired`/`findExpiringWithin`/`findActiveExpired`. НЕ трогать живые: `create`/`createOrganizer`/`reactivateFree`/`cancel`.

## 9. 🟡 Решения для PO (всплывут при кодинге)

| # | Решение | Контекст | Рекоменд. |
|---|---|---|---|
| **D1** | Живые `cancelled & expires_at>now` membership'ы при пивоте hasAccess | Уберём ветку → silent revoke доступа у платящих в момент деплоя | Data-migration: либо `cancelled→active`, либо держать backward-compat ветку до sunset |
| **D2** | Считается ли `frozen` в club-quality знаменателях / member-list / applications / skladchina | 8 прямых статус-сайтов + инверсия полярности на `:160` | Покейсово; зафиксировать матрицу status×surface |
| **D3** | Трогать ли финанс-панель (20/80) в этом слайсе | Это про деньги, слайс про access | Минимум: footer Stars→нейтр.; полная переделка — billing-слайс |
| **D4** | L3 charge-id сигнал | Source иссякнет; флаг off | Изъять paying-axis сейчас + TODO repoint на dues |
| **D5** | Нужна ли V38 | DEFAULT 0 уже в V8 → почти no-op | COMMENT-маркеры ИЛИ пропустить, «писать 0» в коде |
| **D6** | `hasActivePaidAccess` (router выхода) | `subscriptionPrice>0` станет всегда-true | Пересмотреть отдельной задачей |
| **D7** | Удалять ли `completeFreeMembership` | Free self-heal, НЕ Stars | Подтвердить, что stuck-state в P2P-out невозможен (B6 создаёт inline) → тогда удалять |

## 10. Рекомендованный порядок сборки

1. **V37** (enum frozen + колонки) → codegen → commit jOOQ.
2. **B5** ядро: `MembershipAccess` + 3 inline-копии + `findActiveByUserAndClub` + `isAlive()` + матрица status×surface + тесты. ← делать вдумчиво, это риск.
3. **B4** AccessGateService + repo + endpoints (clone складчины).
4. **B6+B8 вместе** (один PR): retire Stars egress/ingress + awaiting-payment decommission.
5. **B9** member-list gate UI (+ предусловие: `MemberListItemDto` со статусом).
6. **B7/B11** split-финансы + frozen-copy фронт (D3 — минимум).
7. **B10** frontend pay-to-join → P2P-out copy + тесты. ← в одном релизе с B6 (202→201).
8. Lifecycle: снять Stars-текст `:33`, scheduler дремлет.
9. Post-flight: обновить `docs/modules/payment.md` + `applications-inbox.md` (станут устаревшими после B8) + матрица в `payment-v2.md`.

## 11. Грабли (быстрый чеклист)
- [ ] `ADD VALUE` без UPDATE в той же миграции.
- [ ] Не `DROP COLUMN` split-колонки (jOOQ binding ×4).
- [ ] Новые классы НЕ `Subscription*` (bean-name clash рушит весь контекст).
- [ ] codegen на временном PG :5433, не на dev-БД.
- [ ] 3 inline-копии предиката + `hasActivePaidAccess` — НЕ ловятся `find_referencing_symbols`, искать grep'ом.
- [ ] Полярность `:160` (notIn) vs `:216/:197` (in) — frozen ведёт себя противоположно.
- [ ] BE 202→201 и FE — один релиз.
- [ ] B6+B8 — один PR.
- [ ] Тронули `payment.SubscriptionScheduler`, НЕ `subscription.ServiceSubscriptionScheduler`.
