import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.openapi.generator)
}

// Inbound adapters (web controllers, request filters). Must not depend on :adapters:infra.
dependencies {
    implementation(project(":app"))
    implementation(project(":domain"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    // DataAccessException types, so error handling can stay independent of the Redis adapter.
    implementation(libs.spring.tx)

    testImplementation(libs.spring.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.assertj.core)
}

// Controller interfaces and DTOs are generated from the OpenAPI spec at build time (not committed).
val generatedOpenApiDir = layout.buildDirectory.dir("generated/openapi")

tasks.named<GenerateTask>("openApiGenerate") {
    generatorName = "spring"
    inputSpec = layout.projectDirectory.file("src/main/resources/openapi/gateway-api.yaml")
    outputDir = generatedOpenApiDir
    apiPackage = "com.mikeflat.gateway.adapters.api.generated.api"
    modelPackage = "com.mikeflat.gateway.adapters.api.generated.model"
    // Errors use Spring's own ProblemDetail; don't generate a model for it.
    schemaMappings = mapOf("ProblemDetail" to "org.springframework.http.ProblemDetail")
    globalProperties = mapOf("apis" to "", "models" to "", "supportingFiles" to "false")
    configOptions = mapOf(
        "interfaceOnly" to "true",
        "useSpringBoot4" to "true",
        "useTags" to "true",
        "skipDefaultInterface" to "true",
        "openApiNullable" to "false",
        "useBeanValidation" to "true",
        "documentationProvider" to "none",
        "annotationLibrary" to "none",
        "hideGenerationTimestamp" to "true",
        "sourceFolder" to "src/main/java",
    )
}

sourceSets {
    main {
        java.srcDir(generatedOpenApiDir.map { it.dir("src/main/java") })
    }
}

tasks.named("compileJava") {
    dependsOn("openApiGenerate")
}
