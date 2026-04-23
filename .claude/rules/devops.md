---
paths:
  - "docker-compose*.yml"
  - "**/Dockerfile"
  - ".github/**/*.yml"
  - ".github/**/*.yaml"
  - "nginx/**/*.conf"
  - "**/nginx.conf"
  - "coolify/**"
  - "infrastructure/**"
---

# Правила DevOps

Правила для инфраструктуры, CI/CD, деплоя. Наш масштаб: VPS + Docker Compose + Coolify + GitHub Actions.

## Secrets и конфигурация

### Правила
- Секреты **только в env vars**, никогда в коде или коммитах
- `.env.example` в репо с плейсхолдерами, `.env` — в `.gitignore`
- Production secrets — в Coolify Environment Variables, не в `docker-compose.yml`
- Build-time secrets (`--build-arg`) — только для публичных параметров
- Runtime secrets — через env, не через build args (иначе попадут в image layer)
- GitHub Secrets — для CI/CD токенов (API, webhooks)

### Анти-паттерны
- `docker-compose.yml` с `POSTGRES_PASSWORD: password123` даже в dev
- Секреты в логах (токены, пароли, API keys)
- Коммит `.env` файла
- Секреты в Dockerfile (остаются в image навсегда)

---

## Docker / Контейнеры

### Security
- **Non-root user** в контейнере: `USER app` в Dockerfile
- **Минимальный base image**: alpine или distroless, не `ubuntu:latest`
- **Multi-stage builds** — build и runtime раздельно, финальный образ без build tools
- **Pin версии base images**: `postgres:16.1`, не `postgres:latest`

### Healthchecks
- Обязательны для всех сервисов
- `start_period` под JVM — **минимум 90s** (иначе Docker убивает backend до старта)
- Healthcheck команда должна быть в образе (`curl` нужно доставить)

### Network isolation
- Отдельные networks для frontend и backend
- БД доступна ТОЛЬКО из backend network
- Не публиковать порты БД/Redis наружу (`5432:5432` — анти-паттерн в prod)

### Logging
- `json-file` driver с `max-size` и `max-file` — иначе логи съедят диск
- Текущая конфигурация: backend 50MB/5, frontend/postgres/redis 10MB/5

---

## CI/CD — GitHub Actions

### Security
- **Pin actions к SHA**, не к tag: `actions/checkout@a5ac7e5...`
- **Least-privilege permissions** на job-уровне:
  ```yaml
  permissions:
    contents: read
  ```
- Secret scanning включён в репо
- `pull_request_target` с untrusted кодом — запрещён

### Pipeline структура — fail fast
```
Lint → Tests → Security scan → Build → Deploy
```
Упал lint → тесты не запускаются. Упали тесты → не билдим. Упал билд → не деплоим.

### CI vs CD
- **CI на каждый push** (любая ветка)
- **CD по правилу:**
  - push в `master` → prod
  - push в `bugfix/*`, `feature/*`, `devops/*` → staging
- Production деплой через GitHub Environments с protection rules (опционально)

### Caching
- Gradle кеш по hash `build.gradle.kts` — экономит 60-90 секунд на билд
- npm кеш по hash `package-lock.json`
- Docker layer cache через BuildKit — сборка с секунд вместо минут

---

## Деплой

### Один образ — разные окружения
Staging и prod используют один и тот же Docker image. Различие — только env vars.
- ❌ Собирать отдельный image для каждого окружения
- ✅ Один билд, разные env при деплое

### Tagging
- Tag images по git commit SHA, не только `latest`
- `latest` — для удобства, SHA — для точного rollback

### Rollback
- Держать 2-3 предыдущих образа доступными
- Coolify → кнопка Rollback на конкретную версию
- Rollback занимает секунды, не минуты

---

## Observability

### Structured logging
- JSON в prod (парсится автоматически)
- Контекст в каждой записи: `userId`, `requestId`, `traceId`
- Correlation ID проходит через все сервисы (frontend → backend → БД)

### Уровни
См. `.claude/rules/error-handling.md` § "Логирование ошибок".
Уровни переключаются через env vars (`LOGGING_LEVEL_*`), dynamic-friendly без пересборки.

### Мониторинг
- **Uptime** — внешний ping (например uptimerobot — бесплатно)
- **Диск** — алерт при 80% заполнения
- **Память** — алерт при 90% (мы уже ловили OOM)
- **JVM heap** — Spring actuator + external scraping

---

## Infrastructure as Code

### Правило: всё в git
- `docker-compose*.yml`
- `Dockerfile`'ы
- `nginx.conf`
- GitHub Actions workflows
- Flyway миграции

### Никогда не менять прод руками
- Не заходить на VPS и не править конфиги
- Изменения не в git = неизвестное состояние системы
- Все изменения через PR и CI/CD

### Одноразовые настройки — документировать
Если настройка действительно one-time (создание приложения в Coolify, настройка домена):
- Документировать в `CLAUDE.md` или `docs/infrastructure.md`
- Процедура должна быть воспроизводимой

---

## Специфика нашей инфраструктуры

### Hetzner VPS (CPX22: 2 vCPU, 4GB RAM, 80GB disk)
- **Память ограничена** — 2GB swap добавлен, но не запас безграничный
- **Backend JVM** потребляет 1-1.5GB стабильно — следить
- Один VPS = single point of failure (для пет-проекта ок)

### Coolify
- Управляет деплоем, не дёргать Docker напрямую на сервере
- **Auto Deploy** выключен — деплой через GitHub Actions webhook
- API токен с root permissions для CI/CD (хранится в GitHub Secrets)

### Staging environment
- Отдельное приложение в том же Coolify
- Same codebase, отдельные env vars (`TRAEFIK_SERVICE_NAME=clubs-frontend-staging`)
- Domain: `staging.77-42-23-177.sslip.io`

---

## Workflow

### При изменении инфраструктуры (devops-задача):
1. Ветка `devops/*`
2. Изменения в конфиге (docker-compose, Dockerfile, workflows, nginx)
3. Push → staging
4. Проверить:
   - Контейнеры поднялись (`docker ps` в Coolify)
   - Healthchecks зелёные
   - Логи чистые (нет ошибок старта)
   - Endpoint отвечает
5. После проверки — merge в master → prod
6. Проверить prod после деплоя

### Если деплой упал:
- **Не чинить руками на сервере**
- Откатиться через Coolify rollback
- Разобраться в причине в feature-ветке
- Запушить фикс через обычный флоу

---

## Чего избегать

- Hardcoded IP, credentials, API keys в коде или конфигах
- Открытые наружу порты БД/Redis/внутренних сервисов
- Running as root внутри контейнера
- `latest` tag в prod (потеря traceability при rollback)
- Деплой без healthcheck
- Manual changes на проде
- Логи без ротации (забьют диск)
- PR в master без CI
- Секреты в build args / image layers
