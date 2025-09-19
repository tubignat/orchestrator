import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    application
    id("org.springframework.boot") version "3.3.3"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        // Manage Spring Boot dependency versions
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.3")
        // Manage Spring Cloud versions compatible with Boot 3.3.x
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2024.0.3")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux:3.3.3")
    implementation("org.springframework.boot:spring-boot-starter-web:3.3.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("org.apache.commons:commons-compress:1.26.2")

    implementation("org.springframework.cloud:spring-cloud-starter-gateway:4.1.5")

    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.3.3")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.example.orchestrator.MainKt")
}
