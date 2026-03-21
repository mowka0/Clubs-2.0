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
- Coolify на VPS (Timeweb Cloud, IP: 5.42.125.218) для деплоя
- GitHub repo: https://github.com/mowka0/clubs
- Auto-deploy при push в main (настроить через Coolify webhook)

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

Когда я говорю **"готово, запушь"** — выполни следующий сценарий:
1. Закоммить все изменения (conventional commits)
2. Создать новую ветку (если не в feature-ветке) или запушить текущую
3. Создать PR на GitHub через `gh pr create`
4. Влить PR в main через `gh pr merge --merge` (БЕЗ флага --delete-branch)
5. Обновить документацию в `docs/modules/` там, где она устарела
6. Сообщить статус: что закоммичено, ссылка на PR, что обновлено в доках

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
- Ветки: main (продакшен), feature/* для разработки

## Важные решения из предыдущей итерации
- `npm install` требует `--legacy-peer-deps` из-за конфликта telegram-ui и React 19
- jOOQ codegen task называется `generateJooq` (не `generateMainJooqSchemaSource`)
- JWT хранит telegram_id как Number, кастить через `(claims["telegram_id"] as? Number)?.toLong()`
- Spring Security: /actuator/** и /api/auth/** = permitAll, остальные /api/** = JWT
- Telegram initData: получать через `retrieveLaunchParams().initDataRaw`, НЕ через mock fallback
- Backend healthcheck в Docker: нужен curl в образе + start_period минимум 90s для JVM
