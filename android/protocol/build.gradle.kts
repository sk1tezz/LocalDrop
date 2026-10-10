// Pure Kotlin/JVM: the wire protocol shared with the desktop app, testable without Android.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
}

tasks.test {
    // interop tests run the desktop code from the repo's .venv when it exists
    systemProperty("ld.repo", rootProject.projectDir.parentFile.absolutePath)
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
