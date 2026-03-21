## Выполнено: TASK-001

### Изменённые файлы
- `backend/build.gradle.kts` — Gradle KTS build configuration with all dependencies
- `backend/settings.gradle.kts` — rootProject.name = "clubs"
- `backend/gradle/wrapper/gradle-wrapper.jar` — Gradle 8.12 wrapper JAR
- `backend/gradle/wrapper/gradle-wrapper.properties` — Gradle 8.12 distribution URL
- `backend/gradlew` — Unix Gradle wrapper script
- `backend/gradlew.bat` — Windows Gradle wrapper script
- `backend/.gitignore` — covers build/, .gradle/, *.jar, .env, src/generated/
- `backend/src/main/kotlin/com/clubs/ClubsApplication.kt` — Spring Boot entry point
- `backend/src/main/resources/application.yml` — configuration with dev/prod profiles

### Acceptance Criteria
- [x] Создан `backend/` с `build.gradle.kts` (Spring Boot 3.4.1, Kotlin 2.1.0)
- [x] Зависимости: spring-boot-starter-web, spring-boot-starter-security, spring-boot-starter-data-redis, spring-boot-starter-actuator, jooq 3.19.16, flyway-core + flyway-database-postgresql, jjwt-api/impl/jackson 0.12.6, postgresql driver, jackson-module-kotlin, spring-boot-starter-validation
- [x] `settings.gradle.kts` с корректным rootProject.name = "clubs"
- [x] `src/main/resources/application.yml` с профилями dev/prod
- [x] `src/main/kotlin/com/clubs/ClubsApplication.kt` — точка входа
- [x] `.gitignore` покрывает build/, .gradle/, *.jar, .env
- [x] Конфигурация jOOQ codegen (generateJooq task) в build.gradle.kts — пакет `com.clubs.generated.jooq`, директория `src/generated/jooq/`
- [x] `./gradlew build` проходит без ошибок

### Test Steps
1. `cd backend && ./gradlew build` — BUILD SUCCESSFUL in 3m 58s (5 actionable tasks: 5 executed)
2. JAR создан в `build/libs/` — clubs-0.0.1-SNAPSHOT.jar (49.8M) + clubs-0.0.1-SNAPSHOT-plain.jar
3. `./gradlew bootRun` — Spring Boot стартует на порту 8080, Tomcat инициализирован, контекст загружен. Процесс упал только потому что порт 8080 был занят другим процессом — не ошибка компиляции или конфигурации.

### Build
- ./gradlew build: BUILD SUCCESSFUL

### Коммит
`chore(backend): initialize Spring Boot project`

### Решения и заметки
- **Gradle 8.12** — latest stable, compatible with Kotlin 2.1.0
- **jOOQ codegen**: `generateSchemaSourceOnCompilation` set to `false` so that `./gradlew build` does not require a live database. The `generateJooq` task can be run manually when a database is available (after TASK-002 and TASK-003).
- **Flyway + PostgreSQL**: Added `flyway-database-postgresql` dependency (required since Flyway 10.x for PostgreSQL support).
- **Spring Security**: Default auto-configuration active; custom SecurityConfig will be implemented in TASK-005.
- **Kotlin compile options**: `-Xjsr305=strict` for null-safety with Spring annotations, JVM target 21.
- **Port 8080 conflict**: bootRun test showed the app starts correctly; the failure was due to port 8080 being already occupied on the dev machine.
