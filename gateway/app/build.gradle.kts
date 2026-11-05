// Application layer: depends on the domain only. No Spring.
dependencies {
    implementation(project(":domain"))

    testImplementation(libs.assertj.core)
}
