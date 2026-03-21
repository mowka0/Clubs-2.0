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

### Corner Cases

| Ситуация | Решение |
|----------|---------|
| JVM не успевает стартовать за 30s | `start_period=90s` в healthcheck |
| Порты БД не должны быть открыты наружу | Не добавлять `ports:` для postgres/redis в prod |
| Конфликт peer deps в npm | `--legacy-peer-deps` флаг |
| Flyway в prod | Без `baseline-on-migrate` — при первом деплое применит все миграции |
