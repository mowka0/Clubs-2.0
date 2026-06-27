# Branch handoff — `feature/de-stars-decoupling` (читать ПЕРВЫМ в новой сессии)

> **Дата:** 2026-06-27. **Ветка:** `feature/de-stars-decoupling` — запушена, на **staging**, **НЕ смёржена** в master.
> Это точка входа: что на ветке, что на staging, что дальше. Детали — в под-документах (ссылки внизу).

## 0. TL;DR

В этой ветке накопилось **5 связанных кусков** про управление участниками + деньги-мимо-платформы.
Всё собрано, зелёное (компиляция/тесты/build), и **ждёт ручного теста PO на staging**.
> 🟢 **2026-06-27:** добавлен **S2 — награды клуба (V40)**, собран и зелёный (Reviewer+Security: SHIP, 0 блокеров). **S3 (роли) — ОТЛОЖЕН** решением PO (YAGNI; см. `member-admin-roles-S3-deferred.md`). Дальше: PO тестит staging (блок L тест-кейсов) → один PR в master.

## 1. Что на ветке (6 коммитов, снизу вверх)

| Коммит | Что |
|---|---|
| `5b7099e` | **de-Stars Slice 2 — backend** (организатор-гейт доступа: `frozen` + «взнос получен», V37, Stars вырезаны). *Собран прошлой сессией.* |
| `09926fd` | **de-Stars Slice 2 — frontend** (B9 дашборд 3 корзины + карточка-гейт + red-dot; B10 pay-to-join→P2P, Stars→₽; B11 финансы). |
| `36774f4` | **Items 1-2** — кросс-клубовый «Ждут оплаты» на «Мои клубы» (`GET /api/users/me/organizer/awaiting-dues`) + red-dot учитывает frozen. |
| `91d6a6f` | **S1 member-admin** — режим правки карточки (вариант B): «Своя дата» доступа + «Заметка организатора». V38. |
| `73a6242` | docs — S2/S3 handoff. |
| `722185d` | **СБП-взнос** — реквизиты клуба (`payment_link`/`note`) + «Оплатить по СБП» на экране frozen-участника, honor-system. V39. |

**Миграции на ветке:** V37 (frozen+gate), V38 (organizer_note), V39 (club payment requisites). Generated jOOQ закоммичен. Следующая свободная = **V40**.

## 2. Состояние staging

`https://staging.77-42-23-177.sslip.io` — задеплоено, health 200, новые эндпоинты отвечают 401 (wired+auth). V37/V38/V39 применились (бэкенд поднялся healthy). **Открывать через @clubs_admin_bot** (нужен живой initData).

## 3. Что дальше — ПОРЯДОК

1. **🔴 PO тестит на staging** по `docs/backlog/de-stars-member-admin-test-cases.md` (блоки A–K + **L = награды S2** + регрессия). Баги → фиксить на этой же ветке (передеплой авто по пушу).
2. ~~S2 награды~~ ✅ **СОБРАН** (V40, Reviewer+Security SHIP). **S3 роли (V41) — ⏸️ ОТЛОЖЕН** (PO 2026-06-27): impact-анализ + матрица + открытые вопросы в `docs/backlog/member-admin-roles-S3-deferred.md`. Вернёмся при реальном спросе на делегирование.
3. **Остаток гейтов фича-флоу** (CLAUDE.md): Analyst docs-alignment по смежным модулям, устаревшим после de-Stars (`payment-v2.md` + матрица status×surface, `applications-inbox.md`/`payment.md`). Reviewer/Security по S2 — пройдены.
4. **Merge** — всё одним PR в master (де-Stars + items + S1 + СБП + S2). `--squash`, без `--delete-branch` (память [[feedback_git_squash]], [[feedback_git_workflow]]).

## 4. Залоченные продуктовые решения

**de-Stars модель:** платный клуб = honor-system месячная подписка; деньги участник→организатор **мимо платформы** (как складчина); доступ открывает орг кнопкой «Взнос получен» (+30д от max(now, тек.даты)); шедулер роняет просроченных в `frozen`; Stars отвергнуты.

**Member-admin (вариант B), решения R1-R4** (`member-admin-profile.md` §8):
- **R1** роли = **реальные права** (матрицу §4 продумать осмысленно в начале S3).
- **R2** награды создаёт орг «как интересы» (автоподсказки существующих + ввод своей; нейронка опц.).
- **R3** награды видят **все** участники. **R4** награды — косметика, на репутацию/XP не влияют.

**СБП:** только P2P-ссылка (honor-system, орг подтверждает). Авто-подтверждение (СБП C2B мерчант) — отдельная фича за ИП/эквайером, не здесь.

## 5. Под-документы (карта)

- **Тест-кейсы:** `docs/backlog/de-stars-member-admin-test-cases.md` ← для PO-теста.
- **S2/S3 план + codegen-рецепт + грабли:** `docs/backlog/member-admin-profile-handoff.md`.
- **Спека member-admin (R1-R4, API, матрица прав-черновик):** `docs/modules/member-admin-profile.md`.
- **Макет вариант B (на диске, mockups/ в .gitignore):** `docs/design/member-admin-profile/mockups/variants.html`.
- **de-Stars impl-map (как делался backend):** `docs/backlog/de-stars-decoupling-impl-map.md`.
- **de-Stars дашборд макет:** `docs/design/members-dashboard/mockups/variants.html`.

## 6. Codegen-рецепт (для V40/V41 — НЕ накатывать на dev-БД)

```bash
cd backend
docker rm -f clubs-cg >/dev/null 2>&1
docker run -d --name clubs-cg -e POSTGRES_DB=clubs -e POSTGRES_USER=clubs -e POSTGRES_PASSWORD=clubs_secret -p 5433:5432 postgres:16-alpine
# wait: docker exec clubs-cg pg_isready -U clubs -d clubs ; then apply ALL migrations in order:
for f in $(ls src/main/resources/db/migration/V*.sql | sort -V); do docker exec -i clubs-cg psql -v ON_ERROR_STOP=1 -q -U clubs -d clubs < "$f"; done
DB_URL="jdbc:postgresql://localhost:5433/clubs" DB_USER=clubs DB_PASSWORD=clubs_secret ./gradlew generateJooq --rerun-tasks
docker rm -f clubs-cg
./gradlew compileKotlin compileTestKotlin   # верить компилятору, не LSP (LSP врёт на новые enum)
```

## 7. Грабли / заметки

- `bootJar` в Dockerfile тесты НЕ гоняет → деплою компилит-ошибки в тестах не мешают. Перед мержем прогнать `./gradlew test`.
- MSW + `graphql` ESM-флака: иногда test-файлы падают на коллекции «(0 test)» — это окружение, не код; перепроверять изоляцией (`vitest run <file>`).
- Доменам `Membership`/`Club` дал `organizerNote`/`paymentLink/note` с `= null` дефолтами — чтобы тест-билдеры не править; прод всегда заполняет через маппер.
- jOOQ-codegen на временном PG :5433; generated коммитится.
- Не закоммичено намеренно: `.claude/settings.json` (tooling-дрейф), `Что нужно сделать.md` (личная заметка PO).

## 8. Память (recall в новой сессии)

`[[project_monetization_v2_handoff]]` (de-Stars + слайсы), `[[project_member_admin_profile]]` (вариант B + S1 done + S2/S3 pending). Этот файл — мастер-точка входа.
