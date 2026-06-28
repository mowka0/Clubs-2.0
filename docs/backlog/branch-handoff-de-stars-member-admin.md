# Branch handoff — `feature/de-stars-decoupling` (читать ПЕРВЫМ в новой сессии)

> **Обновлено:** 2026-06-28. **Ветка:** `feature/de-stars-decoupling` — запушена, на **staging**, **НЕ смёржена** в master.
> **Задача новой сессии:** **продолжить ручное тестирование PO на staging и фиксить найденные баги на этой же ветке** (передеплой авто по пушу). После зелёного теста — финальные гейты → один PR в master.

## 0. TL;DR

Ветка = большой связный пласт de-Stars (деньги мимо платформы) + member-admin + платёжный флоу. Всё собрано, **зелёное** (полный `./gradlew test`, фронт 187 тестов, build), ключевые куски прошли Reviewer+Security (SHIP). Ждёт **сквозного теста PO** и правок.

**Главный чек-лист для прогона:** `docs/backlog/de-stars-session-checklist.md` (единый, блоки A–H). Узкие детали — `payment-flow-test-cases.md`, `de-stars-member-admin-test-cases.md`.

## 1. Что на ветке (коммиты, новые сверху)

| Коммит | Что |
|---|---|
| `5884ba5` | docs — сквозной чек-лист сессии. |
| `9ad04f4` | **B+C: «Отказать · вернуть перевод»** — орг одним решением принимает/отклоняет оплату; reject убирает frozen-участника + DM (возврат оффлайн). |
| `1ad47b6` | **Обзор участника у орга** — «Ответ на заявку» (org-only) + убрана метка «Только орг». |
| `6ae3f1c` | **Карточка доверия организатора** на sheet оплаты (`GET …/organizer-card`): идентичность + «на платформе с» + клубы/доверие; свежий орг → «недавно» + nudge. |
| `1ad47b6`/`b61b4e9` | docs — тест-кейсы платёжного флоу. |
| `ef79742` | **claim-флоу оплаты** — участник: СБП+скриншот / наличные+чекбокс → «на проверке»; орг видит и подтверждает. **V41** (`memberships.dues_claimed_at/_method/_proof_url`). proofUrl origin-bound (анти-XSS). |
| `c8697c0` | **СБП-реквизиты обязательны** для платного клуба (инвариант `price>0 ⇒ paymentLink` в create/settings). |
| `51a0ff8` | **S2 награды клуба** — **V40** `club_awards`, модуль `com.clubs.award`, «как интересы», видны всем (R3), косметика (R4). |
| `722185d` | **СБП-реквизиты** клуба (`payment_link`/`note`) + «Оплатить по СБП». **V39**. |
| `91d6a6f` | **S1 member-admin** — режим правки (заметка + своя дата). **V38**. |
| `36774f4` | Items 1-2 — кросс-клубовый «Ждут оплаты» + red-dot. |
| `09926fd`/`5b7099e` | **de-Stars Slice 2** (frontend/backend): орг-гейт доступа, дашборд, P2P-join, ₽ вместо Stars. **V37**. |

**Миграции:** V37–V41 заняты. **Следующая свободная = V42.**

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
- **Награды (R1-R4):** R2 «как интересы», R3 видят все, R4 косметика. (R1 роли — см. ниже, отложено.)
- **Карточка доверия:** акцент на аккаунте орга; факты условные (без нулей); свежий → «недавно» + nudge. TG-дата регистрации НЕ показываем (только неточная оценка по id).

## 5. Отложено (беклог, НЕ в этой ветке)

- **S3 роли+права** ⏸️ (PO, YAGNI; OWASP A01). Impact-анализ + матрица готовы → `member-admin-roles-S3-deferred.md`. Миграция была бы **V42**.
- **Анти-скам: петля жалоб/не-доставки** (репутация доставки орга + выпуск из cold-start) — keystone, отдельная фича → `payment-trust-and-antiscam.md`.
- **TG Premium-бейдж** в карточке доверия — нужно начать сохранять `is_premium` из initData.

## 6. Под-документы (карта)

- **Сквозной чек-лист сессии:** `de-stars-session-checklist.md` ← ГЛАВНОЕ для PO-теста.
- **Платёжный флоу детально:** `payment-flow-test-cases.md`.
- **Награды/заметка/de-Stars блоки:** `de-stars-member-admin-test-cases.md`.
- **B+C дизайн:** `paid-join-redesign-bplusc.md`. **Анти-скам/доверие:** `payment-trust-and-antiscam.md`.
- **Спека member-admin:** `docs/modules/member-admin-profile.md`. **S3 отложен:** `member-admin-roles-S3-deferred.md`.
- **Макеты (на диске, mockups/ в .gitignore):** `docs/design/dues-payment/mockups/variants.html` (карточка доверия), `docs/design/member-admin-profile/mockups/variants.html`.

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
