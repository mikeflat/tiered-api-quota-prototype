plugins {
    alias(libs.plugins.spring.boot)
}

// Infrastructure layer: the only module with the Spring Boot main class (GatewayApplication)
// and the per-module wiring. It assembles every layer into one executable jar (gateway.jar).
dependencies {
    implementation(project(":domain"))
    implementation(project(":app"))
    implementation(project(":adapters:api"))
    implementation(project(":adapters:infra"))

    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.redis)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.archunit.junit5)
}

// Build only the executable jar, so build/libs holds a single artifact.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "gateway.jar"
}
