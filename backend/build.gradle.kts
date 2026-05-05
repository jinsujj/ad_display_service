import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
    kotlin("plugin.jpa") version "2.3.0"
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "me.owldev"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // /actuator/health — deploy/scripts/install-backend.sh, nginx 프로브,
    // Docker HEALTHCHECK 가 모두 의존하는 최소 운영 인터페이스.
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Database
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")

    // Flyway
    implementation("org.flywaydb:flyway-core")
    // Flyway 10 부터 vendor 모듈이 분리됨 — PostgreSQL 마이그레이션 시 필요.
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // OpenAPI (Swagger UI) — Spring Boot 3.4 호환 버전
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // Kotlin null-safety 와 호환되는 Mockito argument matcher (`any()`, `eq()` 등).
    // 표준 Mockito 의 `any(Class)` 는 Kotlin non-null 파라미터에 null 을 반환해 NPE.
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    // Required by Gradle 8.8+ when using useJUnitPlatform() — supplies the
    // JUnit Platform launcher to the test runtime classpath.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Mockito + byte-buddy: Java 25 는 byte-buddy 1.15.x 의 공식 지원 범위
    // (Java 24) 를 넘어 IllegalArgumentException 으로 mock 생성을 거부한다.
    // experimental 모드를 켜면 새 클래스 파일 버전을 받아 들여 인라인 mock-maker
    // 가 동작한다. JDK 가 byte-buddy 의 안정 지원 범위 안으로 들어오면 제거.
    systemProperty("net.bytebuddy.experimental", "true")
}
