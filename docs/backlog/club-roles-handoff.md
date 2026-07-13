# Handoff: движок ролей клуба (co-organizers → capabilities) + нерешённый UI-баг

> Точка входа для новой сессии. Ветка `feature/co-organizers`, НЕ смёржена. Обновлено 2026-07-13.

## 1. Статус фичи — ГОТОВА, ждёт финального теста PO

Движок ролей на капабилити-модели полностью реализован и на staging. Ветка `feature/co-organizers`,
коммиты `ea5eab0` (первая версия co-organizers) → `4666f08` (рефактор в capabilities) → `047cd11`
(heap-фикс) → `c50103b`/`d5db86c`/`a5657ea` (попытки фикса UI-бага, см. §3).

- **Спека:** `docs/modules/club-roles.md` (модель прав), `docs/modules/co-organizers.md` (семантика co_organizer).
- **Бэкенд:** `ClubCapability` (14 прав), `RoleCapabilities` (карта роль→набор, **fail-closed**), `ClubRoleGuard`
  (`hasCapability`, owner-bypass + строго active), `@RequiresCapability` на 45 точках. 793 теста зелёные
  (CoOrganizerIntegrationTest 12 — сквозной регресс co-org 1:1). Владельческие 5: MANAGE_ROLES,
  EDIT_PAYMENT_REQUISITES (СБП + free→paid), MANAGE_CHAT, MANAGE_BILLING, DELETE_CLUB.
- **Фронт:** селектор роли с описаниями (заменил кнопку «Сделать со-организатором»). 277 тестов зелёные.
- **Гейты:** Reviewer APPROVE, Security PASS (все owner-only пути эскалации закрыты). Analyst docs aligned.
- **Деплой:** staging deploy 517 (`a5657ea`) — поднят, healthy. https://staging.77-42-23-177.sslip.io

**Когда UI-баг (§3) будет решён и PO протестирует → «готово, запушь»:** PR в master через `gh pr merge --squash`
(без --delete-branch). PRD §4.5.2 был переформулирован под движок ролей — PO может вето.

## 2. Инфра-фиксы, применённые в ходе (ВАЖНО знать)

Деплой staging трижды падал/тормозил — три РАЗНЫЕ причины, все инфра, не код:
1. **SSH-mux реапер Coolify** (exit 255): чинится `SSH_MUX_MAX_AGE=10800` в `/data/coolify/source/.env` на
   VPS — **применено вручную 2026-07-12** (см. память `reference_coolify_ssh_mux_failure.md`, там помечено ✅).
   Бэкап `.env.bak-premux`. Это ручное изменение прода, НЕ в git (это конфиг демона Coolify, не наш репо).
2. **Компайл-OOM** (`java.lang.OutOfMemoryError` в :compileKotlin): дефолтного heap gradle (512m) не хватало.
   Чинится `backend/gradle.properties` (heap 1536m + `kotlin.compiler.execution.strategy=in-process`) +
   `COPY gradle.properties` в `backend/Dockerfile` — **в git, коммит `047cd11`**. Бонус: убрал GC-трэшинг,
   билд снова 3-4 мин вместо 30. Это и был ответ на «почему раньше собиралось быстро» — фича перешагнула
   порог 512m, за которым компиляция сваливалась в своп/GC-ад.
3. **Локальные Testcontainers-зависоны** (застрявший postgres-контейнер в «Created») — чинится
   очисткой контейнера; сам Docker здоров. Разовое, к коду отношения нет.

**Опционально на будущее:** собирать Docker-образ в CI (GitHub Actions) + Coolify pull готового образа —
убрать сборку с тесного 4GB VPS совсем. Не срочно (heap-фикс уже ускорил).

## 3. ⚠️ НЕРЕШЁННЫЙ UI-БАГ — секция роли не видна без растягивания окна

**Симптом (iOS Telegram, реальное устройство PO):** в карточке участника (`MemberProfileModal`) нижние
секции — «🤝 РОЛЬ В КЛУБЕ» (селектор) и «⚙ УПРАВЛЕНИЕ УЧАСТНИКОМ» — не видны/недостижимы, пока вручную
не растянуть окно Mini App на полную высоту. PO жаловался ТРИЖДЫ, все три фикса он отверг («не помогло»/
«всё так-же»). Скриншоты PO показывают: **когда окно развёрнуто — вся карточка помещается**, проблема
только в medium-режиме.

**Файлы:** `frontend/src/components/club/MemberProfileModal.tsx` (модалка, `createPortal` в `document.body`),
`frontend/src/styles/redesign.css` (`.rd-sheet` / `.rd-sheet--full` ~строка 1225), `frontend/src/telegram/sdk.ts`
(setupViewport/expandViewport).

**Что уже испробовано (НЕ ПОВТОРЯТЬ, всё отвергнуто PO):**
- Попытка 1 (`c50103b`): `viewport.bindCssVars()` + `.rd-sheet` `max-height: var(--tg-viewport-stable-height, 88dvh)`
  + `.rd-sheet-body` min-height:0 скролл. → не помогло. (Риск: stable-height мог резолвиться в полную высоту
  вебвью и отключать кап. Откачено в попытке 2.)
- Попытка 2 (`d5db86c`): `expandViewport()` (viewport.expand) в mount-useEffect модалки + `.rd-sheet-body`
  `flex: 1 1 0` + `-webkit-overflow-scrolling: touch`. → не помогло.
- Попытка 3 (`a5657ea`, ТЕКУЩЕЕ): модификатор `.rd-sheet--full` — `top:0; bottom:0; max-height:none` =
  полноэкранная панель с ВЕРХНЕЙ привязкой вместо нижнего шита; грабер скрыт. Теория: top:0 ставит шапку в
  видимую зону, тело скроллится как обычная страница независимо от вьюпорта. → PO: «всё так-же не работает».

**КРИТИЧЕСКАЯ НЕИЗВЕСТНОСТЬ — кэш клиента Telegram.** Серверный кэш `index.html` ПРОВЕРЕН и корректен
(`no-cache, no-store`, versioned-бандлы immutable — сервер отдаёт свежак). НО Telegram-вебвью на iOS
кэширует мини-апп агрессивно на клиенте. Я многократно просил PO полностью закрыть/переоткрыть мини-апп —
**неизвестно, делал ли он это между тестами.** Т.е. НЕ ИСКЛЮЧЕНО, что попытка 3 (структурная, полноэкранная)
на самом деле работает, но PO видел старый кэш. **Прежде чем гнать новый фикс — сначала ДОКАЗАТЬ, какая
версия реально грузится на устройстве.**

**План для новой сессии (по приоритету):**
1. **Дать PO способ гарантированно сбросить кэш** и/или добавить видимый маркер версии в UI (например
   короткий commit-hash в углу карточки за флагом), чтобы на скриншоте было видно, свежий ли бандл.
2. **On-device отладка:** подключить Eruda (мобильная консоль) за dev-флагом ИЛИ воспроизвести в Telegram
   Desktop (там есть DevTools) — посмотреть РЕАЛЬНЫЕ `window.innerHeight`, `viewport.height`,
   `viewport.isExpanded`, computed-высоту `.rd-sheet--full`, и скроллится ли `.rd-sheet-body`.
3. **A/B на том же устройстве:** открыть `ProfileEditModal` (`.pf-edit-sheet`, длинная форма, которая
   якобы скроллится) — если она ТОЖЕ обрезается в medium-режиме, баг глобальный (вьюпорт/все шиты), а не
   специфичный для карточки; если нет — разница в структуре.
4. Проверить, не клипует ли портал родитель: есть ли у `#root`/`body`/app-контейнера `overflow:hidden`
   или фиксированная высота, обрезающая `position:fixed` оверлей (grep `overflow: hidden`, `height: 100vh`
   на корневых контейнерах).
5. Крайняя мера: `viewport.requestFullscreen()` (Bot API 8.0) — агрессивный полноэкранный режим.

**Гипотеза автора хэндоффа:** учитывая, что попытка 3 структурно верная (полноэкранная top-привязка — ровно
то, что просил PO: «как длинные формы, просто скролл»), наиболее вероятно, что PO тестировал на кэше. Первый
шаг — доказать версию на устройстве, а не слепо городить фикс №4.

## 4. Команды проверки
- Backend: `cd backend && ./gradlew test` (Testcontainers, ~4-16 мин; если завис — чистить застрявшие
  postgres-контейнеры `docker ps -a`).
- Frontend: `cd frontend && npm test` (иногда флакает тест складчины на rate-limit — перезапустить) +
  чистый билд `rm -f tsconfig.tsbuildinfo && npm run build`.
- Staging деплой: push в ветку → авто-деплой Coolify (app_id 2), ~3-4 мин. Статус:
  `ssh root@77.42.23.177 'docker exec coolify-db psql -U coolify -d coolify -t -c "SELECT id,status FROM application_deployment_queues WHERE application_id='"'"'2'"'"' ORDER BY id DESC LIMIT 3"'`
