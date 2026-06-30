# Branch handoff — `feature/de-stars-decoupling` (читать ПЕРВЫМ в новой сессии)

> **Обновлено:** 2026-06-28. **Ветка:** `feature/de-stars-decoupling` — запушена, на **staging**, **НЕ смёржена** в master.
> **Задача новой сессии:** **продолжить ручное тестирование PO на staging и фиксить найденные баги на этой же ветке** (передеплой авто по пушу). После зелёного теста — финальные гейты → один PR в master.

## 0. TL;DR

Ветка = большой связный пласт de-Stars (деньги мимо платформы) + member-admin + платёжный флоу + управление участником (включая кик). Всё собрано, **зелёное** (полный `./gradlew test` BUILD SUCCESSFUL, фронт **189 тестов**, чистый `npm run build`), ключевые куски прошли Reviewer+Security (SHIP). Ждёт **сквозного теста PO** и правок.

**Главный чек-лист для прогона:** `docs/backlog/de-stars-session-checklist.md` (единый, блоки **Core + A–H**, покрывает ВЕСЬ функционал ветки). Узкие детали — `payment-flow-test-cases.md`, `de-stars-member-admin-test-cases.md`.

**Сделано в сессии 3 (2026-06-28, поверх de-Stars базы):** награды-полировка (ростер/✎-в-шапке/переиспользование/без-дубля) · уведомление оргу при claim + точка на «Мои клубы» · компактный claim + zoom скриншота · approval-DM участнику (Flow Y) · **кик «Удалить из клуба»** · **R1-панель «Управление участником»** · экран «Заявка» (fade+авто-скролл) · фикс «зависшей заявки» · **V42** re-reject.

**Сделано в сессии 4 (2026-06-28, 5 PO-багов со staging — фронт + backend, миграций нет):**
1. **Одобренная заявка → сразу в «Оплата вступления» на «Мои клубы»** — добавлена инвалидация `organizer.awaitingDues` в approve-мутацию (раньше участник был только в Управление→Участники, блок на «Мои клубы» с `staleTime:60s` не обновлялся).
2. **Орг без Telegram-username → только наличные** в `DuesPaymentSheet`: СБП скрыт + плашка безопасности; nudge «новичка» больше не зовёт «написать» тому, кому нельзя. ⚠️ Узкое исключение к «гейтинг метода не работает» — оговорка в `payment-trust-and-antiscam.md §2`, открытый вопрос к PO.
3. **Блок «Ждут оплаты» → «Оплата вступления»** (и «Мои клубы», и Управление→Участники) — покрывает и «ждёт», и «оплата заявлена», и одобренные-неоплаченные.
4. **Заметка организатора — всегда открытое поле** в панели управления (своя кнопка «Сохранить заметку», не закрывает карточку); чинит FREE-клуб, где панель схлопывалась в одну «Удалить из клуба». ✎ теперь только для «своя дата» + наград.
5. **«Основа клуба» (`coreSize`) падает при выходе/кике** — join к текущему членству (`active/grace/frozen`), `cancelled/expired` исключены; `frozen` считается (дюз-пауза ≠ уход).

Добор (та же сессия, 2-й запрос PO):
6. **DM участнику при «Закрыть доступ»** — `AccessGateService.freezeAccess` → `sendAccessFrozenDM` (@Async) с inline-кнопкой «Оплатить взнос» (deep-link `/clubs/{id}`). Best-effort. *(Шедулер-экспирация DM пока не шлёт.)*
7. **Блок «🔒 Доступ закрыт — оплатите» на «Мои клубы» участника** — собственные frozen-членства вынесены в отдельную секцию (ведёт список), исключены из «Где я состою». Чисто фронт (`MyClubsPage`, `FrozenMembershipRow`).

> Ретест: `de-stars-member-admin-test-cases.md` §S4 (S4-1…S4-7). Тесты зелёные (фронт 191 + build, backend ClubQuality + coreSize + AccessGate freeze-DM).

**Сделано в сессии 5 (2026-06-30) — 3 UI-правки PO + новый эндпоинт:**
1. **Отступы панели «Управление участником»** — убраны лишние `margin-top/bottom: 12px` у `.rd-org-edit` (заметка + «своя дата»): теперь стандартный ритм панели.
2. **Management-бакеты только в Управлении** — `ClubMembersTab` получил проп `managementView`; «Скоро закончится» / «Оплата вступления» больше НЕ дублируются на табе «Участники» страницы клуба (там плоский ростер). Бакеты — только в Управление → Участники.
3. **Отзыв заявки участником** — `POST /api/applications/{id}/cancel` (applicant-only, pending→`cancelled`). **V43** (`application_status` ADD VALUE `cancelled`). UI: крестик «×» (corner-notch) на карточке pending-заявки в «Мои заявки» → **попап `ConfirmDialog`** → отзыв; `cancelled` не активен → можно подать заново. Новый компонент `ConfirmDialog` (переиспользуемый).

> Тесты сессии 5: фронт 192 + build; backend ApplicationServiceTest (+3 cancel) + ClubQuality (полная Flyway-цепочка с V43) зелёные. ⚠️ jOOQ-enum дописан вручную (локальная codegen-БД отстала на V18; codegen против актуальной БД воспроизведёт `cancelled`). Доки: `application.md` (эндпоинт+статус), реестр миграций (V43 занят, S3→V44).

## 1. Что на ветке (коммиты, новые сверху)

| Коммит | Что |
|---|---|
| `afd23d2` | docs — перенумерация отложенной S3-миграции V42→V43. |
| `64b223a` | **fix: re-reject** — ослаблен `UNIQUE(user,club,status)` → частичный индекс «≤1 активной заявки». **V42**. Можно отклонять заявку одного юзера дважды. |
| `5183f17` | **Кик «Удалить из клуба»** (`POST …/members/{id}/remove`, owner-only, причина обязат.≥5, DM) + **R1-панель «Управление участником»** + экран «Заявка» (fade над кнопками + авто-скролл к причине) + **фикс зависшей заявки** (каскад-чистка заявки при reject/kick/cancel + self-heal submit + фронт-гард). |
| `a988e20` | **Approval-DM участнику** (Flow Y): при одобрении заявки → DM «одобрили, оплатите» (платный) / welcome (бесплатный). |
| `eeb1bfb` | **Уведомление оргу при claim** (DM + точка на «Мои клубы») + награды в ростере (под именем) + компактный claim + **zoom скриншота** (`ImageLightbox`). |
| `c1fc4a0`/`4cdbd81` | **Награды-полировка**: ✎ в шапку, награды в одном месте (под интересами, без дубля), переиспользование подсказок; + фикс прод-сборки (стабы `MemberListItemDto.awards`). |
| `9ad04f4` | **B+C: «Отказать · вернуть перевод»** — reject убирает frozen-участника + DM (возврат оффлайн). |
| `1ad47b6` | **Обзор участника у орга** — «Ответ на заявку» (org-only) + убрана метка «Только орг». |
| `6ae3f1c` | **Карточка доверия организатора** на sheet оплаты (`GET …/organizer-card`). |
| `ef79742` | **claim-флоу оплаты** — СБП+скриншот / наличные → «на проверке»; орг подтверждает. **V41**. proofUrl origin-bound. |
| `c8697c0` | **СБП-реквизиты обязательны** для платного клуба (`price>0 ⇒ paymentLink`). |
| `51a0ff8` | **S2 награды клуба** — **V40** `club_awards`, модуль `com.clubs.award`. |
| `722185d` | **СБП-реквизиты** клуба (`payment_link`/`note`) + «Оплатить по СБП». **V39**. |
| `91d6a6f` | **S1 member-admin** — режим правки (заметка + своя дата). **V38**. |
| `36774f4` | кросс-клубовый «Ждут оплаты» + red-dot. |
| `09926fd`/`5b7099e` | **de-Stars Slice 2** (frontend/backend): орг-гейт доступа, дашборд, P2P-join, ₽ вместо Stars. **V37**. |

**Миграции:** V37–V43 заняты (V37 access-gate, V38 note, V39 СБП-реквизиты, V40 награды, V41 claim, V42 ослабление UNIQUE заявок, **V43 = `application_status` ADD VALUE `cancelled`** — отзыв заявки). **Следующая свободная = V44.** *(Отложенный S3 roles, если вернёмся, теперь = V44.)*

## 2. Состояние staging

`https://staging.77-42-23-177.sslip.io` — задеплоено, health 200. **Открывать через @clubs_admin_bot** (нужен живой initData). Каждый push в ветку → авто-передеплой (~3 мин на Coolify). Проверка «новый образ поднялся» = миграция применилась / эндпоинт отвечает.

## 3. Что дальше — ПОРЯДОК (для новой сессии)

1. **🔴 PO тестит на staging** по `de-stars-session-checklist.md` (A–H). **Это режим bugfix:** баг → фикс на этой ветке (Developer→Reviewer→Security при правках auth/input/БД→push→ретест).
2. **Остаток Analyst docs-alignment** перед мержем: смежные модули, устаревшие после de-Stars (`payment-v2.md`, `applications-inbox.md`/`payment.md`, матрица status×surface). По собранным кускам спеки/тест-кейсы уже писались.
3. **Merge** — всё **одним** PR в master (de-Stars + items + S1 + СБП + S2 + claim + карточка доверия + обзор + B+C). `--squash`, БЕЗ `--delete-branch` ([[feedback_git_squash]], [[feedback_git_workflow]]). Перед мержем: `./gradlew test` (Dockerfile bootJar тесты не гоняет).

## 4. Залоченные продуктовые решения

- **de-Stars:** платный клуб = honor-system месячная подписка; деньги участник→организатор **мимо платформы**; доступ открывает орг «Взнос получен» (+30д от max(now, тек.даты)); шедулер роняет просроченных в `frozen`; Stars отвергнуты.
- **Платный вход = B+C (2026-06-28):** из трилеммы «отсев-до-оплаты (A) · орг-подтверждает (B) · один гейт (C)» выбрано **B+C**. Участник оплачивает (frozen+claim) → орг **одним** решением: «Взнос получен» (доступ) / «Отказать · вернуть перевод» (убрать + возврат оффлайн). Отдельная заявка/анкета на платный вход **НЕ нужна** (PO). Детали: `paid-join-redesign-bplusc.md`.
- **СБП:** только P2P-ссылка (honor-system); реквизиты **обязательны** для платного клуба; авто-подтверждение (эквайер) — отдельная фича за ИП.
- **Награды (R1-R4):** R2 «как интересы», R3 видят все (карточка + ростер), R4 косметика. (R1 роли — см. ниже, отложено.)
- **Карточка доверия:** акцент на аккаунте орга; факты условные (без нулей); свежий → «недавно» + nudge. TG-дата регистрации НЕ показываем (только неточная оценка по id).
- **Платный закрытый клуб = Flow Y (2026-06-28):** заявка(ответ) → орг **одобряет** (вет до денег) → участнику DM «одобрили, оплатите» → оплата(claim) → орг «Взнос получен». Орг действует дважды (approve + confirm), зато отсев до перевода. **Платный открытый** платит сразу при вступлении (без заявки/одобрения).
- **Кик «Удалить из клуба» (2026-06-28):** отдельно от «Закрыть доступ» (freeze=обратимая пауза). Кик = отмена membership + **обнуление оплаченного окна** (доступ снят сразу). Причина **обязательна** (≥5, DM). v1 = простое удаление (можно переподать; **бана нет**). Показ для active/free; frozen-paid-join → «Отказать·вернуть». Орга удалить нельзя.
- **Панель «Управление участником» = R1** (PO выбрал): сводка-статус + парные кнопки `Взнос получен`/`Закрыть доступ`, заметка строкой, кик в подвале; правка по ✎ в шапке (инлайн-карандаша нет).
- **Уникальность заявок (V42):** ≤1 активной (pending/approved) на пару (юзер,клуб); терминальные (rejected/auto_rejected) могут повторяться → отклонять можно многократно.

## 5. Отложено (беклог, НЕ в этой ветке)

- **S3 роли+права** ⏸️ (PO, YAGNI; OWASP A01). Impact-анализ + матрица готовы → `member-admin-roles-S3-deferred.md`. Миграция была бы **V44** (V43 занята отзывом заявки `cancelled`).
- **Анти-скам: петля жалоб/не-доставки** (репутация доставки орга + выпуск из cold-start) — keystone, отдельная фича → `payment-trust-and-antiscam.md`.
- **TG Premium-бейдж** в карточке доверия — нужно начать сохранять `is_premium` из initData.

## 6. Под-документы (карта)

- **Сквозной чек-лист сессии:** `de-stars-session-checklist.md` ← ГЛАВНОЕ для PO-теста.
- **Платёжный флоу детально:** `payment-flow-test-cases.md`.
- **Награды/заметка/de-Stars блоки:** `de-stars-member-admin-test-cases.md`.
- **B+C дизайн:** `paid-join-redesign-bplusc.md`. **Анти-скам/доверие:** `payment-trust-and-antiscam.md`.
- **Спека member-admin:** `docs/modules/member-admin-profile.md`. **S3 отложен:** `member-admin-roles-S3-deferred.md`.
- **Макеты (на диске, mockups/ в .gitignore):** карточка доверия `docs/design/dues-payment/mockups/variants.html`; member-admin `docs/design/member-admin-profile/mockups/` (`variants.html`, `subscription-variants.html`, `subscription-panel-v2.html`, **`subscription-panel-v3.html`** ← выбран R1 + кик); экран «Заявка» `docs/design/applications-inbox/mockups/review-variants.html` (выбран A).

## 7. Codegen-рецепт (для V42+ — НЕ накатывать на dev-БД)

```bash
cd backend
docker rm -f clubs-cg >/dev/null 2>&1
docker run -d --name clubs-cg -e POSTGRES_DB=clubs -e POSTGRES_USER=clubs -e POSTGRES_PASSWORD=clubs_secret -p 5433:5432 postgres:16-alpine
for i in $(seq 1 30); do docker exec clubs-cg pg_isready -U clubs -d clubs >/dev/null 2>&1 && break; sleep 1; done
for f in $(ls src/main/resources/db/migration/V*.sql | sort -V); do docker exec -i clubs-cg psql -v ON_ERROR_STOP=1 -q -U clubs -d clubs < "$f"; done
DB_URL="jdbc:postgresql://localhost:5433/clubs" DB_USER=clubs DB_PASSWORD=clubs_secret ./gradlew generateJooq --rerun-tasks
docker rm -f clubs-cg
./gradlew compileKotlin compileTestKotlin   # верить компилятору, не LSP (LSP врёт на новые символы/пакеты)
```

## 8. Грабли / заметки

- **LSP врёт** на новые пакеты/символы (`com.clubs.award`, новые enum) — `Unresolved reference`. Источник истины — `./gradlew compileKotlin`.
- **Serena Kotlin-LSP** в сессии может не стартовать (`cancelled -32800`); фолбэк grep/Read (CLAUDE.md разрешает). После `generateJooq` — `serena project index` + перезапуск Claude Code.
- `bootJar` тесты не гоняет → деплою компилит-ошибки тестов не мешают. Перед мержем — `./gradlew test`.
- **Интеграционные тесты** (`ClubIntegrationTest`, `ActivityControllerTest`) в полном прогоне иногда каскадно падают, в изоляции зелёные — перепроверять `--tests`. (ClubIntegrationTest чинён под обязательные реквизиты — платные фикстуры теперь с `paymentLink`.)
- MSW ESM-флака на фронте: иногда файл падает на «(0 test)» — окружение, не код; `vitest run <file>` изоляцией.
- Домены (`Membership`/`Club`) — новые поля с `= null` дефолтами (тест-билдеры не править; прод заполняет через маппер).
- Не коммитить: `.claude/settings.json`, `Что нужно сделать.md` (личная заметка PO).
- **proofUrl** валидируется как наш `/uploads/...` (origin-bound к `s3.base-url`, в проде пусто → root-relative). Не ослаблять (анти-XSS/SSRF).

## 9. Память (recall)

`[[project_member_admin_profile]]` (вариант B + S1/S2 + СБП + claim + B+C), `[[project_monetization_v2_handoff]]` (de-Stars). Этот файл — мастер-точка входа.
