# Монетизация v2 — отчёт по модулю и хэндофф в новую сессию

> **Назначение:** self-contained точка входа для следующей сессии. Достаточно этого файла + ссылок ниже, чтобы продолжить без иного контекста.
> **Дата:** 2026-06-25. **Статус:** Slice 1 (организаторская подписка) **в проде** (PR #87, squash в master `470da92`); реальных денег нет (стаб).

---

## 0. TL;DR

Платформа берёт **собственный рекуррентный сервисный сбор с организатора** за тариф-ёмкость (сколько ПЛАТНЫХ клубов он может вести). Деньги участников за клуб идут мимо платформы. **Slice 1 построен, протестирован на staging, влит в прод** — но за **стаб-`PaymentProvider`**: реальный эквайринг (ЮKassa/Т-Банк) ждёт ИП и вшивается позже за тем же сеамом. Member-подписка встроена в движок, но **за флагом `MEMBER_PAYS_ENABLED=false`** (свет выключен).

---

## 1. Где что лежит

| Документ | Что |
|---|---|
| `docs/modules/payment-v2.md` | **Залоченная as-built спека** (модель, стейт-машина, схема, §7.1 hardening-гейт). SOURCE OF TRUTH. |
| `docs/design/payment-monetization-v2.md` | Decision record (стратегия §10, отвергнутые варианты, юр-факты РФ). Маркеры §6.3/§7/§11.2 = РЕШЕНО. |
| `docs/backlog/monetization-v2-model-handoff.md` | Операционный план + decoupling-слайс (Трек B, B1–B11). |
| `docs/design/subscription-redesign/mockups/variants.html` | 3 мокапа пэйвола; **выбран Вариант A · тарифные карточки**. |
| `docs/modules/payment.md` | v1 as-built (Stars-путь, ещё частично живой — member-join). НЕ перезаписывать. |

**Ветка:** `feature/payment-subscription-engine` (НЕ удалена, влита). Прод на master.

---

## 2. Что ПОСТРОЕНО (Slice 1, as-built)

### Backend — модуль `backend/src/main/kotlin/com/clubs/subscription/`
- `SubscriptionController` — `GET /api/subscriptions/status`, `GET /plans`, `POST /` (subscribe/upgrade), `POST /cancel`, `POST /webhook` (permitAll).
- `SubscriptionService` — subscribe (+ downgrade-гард, concurrent→409), cancel (+ over-capacity гард), status, listPlans, requirePaidClubCapacity, handleWebhook (идемпотентность).
- `SubscriptionRepository` / `JooqSubscriptionRepository` — CRUD + forward-only transitions (`WHERE status IN(from)` + rows-affected), pricing lookup, `recordEventIfNew` (insert-on-conflict).
- `SubscriptionMapper`, `ServiceSubscription` (domain), `SubscriptionDto` (CreateSubscriptionRequest/SubscriptionStatusDto/PlanOptionDto).
- `SubscriptionPlanPolicy` (capacity: FREE=1/TRIO=3/UNLIMITED=∞, smallestPlanFor, displayMaxPaidClubs), `PricingInvariant` (free-floor + no-cliff, pure).
- `ServiceSubscriptionLifecycleService` + `ServiceSubscriptionScheduler` (CANCELLED_PENDING_END→ENDED по period_end). ⚠️ имена с префиксом `ServiceSubscription` — НЕ `Subscription*` (иначе bean-name коллизия с `com.clubs.payment.SubscriptionScheduler`/`SubscriptionLifecycleService`).

### Backend — сеам платежей `backend/src/main/kotlin/com/clubs/payment/`
- `PaymentProvider` (интерфейс: createSubscription/cancelSubscription/parseWebhook→`WebhookResult` sealed).
- `StubPaymentProvider` (`@Component`, синхронно «активирует», денег не двигает, `parseWebhook→Ignored`). Реальный acquirer = второй `@Component`.

### Backend — врезка в клубы
- `ClubService.createClub` + `updateClub` (free→paid) → capacity-чек → 402 `PaymentRequiredException` (→ `GlobalExceptionHandler` → `PaywallResponse`).
- `ClubRepository.countPaidByOwnerId` (subscription_price > 0).
- `SubscriptionService` инжектит `ClubRepository` для гардов отмены/даунгрейда (кросс-модульно, цикла нет).

### Backend — схема
- `V35__create_service_subscription.sql` — enum `subscription_payer_role`/`subscription_status`/`subscription_plan`; таблицы `service_subscription` (+ partial-unique на одну живую org/member подписку) и `subscription_event` (`provider_event_id UNIQUE` = идемпотентность вебхука).
- `V36__create_subscription_pricing.sql` — `subscription_pricing(plan, price_kopecks, effective_from)`, сид FREE=0/TRIO=20000/UNLIMITED=40000 коп.

### Backend — конфиг (`application.yml`)
- `features.member-pays-enabled: ${MEMBER_PAYS_ENABLED:false}`
- `subscription.period-days: ${SUBSCRIPTION_PERIOD_DAYS:30}`, `subscription.lifecycle-cron: ${SUBSCRIPTION_LIFECYCLE_CRON:0 30 9 * * *}`
- `SecurityConfig`: `/api/subscriptions/webhook` = permitAll (перед `/api/**`).

### Frontend — `frontend/src/components/subscription/`
- `PlanCard` (тарифная карточка), `PaywallModal` (Вариант A, реагирует на 402), `SubscriptionCard` (профиль, только организаторам), `planDisplay.ts` (хелперы).
- `api/subscription.ts` (+ `paywallFromError`), `queries/subscription.ts`, `queryKeys.subscription`. `ApiError.body` несёт тело 402.
- `CreateClubModal` ловит 402 → подписка → ретрай create. `ProfilePage` монтирует `SubscriptionCard`. CSS `.rd-plan*` в `redesign.css`.

### Тесты
- Backend: `SubscriptionPolicyTest` (инвариант + ёмкость), `SubscriptionServiceTest` (402-границы, FREE-reject, member-gate, cancel/downgrade-гарды, webhook-идемпотентность, plan-swap). Полный suite зелёный (Testcontainers).
- Frontend: 170 зелёных (+ inline `graphql` в `vite.config.ts` — фикс msw-флаки).

---

## 3. Залоченные решения (модель)

1. **Кто платит:** оба обязательны. v1 = **organizer-pays** (живой). Фаза 2 = **member-pays** (рельсы есть, флаг off).
2. **Тарифы:** `FREE`(1 платный клуб, 0₽) / `TRIO`(до 3, 200₽) / `UNLIMITED`(∞, 400₽). Единица = платный клуб (`subscription_price>0`); бесплатные не считаются. Инвариант: free-floor + no-cliff (`UNLIMITED ≤ 2×TRIO`). Цены config-tunable через `effective_from`.
3. **Триггер:** месячная подписка; пэйвол на последнем шаге создания/редактирования 2-го+ платного клуба.
4. **Формат подписки (PO-override):** плоская — работает весь период независимо от клубов, НЕ паузится, выключается в конце периода. `ACTIVE → CANCELLED_PENDING_END → ENDED` + `PAST_DUE`.
5. **Сверх-лимит (decision A):** отмена/даунгрейд блокируются (409), пока платных клубов > ёмкости целевого плана. Снимает вопрос «что при ENDED».
6. **Граница member-pays:** участник платит платформе только за платформенное (принадлежность/штаб-квартира/каталог/приоритет на места/репутация), НЕ за доступ к контенту орга (это P2P-взнос организатору).

---

## 4. Что НЕ построено / отложено (честно)

| Что | Почему / где |
|---|---|
| **Реальный эквайринг** (списание/рекуррент/dunning) | стаб; ждёт ИП + acquirer. Hard-гейт hardening → `payment-v2.md §7.1` |
| **HMAC-подпись вебхука, тайтовый rate-limit, TOCTOU-лок, involuntary-ENDED over-capacity** | обязательны ПЕРЕД реальным acquirer (Security-ревью). `payment-v2.md §7.1` |
| **Member-pays живьём** (триггер/UI/что разблокирует) | флаг off; нечего продавать пока нет HQ. Нужна дизайн-сессия «за что участник платит» |
| **HQ (штаб-квартира клуба)** | отдельный продуктовый трек (стена/каталог/идентичность/опросы), бесплатно ради retention; member-премиум — слой поверх |
| **de-Stars decoupling (Slice 2)** | B1–B11 в model-handoff §7. ⚠️ V35/V36 ЗАНЯТЫ подпиской → decoupling стартует с **V37+**. В `CreateClubModal` ещё висит v1-копирайт «Цена подписки (Stars/мес)» + «80% дохода» — снять в Slice 2 |
| **PAST_DUE→ENDED dunning, реальное продление** | нет драйвера со стабом; приедет с acquirer |

---

## 5. Технические грабли для следующей сессии

1. **jOOQ codegen:** локальная dev-БД отстала (была на **V18**, код на V36+). НЕ накатывать миграции на dev-БД (есть деструктивные V30/V33). Для codegen — **временный Postgres**: подними `postgres:16-alpine` на :5433, накати ВСЕ миграции (psql concat в порядке `sort -V`), `DB_URL=jdbc:postgresql://localhost:5433/clubs ./gradlew generateJooq`, снеси контейнер. Generated jOOQ **коммитится** в репо.
2. **Bean-name коллизии:** в `com.clubs.payment` уже есть `SubscriptionScheduler`/`SubscriptionLifecycleService`. Новые классы НЕ называть `Subscription*` без префикса — иначе `ConflictingBeanDefinitionException` рушит ВЕСЬ Spring-контекст (все @SpringBootTest падают с `IllegalStateException`).
3. **vitest + msw:** `graphql` ESM-флака («does not provide an export named 'parse'») всплывает при росте модульного графа → инлайн `graphql` в `vite.config.ts` (`test.server.deps.inline`).
4. **LSP staleness:** при добавлении нового пакета IDE-диагностики массово врут «Unresolved reference» (вкл. generated jOOQ enum) — **доверять `./gradlew compileKotlin`**, не LSP.
5. **zsh ≠ bash:** `for f in $VAR` не разбивает по словам (нужен `while IFS= read -r`).
6. **Прод деплоит Coolify** по пушу в master (НЕ через GitHub Actions — там только staging-деплой + secret-scan). Проверять прод курлом `/api/subscriptions/plans` → 401 = новый код live.

---

## 6. Рекомендованный порядок дальше (на выбор PO)

- **Трек «участник»:** дизайн-сессия member-pays («за что платит») → HQ-трек (бесплатно) → member-премиум поверх → включить флаг.
- **Трек «деньги по-настоящему»:** юр-решение (ИП/acquirer/оферта) → реализовать реальный `PaymentProvider` + hardening §7.1 → флип live-пэйвола (gated §10).
- **Трек «de-Stars» (Slice 2):** B1–B11, начиная с V37; убирает Stars-копирайт и вводит организатор-гейт доступа.

§10 держится: **не брать деньги рано** — стаб ничего не списывает, флаг участников off.

---

## 7. Команды

```bash
# backend
cd backend && ./gradlew test                 # полный suite (Testcontainers, Docker нужен)
./gradlew compileKotlin                       # быстрая проверка типов (LSP врёт — верить этому)
# jOOQ codegen после смены схемы — см. §5.1 (временный PG на :5433)

# frontend (из frontend/)
npm install --legacy-peer-deps
npm test ; npm run build                      # build = tsc && vite build (падает на типах)

# деплой: push в feature/* → staging; merge в master → Coolify прод
# staging: https://staging.77-42-23-177.sslip.io  ·  prod: https://77-42-23-177.sslip.io
```

---

## 8. Статус деплоя на момент хэндоффа
- PR #87 влит squash'ом в master (`470da92`). Staging оттестирован PO. Прод-деплой Coolify был **в процессе** на момент написания — **следующей сессии проверить** `curl https://77-42-23-177.sslip.io/api/subscriptions/plans` (ожидается 401).
