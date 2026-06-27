# Member admin profile — хэндофф в новую сессию (S2 + S3)

> 🟢 **СТАТУС-АПДЕЙТ 2026-06-27:** **S2 (награды) — ✅ СОБРАН** (V40 `club_awards`, на `feature/de-stars-decoupling`, ждёт staging-теста PO). **S3 (роли) — ⏸️ ОТЛОЖЕН** решением PO (YAGNI; OWASP A01-рефактор преждевременен). Всё ниже про S2 — историческое (план, уже выполнен); раздел S3 — см. отдельный актуальный док `docs/backlog/member-admin-roles-S3-deferred.md` (impact-анализ готов). Прогресс — в `docs/modules/member-admin-profile.md` §9.

> **Назначение:** self-contained точка входа для НОВОЙ сессии, которая доделывает админ-карточку участника (вариант B).
> **Дата:** 2026-06-27. **Ветка:** `feature/de-stars-decoupling` (S1 закоммичен + на staging, НЕ смёржен в master).
> **Спека (source of truth):** `docs/modules/member-admin-profile.md`. **Макет:** `docs/design/member-admin-profile/mockups/variants.html` (вариант B; mockups/ в .gitignore — на диске).

> ⚠️ **ОБНОВЛЕНИЕ 2026-06-27:** **V39 занята** отдельным слайсом «СБП-реквизиты клуба» (`clubs.payment_link`/`payment_method_note`, кнопка «Оплатить по СБП» на экране frozen-участника — honor-system, платформа вне денег). Поэтому **S2 awards = V40, S3 roles = V41** (везде ниже где «V39/V40» — читать как V40/V41).

## 0. TL;DR — где мы

Вариант B залочен (форма правки ✎ на карточке участника, owner-only). Решения R1–R4 зафиксированы (спека §8).
**S1 (основа) — ГОТОВ и на staging:** режим правки + «Своя дата» доступа + «Заметка организатора» (V38). Бэк компилируется, фронт 176 тестов зелёные.
**Осталось: S2 (награды клуба) + S3 (роли).** Оба — новая сессия, каждый со своей миграцией + codegen.

## 1. Что уже сделано (S1) — НЕ переделывать

**Бэк:** `V38__membership_organizer_note.sql` (колонка `memberships.organizer_note`) + jOOQ codegen.
- `MemberProfileDto.organizerNote` (organizer-only, как `subscriptionExpiresAt`).
- `AccessGateService.setAccessUntil` + `updateNote`; репо `setAccessUntil`/`updateOrganizerNote`.
- Эндпоинты в `MemberController`: `POST …/members/{userId}/access-until` (своя дата, future-only) + `PATCH …/members/{userId}/note`.
- DTO `SetAccessUntilRequest`/`UpdateNoteRequest` в `MemberListDto.kt`.

**Фронт:** `MemberProfileModal.tsx` — `OrganizerGate` получил режим правки (✎ → форма: дата + заметка + Save/Отмена); заметка показывается read-only организатору. `api/membership.ts` (`setMemberAccessUntil`/`updateMemberNote`), `queries/members.ts` (мутации). CSS `.rd-org-edit*`/`.rd-org-note-read` в redesign.css. Тест `MemberProfileModalEdit.test.tsx`.

**Форма сейчас:** immediate-кнопки «Взнос получен»/«Закрыть доступ» (de-Stars) + ✎ открывает «Своя дата» + «Заметка» с одним «Сохранить». S2/S3 добавляют секции «Награды» и «Роль» в ту же форму.

## 2. S2 — награды клуба (V39)

**Модель (спека §2/§5, R2/R3/R4):** таблица `club_awards(id, club_id, user_id, emoji, label, awarded_by, awarded_at, UNIQUE(club_id,user_id,label))`. Косметика, на репутацию НЕ влияют.

**Бэк:**
- V39 `club_awards` + COMMENT ON (на русском, §2 спеки) → codegen (temp PG :5433, рецепт ниже).
- Репо: `addAward(clubId,userId,emoji,label,awardedBy)`, `removeAward(awardId, clubId)`, `findAwardsByMember(clubId,userId)`, `findAwardSuggestions(clubId)` (distinct emoji+label для автоподсказок — источник «как интересы»).
- DTO `AwardDto(id, emoji, label)`. `MemberProfileDto.awards: List<AwardDto>` (видят ВСЕ — R3, не гейтить как note).
- Эндпоинты `MemberController`: `POST …/members/{userId}/awards {emoji,label}` → AwardDto; `DELETE …/members/{userId}/awards/{awardId}`; `GET /api/clubs/{clubId}/award-suggestions` → List<AwardDto> (для автоподсказок при вводе).
- Лимит 6 наград/участник (валидация в сервисе). Owner/co-org (co-org появится в S3 — пока owner-only через `@RequiresOrganizer`).

**Фронт (R2 «как интересы»):** в форме правки секция «Награды клуба» — чипы с × (снять) + «＋ Добавить награду» → инпут с автоподсказками из `award-suggestions` (эмодзи+название); если нет — ввод своей (эмодзи-пикер ~12 штук + текст ≤40) + зелёная стрелка (создать). Показ чипов на карточке у всех. Паттерн автокомплита — посмотреть как сделаны интересы (`interest`-компоненты / `useInterests`).
**Опц.:** нейронка-генерация стартовых подсказок по описанию клуба (Claude) — если дёшево; для MVP не нужно.

## 3. S3 — роли + права (V40) — САМОЕ ТЯЖЁЛОЕ

**R1 = реальные права. Матрицу §4 «осмысленно продумать» в начале S3 (PO).** Сейчас матрица §4 — черновик-предложение.

**Бэк:**
- V40 `ALTER TYPE membership_role ADD VALUE 'moderator'; ADD VALUE 'co_organizer';` (изолированно, без UPDATE в той же миграции — как V37) → codegen.
- Эндпоинт `PUT …/members/{userId}/role {role}` (owner меняет любой; co-org только member↔moderator — §4 сноска ¹).
- 🔴 **Главный объём:** расширить авторизацию. Сейчас `@RequiresOrganizer(clubIdParam)` = owner-only (`AuthorizationAspect` + `common/auth/`). Ввести `@RequiresClubRole(min=MODERATOR|CO_ORGANIZER, clubIdParam)` и проставить на ~10 эндпоинтов по матрице §4 (applications approve/reject, events create/attendance, skladchina create/mark-paid — MODERATOR; members-management/settings/finances — CO_ORGANIZER; владельческое остаётся owner-only). Аккуратно: НЕ сломать owner (owner проходит любой гейт).
- `MemberProfileDto.role` уже есть — начнёт отдавать новые значения.

**Фронт:** в форме секция «Роль» — сегмент Участник/Модератор/Со-организатор (`PUT …/role`). Бейдж роли на карточке/в ростере (ClubMembersTab показывает role — расширить лейблы). Гейтить видимость управляющих экранов под роль вызывающего.

## 4. Codegen-рецепт (для V39/V40 — НЕ накатывать на dev-БД)

```bash
cd backend
docker rm -f clubs-cg >/dev/null 2>&1
docker run -d --name clubs-cg -e POSTGRES_DB=clubs -e POSTGRES_USER=clubs -e POSTGRES_PASSWORD=clubs_secret -p 5433:5432 postgres:16-alpine
# wait pg_isready; then apply ALL migrations in order:
for f in $(ls src/main/resources/db/migration/V*.sql | sort -V); do docker exec -i clubs-cg psql -v ON_ERROR_STOP=1 -q -U clubs -d clubs < "$f"; done
DB_URL="jdbc:postgresql://localhost:5433/clubs" DB_USER=clubs DB_PASSWORD=clubs_secret ./gradlew generateJooq --rerun-tasks
docker rm -f clubs-cg
# generated jOOQ коммитится. LSP может врать «Unresolved reference» на новый enum — верить ./gradlew compileKotlin.
```

## 5. Грабли

- [ ] `ADD VALUE` (роли) — изолированно, без UPDATE в той же миграции (V37-урок).
- [ ] Награды видят ВСЕ (R3) — НЕ гейтить полем как note (note = organizer-only).
- [ ] S3 авторизация: owner должен проходить любой `@RequiresClubRole`; не сломать существующие `@RequiresOrganizer`.
- [ ] Награды на репутацию/XP/ранг НЕ влияют (R4) — никаких ledger/reputation хуков.
- [ ] codegen на временном PG :5433, не на dev-БД (V30/V33 деструктивны).
- [ ] mockups/ в .gitignore — макет варианта B на диске.

## 6. Порядок гейтов (после S2/S3)

Фича-флоу CLAUDE.md: Reviewer → Security → Tester → Analyst docs-alignment (`member-admin-profile.md` + матрица status×surface) → staging → «готово, запушь». de-Stars + эти слайсы уедут одним PR (всё на `feature/de-stars-decoupling`).
