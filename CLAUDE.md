# Clubs 2.0 — Telegram Mini App

## Проект
Telegram Mini App для создания и управления платными офлайн-сообществами (клубами) по интересам. Полный PRD в файле `PRD-Clubs.md`.

## Текущий статус
- PRD декомпозирован в 45 задач → `tasks.json`
- Прогресс → `progress.md`
- Код ещё НЕ написан — проект стартует с нуля

## Следующие шаги (по порядку)
1. **Архитектурный план** — API контракты, схема БД, структура пакетов, роутинг фронтенда
2. **Инструкции агентов** — промпты для backend, frontend, devops, telegram-специалиста
3. **Разработка** — начать с TASK-001 (backend init) и TASK-023 (frontend init) параллельно

## Технический стек
### Backend
- Kotlin 2.1.x + Spring Boot 3.4.x + Gradle KTS
- jOOQ (генерация из БД, KotlinGenerator)
- Flyway для миграций
- JWT (jjwt 0.12.x) для авторизации
- Redis (Lettuce) для rate limiting и кэширования
- PostgreSQL 16

### Frontend
- React 19 + TypeScript + Vite
- @telegram-apps/sdk-react v3 (Telegram Mini Apps SDK)
- @telegram-apps/telegram-ui v2 (UI kit)
- Zustand v5 (state management)
- react-router-dom v7

### Infrastructure
- Docker Compose для локальной разработки и продакшена
- Coolify на VPS (Hetzner Cloud, IP: 77.42.23.177) для деплоя
- GitHub repo: https://github.com/mowka0/Clubs-2.0
- Auto-deploy: push в `master` → production, push в `bugfix/*`/`feature/*`/`devops/*` → staging
- Staging: отдельное приложение в Coolify на другом домене (настраивается один раз, см. ниже)

#### Настройка staging в Coolify (одноразово)
1. Coolify → New Application → Docker Compose → тот же репо, `docker-compose.prod.yml`
2. Задать домен staging (например `staging.77-42-23-177.sslip.io`)
3. Env vars: те же что у production + `TRAEFIK_SERVICE_NAME=clubs-frontend-staging`
4. Скопировать UUID приложения (из URL в Coolify) и Webhook URL
5. Добавить GitHub Secrets:
   - `COOLIFY_API_URL` = `http://77.42.23.177:8000`
   - `COOLIFY_STAGING_UUID` = UUID из Coolify
   - `COOLIFY_STAGING_WEBHOOK_URL` = Webhook URL из Coolify
   - `COOLIFY_STAGING_URL` = `https://staging.77-42-23-177.sslip.io`
   - `COOLIFY_TOKEN` уже есть (используется общий)

### Telegram
- Bot: @clubs_admin_bot
- Bot Token: хранится в env var TELEGRAM_BOT_TOKEN
- Mini App открывается через menu button бота

## Работа с задачами (tasks.json)
- Задачи имеют id, category, priority, dependencies, status
- Статусы: pending → in_progress → done
- Перед взятием задачи проверь что все dependencies = done
- После выполнения: пройди test_steps, обнови status, запиши в progress.md
- ЗАПРЕЩЕНО удалять или редактировать задачи — только менять status

## Мульти-агентная система

Системные правила и workflow: `.claude/agents/00-SYSTEM.md`
Архитектура проекта: `ARCHITECTURE.md`

### Агенты

| # | Агент | Инструкции | Зона ответственности |
|---|-------|-----------|---------------------|
| 1 | **Orchestrator** | `.claude/agents/01-orchestrator.md` | Декомпозиция задач, приоритизация, назначение агентов, приёмка |
| 2 | **Backend Developer** | `.claude/agents/02-backend.md` | Kotlin, Spring Boot, jOOQ, Flyway, REST API, бизнес-логика |
| 3 | **Frontend Developer** | `.claude/agents/03-frontend.md` | React 19, TypeScript, Telegram UI, Zustand, роутинг |
| 4 | **DevOps Engineer** | `.claude/agents/04-devops.md` | Docker, Coolify, CI/CD, nginx, мониторинг |
| 5 | **Telegram Specialist** | `.claude/agents/05-telegram.md` | Mini Apps SDK, Bot API, Stars payments, deep links |
| 6 | **Code Reviewer** | `.claude/agents/06-reviewer.md` | Code review, безопасность, соответствие архитектуре |
| 7 | **Analyst** | `.claude/agents/07-analyst.md` | Анализ задач, спецификации в docs/modules/*.md, критерии приёмки |
| 8 | **Tester (QA)** | `.claude/agents/08-tester.md` | Тестирование по спецификациям, баг-репорты |

### Быстрый запуск команды

Когда я говорю **"запусти команду"** — создай Agent Team по 00-SYSTEM.md:
1. Прочитай `tasks.json` — найди pending задачи с выполненными зависимостями
2. Определи каких агентов запустить (по category задач)
3. Создай teammates, каждому укажи его инструкции из `.claude/agents/`
4. Раздай задачи по приоритету

Когда я говорю **"продолжи"** — прочитай `tasks.json` и `progress.md`, подхвати in_progress задачи или возьми следующие pending.

### Bugfix флоу

Когда я описываю баг — автоматически запускай этот флоу:

```
Я (описание бага) → Developer (фикс) → Reviewer (ревью) → Staging деплой → Я (тест) → Prod
```

1. Если в `master` → создай ветку `bugfix/<краткое-описание>`. Если уже в `bugfix/*` → используй текущую.
2. **Developer** — находит root cause, фиксит точечно, не трогает лишнее
3. **Reviewer** — проверяет только изменённые файлы: side effects, безопасность, соответствие архитектуре
4. После ревью — закоммить и **запушь ветку** (`git push -u origin <ветка>`)
   → GitHub Actions автоматически задеплоит в staging
   → Подожди ~3 мин пока поднимется
5. Скажи мне что именно проверить и на каком staging URL
6. Жди команды **"готово, запушь"** (я протестировал в staging)

### Фича флоу

Когда я говорю **"фича: <описание>"** — автоматически запускай этот флоу:

```
Я (описание) → Orchestrator (декомпозиция) → Analyst (спека) → Developer (код) → Reviewer (ревью) → Tester (QA) → Staging деплой → Я (тест) → Prod
```

1. Если в `master` → создай ветку `feature/<краткое-описание>`. Если уже в `feature/*` → используй текущую.
2. **Orchestrator** — декомпозирует фичу на подзадачи, определяет порядок
3. **Analyst** — пишет спецификацию в `docs/modules/<feature>.md`: что делаем, API контракты, критерии приёмки
4. **Developer** — реализует по спецификации: backend + frontend
5. **Reviewer** — проверяет изменённые файлы: архитектура, безопасность, соответствие спеке
6. **Tester** — тестирует по критериям приёмки из спеки, пишет баг-репорт если есть замечания
7. После прохождения Tester — закоммить и **запушь ветку**
   → GitHub Actions задеплоит в staging
   → Подожди ~3 мин
8. Скажи мне что проверить (сценарии из спеки) и staging URL
9. Жди команды **"готово, запушь"**

### DevOps флоу

Когда я говорю **"девопс задача: <описание>"** — автоматически запускай этот флоу:

```
Я (описание задачи) → DevOps (план → реализация) → Reviewer (безопасность) → Staging деплой → Я (проверка) → Prod
```

1. Если в `master` → создай ветку `devops/<краткое-описание>`. Если уже в `devops/*` → используй текущую.
2. **DevOps** — читает `.claude/agents/04-devops.md`, декомпозирует задачу на шаги, реализует: Docker Compose, Nginx, Coolify, CI/CD конфиги
3. **Reviewer** — проверяет только изменённые файлы: секреты не в коде, порты не открыты лишние, конфиги корректны, нет hardcoded IP/credentials
4. После ревью — закоммить и **запушь ветку**
   → GitHub Actions задеплоит в staging
   → Подожди ~3 мин
5. Скажи мне что проверить (curl эндпоинт, открыть URL, проверить логи) и staging URL
6. Жди команды **"готово, запушь"**

### Команда "готово, запушь"

> Вызывается только после того, как я протестировал в staging и дал добро.
> К этому моменту ветка уже запушена, staging задеплоен.

1. Обновить документацию в `docs/modules/` там, где она устарела
2. Обновить `PRD-Clubs.md` если реализация отклонилась от требований
3. Закоммитить все изменения (conventional commits) — включая документацию
4. Запушить ветку (если были новые коммиты в п.1-3)
5. Создать PR на GitHub через `gh pr create`
6. Влить PR в master через `gh pr merge --merge` (БЕЗ флага `--delete-branch`)
   → GitHub Actions автоматически задеплоит master в production
7. Переключиться на `master`, сделать `git pull`
8. Сообщить статус: что закоммичено, ссылка на PR, что обновлено в доках

### Свободные задачи (не из tasks.json)

Когда я описываю задачу своими словами (не по ID):
1. Orchestrator декомпозирует на подзадачи
2. Определяет каких агентов задействовать
3. Устанавливает зависимости между подзадачами
4. Запускает выполнение

### Workflow на каждую задачу

```
Orchestrator → Analyst (спецификация) → Developer (код) → Tester (тесты) → Reviewer (ревью) → Done
```

## Конвенции
- Язык интерфейса: русский
- Язык кода и комментариев: английский
- Коммиты: conventional commits (feat:, fix:, chore:)
- Ветки: `master` (продакшен), `feature/*`, `bugfix/*`, `devops/*` для разработки

## Важные решения из предыдущей итерации
- `npm install` требует `--legacy-peer-deps` из-за конфликта telegram-ui и React 19
- jOOQ codegen task называется `generateJooq` (не `generateMainJooqSchemaSource`)
- JWT хранит telegram_id как Number, кастить через `(claims["telegram_id"] as? Number)?.toLong()`
- Spring Security: /actuator/** и /api/auth/** = permitAll, остальные /api/** = JWT
- Telegram initData: получать через `retrieveLaunchParams().initDataRaw`, НЕ через mock fallback
- Backend healthcheck в Docker: нужен curl в образе + start_period минимум 90s для JVM
