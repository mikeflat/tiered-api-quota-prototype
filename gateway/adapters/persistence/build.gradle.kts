// Persistence adapters: implementations of the domain repository ports, one package per storage
// technology (file now, db later). Depends on :domain only. No Spring.
// Must not depend on :app, :adapters:api or :adapters:infra.
dependencies {
    implementation(project(":domain"))
    // Jackson 3 for reading JSON files; version from the Spring Boot BOM.
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.jackson.databind)

    testImplementation(libs.assertj.core)
}
