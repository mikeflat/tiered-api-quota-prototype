plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.openapi.generator) apply false
}

// Shared Java setup for every code module. ":adapters" is only an aggregator directory.
subprojects {
    if (path == ":adapters") return@subprojects

    apply(plugin = "java")

    group = "com.mikeflat"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    dependencies {
        // The BOM only manages test versions here, so it stays off main compile classpaths.
        "testImplementation"(platform(rootProject.libs.spring.boot.dependencies))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
