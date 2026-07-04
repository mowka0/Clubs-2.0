import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("nu.studer.jooq") version "9.0"
}

group = "com.clubs"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Стартеры Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // jOOQ
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.jooq:jooq:3.19.16")
    jooqGenerator("org.postgresql:postgresql")

    // Flyway
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // JWT (jjwt 0.12.x)
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Rate limiting — ограничение частоты запросов
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // AOP для ролевой авторизации
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // AWS S3 SDK
    implementation("software.amazon.awssdk:s3:2.25.0")

    // Telegram Bot
    implementation("org.telegram:telegrambots-springboot-longpolling-starter:7.10.0")
    implementation("org.telegram:telegrambots-longpolling:7.10.0")
    implementation("org.telegram:telegrambots-client:7.10.0")

    // PostgreSQL
    runtimeOnly("org.postgresql:postgresql")

    // Тесты
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.springframework.security:spring-security-test")
    // Переопределяем docker-java на 3.4.x (Spring Boot BOM фиксирует 3.3.x с API 1.41,
    // а Docker Desktop требует минимум API 1.44)
    testImplementation("com.github.docker-java:docker-java-api:3.4.0")
    testImplementation("com.github.docker-java:docker-java-transport-httpclient5:3.4.0")
}

configurations.testImplementation {
    resolutionStrategy.force(
        "com.github.docker-java:docker-java-api:3.4.0",
        "com.github.docker-java:docker-java-transport-httpclient5:3.4.0",
        "com.github.docker-java:docker-java-core:3.4.0"
    )
}

// Конфигурация jOOQ codegen
jooq {
    version.set("3.19.16")
    edition.set(nu.studer.gradle.jooq.JooqEdition.OSS)

    configurations {
        create("main") {
            generateSchemaSourceOnCompilation.set(false) // Не запускать codegen автоматически при компиляции

            jooqConfiguration.apply {
                logging = org.jooq.meta.jaxb.Logging.WARN

                jdbc.apply {
                    driver = "org.postgresql.Driver"
                    url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/clubs"
                    user = System.getenv("DB_USER") ?: "clubs"
                    password = System.getenv("DB_PASSWORD") ?: "clubs_secret"
                }

                generator.apply {
                    name = "org.jooq.codegen.KotlinGenerator"

                    database.apply {
                        name = "org.jooq.meta.postgres.PostgresDatabase"
                        inputSchema = "public"

                        // Исключаем служебную таблицу истории миграций Flyway
                        excludes = "flyway_schema_history"
                    }

                    generate.apply {
                        isDeprecated = false
                        isRecords = true
                        isPojos = true
                        isDaos = true
                        isImmutablePojos = false
                        isFluentSetters = true
                        isKotlinNotNullPojoAttributes = true
                        isKotlinNotNullRecordAttributes = true
                        isKotlinNotNullInterfaceAttributes = true
                    }

                    target.apply {
                        packageName = "com.clubs.generated.jooq"
                        directory = "src/generated/jooq"
                    }
                }
            }
        }
    }
}

// Подключаем сгенерированные jOOQ-исходники к компиляции
sourceSets {
    main {
        kotlin {
            srcDir("src/generated/jooq")
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Прокидываем Docker-окружение в форкнутую тестовую JVM, чтобы Testcontainers нашёл Docker Desktop
    // на macOS. Docker Desktop 29.x требует минимум API 1.44; shaded docker-java в Testcontainers
    // по умолчанию использует 1.32. Raw-сокет поддерживает версионированные вызовы,
    // а proxy-сокет /var/run/docker.sock — нет.
    val home = System.getProperty("user.home") ?: ""
    val rawSock = "$home/Library/Containers/com.docker.docker/Data/docker.raw.sock"
    if (File(rawSock).exists()) {
        environment("DOCKER_HOST", "unix://$rawSock")
        environment("DOCKER_API_VERSION", "1.44")
        systemProperty("api.version", "1.44")
        // Ryuk монтирует сокет как volume — с docker.raw.sock это не работает, поэтому отключаем
        environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    }
}
