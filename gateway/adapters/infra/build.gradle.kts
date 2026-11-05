// Outbound adapters (Redis, config loading). Must not depend on :adapters:api.
dependencies {
    implementation(project(":app"))
    implementation(project(":domain"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter.data.redis)

    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.assertj.core)
}
