# Module: Infrastructure

---

## TASK-001 — Backend инициализация: Kotlin + Spring Boot + Gradle KTS

### Структура проекта

```
backend/
  build.gradle.kts          — зависимости, плагины, jOOQ codegen
  settings.gradle.kts       — rootProject.name = "clubs"
  src/main/kotlin/com/clubs/
    ClubsApplication.kt     — @SpringBootApplication, fun main()
  src/main/resources/
    application.yml         — конфиг с профилями dev/prod
```

### Ключевые зависимости (build.gradle.kts)

```kotlin
plugins {
    kotlin("jvm") version "2.1.x"
    kotlin("plugin.spring") version "2.1.x"
    id("org.springframework.boot") version "3.4.x"
    id("io.spring.dependency-management") version "1.1.x"
    id("nu.studer.jooq") version "9.x"
    id("org.flywaydb.flyway") version "10.x"
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-jooq")   // DSLContext
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
}
```

### application.yml — логирование

Уровни логирования управляются через env-переменные (без перезапуска не меняются, но удобны для настройки окружений):

```yaml
logging:
  level:
    root: ${LOGGING_LEVEL_ROOT:INFO}
    com.clubs: ${LOGGING_LEVEL_COM_CLUBS:INFO}
    org.springframework.web: ${LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB:WARN}
    org.springframework.security: ${LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY:WARN}
    org.hibernate: ${LOGGING_LEVEL_ORG_HIBERNATE:WARN}
    org.flywaydb: ${LOGGING_LEVEL_ORG_FLYWAYDB:INFO}
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
```

Для включения DEBUG нашего кода: задать `LOGGING_LEVEL_COM_CLUBS=DEBUG` в Coolify env.

### application.yml профили

```yaml
spring:
  profiles:
    active: dev

---
spring:
  config:
    activate:
      on-profile: dev
  datasource:
    url: jdbc:postgresql://localhost:5432/clubs
    username: clubs
    password: clubs
  flyway:
    baseline-on-migrate: true
    baseline-version: 9   # если схема уже существует без flyway_schema_history
  redis:
    host: localhost
    port: 6379

---
spring:
  config:
    activate:
      on-profile: prod
  datasource:
    url: ${DATABASE_URL}
    username: ${DATABASE_USER}
    password: ${DATABASE_PASSWORD}
  redis:
    host: ${REDIS_HOST}
    port: 6379
```

---

## TASK-002 — Docker Compose: локальная разработка

### Файл: `docker-compose.dev.yml`

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: clubs
      POSTGRES_USER: clubs
      POSTGRES_PASSWORD: clubs
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

volumes:
  postgres_data:
```

**Запуск:** `docker compose -f docker-compose.dev.yml up -d`

---

## TASK-003 — Flyway миграции

### Расположение

```
backend/src/main/resources/db/migration/
  V1__create_users.sql
  V2__create_clubs.sql
  V3__create_memberships.sql
  V4__create_applications.sql
  V5__create_events.sql
  V6__create_event_responses.sql
  V7__create_invitations.sql
  V8__create_payments.sql
  V9__create_activity_ratings.sql
```

### Ключевые таблицы

| Таблица | Описание |
|---------|----------|
| `users` | id (uuid), telegram_id (bigint unique), username, first_name, last_name, avatar_url, created_at, updated_at |
| `clubs` | id (uuid), organizer_id (uuid FK), name, description, category, access_type (enum: open/closed/private), member_limit, member_count, activity_rating, created_at |
| `memberships` | id (uuid), user_id, club_id, role (enum: member/organizer), status (enum: active/left/kicked), joined_at |
| `applications` | id (uuid), user_id, club_id, status (enum: pending/approved/rejected), answer_text, created_at |
| `events` | id (uuid), club_id, title, description, venue_address, datetime, stage, member_limit, confirmed_count, stage_2_triggered, created_at |
| `event_responses` | id (uuid), event_id, user_id, stage_1_vote (enum: yes/no/maybe), stage_2_confirmed (bool), created_at |
| `invitations` | id (uuid), club_id, code (unique), created_by, uses_left, expires_at, created_at |

### Dev-профиль: baseline-on-migrate

Если схема уже была создана вручную (без Flyway), добавить в `application.yml`:
```yaml
spring:
  flyway:
    baseline-on-migrate: true
    baseline-version: 9
```

Это пропустит применение V1–V9 и запишет версию 9 в `flyway_schema_history`.

---

## TASK-004 — jOOQ Codegen

### Конфигурация в build.gradle.kts

```kotlin
jooq {
    version.set("3.19.16")
    configurations {
        create("main") {
            jooqConfiguration.apply {
                jdbc.apply {
                    driver = "org.postgresql.Driver"
                    url = "jdbc:postgresql://localhost:5432/clubs"
                    user = "clubs"
                    password = "clubs"
                }
                generator.apply {
                    name = "org.jooq.codegen.KotlinGenerator"
                    database.apply {
                        name = "org.jooq.meta.postgres.PostgresDatabase"
                        inputSchema = "public"
                        excludes = "flyway_schema_history"
                    }
                    target.apply {
                        packageName = "com.clubs.jooq"
                        directory = "src/main/kotlin"
                    }
                }
            }
        }
    }
}
```

**Запуск codegen:** `./gradlew generateJooq`

**Результат:** генерируется пакет `com.clubs.jooq.tables` с классами `USERS`, `CLUBS`, `MEMBERSHIPS`, `EVENTS`, `EVENT_RESPONSES`, `INVITATIONS`, `APPLICATIONS`.

### Важные детали

- Task называется `generateJooq` (не `generateMainJooqSchemaSource`)
- Codegen нужно запускать после каждого изменения схемы (новые Flyway миграции)
- В Docker build используется флаг `-x generateJooq` чтобы пропустить codegen (классы уже в src)
- Поля из enum: `AccessType.open` → `AccessType.\`open\`` (Kotlin reserved word)

---

## TASK-007 — GlobalExceptionHandler

### Файл: `backend/src/main/kotlin/com/clubs/common/exception/`

```
exception/
  GlobalExceptionHandler.kt   — @RestControllerAdvice
  NotFoundException.kt        — 404
  ConflictException.kt        — 409
  ValidationException.kt      — 400
  ForbiddenException.kt       — 403
  ErrorResponse.kt            — { error: String, message: String }
```

### Маппинг исключений → HTTP коды

| Исключение | HTTP | Поле error |
|------------|------|-----------|
| `NotFoundException` | 404 | "Not Found" |
| `ConflictException` | 409 | "Conflict" |
| `ValidationException` | 400 | "Bad Request" |
| `ForbiddenException` | 403 | "Forbidden" |
| `MethodArgumentNotValidException` | 400 | "Validation Failed" |
| Прочие | 500 | "Internal Server Error" |

### ErrorResponse формат

```json
{
  "error": "Not Found",
  "message": "Club not found"
}
```

---

## TASK-036 — Production Docker

### Multi-stage Dockerfile для backend

```dockerfile
FROM gradle:8.12-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle bootJar --no-daemon -x generateJooq

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
RUN apk add --no-cache curl
COPY --from=build /app/build/libs/*.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Важно:** `start_period=90s` — JVM на alpine стартует медленно.

### Multi-stage Dockerfile для frontend

```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install --legacy-peer-deps
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -q --spider http://localhost/ || exit 1
```

**Важно:** `npm install` требует `--legacy-peer-deps` из-за конфликта `@telegram-apps/telegram-ui` и React 19.

### docker-compose.prod.yml

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    networks:
      - clubs_net

  redis:
    image: redis:7-alpine
    networks:
      - clubs_net

  backend:
    build: ./backend
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DATABASE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB}
      DATABASE_USER: ${POSTGRES_USER}
      DATABASE_PASSWORD: ${POSTGRES_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
      JWT_EXPIRATION: ${JWT_EXPIRATION:-3600000}  # 1h per security.md
      TELEGRAM_BOT_TOKEN: ${TELEGRAM_BOT_TOKEN}
      REDIS_HOST: redis
    depends_on:
      postgres:
        condition: service_healthy
    networks:
      - clubs_net

  frontend:
    build: ./frontend
    ports:
      - "80:80"
      - "443:443"
    depends_on:
      - backend
    networks:
      - clubs_net

networks:
  clubs_net:

volumes:
  postgres_data:
```

### nginx.conf (SPA routing)

```nginx
server {
    listen 80;
    root /usr/share/nginx/html;
    index index.html;

    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

### Traefik routing: как prod и staging разведены

**TL;DR:** роутеры делает Coolify сам (уникальные на приложение), сервис Traefik
автогенерирует per-container из `EXPOSE 80`. В compose **нет** явных
`traefik.http.services.*` лейблов.

#### Как это работает

1. **Coolify автоматически** навешивает router-лейблы на каждый запущенный
   контейнер. Имя роутера содержит UUID Coolify-приложения:
   ```
   traefik.http.routers.http-0-u91a5392n24ubfq17kl251z4-frontend.rule = Host(`staging.77-42-23-177.sslip.io`)
   traefik.http.routers.http-0-qhbcadbuungspby1mxw7p7n9-frontend.rule = Host(`77.42.23.177.sslip.io`)
   ```
   Разные приложения в Coolify = разные UUID = разные имена роутеров.
2. **Наш compose** не объявляет `traefik.http.services.*` — Traefik Docker provider
   видит роутер без явного `service=`, на том же контейнере не находит никакого
   `services.*` лейбла → создаёт default service и связывает его с этим
   контейнером, используя порт из Dockerfile `EXPOSE 80`.
3. Итог: `staging.*.sslip.io` ↔ router `u91a5...-frontend` ↔ auto-service ↔
   только staging-контейнер. Prod — аналогично, со своим UUID. Никакого
   пересечения между окружениями.

#### Почему это не через `TRAEFIK_SERVICE_NAME` (исторический inцидент)

Раньше в compose было:
```yaml
labels:
  - "traefik.http.services.${TRAEFIK_SERVICE_NAME:-clubs-frontend-prod}.loadbalancer.server.port=80"
```
Идея: в Coolify у prod и staging приложений выставить разные значения
(`clubs-frontend-prod` / `clubs-frontend-staging`), Compose подставит,
Traefik разведёт по именам сервисов.

**На практике Coolify deployer не подставляет `${VAR:-default}` синтаксис
в ключах Traefik-лейблов.** `docker inspect` живых контейнеров показывал
лейбл как литеральную строку:
```
"traefik.http.services.${TRAEFIK_SERVICE_NAME:-clubs-frontend-prod}.loadbalancer.server.port": "80"
```
На **обоих** контейнерах (prod и staging) получался **одинаковый литеральный
ключ сервиса**. Traefik видел один service с двумя backend'ами и балансировал
50/50 между окружениями. Результат: на `staging.*.sslip.io` случайно падаешь
то на staging-контейнер, то на prod-контейнер — каждый со своим бандлом.
Всё выглядело как флаки в любом тесте.

Выставление переменной в Coolify UI **не помогало** — дефис в `:-default`
ломал парсинг регекспа Coolify (простое `${VAR}` без fallback, возможно,
сработало бы, но это не проверялось).

Фикс: убрали лейбл целиком, положились на auto-service Traefik.
`TRAEFIK_SERVICE_NAME` теперь **нигде в проекте не используется** — можно
удалить из env-переменных в Coolify, но не обязательно.

#### Что делать если понадобятся multiple replicas одного окружения

Тогда auto-service уже не подойдёт (Traefik создаст отдельные сервисы на
каждый контейнер вместо одного пула). Путь — явный named service, но
**не через compose-переменную** (Coolify не подставит). Варианты:
- Отдельный `docker-compose.staging.yml` с хардкодом имени сервиса.
- Сделать замену имени в CI перед `docker compose up` через `sed`.
- Посмотреть umбельно конфиги per-application в Coolify UI (если такое есть).

---

### Debug playbook: «на одном домене два разных ответа»

Симптомы: `curl` одной и той же страницы даёт разные бандлы / ETag / Last-Modified
при последовательных запросах. Или браузер в инкогнито видит одно, Telegram — другое.
Ошибки типа 404 на ассет который есть на одном контейнере, но нет на другом.

1. **Подтвердить split:**
   ```bash
   for i in {1..6}; do curl -s https://<domain>/ | grep -oE 'assets/index-[^".]+\.js'; done | sort | uniq -c
   ```
   Если в выводе разные хеши — трафик размазывается между несколькими бэкендами.

2. **Найти все контейнеры на домене** (SSH на VPS):
   ```bash
   docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}' | grep -i <service>
   ```
   Если более одного — смотреть Traefik-лейблы у каждого:
   ```bash
   docker inspect <container> --format '{{json .Config.Labels}}' | tr ',' '\n' | grep -i traefik
   ```

3. **Что смотреть в лейблах:**
   - Host-rule у роутеров — разные ли они?
   - Имя сервиса в `traefik.http.services.X.loadbalancer.server.port` — не совпадает ли между контейнерами?
   - Не висит ли в ключе буквальная строка `${...}` — значит переменная не подставилась, и это общий ключ для всех контейнеров.

4. **Быстрый sanity-check Coolify auto-routers:** роутер у каждого приложения
   должен содержать UUID Coolify-приложения (`http-0-<uuid>-<service>`). Если
   у двух контейнеров из РАЗНЫХ приложений совпадает что-то ещё кроме Host-rule
   — это потенциальная коллизия.

5. **Если нашлось совпадение имени сервиса** — выбор из трёх:
   - Убрать явный `services.*` лейбл целиком (как мы сделали). Работает для 1
     контейнер = 1 окружение.
   - Захардкодить уникальное имя сервиса в compose и держать отдельные compose-файлы
     на env (prod/staging).
   - Перед деплоем подставить имя через CI (sed/envsubst на compose-файле
     **вне** Coolify-deployer).

### Corner Cases

| Ситуация | Решение |
|----------|---------|
| JVM не успевает стартовать за 30s | `start_period=90s` в healthcheck |
| Порты БД не должны быть открыты наружу | Не добавлять `ports:` для postgres/redis в prod |
| Конфликт peer deps в npm | `--legacy-peer-deps` флаг |
| Flyway в prod | Без `baseline-on-migrate` — при первом деплое применит все миграции |
| Prod и staging конфликтуют в Traefik (split 50/50) | Убрать `services.*` лейбл из compose, положиться на auto-service из `EXPOSE 80`. См. «Traefik routing: как prod и staging разведены» и debug playbook ниже. |

## CI/CD — GitHub Actions + Coolify

### Карта деплоев

| Ветка | Окружение | Триггер |
|-------|-----------|---------|
| `master` | production | Coolify Git Source integration (нативный webhook) |
| `bugfix/**`, `feature/**`, `devops/**` | staging | `deploy-preview.yml` → Coolify API + webhook |

### Production deploy

Coolify сам следит за веткой `master` через встроенную интеграцию с GitHub.
Файл `deploy.yml` **удалён** — был избыточен и вызывал двойной деплой.

### deploy-preview.yml (staging)

Триггер: push в `bugfix/**`, `feature/**`, `devops/**`.

1. `PATCH /api/v1/applications/{uuid}` — меняет ветку staging-приложения на текущую
2. `GET COOLIFY_STAGING_WEBHOOK_URL` — запускает деплой

**Важно:** в Coolify staging-приложении опция "Auto Deploy" должна быть **отключена**,
иначе будет двойной деплой (PATCH тоже триггерит деплой).

### Docker логирование (docker-compose.prod.yml)

Все сервисы используют ограничение логов:
```yaml
logging:
  driver: json-file
  options:
    max-size: "50m"   # backend
    max-file: "5"
```
Frontend, postgres, redis — `max-size: "10m"`.

### nginx access_log

Для статических ассетов логирование отключено (`access_log off`), чтобы не засорять логи.

### Необходимые GitHub Secrets

| Secret | Описание |
|--------|----------|
| `COOLIFY_TOKEN` | API-токен Coolify (root права, общий для prod и staging) |
| `COOLIFY_API_URL` | Base URL Coolify (`http://77.42.23.177:8000`) |
| `COOLIFY_STAGING_UUID` | UUID staging-приложения в Coolify |
| `COOLIFY_STAGING_WEBHOOK_URL` | Webhook staging-приложения |
| `COOLIFY_STAGING_URL` | Публичный URL staging для summary |
