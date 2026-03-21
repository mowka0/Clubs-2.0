# Clubs 2.0 — Multi-Agent Development System

---

## 1. Архитектура системы агентов

### 1.1 Состав команды

```
┌──────────────────────────────────────────────────────────────────────────┐
│                            ORCHESTRATOR                                  │
│              Координация, приоритизация, контроль                         │
└──┬──────────┬──────────┬──────────┬──────────┬──────────┬──────────┬─────┘
   │          │          │          │          │          │          │
┌──▼────┐ ┌──▼─────┐ ┌──▼────┐ ┌──▼─────┐ ┌──▼──────┐ ┌▼───────┐ ┌▼──────┐
│Analyst│ │Backend │ │Front- │ │DevOps  │ │Telegram │ │Tester  │ │Review-│
│       │ │  Dev   │ │ end   │ │        │ │  Spec   │ │(QA)    │ │  er   │
└───────┘ └────────┘ └───────┘ └────────┘ └─────────┘ └────────┘ └───────┘
```

| # | Agent | Файл инструкций | Зона ответственности |
|---|-------|-----------------|---------------------|
| 1 | **Orchestrator** | `01-orchestrator.md` | Декомпозиция задач, приоритизация, назначение агентов, приёмка результатов, обновление tasks.json и progress.md |
| 2 | **Backend Developer** | `02-backend.md` | Kotlin + Spring Boot, jOOQ, Flyway миграции, REST API, бизнес-логика, schedulers |
| 3 | **Frontend Developer** | `03-frontend.md` | React 19, TypeScript, Telegram UI, Zustand, роутинг, компоненты, API-клиенты |
| 4 | **DevOps Engineer** | `04-devops.md` | Docker, docker-compose, Dockerfiles, nginx, Coolify, CI/CD, мониторинг |
| 5 | **Telegram Specialist** | `05-telegram.md` | Bot API, Mini Apps SDK, initData, Stars payments, deep links, уведомления |
| 6 | **Code Reviewer** | `06-reviewer.md` | Ревью кода, безопасность, производительность, соответствие архитектуре |
| 7 | **Analyst** | `07-analyst.md` | Анализ задач, модульная документация (docs/modules/*.md), corner cases, критерии приёмки |
| 8 | **Tester (QA)** | `08-tester.md` | Тестирование по документации Аналитика, баг-репорты, ре-тесты, приёмочное тестирование |

### 1.1.1 Naming Convention (обязательно для всех агентов)

```
БД (SQL, миграции):       snake_case    → invite_link, access_type, created_at
Kotlin (backend код):     camelCase     → inviteLink, accessType, createdAt
JSON API (request/response): camelCase  → inviteLink, accessType, createdAt
TypeScript (frontend):    camelCase     → inviteLink, accessType, createdAt
CSS classes:              kebab-case    → .club-card, .event-list
Env variables:            UPPER_SNAKE   → DB_PASSWORD, JWT_SECRET
```

Spring Boot автоматически маппит snake_case ↔ camelCase через `@JsonNaming(SnakeCaseStrategy)` или `@Column("invite_link")`. При передаче контекста между агентами используй **тот формат, который соответствует слою**: БД-поле → snake_case, API-поле → camelCase.

### 1.2 Границы ответственности

```
Analyst:         НЕ пишет код. Пишет ТОЛЬКО документацию в docs/modules/. Консультируется с ARCHITECTURE.md
Backend Dev:     НЕ трогает frontend/, НЕ трогает Docker*, НЕ пишет Telegram bot API напрямую
Frontend Dev:    НЕ трогает backend/, НЕ трогает Docker*, НЕ меняет API контракты
DevOps:          НЕ пишет бизнес-логику, НЕ меняет API, НЕ трогает src/ код
Telegram Spec:   Работает В ОБОИХ стеках, но только с Telegram-специфичным кодом
Tester (QA):     НЕ пишет application code. Тестирует по документации Аналитика. Пишет баг-репорты
Reviewer:        НЕ пишет код, только ревьюит и даёт actionable feedback
Orchestrator:    НЕ пишет код, только координирует
```

### 1.3 Взаимодействие (Workflow)

```
┌──────────────┐
│ Orchestrator │  Шаг 1: Выбирает задачу из tasks.json
│ выбирает     │         (pending, dependencies done, highest priority)
└──────┬───────┘
       │
┌──────▼───────┐
│   Analyst    │  Шаг 2: Анализирует задачу
│ 1. Читает    │         - Определяет модуль (clubs, events, payments...)
│    PRD +     │         - Согласует неясности с ARCHITECTURE.md
│    ARCH      │         - Описывает бизнес-логику пошагово
│ 2. Пишет     │         - Определяет corner cases
│    module.md │         - Пишет критерии приёмки
└──────┬───────┘         - Дописывает в docs/modules/{module}.md
       │
       │ Артефакт: docs/modules/{module}.md (спецификация задачи)
       │
┌──────▼───────┐
│  Developer   │  Шаг 3: Реализует по спецификации
│ (Backend/    │         - Читает module.md (НЕ задачу напрямую)
│  Frontend/   │         - Кодит по описанной бизнес-логике
│  DevOps/     │         - Покрывает все corner cases
│  Telegram)   │         - Self-check: build + acceptance criteria
└──────┬───────┘
       │
       │ Артефакт: код + Completion Report
       │
┌──────▼───────┐
│   Tester     │  Шаг 4: Тестирует по спецификации
│ 1. Читает    │         - Берёт критерии из module.md
│    module.md │         - Выполняет каждый тест
│ 2. Happy path│         - Проверяет corner cases
│ 3. Corner    │         - Exploratory testing
│    cases     │
│ 4. Exploratory│
└──┬───────┬───┘
   │       │
   │  ❌   │  ✅ ALL PASS
   │ BUGS  │
   │       │
   │  ┌────▼──────┐
   │  │  Reviewer  │  Шаг 5: Code review
   │  │  проверяет │
   │  └──┬─────┬──┘
   │     │     │
   │   ✅│     │❌
   │     │     │
   │  ┌──▼──┐ │
   │  │DONE │ │
   │  └─────┘ │
   │           │
   ▼           ▼
┌──────────────────┐
│    Developer     │  Bug-fix / Review-fix цикл
│    фиксит        │  (максимум 3 итерации,
│    → re-test     │   потом эскалация Orchestrator'у)
└──────────────────┘
```

### 1.3.1 Артефакты на каждом шаге

| Шаг | Кто | Вход | Выход |
|-----|-----|------|-------|
| 1 | Orchestrator | tasks.json | Handoff (task assignment) |
| 2 | Analyst | Handoff + PRD + ARCHITECTURE.md | docs/modules/{module}.md (спецификация) |
| 3 | Developer | docs/modules/{module}.md | Код + Completion Report |
| 4 | Tester | docs/modules/{module}.md + запущенный код | Test Report или Bug Report |
| 5 | Reviewer | Код + Test Report | Review Report (APPROVE / REQUEST CHANGES) |
| 6 | Orchestrator | Review Report (APPROVE) | tasks.json status → done, progress.md |

### 1.3.2 Модульная документация (docs/modules/)

Аналитик ведёт накопительную документацию по модулям:

```
docs/modules/
├── auth.md            ← TASK-005, TASK-006, TASK-044
├── clubs.md           ← TASK-008, TASK-009
├── membership.md      ← TASK-010, TASK-011, TASK-012
├── events.md          ← TASK-013, TASK-014, TASK-015
├── attendance.md      ← TASK-016, TASK-017, TASK-018
├── applications.md    ← TASK-019
├── users.md           ← TASK-020, TASK-021
├── payments.md        ← TASK-022, TASK-041, TASK-042
├── telegram-bot.md    ← TASK-039, TASK-040, TASK-043
├── frontend-core.md   ← TASK-023, TASK-024, TASK-025
├── frontend-pages.md  ← TASK-026..TASK-035
├── frontend-stores.md ← TASK-038
└── infrastructure.md  ← TASK-001..TASK-004, TASK-007, TASK-036, TASK-037
```

Каждый module.md накапливается: новые задачи ДОПИСЫВАЮТСЯ, старые НЕ удаляются.

### 1.4 Handoff Protocol

Каждая передача задачи между агентами содержит:

```
FROM: {agent_name}
TO: {agent_name}
TASK: {TASK-ID}
ACTION: {analyze | specify | develop | test | fix_bugs | review | fix_review | approve | done}
CONTEXT: {что сделано, что нужно, что важно}
FILES: {список затронутых файлов}
MODULE_DOC: {docs/modules/{module}.md — если применимо}
```

---

## 2. End-to-End Process

### 2.1 Жизненный цикл задачи

```
pending → analyzing → specified → in_progress → self_checked → testing → [bugs_found → fixing → re-testing] → in_review → [approved | changes_requested] → done
```

### 2.2 Шаги

| # | Кто | Что делает | Артефакт |
|---|-----|-----------|----------|
| 1 | Orchestrator | Читает tasks.json, выбирает задачу с наивысшим приоритетом и выполненными зависимостями | Handoff prompt |
| 2 | **Analyst** | Анализирует задачу, согласует с ARCHITECTURE.md, пишет спецификацию | `docs/modules/{module}.md` |
| 3 | Developer | Читает module.md, реализует по спецификации | Код + коммиты |
| 4 | Developer | Self-check: проходит чек-лист, выполняет test_steps | Completion Report |
| 5 | **Tester** | Тестирует по критериям из module.md (happy path + corner cases + exploratory) | Test Report или Bug Report |
| 6 | Developer (если баги) | Фиксит баги из Bug Report | Обновлённый код |
| 7 | Tester (ре-тест) | Перепроверяет фиксы + регрессия | Test Report (повторно) |
| 8 | Reviewer | Проверяет код по чек-листу ревью | Review Report |
| 9 | Developer (если нужно) | Фиксит замечания ревьюера | Обновлённый код |
| 10 | Orchestrator | Обновляет tasks.json → status: "done", пишет в progress.md | Обновлённые файлы |

### 2.3 Fast Track (инфраструктурные задачи)

Задачи категории `infrastructure` (TASK-001..004, 007, 023, 036, 037) проходят упрощённый цикл:

```
Orchestrator → Developer → Self-check → Orchestrator (done)
```

Без Analyst, Tester и Reviewer. Причина: эти задачи тривиальны (init проекта, docker, миграции), их качество проверяется `./gradlew build` / `npm run build` / `docker compose up`.

Полный цикл (Analyst → Developer → Tester → Reviewer) — для задач с бизнес-логикой: auth, clubs, events, payments, membership, UI pages.

### 2.4 Параллельная работа

Агенты могут работать параллельно ТОЛЬКО если их задачи:
- Не имеют общих зависимостей
- Не модифицируют одни и те же файлы
- Находятся в разных доменах (backend / frontend / devops)

Пример: TASK-001 (backend init) + TASK-023 (frontend init) + TASK-002 (docker) — параллельно.

---

## 3. Механизмы надёжности

### 3.1 Self-Checking (каждый агент)

```
Перед отправкой на ревью агент ОБЯЗАН:

1. ПЕРЕЧИТАТЬ acceptance_criteria — сопоставить КАЖДЫЙ пункт с конкретным кодом
2. ВЫПОЛНИТЬ все test_steps — задокументировать результат каждого
3. СОБРАТЬ проект:
   - Backend: ./gradlew build (0 errors, 0 warnings)
   - Frontend: npm run build (0 TS errors)
4. ПРОВЕРИТЬ edge cases:
   - Пустой/null input → корректная ошибка?
   - Нет авторизации → 401?
   - Нет прав → 403?
   - Ресурс не найден → 404?
   - Дубликат → 409?
   - Превышен лимит → 400/429?
5. ПРОВЕРИТЬ что нет:
   - Захардкоженных секретов
   - console.log / println (кроме логгера)
   - TODO/FIXME без трекинга
   - Неиспользуемого кода
```

### 3.2 Cross-Review

```
Правило: НИ ОДНА задача не получает status: "done" без ревью.

Reviewer проверяет:
- Correctness (логика, edge cases)
- Security (auth, injection, data leaks)
- Architecture (соответствие ARCHITECTURE.md)
- Quality (code style, naming, structure)
- Performance (N+1, re-renders, pagination)
```

### 3.3 Fallback-стратегии

| Ситуация | Действие |
|----------|----------|
| Зависимость не выполнена | СТОП. Нельзя начинать. Вернуть Orchestrator'у |
| Непонятен API контракт | Читать ARCHITECTURE.md. Если не описан → спросить Orchestrator |
| Непонятна бизнес-логика | Читать PRD-Clubs.md. Если неоднозначно → спросить Orchestrator |
| Не знаешь API библиотеки | Читать официальную документацию. НИКОГДА не угадывать |
| Test step не проходит | НЕ помечать done. Фиксить или эскалировать |
| Build падает | Починить. Никогда не коммитить сломанный build |
| Конфликт с чужим кодом | git pull, resolve, проверить что ничего не сломал |

### 3.4 Обработка неопределённости

```
Шкала уверенности:
- 100% уверен → делай
- 80%+ уверен → делай, пометь решение в Completion Report
- 50-80% → реализуй простейший вариант, пометь как "needs review"
- <50% → СТОП. Спроси Orchestrator. Не угадывай.

Категорически запрещено:
- Выдумывать API endpoints, не описанные в ARCHITECTURE.md
- Выдумывать поля БД, не описанные в миграциях
- Угадывать поведение Telegram API — только документация
- Предполагать существование кода, который не читал
```

---

## 4. Стандарты разработки

### 4.1 Code Style

**Kotlin (Backend):**
```
- val > var (иммутабельность по умолчанию)
- data class для DTO
- Нет wildcard imports
- Explicit return types для public methods
- Один класс на файл (кроме sealed classes)
- Порядок: companion → properties → init → public methods → private methods
```

**TypeScript (Frontend):**
```
- const > let, никогда var
- Named exports (не default)
- FC<Props> для компонентов
- Strict TypeScript (noImplicitAny: true)
- Интерфейсы для props, состояний, API responses
- Нет `any` типа — всегда конкретный тип
```

### 4.2 Commits & Pull Requests

```
Формат: conventional commits
Примеры:
  feat(club): add CRUD endpoints
  fix(auth): handle expired JWT gracefully
  chore(docker): add healthcheck to backend service
  refactor(event): extract voting logic to separate service

Правила коммитов:
- Один коммит = одно логическое изменение
- Коммит-сообщение на английском
- Не коммитить .env, secrets, node_modules, build/

Правила PR и веток:
- При создании PR через `gh pr merge` НИКОГДА не использовать флаг --delete-branch
- Ветки после мержа НЕ удалять — они остаются для истории
- В описании PR указывать ссылку на документацию, если она обновлялась
```

### 4.3 Тестирование

```
MVP фаза: ручное тестирование по test_steps из tasks.json
Каждый test_step = конкретная curl-команда или действие в браузере

Формат документирования:
  Step 1: POST /api/clubs {...} → 201 ✅
  Step 2: GET /api/clubs/abc → 200, data matches ✅
  Step 3: POST /api/clubs с name > 60 chars → 400 ✅
```

### 4.4 Работа с зависимостями

```
Backend:
- Все зависимости в build.gradle.kts
- Версии зафиксированы (не LATEST)
- Spring Boot BOM для Spring-зависимостей

Frontend:
- npm install --legacy-peer-deps (обязательно!)
- package-lock.json коммитится
- Версии зафиксированы в package.json
```

---

## 5. Шаблоны

### 5.1 Шаблон задачи (Orchestrator → Agent)

```markdown
## TASK-{ID}: {название}

**Агент:** {Backend Dev | Frontend Dev | DevOps | Telegram Spec}
**Приоритет:** {critical | high | medium | low}
**Зависимости (все done ✅):** TASK-001, TASK-002

### Что сделать
{описание из tasks.json}

### Acceptance Criteria
- [ ] {критерий 1}
- [ ] {критерий 2}

### Test Steps
1. {шаг 1}
2. {шаг 2}

### Контекст
- API контракт: {секция из ARCHITECTURE.md}
- Пакет: {путь в проекте}
- Зависит от: {что уже создано в предыдущих задачах}

### Предупреждения
- {pitfall 1}
- {pitfall 2}
```

### 5.2 Шаблон ответа (Agent → Orchestrator)

```markdown
## Выполнено: TASK-{ID}

### Изменённые файлы
- `path/file1.kt` — {что сделано}
- `path/file2.kt` — {что сделано}

### Acceptance Criteria
- [x] {критерий 1} — реализовано в {файл}
- [x] {критерий 2} — реализовано в {файл}

### Test Steps
1. ✅ {шаг 1} — результат: {output}
2. ✅ {шаг 2} — результат: {output}

### Build
- ./gradlew build: ✅ SUCCESS
- npm run build: ✅ 0 errors (если frontend)

### Коммит
`feat(club): add CRUD endpoints for clubs`

### Решения и заметки
- {решение 1 с обоснованием}
- {если есть follow-up}
```

### 5.3 Шаблон ревью (Reviewer → Orchestrator)

```markdown
## Review: TASK-{ID}

**Вердикт:** ✅ APPROVE / ❌ REQUEST CHANGES

### Проверено
- Correctness: ✅/❌
- Security: ✅/❌
- Architecture: ✅/❌
- Code Quality: ✅/❌
- Performance: ✅/❌

### Проблемы (если REQUEST CHANGES)

#### 🔴 Критично (блокер)
1. `file.kt:42` — Нет проверки прав: эндпоинт доступен без авторизации
   → Добавить `@RequiresOrganizer` или проверку в service

#### 🟡 Важно (нужно исправить)
1. `file.tsx:15` — Нет обработки ошибки при API-вызове
   → Добавить try/catch + error state

#### 🔵 Рекомендация
1. `file.kt:88` — Вынести валидацию в отдельный метод

### Что хорошо
- {конкретная похвала}
```

---

## 6. Фазы разработки (параллелизация)

```
Phase 1 — Foundation (параллельно):
  Backend:  TASK-001 (init)
  Frontend: TASK-023 (init)
  DevOps:   TASK-002 (docker compose)

Phase 2 — Core Infra:
  Backend:  TASK-003 (migrations) → TASK-004 (jOOQ) → TASK-005 (auth)
  Backend:  TASK-007 (error handling) — параллельно с TASK-003
  Frontend: TASK-025 (routing) — параллельно

Phase 3 — Auth + SDK:
  Frontend: TASK-024 (SDK + apiClient) — после TASK-005

Phase 4 — Core Features (параллельно):
  Backend:  TASK-008 (clubs), TASK-044 (authorization), TASK-006 (rate limit)
  Frontend: TASK-038 (stores)

Phase 5 — Features + UI (параллельно):
  Backend:  TASK-009, TASK-010, TASK-013
  Frontend: TASK-026, TASK-030, TASK-032, TASK-033

Phase 6 — Advanced:
  Backend:  TASK-011, TASK-014, TASK-015
  Frontend: TASK-027, TASK-028, TASK-029, TASK-031

Phase 7 — Telegram + Deploy:
  Backend:  TASK-039, TASK-040, TASK-041, TASK-043
  Frontend: TASK-034
  DevOps:   TASK-036, TASK-037

Phase 8 — Polish:
  Backend:  TASK-016..TASK-022, TASK-042, TASK-045
  Frontend: TASK-035
```
